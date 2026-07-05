# PRD: Quick Settings Tile Functionality — SystemUI-Lite2

## Introduction

SystemUI-Lite2 is a custom Android SystemUI replacement built with Kotlin + Jetpack Compose. The notification shade renders a 7-tile Quick Settings grid (Wi-Fi, Bluetooth, DND, Flashlight, Airplane Mode, Auto-Rotate, Screen Recording) backed by sliders for brightness and media volume. Today most tiles either don't function at all or only write a setting without actually producing the system behavior a user expects. Screen Recording has no backend at all. DND writes `zen_mode` but does not push that through `NotificationManager.suppressNotificationSoundAndVibration()` / `setInterruptionFilter()`, so users still hear and feel notifications. Battery Saver is a no-op stub. Bluetooth toggles the adapter but the tile state desyncs when the system broadcasts a state change.

This PRD defines the work required to make every tile do what its label promises, and to clean up the duplicate state-management situation so future tile work has a clear home.

## Goals

- Every one of the 7 existing QS tiles toggles a real system state and reflects the actual state when changed externally
- DND suppresses notifications end-to-end via `NotificationManager.setInterruptionFilter()` and `NotificationManager.Policy`
- Screen Recording tile starts and stops a real `MediaProjection` + MediaRecorder session
- Battery Saver tile reads power-save state accurately and opens the system battery settings rather than silently failing
- Bluetooth tile stays in sync with `BluetoothAdapter` state broadcasts, including async enable/disable transitions
- Per-tile icon comes from a correct visual source — either drawn from `~/android-os/SystemUI` AOSP vector assets or a sensible equivalent Material Icons vector
- Single clearly-defined responsibility for each of the two existing state files; no more two-source-of-truth confusion

## User Stories

### US-001: Wi-Fi tile actually turns Wi-Fi on/off
**Description:** As a user, I want to tap the Wi-Fi tile and have Wi-Fi actually toggle on or off, so that I can use the shade to manage connectivity instead of going into Settings.

**Acceptance Criteria:**
- [ ] Tapping Wi-Fi tile calls `WifiManager.setWifiEnabled(true/false)` and the tile's visual active/inactive state reflects the result
- [ ] Tile state updates when the system broadcasts `WifiManager.WIFI_STATE_CHANGED_ACTION` (e.g. user toggles Wi-Fi from another app or Settings)
- [ ] On Android 10+ where `WifiManager.setWifiEnabled` is restricted, tile falls back to opening `Settings.Panel.ACTION_WIFI` rather than silently failing
- [ ] No duplicate Wi-Fi state logic between QSTileManager and SystemStateProvider after refactor
- [ ] `kotlinc`/lint passes
- [ ] Tile reflects correct state when shade is opened after external change

### US-002: Bluetooth tile stays in sync with adapter state
**Description:** As a user, I want the Bluetooth tile to show the real adapter state at all times and toggle Bluetooth on/off without the tile showing the wrong state afterward.

**Acceptance Criteria:**
- [ ] Tapping Bluetooth tile calls `BluetoothAdapter.enable()` or `BluetoothAdapter.disable()`
- [ ] Tile state updates on `BluetoothAdapter.ACTION_STATE_CHANGED`, correctly reflecting `STATE_TURNING_ON`, `STATE_ON`, `STATE_TURNING_OFF`, `STATE_OFF`
- [ ] While transition is in flight (`STATE_TURNING_ON` / `STATE_TURNING_OFF`), tile shows a distinct "in-progress" visual (e.g. animated or dimmed state) and ignores rapid taps
- [ ] Tile state matches `BluetoothAdapter.getDefaultAdapter().isEnabled` on every `onResume`/shade-open
- [ ] `kotlinc`/lint passes

### US-003: DND tile suppresses notifications end-to-end
**Description:** As a user, I want to enable DND from the Quick Settings tile and have the system actually suppress notification sounds, vibrations, and visual interruptions.

