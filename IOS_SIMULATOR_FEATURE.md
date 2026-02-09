# iOS Simulator Selector - Feature Implementation

## 📋 Overview

This feature adds support for detecting and selecting iOS Simulators directly from the Flutter IntelliJ plugin's device selector dropdown. Previously, iOS simulators were only accessible through the Flutter daemon, but this implementation adds a direct integration using `xcrun simctl`.

## 🎯 Features Implemented

### 1. **IOSSimulator Class** (`src/io/flutter/sdk/IOSSimulator.java`)
- Represents an iOS Simulator instance
- Properties: UDID, name, state, availability, runtime version
- Helper methods to check if simulator is booted

### 2. **IOSSimulatorManager Service** (`src/io/flutter/sdk/IOSSimulatorManager.java`)
- Discovers iOS simulators using `xcrun simctl list devices available --json`
- Parses JSON output to extract simulator information
- Provides methods to:
  - Refresh simulator list
  - Launch simulators by UDID
  - Convert simulators to FlutterDevice instances
- Notifies listeners when simulator list changes
- Automatically refreshes when DeviceService starts

### 3. **DeviceService Integration** (`src/io/flutter/run/daemon/DeviceService.java`)
- Combines Flutter daemon devices with iOS simulators
- Listens to IOSSimulatorManager changes
- Refreshes iOS simulators when daemon starts
- Adds iOS simulators to the device dropdown menu

### 4. **FlutterDevice Enhancement** (`src/io/flutter/run/FlutterDevice.java`)
- Updated `bringToFront()` method to launch specific iOS simulator by UDID
- Uses `open -a Simulator --args -CurrentDeviceUDID <udid>` to launch the correct simulator

### 5. **Plugin Configuration** (`resources/META-INF/plugin.xml`)
- Registered IOSSimulatorManager as a project service
- Enables dependency injection throughout the plugin

## 🔧 Technical Implementation Details

### Simulator Detection
```bash
xcrun simctl list devices available --json
```

This command returns JSON with all available iOS simulators grouped by runtime version.

### Simulator Launch
```bash
# Boot the simulator
xcrun simctl boot <UDID>

# Open Simulator.app with specific device
open -a Simulator --args -CurrentDeviceUDID <UDID>
```

### Architecture Flow
1. User opens IntelliJ with a Flutter project
2. DeviceService initializes and starts Flutter daemon
3. IOSSimulatorManager refreshes simulator list (parallel to daemon startup)
4. DeviceService combines daemon devices + iOS simulators
5. All devices appear in the device selector dropdown
6. When user selects an iOS simulator:
   - FlutterDevice.bringToFront() is called
   - Simulator is launched if not already running
   - Flutter app runs on the selected simulator

## 🧪 Testing Instructions

### Prerequisites
- macOS system (iOS simulators are only available on Mac)
- Xcode installed with iOS simulators
- IntelliJ IDEA with Flutter plugin
- Flutter SDK installed

### Manual Testing Steps

1. **Build the Plugin**
   ```bash
   cd flutter-intellij
   ./gradlew buildPlugin
   ```

2. **Run Plugin in Development Mode**
   - Open the project in IntelliJ IDEA
   - Use the "Flutter Plugin" run configuration
   - This will launch a new IntelliJ instance with the plugin loaded

3. **Verify Simulator Detection**
   - Open a Flutter project in the dev IntelliJ instance
   - Check the device selector dropdown (usually in the toolbar)
   - You should see your iOS simulators listed

4. **Test Simulator Launch**
   - Select an iOS simulator that is not running
   - Click "Run" or "Debug"
   - Verify that:
     - The simulator launches automatically
     - The correct simulator device appears (match the name)
     - The Flutter app starts successfully

5. **Test Multiple Simulators**
   - Try selecting different simulators
   - Verify each launches correctly
   - Check that the device list updates properly

### Verification Commands

Check available simulators from terminal:
```bash
xcrun simctl list devices available
```

Verify simulator is running:
```bash
ps aux | grep Simulator
```

## 📝 Code Structure

```
src/io/flutter/
├── sdk/
│   ├── IOSSimulator.java           (New: Simulator model)
│   ├── IOSSimulatorManager.java    (New: Simulator service)
│   └── XcodeUtils.java             (Existing: Xcode utilities)
└── run/
    ├── FlutterDevice.java          (Modified: Added simulator launch)
    └── daemon/
        └── DeviceService.java      (Modified: Integrated iOS simulators)

resources/META-INF/
└── plugin.xml                      (Modified: Registered new service)
```

## 🚀 Usage for End Users

Once this feature is merged and released:

1. **Install/Update the Flutter Plugin** in IntelliJ IDEA
2. **Open a Flutter Project**
3. **Click the Device Selector** in the toolbar
4. **Select an iOS Simulator** from the dropdown
5. **Run or Debug** your Flutter app
6. The simulator will launch automatically if not running

## 🔍 Edge Cases Handled

- ✅ Simulator already running - reuses existing instance
- ✅ Simulator not available - filtered out from list
- ✅ No Xcode installed - gracefully returns empty list
- ✅ Non-macOS system - returns empty list
- ✅ Invalid JSON from xcrun - catches exceptions and logs warnings
- ✅ Simulator launch failure - logs error, doesn't crash plugin

## 📊 Performance Considerations

- Simulator list refresh is asynchronous (runs in background thread)
- JSON parsing is efficient using Gson
- Refresh only happens when device daemon starts (not on every selection)
- Listener pattern prevents unnecessary UI updates

## 🐛 Known Limitations

1. **macOS Only**: iOS simulators are only available on macOS
2. **Xcode Required**: Requires Xcode and command-line tools installed
3. **Refresh Timing**: Simulator list doesn't auto-update when simulators are added/removed in Xcode (requires plugin restart)

## 🔮 Future Enhancements

Possible improvements for future iterations:
- [ ] Real-time monitoring of simulator changes
- [ ] Simulator creation from within IntelliJ
- [ ] Display simulator iOS version in dropdown
- [ ] Simulator state indicator (running/stopped)
- [ ] Quick actions (restart simulator, erase contents)
- [ ] Filter simulators by iOS version
- [ ] Favorite/pin frequently used simulators

## 📚 References

- [Flutter IntelliJ Plugin Repository](https://github.com/flutter/flutter-intellij)
- [simctl Command Reference](https://nshipster.com/simctl/)
- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/)

## 👥 Contributing

This implementation follows the Flutter IntelliJ plugin's contribution guidelines. To contribute improvements:

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request with a clear description

See [CONTRIBUTING.md](CONTRIBUTING.md) for detailed guidelines.

---

**Created**: February 2026  
**Branch**: `feature/ios-simulator-selector`  
**Status**: Ready for Review
