# Plan 2 – Support every Sony headphone

Goal: extend the Sony live link (battery + ANC/Ambient/Off detection) from the WH-1000XM5 to all Sony models that use Sony's control protocol, and show left / right / case batteries for earbuds.

Status: **planned, not started.** Current priority is improving the UI of the existing app (see PLAN.md). Pick this up afterwards.

## What already works (WH-1000XM5)
- RFCOMM control channel v2 (`956C7B26-D49A-4BA8-B03F-B17D393CB6E2`), framing, ACKs, command queue: `sony/SonyFrame.kt`, `sony/SonyLink.kt`.
- Init `00 00` → reply length 8 = protocol v2 (length 4 = v1).
- Battery `22 00` → `23 00 <level> <charging>` (single battery, 1% steps).
- Noise control `66 15` / `66 17` → `67 <type> <change> <on/off> <mode 0=NC 1=Ambient> … <ambient level>`. The user's XM5 answers only `66 17`.
- Pushed updates: `25` (battery) and `69` (noise control) arrive when values change.

## Models and what they need
Source: Gadgetbridge device definitions (AGPL, used as a protocol reference only) and SonyHeadphonesClient (MIT). Only the WH-1000XM5 is tested on a real device.

| Model | Protocol | Battery | Noise control notes | Work needed |
|---|---|---|---|---|
| WH-1000XM5 | v2 | single | type 0x17 on user's unit | done |
| WF-1000XM4, WF-1000XM5 | v2 | left/right + case | wind-noise variant (0x17, 8 bytes) | earbud batteries |
| LinkBuds S | v2 | left/right + case | ambient control | earbud batteries |
| LinkBuds | v2 | left/right + case | none (open design) | earbud batteries, no mode → manual |
| WH-1000XM4, XM3, XM2 | v1 | single | v1 layout (see below) | v1 protocol |
| WF-1000XM3, WF-SP800N, WF-C700N | v1 | left/right + case | v1 layout | v1 + earbud batteries |
| WF-C500 | v1 | left/right | none | v1 + earbud batteries |
| WI-C100, WI-SP600N | v1 | single | SP600N has ambient control | v1 |
| WH-1000XM6 and newer | likely v2 | single/dual | likely newer variants (e.g. 0x18/0x19 noise-adaptive) | unverified, needs a test device |

## Protocol differences to implement

### Battery requests
| | v2 | v1 |
|---|---|---|
| Request / reply / notify codes | `22` / `23` / `25` | `10` / `11` / `13` |
| Single battery type byte | `00` | `00` |
| Left/right type byte | `01` (some models `09`) | `01` |
| Case type byte | `0a` | `02` |
| Single reply | `[cmd, type, level, charging]` | same |
| Left/right reply | `[cmd, type, L level, L charging, R level, R charging]` (0 = bud not present) | same |

### Noise-control (ambient sound) in v1
- Request `66 02` → reply `67 …` (8 bytes):
  - byte 2: `00` = off, `01` = on
  - byte 3: `00` = models with NC + ambient only; `02` = models with wind-noise reduction
  - byte 4 when byte 3 = `00`: `00` = **Ambient**, `01` = **NC** (note: reversed vs v2)
  - byte 4 when byte 3 = `02`: `00` = Ambient, `01` = wind-noise reduction (count as ANC), `02` = NC
- Pushed updates use `69`, same layout.

## Steps
1. **Detect Sony by service ID, not by name.** If a connected headphone advertises the Sony v1 or v2 control UUID, treat it as Sony and try the link, even if it is not in `HeadphoneCatalog`. Unknown Sony models show "Sony headphones (model not in list)".
2. **v1 support in `SonyLink`.** Branch on the init reply length: use the v1 codes and the v1 noise-control parser above.
3. **Earbud batteries.**
   - `SonyState`: add `left`, `right`, `case` (each level + charging).
   - On connect, ask single, left/right and case. Keep whichever the headphones answer.
   - Home screen: show L / R / case. Logging: store left, right and case (new DB columns, DB version 2 with migration). Drain and health use the lower of L and R.
4. **Model catalog.** Add each model above with Sony's claimed hours: ANC on, ANC off, and with case for earbuds. Source URL for every number. Record anything missing in `missingdata.md`.
5. **Support status per model** in the catalog: tested / expected to work / unknown. Show it in the UI so users know how much to trust auto-detection.
6. **Testing.** For each model someone connects, check the Sony log in Diagnostics (Copy Sony log) and fix parsing differences.

## Risks
- Only the WH-1000XM5 can be tested now. Other models may answer differently than documented.
- Sony's own app (Sound Connect) may hold the control channel; the user may need to close it.
- Firmware updates can change behaviour (the XM5's 0x15 vs 0x17 difference is an example).
- Non-Sony brands (e.g. the user's realme Buds Air 3S) use different protocols and are out of scope for this plan.
