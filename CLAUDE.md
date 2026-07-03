# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SystemUI-Lite2 is a custom Android **SystemUI replacement** (status bar, notification shade, nav bar, wallpaper) built with Kotlin + Jetpack Compose. It runs as a privileged system app on rooted AOSP devices, replacing the stock `SystemUI.apk` at `/system/priv-app/SystemUI/`.

Unlike a normal app, this code runs in the `com.android.systemui` process with `sharedUserId="android.uid.systemui"`, adding `ComposeView`s directly to `WindowManager` via privileged window types (`TYPE_STATUS_BAR`, `TYPE_NAVIGATION_BAR`, `TYPE_STATUS_BAR_SUB_PANEL`).

## Build & Install

Single Gradle module `:app`. Output APK is always named `SystemUI.apk` regardless of build type (set via `androidComponents` in `app/build.gradle.kts`).

```bash
# Build debug APK (outputs app/build/outputs/apk/debug/SystemUI.apk)
./gradlew assembleDebug

# Clean build
./gradlew clean assembleDebug

# Run unit tests (Robolectric + JUnit)
./gradlew :app:testDebugUnitTest

# Run a single test class
./gradlew :app:testDebugUnitTest --tests "com.example.ExampleUnitTest"

# Install to device (build → push → remount → restart SystemUI)
./install.sh

# Clean build + install
./install.sh clean

# Reinstall already-built APK and restart SystemUI
./install.sh restart

# Stream device logs (SystemUI-specific tags)
./install.sh logs

# Check SystemUI process / window / notification-listener status
./install.sh status
```

`install.sh` requires `adb root` + `adb remount` capability. Both debug and release variants are signed with `platform.keystore` (password `android12345`) — the device's platform key must match for installation.

Versions: Gradle 9.3.1, AGP 9.1.1, Kotlin 2.2.10, compileSdk/targetSdk 36, minSdk 29.

## Architecture

### Startup Flow

```
SystemUIService  (package com.android.systemui — MUST match AOSP for system_server binding)
  └─ SystemUIApplication.startServicesIfNeeded()
       └─ bootstrapComponents() registers + starts all CoreStartables:
            ShadeCoreStartable        → notification shade (TYPE_STATUS_BAR_SUB_PANEL)
            StatusBarCoreStartable    → status bar (TYPE_STATUS_BAR), holds ShadeController ref
            NavigationBarCoreStartable→ nav bar (TYPE_NAVIGATION_BAR)
            GlobalActionsCoreStartable→ power/long-press dialog (TYPE_KEYGUARD_DIALOG) + IPC
       └─ also starts SystemNotificationListenerService
```

Every `CoreStartable` (`lite/CoreStartable.kt`) implements `start()` / `onBootCompleted()` / `onConfigurationChanged()` / `stop()`. They are stored in a `LinkedHashMap<Class<*>, CoreStartable>` and started in class-name-sorted order, stopped in reverse.

### ComposeView + WindowHost

System bars are **not** hosted in an Activity. Each CoreStartable creates a bare `ComposeView` and adds it to `WindowManager`. To give these window-level ComposeViews lifecycle / ViewModel / saved-state ownership, they attach to a `WindowHost` (`core/WindowHost.kt`) which manually drives a `LifecycleRegistry` to `RESUMED`. Always set these three owners on new ComposeViews:
```kotlin
setViewTreeLifecycleOwner(windowHost)
setViewTreeViewModelStoreOwner(windowHost)
setViewTreeSavedStateRegistryOwner(windowHost)
```

### Dependency Injection (Koin)

`di/SystemUIModule.kt` defines `appModule` with four **singletons**: `PluginManager`, `NotificationProvider`, `SystemStateProvider`, `WallpaperProvider`. Components obtain them via `GlobalContext.get().get<T>()` (Koin accessed through a global context, not constructor injection). `initKoin()` is called in `SystemUIApplication.onCreate()`, `destroyKoin()` in `onTerminate()`.

### Reactive State

All system state flows through `kotlinx.coroutines.flow.StateFlow`. The single source of truth for live data is `SystemStateProvider` (battery, connectivity, brightness, volumes, flashlight, auto-rotate, time ticker). Compose UI collects these with `collectAsState()`. **QSTileManager** duplicates much of this as a more AOSP-faithful per-tile abstraction, but the active UI consumes `SystemStateProvider` directly — QSTileManager is not wired into Koin or Compose.

### Package Layout (under `com.android.systemui`)

- root: `SystemUIService`, `lite/SystemUIApplication`
- `lite/core/`: `CoreStartable` iface, `WindowHost`, the 4 startables, `ShadeController`
- `lite/data/`: `SystemStateProvider`, `NotificationProvider`, `WallpaperProvider` (Koin singletons)
- `lite/di/`: `SystemUIModule` + Koin init/destroy
- `lite/model/`: `NotificationItem`, enums (`ClockPosition`, `BatteryPercentageStyle`, `NavigationMode`)
- `lite/statusbar/`: `CommandQueue` (IPC), `StatusBarWindowController`, `StatusBarManager`
- `lite/notification/`: `SystemNotificationListenerService`
- `lite/globalactions/`: `GlobalActionsDialog`
- `lite/plugins/`: `SystemUIPlugin` iface + `PluginManager`
- `lite/qs/`: `QSTileManager`
- `lite/ui/`: Compose screens — `StatusBar`, `NotificationShade`, theme; `ui/navigation/NavigationBarView`
- root: `wallpapers/ImageWallpaper`

## AOSP IPC Bridge

`statusbar/CommandQueue.kt` implements `IStatusBar.Stub` and registers with `StatusBarManagerService` via `ServiceManager.getService("statusbar")`. This uses **direct (non-reflective) calls** to hidden Android APIs from `app/libs/framework-minus-apex.jar` (compileOnly, built from AOSP `android-14.0.0_r28`). `StatusBarStub` uses Kotlin delegation (`IStatusBar by IStatusBar.Default()`) to no-op all 70+ interface methods, overriding only the handful it handles (`disable`, `animateExpand*`, `showGlobalActionsMenu`, `showShutdownUi`, biometric, toast, etc.). On registration failure it degrades to "standalone mode" — so the app still runs (minus power-menu triggers) without a matching platform key.

## Critical Constraints

- **The package name must stay `com.android.systemui`.** `system_server` binds to this exact package/UID. Changing it breaks both the service binding and the privileged window types.
- **`framework-minus-apex.jar` is compileOnly.** It provides `com.android.internal.statusbar.*` and `ServiceManager` signatures at compile time; the real implementations live in the device framework. Do not add it as `implementation`.
- **Notifications require `registerAsSystemService()`** (invoked via reflection in `SystemNotificationListenerService.onCreate`) because the manifest service has no intent-filter — this is the AOSP pattern.
- **Wallpaper/provider state reads use platform-signed permissions** (`WRITE_SECURE_SETTINGS`, `SYSTEM_ALERT_WINDOW`, `INJECT_EVENTS`, etc.). Code should degrade gracefully if a permission is missing rather than crash.
- `NotificationProvider` mediates between the listener service and the UI: the service writes to it; the shade reads from it and routes dismiss/clear-all back via a `listenerCallbacks` interface reference.

## Testing

Unit tests use JUnit4 + Robolectric (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [36])`). `testOptions { unitTests.isIncludeAndroidResources = true }` is enabled so `R.string.*` etc. are available. Existing `src/test/java/com/example/*` are placeholder examples — real feature tests live alongside.
