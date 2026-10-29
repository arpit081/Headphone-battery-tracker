# Changelog

## Phase 1 spike – diagnostics screen
- `AndroidManifest.xml`: declared Bluetooth hardware feature and `BLUETOOTH_CONNECT` permission.
- `MainActivity.kt`: replaced the template "Hello Android" screen with `DiagnosticsScreen`.
- Added `diagnostics/BluetoothDiagnostics.kt`: lists paired devices, which are connected over A2DP/HFP, tries the hidden system battery value, and reads the phone's media volume plus active Bluetooth audio outputs.
- Added `diagnostics/GattDumper.kt`: connects over BLE GATT to a chosen device, logs all services and characteristics, and reads Battery Level and Device Information values.
- Added `diagnostics/DiagnosticsScreen.kt`: permission prompt, volume card, device cards with a "Dump GATT" button, and a copyable log.
- Not yet compiled or run; first build happens in Android Studio.

## Diagnostics screen: log moved to the top
- `diagnostics/DiagnosticsScreen.kt`: the GATT log is now a card near the top (under the volume card) instead of at the bottom of the list. Tapping "Dump GATT" scrolls to it and logs which device was requested.
- Device connection status and battery now refresh automatically every 3 seconds (volume still every second).

## GattDumper: clearer disconnect messages
- `diagnostics/GattDumper.kt`: disconnect log now names the reason for common status codes (0, 8, 62, 133, 147) instead of one generic hint.

## Phase 2 – home screen
- `MainActivity.kt`: app now opens on the new home screen; the diagnostics screen is reachable via a "Diagnostics" button (back button / gesture returns home). Bluetooth permission is requested once for both.
- Added `ui/BluetoothPermissionGate.kt`: shared permission prompt.
- Added `ui/home/HomeScreen.kt`: connected headphones with live battery % and bar, volume %, tracking selector (ANC / Ambient / Normal / Off), company-claim card with estimated time left, and a list of paired but disconnected headphones.
- Added `headphones/TrackingMode.kt`: the four tracking options.
- Added `headphones/HeadphoneCatalog.kt`: model database; first entry Sony WH-1000XM5 (ANC 30 h, Normal 40 h).
- Added `headphones/HeadphoneMonitor.kt`: merges duplicate Bluetooth entries with the same name (the two XM5 entries), hides computers and phones, keeps audio devices.
- Added `headphones/TrackingPreferences.kt`: remembers the chosen tracking mode per headphone.
- `diagnostics/BluetoothDiagnostics.kt`: `DeviceInfo` now includes the Bluetooth major class (used for filtering).
- Mode is set by hand; no data is logged yet (logging comes in Phase 4).

## Phase 4 – background logging
- `AndroidManifest.xml`: added foreground-service (connected device) and notification permissions; declared `tracking.TrackingService`.
- Added `tracking/TrackingService.kt`: foreground service that checks connected headphones every minute and logs battery, volume and the selected mode (written when something changes, or every 5 minutes). Skips headphones set to "Off". Ongoing notification shows current status and has a Stop button.
- Added `tracking/Sample.kt` and `tracking/SampleStore.kt`: local SQLite database (`tracking.db`, table `samples`) using Android's built-in SQLite, no new libraries.
- Added `tracking/DrainCalculator.kt`: listening time, battery used, drain rate, average volume and projected full-charge runtime per mode. Ignores gaps over 7 minutes, mode switches, and charging.
- `headphones/TrackingPreferences.kt`: added the global `trackingEnabled` switch.
- `ui/home/HomeScreen.kt`: added a "Background tracking" switch (asks for notification permission on Android 13+), restarts the service on app open if it was on, and a "Your real usage" card per headphone with per-mode results and the percentage of the company claim. Claim card text updated.

