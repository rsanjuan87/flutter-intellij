/*
 * Copyright 2026 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.sdk;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Represents an iOS Simulator instance available on the system.
 */
public class IOSSimulator {
  private final @NotNull String udid;
  private final @NotNull String name;
  private final @NotNull String state;
  private final boolean isAvailable;
  private final @Nullable String runtime;

  public IOSSimulator(@NotNull String udid, @NotNull String name, @NotNull String state, 
                      boolean isAvailable, @Nullable String runtime) {
    this.udid = udid;
    this.name = name;
    this.state = state;
    this.isAvailable = isAvailable;
    this.runtime = runtime;
  }

  @NotNull
  public String getUdid() {
    return udid;
  }

  @NotNull
  public String getName() {
    return name;
  }

  @NotNull
  public String getState() {
    return state;
  }

  public boolean isAvailable() {
    return isAvailable;
  }

  @Nullable
  public String getRuntime() {
    return runtime;
  }

  public boolean isBooted() {
    return "Booted".equalsIgnoreCase(state);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    IOSSimulator that = (IOSSimulator)o;
    return isAvailable == that.isAvailable &&
           udid.equals(that.udid) &&
           name.equals(that.name) &&
           state.equals(that.state) &&
           Objects.equals(runtime, that.runtime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(udid, name, state, isAvailable, runtime);
  }

  @Override
  public String toString() {
    return "IOSSimulator{" +
           "udid='" + udid + '\'' +
           ", name='" + name + '\'' +
           ", state='" + state + '\'' +
           ", isAvailable=" + isAvailable +
           ", runtime='" + runtime + '\'' +
           '}';
  }
}