**Acceptance Criteria:**
- [ ] Tapping DND tile calls `NotificationManager.setInterRUPTION_FILTER(INTERRUPTION_FILTER_NONE)` (or `ALARMS`/`PRIORITY` for future modes) and updates `Settings.Global.ZEN_MODE`
- [ ] When DND is ON, `SystemNotificationListenerService` respects the interruption filter — notifications are posted to the data layer but marked suppressed so the shade does not visually alert (no heads-up, vibration, or ringtone on this device)
- [ ] Tapping again disables DND: `setInterruptionFilter(INTERRUPTION_FILTER_ALL)`, `ZEN_MODE = OFF`
- [ ] Tile state updates on `android.settings.ZEN_MODE_CHANGED`
- [ ] `NotificationManager.Policy` reflects DND state (can be queried via `NotificationManager.getNotificationPolicy()`)
- [ ] `kotlinc`/lint passes
- [ ] Tapping DND tile visibly changes the tile active state and a toast or brief log confirms mode

### US-004: Flashlight tile toggles camera torch
**Description:** As a user, I want to tap the Flashlight tile and have the camera torch turn on/off, so I can use my phone as a flashlight directly from the shade.

**Acceptance Criteria:**
- [ ] Tapping Flashlight tile calls `CameraManager.setTorchMode()` for the first available camera
- [ ] Tile visually reflects on/off state
- [ ] Torch state is read on initial start and on `ACTION_TORCHMODE_CHANGED` if available; otherwise polled periodically while shade is open
- [ ] If no torch-capable camera exists, tile is disabled/greyed instead of crashing
- [ ] Flashlight state survives brief shade close/reopen (persisted across shade lifecycle)
- [ ] `kotlinc`/lint passes

### US-005: Airplane Mode tile toggles airplane mode
**Description:** As a user, I want to tap the Airplane Mode tile and have airplane mode actually turn on/off, with the tile reflecting the current state.

**Acceptance Criteria:**
- [ ] Tapping Airplane Mode tile writes to `Settings.Global.AIRPLANE_MODE_ON` and broadcasts `Intent.ACTION_AIRPLANE_MODE_CHANGED`
- [ ] Tile active/inactive state updates from broadcast receiver
- [ ] Tile state matches `Settings.Global.AIRPLANE_MODE_ON` on every shade open
- [ ] `kotlinc`/lint passes

### US-006: Auto-Rotate tile toggles rotation lock
**Description:** As a user, I want to tap the Auto-Rotate tile to lock/unlock screen rotation, and have the tile show the actual state.

**Acceptance Criteria:**
- [ ] Tapping Auto-Rotate tile writes to `Settings.System.ACCELEROMETER_ROTATION`
- [ ] Tile visual state matches `Settings.System.ACCELEROMETER_ROTATION` (0 = locked = tile says "Portrait"/inactive, 1 = auto-rotate = active)
- [ ] Actual device rotation behavior changes after toggling
- [ ] Tile state updates on `Settings.System.ACCELEROMETER_ROTATION` change observed via `ContentObserver`
- [ ] `kotlinc`/lint passes

### US-007: Battery Saver tile reflects state and opens settings
**Description:** As a user, I want the Battery Saver tile to power-save mode accurately (read-only toggle) and tapping it opens battery settings so I can manage the feature.

**Acceptance Criteria:**
- [ ] Tile reads current state via `PowerManager.isPowerSaveMode` and visually reflects it
- [ ] Tapping the tile opens `Intent.ACTION_POWER_SUMMARY` (battery settings) — battery saver cannot be toggled directly on un-rooted or non-system apps, so direct settings navigation is the correct fallback
- [ ] Alternatively, with platform签名 + `WRITE_SECURE_SETTINGS`, tile uses `PowerManager.setPowerSaveMode()` if available and falls back to settings intent if denied
- [ ] Tile is never a silent no-op — tapping always produces observable change (either state change or settings open)
- [ ] `kotlinc`/lint passes