## Phase 3 – automatic ANC / Ambient detection for Sony
- Added `sony/SonyFrame.kt`: encoder/decoder for Sony's control-channel frames (start/end bytes, escaping, checksum).
- Added `sony/SonyLink.kt`: connection thread over Bluetooth Classic RFCOMM (service UUID v2 `956C7B26-…`, falls back to v1). Sends init, ACKs every message, queues commands one at a time, and every 60 s asks for battery (`22 00`) and noise-control state (`66 15`, falls back to `66 17`). Parses battery (1% steps + charging flag) and mode (NC / Ambient / Off), including updates the headphones push when the mode is changed. Older v1 protocol is detected but not supported yet.
- Added `sony/SonyState.kt` and `sony/SonyManager.kt`: shared live state and log for the home screen and the tracking service; retries 30 s after a failed connection.
- Added `headphones/LiveReading.kt`: one place that decides battery (Sony value preferred over Android's) and the mode to track (auto-detected, chosen by hand, or off).
- `headphones/HeadphoneCatalog.kt`: `supportsSonyProtocol` flag (on for WH-1000XM5).
- `headphones/HeadphoneMonitor.kt`: `Headphone.primaryAddress` (the A2DP address used for the Sony channel).
- `headphones/TrackingPreferences.kt`: per-headphone auto-detect setting (default on).
- `ui/home/HomeScreen.kt`: opens the Sony link for supported headphones; new "Auto" option in the mode selector with "Detected: …" text and link status; battery shows its source and a charging label; claim card uses the effective mode and the better battery value.
- `tracking/TrackingService.kt`: logs the detected mode and Sony battery; skips samples while charging or while waiting for the mode.
- `diagnostics/DiagnosticsScreen.kt`: "Sony headphone link" card with status, parsed values, raw log, "Copy Sony log" and "Ask again".
- Protocol facts cross-checked against Gadgetbridge (AGPL, reference only) and SonyHeadphonesClient (MIT); code written independently.

## Faster mode detection
- `sony/SonyLink.kt`: until the headphones have answered a noise-control query, the app asks both known variants (`66 15` and `66 17`) at once, and asks again after 5 seconds if neither answered. Previously the second variant was only tried at the next 60-second poll, which made the first detection slow. Once one variant answers, only that one is used.

## Phase 5 – battery health and graphs
- `headphones/TrackingMode.kt`: "Off" renamed to "Don't track" (it means no tracking; Normal = ANC and ambient off). Stored value unchanged. Added `TRACKED_MODES`.
- Added `tracking/HealthCalculator.kt`: splits data into charges (a rise of 3%+ = charged), measures each charge's full-charge runtime in Normal-mode-equivalent hours (mode hours weighted by the company's claims), and reports health vs your first measured charge, vs the company claim, and the number of full charges used. A charge needs 30%+ used to be measured.
- Added `ui/stats/Charts.kt`: mode colours (validated colour-blind-safe palette, light/dark steps), legend, battery-over-time line chart coloured by mode with tap/drag readout, and horizontal bar rows with a claim tick. Drawn with Compose Canvas, no new library.
- Added `ui/stats/StatsScreen.kt`: period filter (24 h / 7 days / 30 days / All), battery over time, battery used per mode, drain rate per mode vs claim, battery health with per-charge runtime bars, and a summary table.
- `MainActivity.kt`: navigation to the stats screen.
- `ui/home/HomeScreen.kt`: "Graphs & battery health →" button in the usage card, and a "Stats" button for paired headphones that aren't connected.

## Fix: fake drain when the battery source changed
- Bug: samples logged with Android's rounded battery (e.g. 70%, 10% steps) and later ones from the headphones (e.g. 61%, 1% steps) were compared, so the jump was counted as real usage (e.g. "0.3 h · 10% used").
- `tracking/Sample.kt`: each sample now stores `batterySource` (`headphones` / `android` / `none`).
- `tracking/SampleStore.kt`: database version 2 with a `battery_source` column. Upgrading from version 1 clears the old samples (test data only; it could not be told apart reliably). Added `deleteAll()`.
- `tracking/DrainCalculator.kt`: only compares consecutive samples from the same battery source.
- `tracking/HealthCalculator.kt`: a rise between samples from different sources is no longer treated as a charge.
- `headphones/LiveReading.kt`: exposes `batterySourceKey`; `tracking/TrackingService.kt` stores it.
- `diagnostics/DiagnosticsScreen.kt`: "Delete all tracking data" button (tap twice to confirm).

