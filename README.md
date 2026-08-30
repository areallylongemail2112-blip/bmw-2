# BMW Assistant (Android)

One native Android app for **BMW F10 (5 Series)** owners that consolidates two jobs into a
single codebase:

- **Coding** — change what your car does (turn features on/off, adjust values) in plain English,
  BimmerCode-style. FRM, KOMBI, NBT, HUD, CAS.
- **Diagnostics** — see what your car is doing, BimmerLink-style: read and clear **fault codes
  (DTCs)** and watch **live sensor data** (engine temps, RPM, battery voltage, fuel level…).

Everything runs fully offline in a **demo mode**, and against a real car over an **ENET/DoIP**
connection.

> **This is the one folder for all our BMW Android app code.** Earlier attempts (an Expo/React
> Native prototype and a coding-only native app) have been folded into this single app. There is
> one Gradle module (`:app`), one namespace (`com.bmw.assistant`), and two features (coding and
> diagnostics) sharing one transport/UDS core.

- **Phase 1 research / knowledge base:** [`docs/BMW_CODING_KNOWLEDGE_BASE.md`](docs/BMW_CODING_KNOWLEDGE_BASE.md)
- **Service functions** — oil / brake CBS reset and battery registration (data-driven UDS routines; illustrative maps, same verified-gate as coding)

### A note on BimmerCode / BimmerLink / BimmerTool

Those are **commercial, third-party apps** and are not part of this project. We use them only as
feature-level references for what an F-series coding/diagnostics tool should do — none of their
code, protocols, or assets are copied here. Everything in this repo is an original implementation
built on the public ISO-standard protocols (UDS / DoIP) described in the knowledge base.

---

## ⚠️ Read this before connecting to a real car

Coding writes configuration bytes to your car's modules. Done with **correct, verified** maps it
is low-risk and reversible; done with **wrong byte offsets** it can misconfigure or, in bad cases,
disable a module.

The coding maps bundled in [`app/src/main/assets/codings_f10.json`](app/src/main/assets/codings_f10.json)
and the diagnostics definitions in [`app/src/main/assets/diagnostics_f10.json`](app/src/main/assets/diagnostics_f10.json)
are **illustrative** (`"verified": false` for coding). The app therefore:

- lets you exercise every one of them freely in **demo mode** (nothing physical happens), but
- **refuses to write an unverified coding map to real hardware** (`CodingEngine` throws unless the
  map is `verified: true`).

Reading fault codes and live data is passive; **clearing** fault codes is a standard, reversible
operation (codes for faults still present return on the next drive cycle) and is allowed on real
hardware, always behind a confirmation dialog.