### US-008: Screen Recording tile starts/stops a real MediaProjection session
**Description:** As a user, I want to tap the Screen Recording tile and have the phone start recording the screen and stop when I tap again, with the recording saved to storage.

**Acceptance Criteria:**
- [ ] Tapping Screen Recording tile launches `MediaProjectionManager.createScreenCaptureIntent()` and stores the resulting `MediaProjection` token
- [ ] After user grants consent, tile starts a `MediaRecorder` writing to `Movies/ScreenRecordings/` on shared storage
- [ ] Tile visually reflects "recording" state (active/animated)
- [ ] Tapping again stops the recorder, releases MediaProjection, and finalizes the file
- [ ] Tile is disabled/inactive state until recording successfully starts (handles deny from permission dialog)
- [ ] File is playable from the gallery/files app after recording stops
- [ ] Handles runtime permission for `RECORD_AUDIO`, `POST_NOTIFICATIONS`, and storage (`READ_MEDIA_*` / `WRITE_EXTERNAL_STORAGE` pre-13)
- [ ] If permission flow is cancelled, tile returns to inactive and logs the event
- [ ] `kotlinc`/lint passes
- [ ] Tile reflects correct state after shade reopen during an active recording

### US-009: Each tile shows a correct, meaningful icon
**Description:** As a user, I want each QS tile to display a recognizable icon so I can scan the grid quickly.

**Acceptance Criteria:**
- [ ] Wi-Fi tile shows a Wi-Fi signal icon
- [ ] Bluetooth tile shows a "B" bluetooth icon
- [ ] DND tile shows a "do not disturb" / bell-slash icon
- [ ] Flashlight tile shows a flashlight/torch icon
- [ ] Airplane Mode tile shows a small airplane icon
- [ ] Auto-Rotate tile shows a phone-with-rotation-arrows icon
- [ ] Battery Saver tile shows a battery-with-leaf icon
- [ ] Screen Recording tile shows a record/filled-circle icon
- [ ] Icons come from existing Material Icons in the project or from `~/android-os/SystemUI/res/drawable/` vector assets (AOSP-licensed)
- [ ] Icons render correctly in both active and inactive tile states with contrast
- [ ] `kotlinc`/lint passes
- [ ] Verify visually using dev-browser skill

### US-010: Consolidate tile state ownership between QSTileManager and SystemStateProvider
**Description:** As a developer, I want a single clear owner of QS tile state and toggles so that tile behavior is easy to extend and there's only one place to look.

**Acceptance Criteria:**
- [ ] One of `QSTileManager.kt` or `SystemStateProvider.kt` becomes the canonical "QS state + actions" class and the other has its QS-relevant code removed (not just commented out)
- [ ] The remaining class is registered in the Koin module (`SystemUIModule`) as the single `QSTileManager`-type dependency
- [ ] All 7 tiles wire through the single class — no tile references both
- [ ] The `NotificationShade` composable sends tile taps to the consolidated class
- [ ] Class name and package clearly express its role (e.g. `com.android.systemui.lite.qs.QSTileManager` or moved into `data/` — reason documented in a 3-line comment at top of file)
- [ ] No duplicate BroadcastReceiver logic; one receiver handles all state-change intents
- [ ] `kotlinc`/lint passes
- [ ] App builds and all existing non-QS features (status bar, notification shade, navigation bar, wallpaper) still work after refactor

## Functional Requirements