## Home screen redesign
- `ui/theme/Color.kt`, `ui/theme/Theme.kt`: the app now uses its own cool-slate palette (light and dark) instead of colours taken from the wallpaper. The three mode colours are the only accents.
- Added `ui/home/HeadbandGauge.kt`: battery gauge drawn as headphones; the headband fills with the current mode's colour and animates when the level changes.
- `ui/home/HomeScreen.kt` rewritten:
  - Hero: headband gauge with battery %, "About X left" (from your own drain rate in the current mode once there's enough data, otherwise from the company claim) and what the estimate is based on. Shows "Charging" while charging.
  - Name and a mode line ("ANC, read from the headphones" / "…, set by you" / "Not being tracked"); link problems shown only when the link isn't working.
  - Mode switch as one segmented row: Auto / ANC / Ambient / Normal.
  - "Since last charge" panel: listening time, battery used, current volume, plus one sentence comparing real usage with the claim, and a link to graphs and health.
  - "Don't track" moved out of the mode switch into a "Track these headphones" switch.
  - Settings panel: "Record in the background" switch and Diagnostics (the Diagnostics button left the top bar).
  - Other paired headphones listed in one panel with a Stats button each.

## Headphone illustration (WH-1000XM5)
- Added `ui/home/HeadphoneIllustration.kt`: XM5-style over-ear headphones drawn in code (no Sony images used). The headband is the battery meter in the mode colour; waves beside the cups move inward for ANC and outward for Ambient; none in Normal; the band pulses while charging. Respects the phone's "remove animations" setting.
- `headphones/HeadphoneCatalog.kt`: `Illustration` per model (`OVER_EAR` for the XM5; other models keep the plain gauge).
- `ui/home/HomeScreen.kt`: uses the illustration for the XM5; "About X left" and its basis moved just below the drawing so the % has room inside the band.

## Real product photo on the home screen (WH-1000XM5)
- Added `ui/home/HeadphonePhoto.kt`: shows a product photo from `app/src/main/assets/headphones/`, eases in once, floats gently, and draws the mode waves beside the ear cups (positions measured per photo). Falls back to the drawn illustration if the file is missing. Respects "remove animations".
- `headphones/HeadphoneCatalog.kt`: `PhotoSpec` (asset path + ear-cup positions) per model; XM5 points to `headphones/wh1000xm5.png`.
- `ui/home/HomeScreen.kt`: with a photo, battery shows as a large % plus a slim bar in the mode colour under the photo.
- Note: the photo is for personal use only. Replace it with an image you have the rights to before publishing (tracked in plan2.md / PLAN.md).

## XM5 photo added + "Keep running after closing the app"
- Added `app/src/main/assets/headphones/wh1000xm5.png`: the user's XM5 photo (`photos/xm5.png`), trimmed to the headphones (194×298, transparent background). Personal use only; replace before publishing.
- `headphones/HeadphoneCatalog.kt`: `PhotoSpec` now has a position and size per ear cup (the photo is a three-quarter view); XM5 values measured on the trimmed photo.
- `ui/home/HeadphonePhoto.kt`: soft glow in the mode colour behind the photo so black headphones stand out on the dark background; waves use each cup's own size.
- Added `tracking/RestartReceiver.kt`: restarts tracking after a phone restart, after an app update, a few seconds after the app is swiped from recents, and when a Bluetooth device connects. Only when "Record in the background" and "Keep running after closing the app" are both on.
- `tracking/TrackingService.kt`: `onTaskRemoved` schedules the restart; service declared with `stopWithTask="false"`.
- `AndroidManifest.xml`: `RECEIVE_BOOT_COMPLETED`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, and the receiver.
- `headphones/TrackingPreferences.kt`: `keepAlive` setting.
- `ui/home/HomeScreen.kt`: new "Keep running after closing the app" switch in Settings, battery-optimisation status with a button to turn it off, tips for phones that close apps aggressively, and an "Open app settings" button.

## Waves removed + high refresh rate
- `ui/home/HeadphonePhoto.kt`: removed the sound-wave arcs around the ear cups (they looked uneven on the three-quarter photo). The photo keeps its fade-in, slow float and mode-coloured glow; parameter renamed to `glowColor`.
- `ui/home/HomeScreen.kt`: no waves for the photo or the drawn illustration.
- `MainActivity.kt`: requests the display's highest refresh rate at the current resolution (e.g. 120 Hz) on start and on resume.

## Fix: photo shown inside a rectangle after a fast swipe
- Cause: the fade-in drew the photo and glow into an offscreen layer the size of the photo box, which cut the larger glow into a rectangle until the fade ended. A fast swipe scrolled the photo off-screen, so it was recreated and the fade replayed.
- `ui/home/HeadphonePhoto.kt`: fade uses `CompositingStrategy.ModulateAlpha` (no offscreen layer, so the glow is never clipped); decoded photos are cached for the life of the app; the entrance animation plays only once per app launch.

## Performance pass
- `diagnostics/BluetoothDiagnostics.kt`: A2DP/HFP profile connections are opened once and reused app-wide (`ProfileProxies`) instead of being opened and closed on every check (every 3 s on the home screen, every minute in the service). The hidden battery method is looked up once. Added `readMediaVolume()` (volume without the audio-device list).
- `tracking/SampleStore.kt`: `fingerprint()` returns sample count + newest timestamp via the index.
- `ui/home/HomeScreen.kt`: drain and health stats are recalculated only when a new sample was logged (previously the whole history was reread and recalculated every 3 s). Volume uses the light read every second.
- `ui/stats/StatsScreen.kt`: rereads samples only when the fingerprint changed.
- `ui/home/HeadphonePhoto.kt`: the float animation is read inside `graphicsLayer`, so it moves the layer each frame without recomposing the screen.
- `ui/home/HeadphoneIllustration.kt`: removed the leftover wave timer; the band pulse animation only exists while charging and is read at draw time.
- `tracking/TrackingService.kt`: the notification is only updated when its text changes.
- `app/build.gradle.kts`: release build signed with the debug key so the optimised (R8, non-debuggable) build can be run from Android Studio. Replace with a real release key before publishing.

## Build fix
- `diagnostics/BluetoothDiagnostics.kt`: `it !in proxies` on a `ConcurrentHashMap` is a compile error in Kotlin (it would call the legacy `contains`, which checks values). Now uses `containsKey`.

## Release build crash fix
- Crash on start in the release build: `IllegalAccessError: Class kotlin.collections.… is inaccessible`, from `androidx.startup.InitializationProvider`.
- Cause: the template's `optimization { packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**") }` optimised only those libraries, which made a Kotlin class inaccessible to code outside the scope.
- `app/build.gradle.kts`: removed `packageScope` so R8 optimises the whole app consistently.
- `app/src/main/keepRules/rules.keep`: keep the app's enum constant names, because they are stored in the database and preferences and must not be renamed by R8.

## Battery pass
- Added `ui/AppVisibility.kt`; `MainActivity.kt` sets it in `onStart`/`onStop`.
- `ui/home/HomeScreen.kt`, `ui/stats/StatsScreen.kt`, `diagnostics/DiagnosticsScreen.kt`: refresh loops pause while the app is in the background (previously the 1-second volume check and 3-second Bluetooth/database refresh kept running until Android removed the app from memory). Home refreshes immediately on return.
- `tracking/TrackingService.kt`: samples every minute only while headphones are connected. With none connected it sleeps (no wake-ups) and is woken by Bluetooth connect/disconnect events (ACL, A2DP, HFP broadcasts, 2 s settle delay).
- `ui/home/HeadphonePhoto.kt`: the float animation runs two slow cycles when the photo appears and when the mode colour changes, then rests, so no frames are drawn while the screen is idle.

## Repository setup
- `.gitignore`: also ignores `.kotlin`, APK/AAB files and `/app/release`.
- Added `README.md` with an overview and pointers to the docs.
