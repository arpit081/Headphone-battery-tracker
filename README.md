# Headphone Tracker

Android app (Kotlin, Jetpack Compose) that detects the connected headphones, tracks battery, listening volume and noise-control mode (ANC / Ambient / Normal), estimates battery health, and compares real battery life with the manufacturer's claim.

Sony WH-1000XM5: live 1% battery and automatic ANC/Ambient detection over Sony's control protocol. Other headphones: Android's battery value (10% steps) and manual mode selection.

## Docs
- `PLAN.md` – product plan, phases, findings, status
- `plan2.md` – plan for supporting all Sony headphones
- `CHANGELOG.md` – what changed, file by file
- `missingdata.md` – data the app still needs (e.g. missing battery claims)

## Build
Open in Android Studio, connect an Android 12+ phone, press Run. The emulator can't use real Bluetooth headphones.

## Note
`app/src/main/assets/headphones/wh1000xm5.png` is a Sony product photo used for personal testing only. Replace it before any public release.