- **FR-1:** Each of the 7 tiles maps 1-to-1 to a real, observable system state
- **FR-2:** Every tile is stateless-only-in-UI — active/inactive is always derived from the canonical backend, never held in Compose memory alone
- **FR-3:** Tapping a tile that cannot achieve the requested outcome (e.g. permission denied, API restricted) surfaces a visible fallback (settings intent / toast) instead of silently failing
- **FR-4:** DND writes to `Settings.Global.ZEN_MODE`, the `NotificationManager` interruption filter, and `NotificationManager.Policy`
- **FR-5:** Screen Recording uses `MediaProjectionManager` → `MediaProjection` → `MediaRecorder` and writes to `Movies/ScreenRecordings/`
- **FR-6:** Screen Recording requires runtime permission prompts for audio capture, storage, and notifications (foreground service)
- **FR-7:** Battery Saver reads `PowerManager.isPowerSaveMode` and either toggles via privileged API or opens `Settings.ACTION_POWER_SUMMARY` — never silently no-ops
- **FR-8:** Bluetooth tile shows transition states and ignores spam taps during `STATE_TURNING_ON` / `STATE_TURNING_OFF`
- **FR-9:** Auto-Rotate tile uses `ContentObserver` on `Settings.System.ACCELEROMETER_ROTATION` to detect external changes
- **FR-10:** All broadcast receivers are registered `RECEIVER_NOT_EXPORTED` on Android 14+ to comply with target API 36 behavior
- **FR-11:** The QS state singleton is registered in `SystemUIModule` (Koin) and injected into both the shade composable and any future QS detail panel
- **FR-12:** Tile icons are vector drawables (not raster-only) and exist as `res/drawable-*/` resources or are re-used from the Material Icons bundled in the project
- **FR-13:** Tile layout in `NotificationShade.kt` keeps the current 3+3+1 grid arrangement intact; only icon graphics and on-click handlers change

## Non-Goals

- No long-press / detail panel for any tile (future PRD)
- No adding new tiles beyond the existing 7 (future PRD)
- No multi-state tiles (e.g. Wi-Fi picker network list, Bluetooth device list) — single tap-only toggle
- No tile rearrange / drag-to-reorder support
- No AOSP-style tile animation (color ripples, etc.) — visual state change only
- No custom QS footer (settings cog, edit tiles, user avatar)
- No lock screen / keyguard QS access
- No auto-dark / palette-based theming of tiles beyond the existing `themeColor` parameter
- No automated instrumentation tests in this PRD — manual verification only

## Design Considerations

- Icon assets: preferred source is `~/android-os/SystemUI/res/drawable/` vector drawables (AOSP Android 14.0, Apache-2.0 licensed). Relevant candidates:
  - `ic_qs_wifi_*.xml`
  - `ic_qs_bluetooth*.xml`
  - `ic_qs_dnd.xml` / `ic_qs_do_not_disturb_on.xml`
  - `ic_qs_flashlight.xml`
  - `ic_qs_airplane.xml`
  - `ic_qs_auto_rotate.xml`
  - `ic_qs_battery_saver.xml`
  - `ic_qs_screenrecord.xml`
- Icons exposed to Jetpack Compose via `painterResource(...)` or by importing Material Icons vector variants from existing `androidx.compose.material.icons.filled.*` where a clean equivalent exists
- Tile background color uses the existing `activeBg = Color(0xFFD3E4FF)` / `inactiveBg = Color(0xFF30343A)` tokens that `QSTile()` already accepts
- Screen Recording needs a foreground service with `FOREGROUND_SERVICE_SPECIAL_USE` permission (manifest already declares it) to keep recording alive when shade is closed — this is out of scope for the PRD (shade can remain open during recording), but the manifest entry exists for future
- The notification shade uses Compose + `WindowManager`-added `ComposeView`; no Activity-based UI changes required for most tiles

## Technical Considerations

