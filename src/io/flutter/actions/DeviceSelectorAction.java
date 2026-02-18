/*
 * Copyright 2016 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.actions;

import com.intellij.icons.AllIcons;
import com.intellij.ide.ActivityTracker;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.DataKey;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.actionSystem.Separator;
import com.intellij.openapi.actionSystem.ex.CustomComponentAction;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.IconUtil;
import com.intellij.util.ModalityUiUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.jetbrains.lang.dart.analytics.Analytics;
import com.jetbrains.lang.dart.analytics.AnalyticsData;
import icons.FlutterIcons;
import io.flutter.FlutterBundle;
import io.flutter.logging.PluginLogger;
import io.flutter.run.FlutterDevice;
import io.flutter.run.daemon.DeviceService;
import io.flutter.sdk.AndroidEmulatorManager;
import io.flutter.utils.FlutterModuleUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.ButtonModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class DeviceSelectorAction extends AnAction implements CustomComponentAction, DumbAware {
  private static final @NotNull Logger LOG = PluginLogger.createLogger(DeviceSelectorAction.class);

  private static final Key<JButton> CUSTOM_COMPONENT_KEY = Key.create("customComponent");
  private static final Key<JBLabel> ICON_LABEL_KEY = Key.create("iconLabel");
  private static final Key<JBLabel> TEXT_LABEL_KEY = Key.create("textLabel");
  private static final Key<JBLabel> ARROW_LABEL_KEY = Key.create("arrowLabel");
  private static final @NotNull Icon DEFAULT_DEVICE_ICON = FlutterIcons.Mobile;
  private static final @NotNull Icon DEFAULT_ARROW_ICON = IconUtil.scale(AllIcons.General.ChevronDown, null, 1.2f);

  /**
   * Theme property key for the main toolbar foreground color.
   * This key is used to retrieve the appropriate text color for toolbar components,
   * ensuring proper visibility in all theme configurations (e.g., light theme with dark header).
   */
  private static final String TOOLBAR_FOREGROUND_KEY = "MainToolbar.foreground";

  /**
   * Theme property key for the main toolbar icon hover background color.
   * This key is used to retrieve the appropriate hover background color for toolbar icon buttons,
   * ensuring consistency with other toolbar actions in all theme configurations.
   */
  private static final String TOOLBAR_ICON_HOVER_BACKGROUND_KEY = "MainToolbar.Icon.hoverBackground";

  private volatile @NotNull List<AnAction> actions = new ArrayList<>();
  private final List<Project> knownProjects = Collections.synchronizedList(new ArrayList<>());

  private @Nullable SelectDeviceAction selectedDeviceAction;

  DeviceSelectorAction() {
    super();
  }

  /**
   * Returns the appropriate foreground color for toolbar text components.
   * <p>
   * This method attempts to retrieve the theme-specific toolbar foreground color using the
   * {@link #TOOLBAR_FOREGROUND_KEY}. If the key is not found in the current theme (which may
   * happen with custom or older themes), it falls back to the standard label foreground color
   * provided by {@link UIUtil#getLabelForeground()}.
   * </p>
   *
   * @return A {@link Color} suitable for toolbar text that adapts to the current theme,
   * including configurations like light themes with dark headers.
   */
  @NotNull Color getToolbarForegroundColor() {
    return JBColor.namedColor(TOOLBAR_FOREGROUND_KEY, UIUtil.getLabelForeground());
  }

  /**
   * Returns the appropriate hover background color for toolbar icon buttons.
   * <p>
   * This method attempts to retrieve the theme-specific toolbar icon hover background color using
   * the {@link #TOOLBAR_ICON_HOVER_BACKGROUND_KEY}. If the key is not found in the current theme
   * (which may happen with custom or older themes), it falls back to the standard action button
   * hover background color provided by {@link JBUI.CurrentTheme.ActionButton#hoverBackground()}.
   * </p>
   *
   * @return A {@link Color} suitable for toolbar icon button hover states that adapts to the
   * current theme, ensuring consistency with other toolbar actions.
   */
  @NotNull Color getToolbarHoverBackgroundColor() {
    return JBColor.namedColor(TOOLBAR_ICON_HOVER_BACKGROUND_KEY, JBUI.CurrentTheme.ActionButton.hoverBackground());
  }

  public @NotNull ActionUpdateThread getActionUpdateThread() {
    return ActionUpdateThread.BGT;
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    final Project project = e.getProject();
    if (!isSelectorVisible(project)) {
      return;
    }

    Analytics.report(AnalyticsData.forAction(this, e));

    final DefaultActionGroup group = new DefaultActionGroup();
    group.addAll(actions);

    final DataContext dataContext = e.getDataContext();
    final JBPopupFactory factory = Objects.requireNonNull(JBPopupFactory.getInstance());
    final ListPopup popup =
      factory.createActionGroupPopup(null, group, dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);

    final Component component = e.getData(Objects.requireNonNull(PlatformCoreDataKeys.CONTEXT_COMPONENT));
    if (component != null) {
      popup.showUnderneathOf(component);
    }
    else {
      popup.showInBestPositionFor(dataContext);
    }
  }

  @Override
  public @NotNull JComponent createCustomComponent(@NotNull Presentation presentation, @NotNull String place) {
    final JBLabel iconLabel = new JBLabel(DEFAULT_DEVICE_ICON);
    final JBLabel textLabel = new JBLabel();
    final JBLabel arrowLabel = new JBLabel(DEFAULT_ARROW_ICON);

    // Set foreground color to adapt to the toolbar theme (e.g., dark header with light theme)
    textLabel.setForeground(getToolbarForegroundColor());

    // Create a wrapper button for hover effects
    final JButton button = new JButton() {
      @Override
      protected void paintComponent(@NotNull Graphics g) {
        if (getModel() instanceof ButtonModel m && m.isRollover()) {
          final @NotNull Graphics2D g2 = (Graphics2D)Objects.requireNonNull(g.create());
          g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
          g2.setColor(getToolbarHoverBackgroundColor());
          final int arc = JBUIScale.scale(JBUI.getInt("MainToolbar.Button.arc", 12));
          g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
          g2.dispose();
        }
        super.paintComponent(g);
      }

      @Override
      public Dimension getPreferredSize() {
        final @Nullable JBLabel iconLabel = (JBLabel)getClientProperty(ICON_LABEL_KEY);
        final @Nullable JBLabel textLabel = (JBLabel)getClientProperty(TEXT_LABEL_KEY);
        final @Nullable JBLabel arrowLabel = (JBLabel)getClientProperty(ARROW_LABEL_KEY);

        int width = 0;
        int height = JBUI.scale(22);

        if (iconLabel instanceof JBLabel label && label.getIcon() instanceof Icon icon) {
          width += icon.getIconWidth();
          height = Math.max(height, icon.getIconHeight());
        }
        else {
          // Fallback: use the default mobile icon size when the component is not fully initialized
          final Icon defaultIcon = DEFAULT_DEVICE_ICON;
          width += defaultIcon.getIconWidth();
          height = Math.max(height, defaultIcon.getIconHeight());
        }

        final @Nullable FontMetrics fm;
        final @NotNull String textLabelText;
        if (textLabel instanceof JBLabel label && label.getText() instanceof String text && !text.isEmpty()) {
          fm = label.getFontMetrics(label.getFont());
          textLabelText = text;
        }
        else {
          // Fallback: estimate width for typical device name length
          fm = getFontMetrics(getFont());
          textLabelText = FlutterBundle.message("devicelist.noDevices");
        }
        if (fm != null) {
          width += fm.stringWidth(textLabelText);
          height = Math.max(height, fm.getHeight());
        }

        if (arrowLabel instanceof JBLabel label && label.getIcon() instanceof Icon icon) {
          width += icon.getIconWidth();
          height = Math.max(height, icon.getIconHeight());
        }
        else {
          // Fallback: use the default arrow icon size
          final Icon defaultArrow = DEFAULT_ARROW_ICON;
          width += defaultArrow.getIconWidth();
          height = Math.max(height, defaultArrow.getIconHeight());
        }

        width += JBUI.scale(24);
        height += JBUI.scale(8);

        return new Dimension(width, height);
      }
    };

    button.setLayout(new BorderLayout());
    button.setBorder(JBUI.Borders.empty(4, 8));

    final JPanel contentPanel = getContentPanel();

    final JBLabel[] labels = {iconLabel, textLabel, arrowLabel};
    for (JBLabel label : labels) {
      Objects.requireNonNull(label).addMouseListener(new MouseAdapter() {
        @Override
        public void mouseClicked(@NotNull MouseEvent e) {
          button.dispatchEvent(SwingUtilities.convertMouseEvent(label, e, button));
        }

        @Override
        public void mousePressed(@NotNull MouseEvent e) {
          button.dispatchEvent(SwingUtilities.convertMouseEvent(label, e, button));
        }

        @Override
        public void mouseReleased(@NotNull MouseEvent e) {
          button.dispatchEvent(SwingUtilities.convertMouseEvent(label, e, button));
        }

        @Override
        public void mouseEntered(@NotNull MouseEvent e) {
          button.dispatchEvent(SwingUtilities.convertMouseEvent(label, e, button));
        }

        @Override
        public void mouseExited(@NotNull MouseEvent e) {
          button.dispatchEvent(SwingUtilities.convertMouseEvent(label, e, button));
        }
      });
    }

    contentPanel.add(iconLabel, BorderLayout.WEST);
    contentPanel.add(textLabel, BorderLayout.CENTER);
    contentPanel.add(arrowLabel, BorderLayout.EAST);

    button.add(contentPanel, BorderLayout.CENTER);
    button.setOpaque(false);
    button.setContentAreaFilled(false);
    button.setBorderPainted(false);
    button.setFocusPainted(false);
    button.setRolloverEnabled(true);

    // Store references for updating
    button.putClientProperty(ICON_LABEL_KEY, iconLabel);
    button.putClientProperty(TEXT_LABEL_KEY, textLabel);
    button.putClientProperty(ARROW_LABEL_KEY, arrowLabel);
    presentation.putClientProperty(CUSTOM_COMPONENT_KEY, button);

    button.addActionListener(e -> {
      final DataKey<Project> dataKey = Objects.requireNonNull(CommonDataKeys.PROJECT);
      final DataManager dataManager = Objects.requireNonNull(DataManager.getInstance());
      final DataContext dataContext = dataManager.getDataContext(button);
      final Project project = dataKey.getData(dataContext);
      if (isSelectorVisible(project)) {
        final DefaultActionGroup group = new DefaultActionGroup();
        group.addAll(actions);

        final JBPopupFactory factory = Objects.requireNonNull(JBPopupFactory.getInstance());
        final ListPopup popup =
          factory.createActionGroupPopup(null, group, dataContext, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, false);
        popup.showUnderneathOf(button);
      }
    });

    return button;
  }

  private void showPopup(@NotNull AnActionEvent e) {
  }

  private @NotNull JPanel getContentPanel() {
    final JPanel contentPanel = new JPanel(new BorderLayout(4, 0)) {
      @Override
      protected void processMouseEvent(@NotNull MouseEvent e) {
        final Container parent = getParent();
        if (parent != null) {
          parent.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, getParent()));
        }
      }

      @Override
      protected void processMouseMotionEvent(@NotNull MouseEvent e) {
        final Container parent = getParent();
        if (parent != null) {
          parent.dispatchEvent(SwingUtilities.convertMouseEvent(this, e, getParent()));
        }
      }
    };
    contentPanel.setOpaque(false);
    return contentPanel;
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    // Only show the device menu when the device daemon process is running.
    final Project project = e.getProject();
    if (!isSelectorVisible(project)) {
      e.getPresentation().setVisible(false);
      return;
    }

    final Presentation presentation = e.getPresentation();
    if (!knownProjects.contains(project)) {
      knownProjects.add(project);
      final Application application = ApplicationManager.getApplication();
      if (application != null) {
        application.getMessageBus().connect().subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
          @Override
          public void projectClosed(@NotNull Project closedProject) {
            knownProjects.remove(closedProject);
          }
        });
      }
      Runnable deviceListener = () -> queueUpdate(project, e.getPresentation());
      DeviceService.getInstance(project).addListener(deviceListener);

      // Listen for android device changes and rebuild the menu if necessary.
      Runnable emulatorListener = () -> queueUpdate(project, e.getPresentation());
      AndroidEmulatorManager.getInstance(project).addListener(emulatorListener);
      var projectManager = ProjectManager.getInstance();
      if (projectManager != null) {
        projectManager.addProjectManagerListener(project, new ProjectManagerListener() {
          public void projectClosing(@NotNull Project project) {
            DeviceService.getInstance(project).removeListener(deviceListener);
            AndroidEmulatorManager.getInstance(project).removeListener(emulatorListener);
          }
        });
      }
      update(project, presentation);
    }

    final DeviceService deviceService = DeviceService.getInstance(project);
    final FlutterDevice selectedDevice = deviceService.getSelectedDevice();
    final Collection<FlutterDevice> devices = deviceService.getConnectedDevices();

    final String text;
    Icon icon = DEFAULT_DEVICE_ICON;

    if (devices.isEmpty()) {
      final boolean isLoading = deviceService.getStatus() == DeviceService.State.LOADING;
      if (isLoading) {
        text = FlutterBundle.message("devicelist.loading");
      }
      else {
        text = FlutterBundle.message("devicelist.noDevices");
      }
    }
    else if (selectedDevice == null) {
      text = FlutterBundle.message("devicelist.noDeviceSelected");
    }
    else {
      text = selectedDevice.presentationName();
      icon = selectedDevice.getIcon();
      presentation.setEnabled(true);
    }

    presentation.setText(text);
    presentation.setIcon(icon);

    // Update the custom component if it exists
    final JButton customComponent = presentation.getClientProperty(CUSTOM_COMPONENT_KEY);
    if (customComponent != null) {
      final @Nullable JBLabel iconLabel = (JBLabel)customComponent.getClientProperty(ICON_LABEL_KEY);
      final @Nullable JBLabel textLabel = (JBLabel)customComponent.getClientProperty(TEXT_LABEL_KEY);

      if (iconLabel != null) {
        iconLabel.setIcon(icon);
      }
      if (textLabel != null) {
        textLabel.setText(text);
        // Update the foreground color to adapt to theme changes.
        textLabel.setForeground(getToolbarForegroundColor());
        customComponent.invalidate();
        Container parent = customComponent.getParent();
        while (parent != null) {
          parent.invalidate();
          parent = parent.getParent();
        }
        customComponent.revalidate();
        customComponent.repaint();
      }
    }
  }

  private void queueUpdate(@NotNull Project project, @NotNull Presentation presentation) {
    ModalityUiUtil.invokeLaterIfNeeded(
      ModalityState.defaultModalityState(),
      () -> update(project, presentation)
    );
  }

  private void update(@NotNull Project project, @NotNull Presentation presentation) {
    if (project.isDisposed()) {
      return; // This check is probably unnecessary, but safe.
    }
    updateActions(project, presentation);
    updateVisibility(project, presentation);
  }

  private static void updateVisibility(final Project project, final @NotNull Presentation presentation) {
    final boolean visible = isSelectorVisible(project);

    final JComponent component = presentation.getClientProperty(CUSTOM_COMPONENT_KEY);
    if (component != null) {
      component.setVisible(visible);
      var parent = component.getParent();
      if (parent != null) {
        parent.doLayout();
        parent.repaint();
      }
    }
  }

  private static boolean isSelectorVisible(@Nullable Project project) {
    if (project == null || !FlutterModuleUtils.hasFlutterModule(project)) {
      return false;
    }
    final DeviceService deviceService = DeviceService.getInstance(project);
    return deviceService.isRefreshInProgress() || deviceService.getStatus() != DeviceService.State.INACTIVE;
  }

  private void updateActions(@NotNull Project project, @NotNull Presentation presentation) {
    final String projectName = project.getName();
    LOG.debug("[" + projectName + "] Building device selector actions");

    // Create a new list instead of modifying the existing one
    final List<AnAction> newActions = new ArrayList<>();

    final DeviceService deviceService = DeviceService.getInstance(project);

    final FlutterDevice selectedDevice = deviceService.getSelectedDevice();
    final Collection<FlutterDevice> devices = deviceService.getConnectedDevices();

    selectedDeviceAction = null;

    // Separate devices into booted and not booted
    final List<FlutterDevice> bootedDevices = new ArrayList<>();
    final List<FlutterDevice> availableDevices = new ArrayList<>();
    
    for (FlutterDevice device : devices) {
      if (device == null) continue;
      
      if (device.isBooted()) {
        bootedDevices.add(device);
      } else {
        availableDevices.add(device);
      }
    }

    // Add booted devices at the top (running devices)
    for (FlutterDevice device : bootedDevices) {
      final SelectDeviceAction deviceAction = new SelectDeviceAction(device, devices, false);
      newActions.add(deviceAction);
      LOG.debug("[" + projectName + "] Booted device action added for " + device.deviceName());

      if (Objects.equals(device, selectedDevice)) {
        selectedDeviceAction = deviceAction;
        presentation.setIcon(device.getIcon());
        presentation.setEnabled(true);
      }
    }

    // Add "Open" group for available (not booted) devices
    if (!availableDevices.isEmpty()) {
      if (!bootedDevices.isEmpty()) {
        newActions.add(new Separator());
      }
      
      // Group available devices by type (simulator, emulator), then by OS version
      final DefaultActionGroup openGroup = new DefaultActionGroup("Open", true);
      
      // Group simulators (iOS)
      final List<FlutterDevice> simulators = new ArrayList<>();
      final List<FlutterDevice> emulators = new ArrayList<>();
      
      for (FlutterDevice device : availableDevices) {
        if (device.isIOS() && device.emulator()) {
          simulators.add(device);
        } else if (device.emulator() && !device.isIOS()) {
          emulators.add(device);
        }
      }
      
      // Add Simulator group
      if (!simulators.isEmpty()) {
        final DefaultActionGroup simulatorGroup = new DefaultActionGroup("Simulator", true);
        groupDevicesByOSVersion(simulatorGroup, simulators, devices, selectedDevice, presentation, projectName);
        openGroup.add(simulatorGroup);
      }
      
      // Add Emulator group
      if (!emulators.isEmpty()) {
        final DefaultActionGroup emulatorGroup = new DefaultActionGroup("Emulator", true);
        groupDevicesByOSVersion(emulatorGroup, emulators, devices, selectedDevice, presentation, projectName);
        openGroup.add(emulatorGroup);
      }
      
      newActions.add(openGroup);
    }

    // Show the 'Open iOS Simulator' action.
    if (SystemInfo.isMac) {
      boolean simulatorOpen = false;
      for (FlutterDevice device : bootedDevices) {
        if (device.isIOS() && device.emulator()) {
          simulatorOpen = true;
          break;
        }
      }
      newActions.add(new Separator());
      newActions.add(new OpenSimulatorAction(!simulatorOpen));
      LOG.debug("[" + projectName + "] 'Open iOS Simulator' action added");
    }

    // Add Open Android emulators actions.
    final List<OpenEmulatorAction> emulatorActions = OpenEmulatorAction.getEmulatorActions(project);
    if (emulatorActions != null && !emulatorActions.isEmpty()) {
      newActions.add(new Separator());
      newActions.addAll(emulatorActions);
      LOG.debug("[" + projectName + "] Emulator action added: " + emulatorActions);
    }
    if (!FlutterModuleUtils.hasInternalDartSdkPath(project)) {
      newActions.add(new Separator());
      newActions.add(RestartFlutterDaemonAction.forDeviceSelector());
    }

    // Atomically replace the action list
    LOG.debug("[" + projectName + "] Replacing device selector actions");
    this.actions = newActions;

    var tracker = ActivityTracker.getInstance();
    if (tracker != null) {
      tracker.inc();
    }
  }

  /**
   * Groups devices by OS version and adds them to the parent group.
   */
  private void groupDevicesByOSVersion(
    @NotNull DefaultActionGroup parentGroup,
    @NotNull List<FlutterDevice> devices,
    @NotNull Collection<FlutterDevice> allDevices,
    @Nullable FlutterDevice selectedDevice,
    @NotNull Presentation presentation,
    @NotNull String projectName
  ) {
    // Group devices by OS version
    final java.util.Map<String, List<FlutterDevice>> devicesByVersion = new java.util.LinkedHashMap<>();
    
    for (FlutterDevice device : devices) {
      final String version = device.osVersion() != null ? device.osVersion() : "Unknown";
      devicesByVersion.computeIfAbsent(version, k -> new ArrayList<>()).add(device);
    }
    
    // Sort versions in descending order
    final List<String> sortedVersions = new ArrayList<>(devicesByVersion.keySet());
    sortedVersions.sort((v1, v2) -> {
      // Try to extract version numbers for proper sorting
      try {
        String num1 = v1.replaceAll("[^0-9.]", "");
        String num2 = v2.replaceAll("[^0-9.]", "");
        String[] parts1 = num1.split("\\.");
        String[] parts2 = num2.split("\\.");
        
        for (int i = 0; i < Math.min(parts1.length, parts2.length); i++) {
          int n1 = Integer.parseInt(parts1[i]);
          int n2 = Integer.parseInt(parts2[i]);
          if (n1 != n2) {
            return Integer.compare(n2, n1); // Descending order
          }
        }
        return Integer.compare(parts2.length, parts1.length);
      } catch (Exception e) {
        return v2.compareTo(v1);
      }
    });
    
    // Create version groups
    for (String version : sortedVersions) {
      final List<FlutterDevice> versionDevices = devicesByVersion.get(version);
      final DefaultActionGroup versionGroup = new DefaultActionGroup(version, true);
      
      // Sort devices by name
      versionDevices.sort((d1, d2) -> d1.deviceName().compareToIgnoreCase(d2.deviceName()));
      
      for (FlutterDevice device : versionDevices) {
        final SelectDeviceAction deviceAction = new SelectDeviceAction(device, allDevices, true);
        versionGroup.add(deviceAction);
        LOG.debug("[" + projectName + "] Device action added for " + device.deviceName());

        if (Objects.equals(device, selectedDevice)) {
          selectedDeviceAction = deviceAction;
          presentation.setIcon(device.getIcon());
          presentation.setEnabled(true);
        }
      }
      
      parentGroup.add(versionGroup);
    }
  }

  private static class SelectDeviceAction extends AnAction {
    @NotNull private final FlutterDevice device;
    private final boolean hideId;

    SelectDeviceAction(@NotNull FlutterDevice device, @NotNull Collection<FlutterDevice> devices, boolean hideId) {
      // Show only device name without ID when hideId is true
      super(hideId ? device.deviceName() : device.getUniqueName(devices), null, device.getIcon());
      this.device = device;
      this.hideId = hideId;
    }

    public @NotNull String presentationName() {
      return device.presentationName();
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      final Project project = e.getProject();
      final DeviceService service = project == null ? null : DeviceService.getInstance(project);
      if (service != null) {
        service.setSelectedDevice(device);
      }
    }

    @Override
    public @NotNull ActionUpdateThread getActionUpdateThread() {
      return ActionUpdateThread.BGT;
    }
  }
}