To code a real car you must supply maps verified against *your* car's coding data (its FA/VO and
I-level). See **[Adding / verifying entries](#adding--verifying-entries)**. Always record a
module's original bytes before changing them, and keep the battery charged.

---

## Architecture & decisions

| Area | Choice | Why |
|------|--------|-----|
| Language / UI | **Kotlin + Views + ViewBinding** (no Compose) | Lightweight; keeps the focus on the transport layer. |
| Pattern | **MVVM** | ViewModels own state and enforce the connection guard so no screen can write/read without a live link. |
| Persistence | **Room** (coding) + **in-memory** (diagnostics defs) | Coding modules/values are stored locally and offline; diagnostics definitions are read-only, loaded from a JSON asset. |
| Transport | **`EcuTransport.transceive(diagAddress, udsRequest)`** | A dumb UDS pipe. `DemoTransport` (offline sim), `EnetDoipTransport` (real DoIP/UDS), `BleObdTransport` (handshake only). TesterPresent keepalive + optional SecurityAccess (pluggable seed-to-key; no BMW algorithm is shipped). |
| Protocol | **`UdsClient`** | One place that sequences sessions and turns negative responses into readable errors. Both engines build on it. |
| Coding | **`CodingEngine`** | Read-modify-write of one coding byte, with the verified-map safety gate. |
| Diagnostics | **`DiagnosticsEngine`** | Fault read/clear (UDS 0x19/0x14) and live-value read/decode (UDS 0x22). |

minSdk 26 · targetSdk/compileSdk 34 · Java 17 · single APK.

### Project layout

```
├─ build.gradle.kts / settings.gradle.kts        # Gradle (Kotlin DSL)
├─ docs/BMW_CODING_KNOWLEDGE_BASE.md              # Phase 1 research
└─ app/
   ├─ build.gradle.kts                            # deps: Room, lifecycle, coroutines, material, gson
   └─ src/main/
      ├─ AndroidManifest.xml                      # INTERNET + BLE perms, 7 activities
      ├─ assets/
      │  ├─ codings_f10.json                      # coding definitions (37+ codings / 6 modules)
      │  └─ diagnostics_f10.json                  # live params + DTC catalog + demo faults
      ├─ res/                                      # layouts, drawables, theme
      └─ java/com/bmw/assistant/
         ├─ BmwAssistantApp.kt                     # Application; seeds Room + loads diagnostics
         ├─ core/
         │  ├─ ecu/
         │  │  ├─ EcuTransport.kt                  # transport interface (transceive + capabilities)
         │  │  ├─ UdsClient.kt                     # session + RDBI/WDBI/ReadDTC/ClearDTC helpers
         │  │  ├─ ConnectionManager.kt             # app-scoped connection state (singleton)
         │  │  ├─ DemoTransport.kt                 # offline simulation (coding + diagnostics)
         │  │  ├─ EnetDoipTransport.kt             # real ENET/DoIP + UDS
         │  │  ├─ Hex.kt
         │  │  ├─ uds/{Uds.kt, Doip.kt, Dtc.kt}    # ISO 14229 + ISO 13400 + DTC parsing
         │  │  └─ ble/{BleScanner.kt, BleObdTransport.kt}
         │  ├─ coding/CodingEngine.kt              # value <-> byte encode/decode + safety gate
         │  └─ diagnostics/{DiagnosticsEngine.kt, LiveDecoder.kt}
         ├─ data/
         │  ├─ model/                              # CodingItem, Module, EcuMap, LiveParameter, Dtc…
         │  ├─ db/                                 # Room entities, DAO, AppDatabase
         │  ├─ CodingRepository.kt                 # coding defs + values (Room)
         │  └─ DiagnosticsRepository.kt            # live params + DTC catalog + demo faults (asset)
         ├─ ui/
         │  ├─ home/HomeActivity.kt                # the hub: Coding · Diagnostics · Connection
         │  └─ common/                             # ConnectionBadge, Icons, Event
         └─ feature/
            ├─ coding/       (module grid → coding list → edit)
            ├─ diagnostics/  (module grid → faults + live data)
            └─ connection/   (demo / ENET / BLE)
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
./gradlew test                 # run the JVM unit tests (protocol + coding/diagnostics logic)
```

Or open the folder in Android Studio (Giraffe or newer) and Run.

### Download a prebuilt APK

Every push to `main` (and every PR) runs the
[**Android CI**](.github/workflows/android.yml) GitHub Actions workflow, which assembles the
debug APK, runs the unit tests, and uploads the APK as a build artifact named
`bmw-assistant-debug-apk`. To install it on
a phone without building locally: open the workflow run in the **Actions** tab → download the
artifact → unzip → sideload `app-debug.apk` (enable "install from unknown sources" for your
browser/Files app when prompted). The build is debug-signed — fine for your own device, not for
distribution.

### Release signing

A **signed release APK** (`bmw-assistant-release-apk` artifact) is produced by the
[**Release APK**](.github/workflows/build-apk.yml) workflow
on tag pushes matching `v*` and on manual **Run workflow** dispatch. Signing credentials come from
environment variables at build time, so nothing secret lives in the repo.

**One-time setup — generate a keystore** (keep the `.jks` file and its passwords somewhere safe;
losing them means you can never ship an update that overwrites an install):

```bash
keytool -genkeypair -v -keystore release.jks -alias bmwf10 \
  -keyalg RSA -keysize 2048 -validity 10000
```

**Add the credentials as GitHub Actions secrets** (repo → Settings → Secrets and variables →
Actions):

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | `base64 -w0 release.jks` (the keystore, base64-encoded) |
| `RELEASE_KEYSTORE_PASSWORD` | the store password |
| `RELEASE_KEY_ALIAS` | the key alias (e.g. `bmwf10`) |
| `RELEASE_KEY_PASSWORD` | the key password |

Then push a tag (`git tag v1.0.0 && git push origin v1.0.0`) or run the workflow manually; download
`app-release.apk` from the run's artifacts.

If the keystore secrets are not set, the workflow still succeeds: it builds a **debug-signed**
release APK (installable by sideload) and records a warning on the run. Add the secrets above
when you want a stable signature that can overwrite previous installs.

**Building a signed release locally** — instead of the CI secrets, drop a git-ignored
`keystore.properties` in the repo root:

```
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=bmwf10
keyPassword=…
```

Then `./gradlew assembleRelease`. Without any keystore configured, the release APK is still
**debug-signed** so it can be sideloaded like the debug build. Add `keystore.properties` (or the
CI secrets) when you need a stable signature that can overwrite previous installs.

### Try it with no hardware

Launch → tap the **connection chip** (or the **Connection** card) → **Start demo mode**. The app
populates realistic coding values, live sensor readouts, and a couple of stored fault codes;
everything is fully interactive and simulated against a fake car.

---

## Using the app

The home screen is a hub with two paths plus connection.

**Coding**
1. Pick a module (FRM, KOMBI, NBT, HUD, CAS) from the card grid.
2. Each coding shows a plain-English summary, an expandable **"What does this do?"**, its current
   value, and **Edit**.
3. Edit shows the safe range/options, a warning banner for sensitive codings, and the right input
   control (toggle / dropdown / slider / hex). Applying pops a **confirmation modal** with the
   exact before → after change.

**Diagnostics**
1. Pick a module. Every module can report faults; the engine module (**DME**) also exposes live data.
2. **Fault codes** — tap **Scan** to read stored DTCs (shown with an SAE-style code, the raw hex,
   a status, and a plain-English description where known). **Clear** erases fault memory (with
   confirmation).
3. **Live data** — read current sensor values once with **Refresh**, or flip **Auto** to poll
   continuously while the screen is open.

**Backups (restore points)**
1. A snapshot of a module's coding block is captured automatically right before every coding
   write, so the exact original bytes can always be restored. The **Backups** screen (home card
   and the toolbar icon on the coding module grid) also offers **Back up all** and **Export**.
2. **Restore** writes the saved bytes back to the module after a confirmation that shows the
   exact bytes. A backup can only be restored onto the same kind of connection it was captured
   from — demo snapshots never reach a real car, and hardware snapshots are never pushed into
   the simulator. Backups are tagged with VIN / I-level when the car was identified.

**Coding values are labelled by source** — *From car*, *Local cache*, or *Default*. Opening a
module (or tapping **Read from car**) decodes the live coding block. Apply is disabled until a
successful ECU read so you never edit a stale default. Import a verified-map JSON from the
coding overflow menu. Bump `assetVersion` in the bundled JSON to re-seed Room without wiping
the app.

On connect the app reads VIN (`0xF190`), probes known modules, and starts a TesterPresent
keepalive. BLE remains handshake-only.

A connection status chip is visible on every screen; the ViewModels block any read/write unless a
connection is live.

---

## Connecting each adapter type

### Demo mode (no hardware)
Connection screen → **Start demo mode**. Coding, faults, and live data are all simulated; safe for
development and demos.

### ENET / WiFi — the coding + diagnostics path
This is the transport that can actually write F10 coding **and** read live UDS diagnostics (DoIP +
UDS, the E-Sys/ISTA family).

1. Connect the car to your phone/laptop network over an **ENET cable** (OBD-II ↔ RJ45; a plain
   patch cable will **not** work — the cable needs the gateway activation wiring, see the knowledge
   base §3.1) or via the gateway/adapter WiFi.
2. Put your device on the same subnet as the car (E-Sys convention: gateway `192.168.0.10`, device
   something like `192.168.0.20`; F-series may also use `169.254.x.y` link-local).
3. Connection screen → enter the **IP** (default `192.168.0.10`) and **port** (`13400`) →
   **Connect over ENET**.
4. The app performs the DoIP routing-activation handshake, then addresses each module by its
   diagnostic address.

> Coding writes still require `verified: true` maps — see below.

### Bluetooth (BLE) OBD adapter
Connection screen → **Scan for BLE adapters** (grant Bluetooth permission) → tap your adapter. The
app connects over the Nordic UART service and runs an ELM327 reset/echo-off handshake.

BLE is provided for **connection/handshake** only: consumer BLE adapters expose ELM327 serial
access, not the module-addressed UDS this app uses, so `BleObdTransport.supportsCoding` and
`supportsDiagnostics` are both `false` and the app routes coding and diagnostics to ENET or demo
mode. `BleObdTransport` is the place to add an ELM327↔UDS bridge if you have one for your adapter.

---

## The definition schemas

### Coding — `assets/codings_f10.json`

Two arrays: `modules` and `codings`. A **module** has an `id`, `name`, `fullName`, `description`,
`iconName` (`zap`, `activity`, `monitor`, `eye`, `key`, `engine`), and a `diagAddress` (decimal
DoIP/UDS address). A **coding** maps a friendly value to one coding byte:

```json
{
  "id": "frm_cornering_lights", "moduleId": "frm", "name": "Cornering Lights",
  "description": "…", "longDescription": "…",
  "valueType": "boolean",              // boolean | enum | integer | hex
  "defaultValue": "false", "safeDefault": "false", "demoValue": "true",
  "ecuMap": {
    "dataIdentifier": 12288,           // UDS DID (decimal; 12288 = 0x3000)
    "byteOffset": 0, "bitMask": 1,     // byte + bits this feature owns (255 = whole byte)
    "encodedValues": { "true": "0x01", "false": "0x00" },
    "scale": 1.0,                      // integer: raw = round(value / scale)
    "verified": false                  // MUST be true to write to real hardware
  }
}
```

| `valueType` | Control | Encoding |
|-------------|---------|----------|
| `boolean`   | Toggle  | `encodedValues["true"/"false"]` under `bitMask` |
| `enum`      | Dropdown of `options` | `encodedValues[optionValue]` under `bitMask` |
| `integer`   | Slider (`min`–`max`)  | `round(value / scale)` under `bitMask` |
| `hex`       | Validated hex field   | raw byte under `bitMask` |

### Diagnostics — `assets/diagnostics_f10.json`

```json
{
  "liveData": [
    { "id": "dme_coolant_temp", "moduleId": "dme", "name": "Coolant temperature",
      "dataIdentifier": 17920,          // UDS DID (decimal; 17920 = 0x4600)
      "byteLength": 1, "scale": 1.0, "offset": -48.0, "unit": "°C",
      "demoRaw": "89" }                 // raw hex the demo transport returns
  ],
  "dtcCatalog": [
    { "code": "2C6A", "description": "Boost pressure control: positive deviation." }
  ],
  "demoFaults": [
    { "moduleId": "dme", "dtc": "2C6A08", "status": 9 }   // 3-byte DTC + status byte
  ]
}
```

A live value is decoded as `raw * scale + offset`, reading `byteLength` bytes big-endian at
`byteOffset`. `dtcCatalog` is keyed by a DTC's high 16 bits (4 hex digits) and supplies the
plain-English text. `demoFaults` is what the offline demo transport pretends each module has stored.

> Like the coding maps, the diagnostics DIDs/scales are **illustrative** and drive demo mode; real
> DIDs vary by ECU and I-level.

---

## Adding / verifying entries

**Coding:** add an object to `codings` referencing an existing `moduleId`; find the real map with
E-Sys (the module's CAFD/NCD) or a known-good cheat-sheet for your car's I-level — the DID, byte
offset, bit mask, and raw value per option — and set `"verified": true` only once confirmed against
your specific car. Until then the app writes it in demo mode but refuses it on real hardware.

**Diagnostics:** add a `liveData` entry with the module's real DID/scale/offset, and `dtcCatalog`
entries for any fault codes you want described in plain English.

Definitions are seeded into Room / loaded from the asset on first launch. To pick up JSON changes,
clear app storage (or bump `AppDatabase` version) so it re-seeds.

---

## Safety model (enforced in code)

- **No read/write without a live connection** — the ViewModels return early if `ConnectionManager`
  isn't connected; the command never reaches a transport.
- **Explicit confirmation** — every coding apply and every fault clear shows a modal first.
- **Unverified coding maps blocked on hardware** — `CodingEngine` throws unless the map is
  `verified` or you're in demo mode.
- **Read-modify-write** — the coding engine reads the current block and edits only the masked bits
  of one byte, leaving the rest untouched.
- **Capability-gated transports** — a transport that can't do coding or diagnostics says so, and the
  UI routes around it instead of sending bytes it can't handle.

---

## Notes / limitations

- The bundled coding maps, diagnostics DIDs, and service routine IDs are illustrative; treat them as templates, not truth.
- `EnetDoipTransport` implements DoIP routing activation + UDS session/RDBI/WDBI/ReadDTC/ClearDTC
  plus TesterPresent keepalive. Modules that require **SecurityAccess (0x27)** will request a seed
  and call a registered `SecurityKeyProvider`. This repo ships only a demo XOR provider — not a
  BMW seed-to-key algorithm. Hardware writes still need `verified: true` maps.
- This app does **coding, diagnostics, and data-driven service routines only** — it never programs or flashes firmware.
- Live-data DIDs are read one at a time; the demo transport adds mild jitter so gauges look alive.