- `sharedUserId="android.uid.systemui"` + platform signature gives the app elevated privilege — `WRITE_SECURE_SETTINGS`, `WRITE_SETTINGS`, `DEVICE_POWER` etc. are already declared in the manifest. Use them for direct toggling where available but always have a graceful fallback.
- Android 14 (target API 36): broadcast receivers must be registered with `RECEIVER_NOT_EXPORTED` (except for system broadcasts that require exported). Already done in existing code but must be preserved.
- `WifiManager.setWifiEnabled` is deprecated and restricted on Android 10+ for non-system apps. Because this app runs as `systemui` UID with platform signature, it should work — but if it throws `SecurityException`, fall back to `Settings.Panel.ACTION_WIFI`.
- `MediaProjection` requires user consent every time via `startActivityForResult` on the capture intent. Since the QS add-on runs in a window-level ComposeView (not an Activity), the consent flow will need to launch via the app's `MainActivity` or a transparent bridge activity that hands the result back to the singleton. This is the main architectural lift in US-008.
- Existing Koin DI via `SystemUIModule.kt`: add the consolidated QS manager as a singleton.
- `NotificationShade.kt` currently takes boolean primitives + lambda callbacks. Consider refactoring the callback surface to take a single `QSTileManager`-like interface so the composable forwards taps without knowing which tile triggered them (helps with future tiles). Not mandatory in this PRD but noted as a clean extension point.
- `SystemNotificationListenerService` extends `NotificationListenerService`, giving the app visibility into notification posting — use this to gate DND suppression at the app level as a secondary safety net even though the system-level zen_mode is the primary mechanism.
- Storage for recordings must use `MediaStore` on API 29+ (scoped storage) rather than direct file paths.
- The current `QSTileManager.kt` is **not registered in Koin** according to inspection — `SystemStateProvider` is the live source. US-010 must clean this up so only one exists and is Koin-injected.

## Success Metrics

| Metric | Current | Target |
|--------|---------|--------|
| Tiles with working toggle behavior | 3/7 (Flashlight, Airplane, Auto-Rotate partially) | 7/7 |
| DND actually suppresses notifications | No | Yes (interruption filter changes, listener respects it) |
| Screen Recording works end-to-end | Missing entirely | Yes (start/stop saved recording) |
| Battery Saver is not a silent no-op | No-op stub | Yes (reads state + opens settings or toggles) |
| Bluetooth tile reflects state after external change | No (desyncs) | Yes |
| Source files managing QS state | 2 (duplicated) | 1 (consolidated) |
| Tile icons are meaningful placeholders | No (all generic icons) | Yes (unique per tile) |
| Graceful fallback on permission/API restriction | No | Yes (settings intent / visible failure) |

## Open Questions

1. **MediaProjection consent flow in window-only ComposeView:** What is the preferred way to launch `startActivityForResult` from the shade? Options: (a) transparent bridge activity in the app, (b) reuse `MainActivity`, (c) existing AOSP pattern via `PendingIntent` / `ActivityResultContract`. Needs design decision in US-008 implementation.
2. **Screen Recording tile behavior during shade close:** Should recording continue as a foreground service when the user closes the shade, or is it acceptable to require the shade to stay open during recording? Affects whether to pre-implement the `ForegroundService` for recordings.
3. **DND granularity:** `INTERRUPTION_FILTER_NONE` (total silence) vs. `INTERRUPTION_FILTER_PRIORITY` (allow priority interruptions)? For this PRD, only `NONE` and `ALL` are required — but the API surface should make future PRIORITY mode easy to add.
4. **Auto-Rotate label:** Current tile label in UI is "Auto-Rotate" — keep as-is or switch to match AOSP "Rotation Lock"? Not a functional concern but consistency.
5. **Tile icon sourcing:** Should icons be committed as project resources (copied from AOSP) OR generated from Material Icons? Preferred is copying the AOSP vectors for strict visual fidelity with `~/android-os/SystemUI/res/drawable`. Confirm before US-009 implementation.

## Implementation Priority

Tackle in this order so each story builds on the previous:

1. **US-010** (consolidate state) — foundation all others depend on
2. **US-001** (Wi-Fi) — simplest, validates consolidated path works
3. **US-005** (Airplane Mode) — similar shape to Wi-Fi
4. **US-006** (Auto-Rotate) — adds ContentObserver pattern
5. **US-002** (Bluetooth) — adds async state-transition handling
6. **US-004** (Flashlight) — adds hardware resource lifecycle
7. **US-003** (DND) — adds NotificationManager integration
8. **US-007** (Battery Saver) — read-only + settings fallback
9. **US-008** (Screen Recording) — biggest lift, depends on MediaProjection bridge design
10. **US-009** (Icons) — visual polish, can be interleaved but safer after all toggle logic is settled
