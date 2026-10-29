# Headphone Battery Tracker – Project Plan & Handoff

Android app (Kotlin, Jetpack Compose) that detects the headphones the user is wearing, tracks battery and listening volume, estimates battery health, and compares real battery life against the manufacturer's claim.

- Package: `com.arpit.headphonebatterytracker`
- Project root: this folder (Android Studio project already created from the "Empty Activity" Compose template)
- Test device: a real Android phone (the emulator cannot use real Bluetooth headphones)
- Status: Phases 2–5 done; home screen redesigned (headband gauge, own palette). Next: polish the stats and diagnostics screens to match, then Plan 2 (all Sony models, see plan2.md).

## Conventions for the agent (user's standing preferences)
1. After editing code files, update the relevant `.md` files at the end, stating what was changed (e.g. a `CHANGELOG.md` or this file's status section).
2. If backend or database data is missing for something (e.g. a model's claimed battery hours), add it to `missingdata.md` in the project root with context on what is missing and for which function. Create the file if it does not exist.

## Product requirements
1. Detect which kind of headphone is connected.
2. Track battery with these user-selectable options:
   - ANC battery tracking
   - Ambient mode battery tracking
   - Normal mode battery tracking (assumed to mean ANC off; confirm with user)
   - No battery tracking at all (no polling or logging)
3. Track the listening volume (shown as a percentage of max; raw step also stored).
4. Display battery health.
5. Show measured battery life as a percentage of the company's claim.

## Key technical constraints (be honest about these in the UI)
- **Battery level:** readable for many models via the standard BLE Battery Service (0x180F / characteristic 0x2A19), a vendor GATT characteristic, or the HFP battery indicator. Some models only use proprietary protocols.
- **ANC / Ambient / Off mode:** NOT exposed by any standard Bluetooth API. Only available through each manufacturer's private protocol (documented, reverse-engineered, or closed). Use a per-model adapter layer. Fallback is a manual mode selector.
- **Volume:** with A2DP and absolute volume support, the phone's music stream volume follows the headphone volume, readable through `AudioManager` (`getStreamVolume` / `getStreamMaxVolume`). Without absolute volume, only the phone-side step is known. Store `volume_source` (absolute or phone-side) and label readings accordingly.
- **Battery health:** Android and most earbuds do not expose true health or cycle count. Health is an ESTIMATE from full-discharge runtime across cycles versus a baseline. Always label it "estimated".
- **Company claim:** claims are usually measured at an unstated volume. Compare at a reference volume (default 50%) and say so in the UI.
- Android 12+ needs `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT`. Background tracking needs a foreground service with a persistent notification.

## Architecture
- Kotlin, Jetpack Compose, MVVM
- Bluetooth: `BluetoothAdapter`, `BluetoothGatt`, A2DP and HFP profile proxies
- Storage: Room (samples, sessions, mode changes)
- Model database: bundled JSON of supported models with adapter id and claimed hours per mode, including source URL and date for each claim
- Modules: `device/` (detection and connection), `adapters/` (per brand or model), `tracking/` (sampling and sessions), `health/` (cycle and health estimation), `claims/` (claim data and comparison), `ui/`

## Data model (draft)
- Sample: timestamp, device id, battery % (left, right, case, any may be null), mode (ANC, AMBIENT, NORMAL, UNKNOWN), volume_level, volume_max, volume_source
- Session: start, end, start battery, end battery, charge-cycle marker
- ModelClaim: model id, claimed hours (ANC on, ANC off, ambient, with case), reference volume, source URL, date

## Feature feasibility
| Feature | Approach | Feasibility |
|---|---|---|
| Detect headphone type | Bluetooth name, device class, GATT Device Information, local model DB | High |
| Battery % (L, R, case) | BLE Battery Service, vendor GATT, or HFP | High for common models |
| ANC / Ambient / Off detection | Vendor protocol per model | Low to medium; manual fallback |
| No tracking option | Do not subscribe or poll | Trivial |
| Battery health | Full-discharge runtime vs baseline plus cycle count | Medium (estimate) |
| Drain per mode and per volume | Log samples with mode and volume, compute drain rate | Medium |
| % of company claim | Measured runtime / claimed runtime | High once claim data exists |
| Volume tracking | `AudioManager`, sampled every 30 to 60 s or on volume change | High |

## Phases
1. **Spike (1 to 2 weeks):** Connect to the user's headphones, read battery, look for vendor GATT services, check whether absolute volume is reported. Build a simple diagnostic screen listing connected headphones, battery, and volume. Findings decide the rest of the plan.
2. **Detection and battery (2 weeks):** Headphone detection, connected-device screen, battery for L, R, case, live volume display, "no tracking" option.
3. **Mode tracking (2 to 3 weeks):** Adapter for the first model that reports ANC mode; manual mode selector as fallback.
4. **Logging and drain (2 weeks):** Record samples and mode changes. Show drain per mode, per volume, and per mode and volume together.
5. **Battery health (1 to 2 weeks):** Full-cycle runtime and cycle count, estimated health over time, clearly labelled "estimated".
6. **Claim comparison (1 week):** Claimed hours per model and mode, "X% of company claim" with the reference volume and a note when volume is phone-side only.
7. **Polish and testing:** Multiple Android versions and devices, disconnect handling, low background battery use, supported-model database.

## Open decisions (still need the user's answers)
1. Which headphone models to support first (determines the spike).
2. Does "normal mode" mean ANC off?
3. Personal use only, or Play Store release?
4. Is an estimated battery health value with an "estimated" label acceptable?

## Defaults already agreed
- Volume is displayed as a percentage of the phone's max, with the raw step stored.
- Claim comparison uses a 50% reference volume.

## Setup status
- Android Studio installed; project created (Empty Activity, Compose, Kotlin DSL).
- Pending: run the template on a physical phone; inspect headphones with nRF Connect for Mobile (Scanner, Connect, screenshot the services list).
- Still needed from the user: headphone brand and model, phone model and Android version, minimum SDK chosen in the project.

## Next step
Build the Phase 1 diagnostic screen: runtime permission requests, list of bonded and connected Bluetooth devices, GATT service dump for the selected device, battery value if available, and current `AudioManager` volume.

## Spike findings (first run)
- Diagnostics screen builds and runs on the user's phone.
- First target headphone: **Sony WH-1000XM5** (over-ear, single battery; Class: Wearable headset; Type: Dual; connected via A2DP + HFP).
- Battery: Android's system battery value works for the XM5 (showed 70%). Plan: use it as the primary battery source.
- Volume: phone media volume readable (8/15, 53%). Absolute volume confirmed: pressing the XM5 buttons changes the phone value, so volume readings are accurate for this model.
- ANC / Ambient mode: no standard API. Gadgetbridge (open source, AGPL) partially supports the XM5 over Sony's proprietary protocol, including ambient sound control. Use it as a protocol reference only; copying its code would make this app AGPL.
- Sony claim for XM5: ~30 h with noise cancelling on, ~40 h with it off (no separate ambient-mode figure found yet).
- GATT: neither XM5 address (AC:80:0A:CE:C6:39 classic, 88:C9:E8:76:AB:D9 LE) answers BLE GATT (both status 147, connection timeout). GATT is not used for the XM5. Likely path for XM5 data beyond battery: Sony proprietary protocol over Classic RFCOMM (as Gadgetbridge does).

## Sony protocol findings (WH-1000XM5, first live test)
- RFCOMM channel v2 (`956C7B26-…`) opens immediately; init reply `01 00 03 00 20 16 00 00` (8 bytes = protocol v2).
- Battery: `22 00` → `23 00 3d 00` = 61%, not charging (1% precision; Android reported 70% in 10% steps).
- Noise control: `66 15` is ACKed but never answered on this unit. `66 17` → `67 17 01 01 00 01 14` = on, mode NC, ambient level 20. So this XM5 uses the 0x17 variant (7 bytes, no wind-noise byte).
- With both variants asked at connect, battery and mode arrive within ~1 second of opening the channel.

## Before publishing
- Replace the Sony product photo in `app/src/main/assets/headphones/` with artwork you have the rights to (own photo, licensed image, or the drawn illustration in `ui/home/HeadphoneIllustration.kt`).
