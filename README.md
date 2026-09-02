# BMW Assistant (Android)

One native Android app for **BMW F10 (5 Series)** owners that consolidates two jobs into a
single codebase:

- **Coding** — change what your car does (turn features on/off, adjust values) in plain English,
  BimmerCode-style. FRM, KOMBI, NBT, HUD, CAS.
- **Diagnostics** — see what your car is doing, BimmerLink-style: read and clear **fault codes
  (DTCs)** and watch **live sensor data** (engine temps, RPM, battery voltage, fuel level…).

Everything runs fully offline in a **demo mode**, and against a real car over an **ENET** cable or
WiFi adapter (HSFZ on a 2012 F10, DoIP on newer gateways) or an **ELM327/STN OBD dongle** over
Bluetooth, BLE or WiFi.

> **This is the one folder for all our BMW Android app code.** Earlier attempts (an Expo/React
> Native prototype and a coding-only native app) have been folded into this single app. There is
> one Gradle module (`:app`), one namespace (`com.bmw.assistant`), and two features (coding and
> diagnostics) sharing one transport/UDS core.

- **Phase 1 research / knowledge base:** [`docs/BMW_CODING_KNOWLEDGE_BASE.md`](docs/BMW_CODING_KNOWLEDGE_BASE.md)
- **Transport audit (2 Sep 2026), including what is still open:** [`docs/AUDIT_2026-09-02.md`](docs/AUDIT_2026-09-02.md)

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
| Transport | **`EcuTransport.transceive(diagAddress, udsRequest)`** | A dumb UDS pipe. `DemoTransport` (offline sim), `EnetHsfzTransport` (HSFZ 6801 — the F10 path), `EnetDoipTransport` (DoIP 13400), `Elm327Transport` (OBD dongles over Bluetooth/BLE/WiFi). The two ENET transports share `FramedTcpTransport`, which owns framing, response correlation and timeouts. |
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
      │  ├─ codings_f10.json                      # coding definitions (26 codings / 5 modules)
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
         │  │  ├─ FramedTcpTransport.kt             # shared ENET machinery (framing, correlation)
         │  │  ├─ FrameReader.kt                    # timeout-safe framed socket reader
         │  │  ├─ EnetHsfzTransport.kt              # HSFZ on TCP 6801 (F-series, incl. F10)
         │  │  ├─ EnetDoipTransport.kt              # DoIP on TCP 13400 (G-series / late F)
         │  │  ├─ EnetDiscovery.kt                  # UDP gateway discovery (6811 / 13400)
         │  │  ├─ TesterPresentKeepAlive.kt         # 0x3E pump so sessions don't lapse
         │  │  ├─ Hex.kt
         │  │  ├─ net/LinkNetwork.kt                # pins sockets to the internet-less ENET link
         │  │  ├─ uds/{Uds.kt, Hsfz.kt, Doip.kt, Dtc.kt}
         │  │  ├─ obd/{Elm327Transport.kt, IsoTp.kt, SerialLink.kt}
         │  │  └─ ble/BleScanner.kt
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
            └─ connection/   (demo / ENET / Bluetooth / BLE / WiFi OBD)
