# BMW F10 Coding (Android)

A native Android app for coding BMW **F10 (5 Series)** control modules — FRM, KOMBI, NBT,
HUD, CAS — in plain English, with a full offline **demo mode** and a real **ENET/DoIP**
transport that can write coding data to a car.

- **Phase 1 research / knowledge base:** [`docs/BMW_CODING_KNOWLEDGE_BASE.md`](docs/BMW_CODING_KNOWLEDGE_BASE.md)
- **Phase 2 app:** this repository (Kotlin, single APK).

---

## ⚠️ Read this before connecting to a real car

Coding writes configuration bytes to your car's modules. Done with **correct, verified** maps
it is low-risk and reversible; done with **wrong byte offsets** it can misconfigure or, in bad
cases, disable a module.

The coding maps bundled in [`app/src/main/assets/codings_f10.json`](app/src/main/assets/codings_f10.json)
are **illustrative** (`"verified": false`). The app therefore:

- lets you exercise every one of them freely in **demo mode** (nothing physical happens), but
- **refuses to write an unverified map to real hardware** (`CodingEngine` throws unless the map
  is `verified: true`).

To code a real car you must supply maps verified against *your* car's coding data (its FA/VO and
I-level). See **[Adding / verifying coding entries](#adding--verifying-coding-entries)**. Always
record a module's original bytes before changing them, and keep the battery charged.

---

## Architecture & decisions

| Area | Choice | Why |
|------|--------|-----|
| Language / UI | **Kotlin + Views + ViewBinding** (no Compose) | Matches the spec; ViewBinding keeps it lightweight and lets the transport layer be the focus. |
| Pattern | **MVVM** | ViewModels own state and enforce the connection guard so no screen can write without a live link. |
| Persistence | **Room** | Module/coding definitions and current values are stored locally — no cloud, fully offline. Seeded once from the JSON asset. |
| Definitions | **Bundled JSON asset** | An FDL-style curated map of *feature → module → byte/bit → allowed values*, the same idea BimmerCode/E-Sys "cheat sheets" use. |
| Transport | **Pluggable `EcuTransport`** | `DemoTransport` (offline sim), `EnetDoipTransport` (real DoIP/UDS coding), `BleObdTransport` (connect/diagnostics). |
| Navigation | **Four Activities** | One per screen (Home, Coding List, Edit, Connection); a singleton `ConnectionManager` keeps the connection alive across them. |

minSdk 26 · targetSdk/compileSdk 34 · Java 17 · single APK.

### Project layout

```
├─ build.gradle.kts / settings.gradle.kts        # Gradle (Kotlin DSL), plugin versions
├─ gradlew / gradle/wrapper/                      # Gradle 8.9 wrapper
├─ docs/BMW_CODING_KNOWLEDGE_BASE.md              # Phase 1 research
└─ app/
   ├─ build.gradle.kts                            # deps: Room, lifecycle, coroutines, material, gson
   └─ src/main/
      ├─ AndroidManifest.xml                      # INTERNET + BLE perms, 4 activities
      ├─ assets/codings_f10.json                  # 26 codings across 5 modules (the seed data)
      ├─ res/                                      # layouts, drawables, theme
      └─ java/com/bmwf10/coding/
         ├─ BmwCodingApp.kt                        # Application; seeds Room on first launch
         ├─ data/
         │  ├─ model/                              # CodingItem, Module, ValueType, EcuMap, EnumOption
         │  ├─ db/                                 # Room entities, DAO, AppDatabase
         │  ├─ CodingAssetLoader.kt                # parses the JSON asset
         │  └─ CodingRepository.kt                 # single source of truth (defs + values)
         ├─ ecu/
         │  ├─ EcuTransport.kt                     # transport interface
         │  ├─ DemoTransport.kt                    # offline simulation
         │  ├─ EnetDoipTransport.kt                # real ENET/DoIP + UDS (coding-capable)
         │  ├─ CodingEngine.kt                     # value <-> byte encode/decode + safety gate
         │  ├─ ConnectionManager.kt                # app-scoped connection state (singleton)
         │  ├─ Hex.kt
         │  ├─ uds/{Uds.kt, Doip.kt}               # ISO 14229 + ISO 13400 framing
         │  └─ ble/{BleScanner.kt, BleObdTransport.kt}
         └─ ui/
            ├─ home/        (HomeActivity, HomeViewModel, ModuleAdapter)
            ├─ coding/      (CodingListActivity, CodingListViewModel, CodingAdapter)
            ├─ edit/        (EditCodingActivity, EditCodingViewModel)
            ├─ connection/  (ConnectionActivity, ConnectionViewModel, BleDeviceAdapter)
            └─ common/      (ConnectionBadge, Icons)
```

---

## Building

Requirements: JDK 17 and the Android SDK (platform 34). Point Gradle at your SDK with a
`local.properties` file in the repo root:

```
sdk.dir=/absolute/path/to/Android/sdk
```

Then:

```bash
./gradlew assembleDebug        # build app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build + install on a connected device/emulator
```

Or open the folder in Android Studio (Giraffe or newer) and Run.

### Download a prebuilt APK

Every push to `main` or a `claude/**` branch (and every PR) runs the
[**Build APK**](.github/workflows/build-apk.yml) GitHub Actions workflow, which assembles the
debug APK and uploads it as a build artifact named `bmw-f10-coding-debug-apk`. To install it on
a phone without building locally: open the workflow run in the **Actions** tab → download the
artifact → unzip → sideload `app-debug.apk` (enable "install from unknown sources" for your
browser/Files app when prompted). The build is debug-signed — fine for your own device, not for
distribution.

### Try it with no hardware

Launch the app → tap the **connection chip** in the top bar (or any Edit screen prompt) →
**Connection** screen → **Start demo mode**. The app populates realistic "current values" and
every coding is fully editable; changes are simulated against a fake car.

---

## Using the app

1. **Home** — pick a module (FRM, KOMBI, NBT, HUD, CAS) from the card grid.
2. **Coding list** — each coding shows a plain-English summary, an expandable **"What does this
   do?"** section, its current value, and an **Edit** button.
3. **Edit** — shows the full description, the safe range/options, a warning banner for
   sensitive/irreversible codings, and the right input control (toggle / dropdown / slider /
   hex). Applying pops a **confirmation modal** with the exact before → after change.
4. **Connection** — pick how to connect (below). A status chip is visible on every screen; the
   ViewModel blocks any write unless a connection is live.

---

## Connecting each adapter type

### Demo mode (no hardware)
Connection screen → **Start demo mode**. Coding is simulated; safe for development and demos.

### ENET / WiFi — the coding-capable path
This is the transport that can actually write F10 coding (DoIP + UDS, the E-Sys/ISTA family).

1. Connect the car to your phone/laptop network over an **ENET cable** (OBD-II ↔ RJ45; a plain
   patch cable will **not** work — the cable needs the gateway activation wiring, see the
   knowledge base §3.1) or via the gateway/adapter WiFi.
2. Put your device on the same subnet as the car (E-Sys convention: gateway `192.168.0.10`,
   device something like `192.168.0.20`; F-series may also use `169.254.x.y` link-local).
3. Connection screen → enter the **IP** (default `192.168.0.10`) and **port** (`13400`, the DoIP
   port) → **Connect over ENET**.
4. The app performs the DoIP routing-activation handshake, then addresses each module by its
   diagnostic address (stored per module in the JSON).

> Writes still require `verified: true` maps — see below.

### Bluetooth (BLE) OBD adapter
Connection screen → **Scan for BLE adapters** (grant Bluetooth permission) → tap your adapter.
The app connects over the Nordic UART service and runs an ELM327 reset/echo-off handshake.

BLE is provided for **connection/diagnostics**; consumer BLE adapters cannot reliably perform
F-series coding writes, so `BleObdTransport.supportsCoding = false` and the app routes coding to
ENET or demo mode. If you have a proprietary coding protocol for a specific adapter,
`BleObdTransport` is the place to implement `readCodingBlock`/`writeCodingBlock`.

---

## The coding definition schema

`app/src/main/assets/codings_f10.json` has two arrays: `modules` and `codings`.

**Module**
```json
{ "id": "frm", "name": "FRM", "fullName": "Footwell Module",
  "description": "…", "iconName": "zap", "diagAddress": 114 }
```
`diagAddress` is the module's DoIP/UDS diagnostic address (decimal; `114` = `0x72`). Icons:
`zap`, `activity`, `monitor`, `eye`, `key` (anything else → a default module icon).

**Coding**
```json
{
  "id": "frm_cornering_lights",
  "moduleId": "frm",
  "name": "Cornering Lights",
  "description": "Short one-line summary.",
  "longDescription": "Full plain-English 'What does this do?' text.",
  "valueType": "boolean",              // boolean | enum | integer | hex
  "defaultValue": "false",             // factory value
  "safeDefault": "false",              // value shown as the safe choice
  "demoValue": "true",                 // pre-populated 'current value' in demo mode
  "options": [ { "label": "…", "value": "…" } ],  // enum only
  "min": 30, "max": 100, "unit": "%",  // integer only
  "hexLength": 2,                       // hex only (max hex digits)
  "warning": "Shown as a red banner if present.",
  "irreversible": false,
  "f10Applicable": true,
  "ecuMap": {                           // how the value maps to a coding byte
    "dataIdentifier": 12288,            // UDS DID of the coding block (decimal; 12288 = 0x3000)
    "byteOffset": 0,                    // byte within that block
    "bitMask": 1,                       // bits this feature owns (255 = whole byte)
    "encodedValues": { "true": "0x01", "false": "0x00" },  // boolean/enum: value → raw byte
    "scale": 1.0,                       // integer: raw = round(value / scale)
    "verified": false                   // MUST be true to write to real hardware
  }
}
```

### Value types → input control
| `valueType` | Control on the Edit screen | Encoding |
|-------------|----------------------------|----------|
| `boolean`   | Toggle switch              | `encodedValues["true"/"false"]` under `bitMask` |
| `enum`      | Dropdown of `options`      | `encodedValues[optionValue]` under `bitMask` |
| `integer`   | Slider (`min`–`max`)       | `round(value / scale)` under `bitMask` |
| `hex`       | Validated hex field        | raw byte under `bitMask` |

---

## Adding / verifying coding entries

1. **Add the definition** to `codings_f10.json` (a new object in `codings`, referencing an
   existing `moduleId`). Write `description`/`longDescription` in plain English.
2. **Find the real map.** Using E-Sys (read the module's CAFD/NCD) or a known-good coding
   cheat-sheet for your car's I-level, identify: the coding block's **DID**, the **byte offset**,
   the **bit mask**, and the **raw value** for each option. Put those in `ecuMap`.
3. **Mark it verified.** Set `"verified": true` only once you have confirmed the map against your
   specific car. Until then the app will write it in demo mode but refuse it on real hardware.
4. **Reinstall / clear data.** Definitions are seeded into Room on first launch. To pick up JSON
   changes, clear app storage (or bump `AppDatabase` version) so it re-seeds.

> Tip: keep a copy of each module's original coding block before you change anything, so you can
> restore the exact bytes.

---

## Safety model (enforced in code)

- **No write without a live connection** — `EditCodingViewModel.apply()` returns
  `NeedsConnection` if `ConnectionManager` isn't connected; the command never reaches a transport.
- **Explicit confirmation** — every apply shows a modal with module, coding, and before → after,
  plus any warning, and requires you to tap **Apply coding**.
- **Unverified maps blocked on hardware** — `CodingEngine` throws unless the map is `verified` or
  you're in demo mode.
- **Read-modify-write** — the engine reads the current coding block and edits only the masked
  bits of one byte, leaving the rest of the block untouched.

---

## Notes / limitations

- The bundled `ecuMap`s are illustrative; treat them as a template, not as coding truth.
- `EnetDoipTransport` implements DoIP routing activation + UDS session/RDBI/WDBI. Some modules
  additionally require **SecurityAccess (0x27)** before a write; add a seed/key exchange there if
  your target module demands it.
- This app does **coding only** — it never programs or flashes firmware.
- The previous Expo/React Native prototype (all coding was simulated) informed this app's coding
  definitions and UX; it was reimplemented natively so real DoIP sockets and BLE are available
  without Expo dev-build constraints.
