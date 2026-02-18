/*
 * Copyright 2026 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.sdk;

import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.concurrency.AppExecutorUtil;
import io.flutter.FlutterUtils;
import io.flutter.logging.PluginLogger;
import io.flutter.run.FlutterDevice;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the list of iOS Simulators available on the system.
 * Uses 'xcrun simctl' to discover and launch iOS simulators.
 */
public class IOSSimulatorManager {
  private static final @NotNull Logger LOG = PluginLogger.createLogger(IOSSimulatorManager.class);

  @NotNull
  public static IOSSimulatorManager getInstance(@NotNull Project project) {
    return Objects.requireNonNull(project.getService(IOSSimulatorManager.class));
  }

  private final @NotNull Project project;
  private final AtomicReference<ImmutableSet<Runnable>> listeners = new AtomicReference<>(ImmutableSet.of());

  private @NotNull List<IOSSimulator> cachedSimulators = new ArrayList<>();

  private IOSSimulatorManager(@NotNull Project project) {
    this.project = project;
  }

  public void addListener(@NotNull Runnable callback) {
    listeners.updateAndGet((old) -> {
      final List<Runnable> changed = new ArrayList<>(old);
      changed.add(callback);
      return ImmutableSet.copyOf(changed);
    });
  }

  public void removeListener(@NotNull Runnable callback) {
    listeners.updateAndGet((old) -> {
      final List<Runnable> changed = new ArrayList<>(old);
      changed.remove(callback);
      return ImmutableSet.copyOf(changed);
    });
  }

  private CompletableFuture<List<IOSSimulator>> inProgressRefresh;

  /**
   * Refreshes the list of available iOS simulators.
   */
  public CompletableFuture<@NotNull List<IOSSimulator>> refresh() {
    // We don't need to refresh if one is in progress.
    synchronized (this) {
      if (inProgressRefresh != null) {
        return inProgressRefresh;
      }
    }

    final CompletableFuture<List<IOSSimulator>> future = new CompletableFuture<>();

    synchronized (this) {
      inProgressRefresh = future;
    }

    AppExecutorUtil.getAppExecutorService().submit(() -> {
      if (!SystemInfo.isMac) {
        // iOS simulators are only available on macOS
        future.complete(Collections.emptyList());
        return;
      }

      final List<IOSSimulator> simulators = fetchSimulators();
      simulators.sort((sim1, sim2) -> sim1.getName().compareToIgnoreCase(sim2.getName()));
      future.complete(simulators);
    });

    future.thenAccept(simulators -> {
      fireChangeEvent(simulators, cachedSimulators);

      synchronized (this) {
        inProgressRefresh = null;
      }
    });

    return future;
  }

  /**
   * Returns the cached list of simulators.
   */
  public @NotNull List<@NotNull IOSSimulator> getCachedSimulators() {
    return cachedSimulators;
  }

  /**
   * Fetches the list of simulators using xcrun simctl.
   */
  private @NotNull List<IOSSimulator> fetchSimulators() {
    try {
      final GeneralCommandLine cmd = new GeneralCommandLine()
        .withExePath("xcrun")
        .withParameters("simctl", "list", "devices", "available", "--json")
        .withCharset(StandardCharsets.UTF_8);

      final CapturingProcessHandler handler = new CapturingProcessHandler(cmd);
      final ProcessOutput output = handler.runProcess(10000);

      if (output.getExitCode() != 0) {
        LOG.warn("xcrun simctl failed with exit code " + output.getExitCode() + ": " + output.getStderr());
        return Collections.emptyList();
      }

      return parseSimulatorOutput(output.getStdout());
    }
    catch (Exception e) {
      FlutterUtils.warn(LOG, "Failed to fetch iOS simulators", e);
      return Collections.emptyList();
    }
  }

  /**
   * Parses the JSON output from xcrun simctl list devices.
   */
  private @NotNull List<IOSSimulator> parseSimulatorOutput(@NotNull String jsonOutput) {
    final List<IOSSimulator> simulators = new ArrayList<>();

    try {
      final JsonObject root = JsonParser.parseString(jsonOutput).getAsJsonObject();
      final JsonObject devices = root.getAsJsonObject("devices");

      if (devices == null) {
        return simulators;
      }

      // Iterate through each runtime (e.g., "iOS 17.0", "iOS 16.4")
      for (String runtime : devices.keySet()) {
        final JsonArray deviceArray = devices.getAsJsonArray(runtime);
        
        for (JsonElement deviceElement : deviceArray) {
          final JsonObject device = deviceElement.getAsJsonObject();
          
          final String udid = getStringOrNull(device, "udid");
          final String name = getStringOrNull(device, "name");
          final String state = getStringOrNull(device, "state");
          final boolean isAvailable = device.has("isAvailable") && device.get("isAvailable").getAsBoolean();

          if (udid != null && name != null && state != null && isAvailable) {
            simulators.add(new IOSSimulator(udid, name, state, true, runtime));
          }
        }
      }
    }
    catch (Exception e) {
      FlutterUtils.warn(LOG, "Failed to parse simulator JSON output", e);
    }

    return simulators;
  }