```

---

## Building

Requirements: JDK 17 and the Android SDK (platform 36). Point Gradle at your SDK with a
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
`app-release.apk` from the run's artifacts. The `release` job fails fast with a clear message if the
keystore secret is missing.

**Building a signed release locally** — instead of the CI secrets, drop a git-ignored
`keystore.properties` in the repo root:

```
storeFile=/absolute/path/to/release.jks
storePassword=…
keyAlias=bmwf10
keyPassword=…
```

Then `./gradlew assembleRelease`. Without any keystore configured, the release build still succeeds
but is left **unsigned** (installable only via `adb install` for testing).

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
   write, so the exact original bytes can always be restored. The **Backups** screen (toolbar
   icon on the coding module grid) also offers **Back up all** for a manual snapshot.
2. **Restore** writes the saved bytes back to the module after a confirmation that shows the
   exact bytes. A backup can only be restored onto the same kind of connection it was captured
   from — demo snapshots never reach a real car, and hardware snapshots are never pushed into
   the simulator.

A connection status chip is visible on every screen; the ViewModels block any read/write unless a
connection is live.

---

## Connecting each adapter type

### Demo mode (no hardware)
Connection screen → **Start demo mode**. Coding, faults, and live data are all simulated; safe for
development and demos.

### ENET cable or ENET-WiFi adapter — the fastest, most reliable path

A **2012 F10 gateway speaks HSFZ on TCP 6801**, not DoIP 13400. DoIP is offered as a second option
for G-series and late F-series gateways.

1. Connect the car to your phone over an **ENET cable** (OBD-II ↔ RJ45 with the gateway activation
   wiring — a plain patch cable will **not** work, see the knowledge base §3.1) plus a USB-C
   Ethernet adapter, or join the ENET-WiFi adapter's network.
2. Switch the ignition on.
3. Connection screen → **Find car on network**. The app broadcasts an HSFZ identification request
   on UDP 6811 and a DoIP vehicle-identification request on UDP 13400, then fills in the address
   and protocol of whatever answers. Enter the IP by hand only if discovery finds nothing.
4. **Connect over ENET.**

The app pins its sockets to the ENET link. That matters: an ENET network has no internet, so
Android keeps cellular as the default route and an unpinned socket never reaches the car.

> Coding writes still require `verified: true` maps — see below.

### OBD adapter — Bluetooth, BLE or WiFi

ELM327/STN dongles (vLinker, OBDLink, UniCarScan, generic ELM327 v1.5) reach the modules over
BMW's extended-addressed UDS on the D-CAN bus: tester CAN id `0x6F1`, each module answering on
`0x600 + its diagnostic address`, with the extended-address byte first in every frame. The adapter
is put in raw CAN mode (`ATCAF0`/`ATCFC0`) and the app does ISO-TP segmentation, flow control and
8-byte padding itself.

- **Bluetooth Classic** — pair the adapter in Android's Bluetooth settings first, then Connection
  screen → **Bluetooth – choose paired adapter**.
- **BLE** — Connection screen → **Scan for BLE adapters** → tap yours. Nordic UART, FFE0 and FFF0
  serial profiles are all supported.
- **WiFi** — join the dongle's network, then enter its address (usually `192.168.0.10:35000`).

Coding and diagnostics both work over an OBD dongle, and both go through the same `verified: true`
gate as ENET. Two caveats: a dongle is much slower than ENET (a 150-byte coding block is roughly 25
CAN frames each way), and cheap clones drop frames under load. Every coding write is read back and
compared, so a corrupted transfer is reported and rolled back rather than silently kept — but an
STN-based adapter is strongly recommended for writes.

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
- **Every write is verified** — after a coding write the block is read back and compared. On a
  mismatch the original bytes are written back and the operation fails loudly.
- **Oversized writes refused** — a block that will not fit in one request on the active link is
  rejected rather than truncated on the wire (`EcuTransport.maxRequestLength`).
- **Responses are correlated to requests** — a transport only returns a frame that is the answer to
  the request in flight, from the module it was addressed to, and `UdsClient` additionally checks
  the identifier echoed in a `0x62`/`0x6E` reply. A stale answer can never be read as the contents
  of a different coding block.
- **Backups are bound to a car** — each snapshot records the VIN it was read from, and a restore
  onto a different VIN is refused. The database is excluded from cloud backup and device transfer.
- **One-time disclaimer** — the app is unusable until the safety notice has been acknowledged.

---

## Notes / limitations

- The bundled coding maps and diagnostics DIDs are illustrative; treat them as templates, not truth.
- **No hardware validation yet.** The transports are covered by unit tests against scripted
  gateways and adapters, but nothing in this repository has been exercised against a real F10. Treat
  the first session with a car as a test, starting with read-only diagnostics.
- **SecurityAccess (0x27) is not implemented.** Modules that demand a seed/key exchange before a
  coding write will answer `0x33` (security access denied) and the write will fail cleanly. Adding
  it is the next step for those modules.
- **No ECU reset after a write.** Some modules only apply new coding after `0x11` or a power cycle.
- This app does **coding and diagnostics only** — it never programs or flashes firmware.
- Live-data DIDs are read one at a time; the demo transport adds mild jitter so gauges look alive.