  @Nullable
  private String getStringOrNull(@NotNull JsonObject obj, @NotNull String key) {
    if (obj.has(key) && !obj.get(key).isJsonNull()) {
      return obj.get(key).getAsString();
    }
    return null;
  }

  /**
   * Launches an iOS simulator by UDID.
   */
  public CompletableFuture<Boolean> launchSimulator(@NotNull String udid) {
    return CompletableFuture.supplyAsync(() -> {
      if (!SystemInfo.isMac) {
        return false;
      }

      try {
        // First, boot the simulator if it's not already booted
        final GeneralCommandLine bootCmd = new GeneralCommandLine()
          .withExePath("xcrun")
          .withParameters("simctl", "boot", udid)
          .withCharset(StandardCharsets.UTF_8);

        final CapturingProcessHandler bootHandler = new CapturingProcessHandler(bootCmd);
        final ProcessOutput bootOutput = bootHandler.runProcess(10000);

        // Exit code 148 means "already booted", which is fine
        if (bootOutput.getExitCode() != 0 && bootOutput.getExitCode() != 148) {
          LOG.warn("Failed to boot simulator " + udid + ": " + bootOutput.getStderr());
        }

        // Open the Simulator app with the specific device
        final GeneralCommandLine openCmd = new GeneralCommandLine()
          .withExePath("open")
          .withParameters("-a", "Simulator", "--args", "-CurrentDeviceUDID", udid)
          .withCharset(StandardCharsets.UTF_8);

        final CapturingProcessHandler openHandler = new CapturingProcessHandler(openCmd);
        final ProcessOutput openOutput = openHandler.runProcess(5000);

        if (openOutput.getExitCode() != 0) {
          LOG.warn("Failed to open simulator " + udid + ": " + openOutput.getStderr());
          return false;
        }

        return true;
      }
      catch (Exception e) {
        FlutterUtils.warn(LOG, "Failed to launch simulator " + udid, e);
        return false;
      }
    }, AppExecutorUtil.getAppExecutorService());
  }

  /**
   * Converts iOS simulators to FlutterDevice instances.
   */
  public @NotNull List<FlutterDevice> getSimulatorsAsFlutterDevices() {
    final List<FlutterDevice> devices = new ArrayList<>();
    
    for (IOSSimulator simulator : cachedSimulators) {
      // Only include available simulators
      if (simulator.isAvailable()) {
        // Extract iOS version from runtime (e.g., "com.apple.CoreSimulator.SimRuntime.iOS-18-1" -> "iOS 18.1")
        String osVersion = extractOSVersion(simulator.getRuntime());
        boolean isBooted = simulator.isBooted();
        
        final FlutterDevice device = new FlutterDevice(
          simulator.getUdid(),
          simulator.getName(),
          "ios",
          true, // emulator
          "mobile",
          "ios",
          true, // ephemeral
          osVersion,
          isBooted
        );
        devices.add(device);
      }
    }
    
    return devices;
  }

  /**
   * Extracts a readable OS version from the runtime string.
   * Examples:
   * - "com.apple.CoreSimulator.SimRuntime.iOS-18-1" -> "iOS 18.1"
   * - "iOS 17.5" -> "iOS 17.5"
   */
  private String extractOSVersion(@Nullable String runtime) {
    if (runtime == null) {
      return "iOS";
    }
    
    // Handle format like "com.apple.CoreSimulator.SimRuntime.iOS-18-1"
    if (runtime.contains("SimRuntime.")) {
      String version = runtime.substring(runtime.lastIndexOf('.') + 1);
      version = version.replace("-", " ").replace("_", ".");
      // Capitalize and format (iOS-18-1 -> iOS 18.1)
      return version.replaceFirst("(\\w+) (\\d+) (\\d+)", "$1 $2.$3");
    }
    
    // Already in readable format
    return runtime;
  }

  private void fireChangeEvent(final @NotNull List<IOSSimulator> newSimulators, final List<IOSSimulator> oldSimulators) {
    if (project.isDisposed()) return;

    // Don't fire if the list of devices is unchanged.
    if (Objects.equals(cachedSimulators, newSimulators)) {
      return;
    }

    cachedSimulators = newSimulators;

    for (Runnable listener : listeners.get()) {
      try {
        listener.run();
      }
      catch (Exception e) {
        FlutterUtils.warn(LOG, "IOSSimulatorManager listener threw an exception", e);
      }
    }
  }
}
