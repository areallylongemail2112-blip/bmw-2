# Phase 1 — BMW Coding Knowledge Base

A practical reference for how BMW coding tools, the F10 platform, and the underlying
connectivity protocols actually work. This is the research that the Android app in this
repository is built on. It is written to be read by a developer, not a dealer technician —
where BMW uses internal jargon, the plain-English meaning is given.

> Scope note: BMW's coding data (PSdZData) and the exact byte maps per module are BMW
> proprietary. Nothing here reproduces that data. This document explains *mechanisms* so the
> app can talk to a car correctly and so a user can supply their own verified coding maps.

---

## 1. BMW Coding Tools

### 1.1 The three things people mean by "coding"

These words get used interchangeably but are different operations with very different risk:

| Operation      | What it changes                                   | Risk | Tools |
|----------------|---------------------------------------------------|------|-------|
| **Coding**     | Configuration bytes in a module (feature on/off)  | Low–medium | BimmerCode, E-Sys, ISTA |
| **Programming**| Re-writes a module's parameter/data set (I-level) | Medium | E-Sys, ISTA-P |
| **Flashing**   | Replaces the module's firmware/software           | High (can brick) | E-Sys, ISTA-P |

This app does **coding only** — editing configuration bytes. It never flashes firmware.

### 1.2 BimmerCode

- **What it is:** A consumer iOS/Android app for coding BMW/Mini modules without a laptop.
- **Architecture:** The app ships with pre-built, per-vehicle coding maps (it knows which
  byte/bit in which module controls which feature, for each chassis and often each I-level).
  The user never sees raw NCD data; they see friendly toggles. This is exactly the model this
  project uses: a JSON definition of *feature → module → byte/bit → allowed values*.
- **How it talks to the car:**
  - **OBD-II adapter** plugged into the car's OBD port. Supported adapters are a short list
    (OBDLink CX, OBDLink MX+, vLinker MC+/FD, etc.) because coding needs a fast, reliable
    transport with correct multi-frame (ISO-TP) handling — generic ELM327 clones are rejected.
  - **WiFi adapters** (e.g. OBDLink MX+): the adapter creates its own WiFi hotspot; the phone
    joins it and talks to the adapter at a fixed IP (commonly `192.168.0.10:35000`). The app
    speaks a serial/STN command protocol to the adapter, which bridges to the car's CAN/ENET.
  - **Bluetooth adapters:** BimmerCode supports BLE (Bluetooth Low Energy) adapters, not
    classic SPP. BLE pairing is done inside the app (scan → select → GATT connect). Latency
    over BLE is higher and less deterministic, which is why WiFi is preferred for large coding
    sessions.
- **Protocol behaviour:** BimmerCode issues UDS (ISO 14229) requests — session control, read
  data by identifier, write data by identifier — wrapped in whatever the adapter needs
  (ISO-TP over CAN for older cars, DoIP for the newest). F-series uses a mix (see §3).
- **F-series support:** Full. F-series is the sweet spot for BimmerCode: modern enough for
  rich coding, old enough that the maps are well understood. The F10 (5 Series) is fully
  supported for the kinds of codings in this app (lighting, cluster, iDrive, comfort access).

### 1.3 BimmerLink

- **Same company, different purpose.** BimmerLink is a *diagnostics* app: live sensor data,
  fault-code (DTC) reading and clearing, service resets, battery registration, performance
  measurements.
- **Coding vs diagnostics distinction:** BimmerLink can read/clear faults and do adaptations
  and service functions, but it is **not** a general coding tool — it will not flip arbitrary
  feature bytes the way BimmerCode does. The rule of thumb: *BimmerCode changes what the car
  does; BimmerLink tells you what the car is doing.*
- Relevance to this app: our "Connection" layer is coding-oriented, but the same transport
  (UDS over DoIP/OBD) is what a diagnostics app would use for ReadDTC/ClearDTC.

### 1.4 E-Sys (the dealer-grade coding tool)

- **What it is:** BMW's engineering coding/programming application for F/G/I-series. Far more
  powerful and far less guard-railed than BimmerCode.
- **PSdZData:** E-Sys is useless without **PSdZData** — a multi-gigabyte data package from BMW
  containing, per module and per I-level, the coding data definitions (CAFD/NCD), the flash
  container (SWFL/BTLD/SWFK), and the description files (CAFD → human-readable via a separate
  data set). PSdZData is versioned by *I-level* (integration level), e.g. `F010-21-03-500`.
- **Two coding philosophies in E-Sys:**
  - **FDL / expert coding:** You open a module's coding data (CAFD), navigate a tree of
    functions, and edit individual values (`werte`). This is byte/bit editing with friendly
    names supplied by the data. This is the closest analogue to what this app does.
  - **FA / VO coding (vehicle order coding):** You edit the car's **FA (Fahrzeugauftrag /
    Vehicle Order)** — the list of factory option codes (e.g. `$205` automatic, `$8KA`,
    `+`/`-` option strings) — then tell E-Sys to *calculate and code* the affected modules so
    they match the new order. The module's coding is regenerated from the VO rather than
    hand-edited. Adding/removing an option code (like `5AC` Lane Departure) and re-coding the
    module is the "FA/VO" workflow.
- **How it connects:** Ethernet, via an **ENET cable** (OBD-II ↔ RJ45) direct to a laptop, or
  through an **ICOM** interface. E-Sys uses **DoIP** (Diagnostics over IP) to reach the car's
  gateway. See §3 for the IP scheme.
- **Coding vs flashing in E-Sys:** "Code" (FDL/VO) only rewrites the coding data (NCD) — safe,
  reversible if you saved the original. "Flash"/"Program" (TAL processing) rewrites firmware
  and data to a target I-level — this is where a dropped connection or low battery can brick a
  module. This app deliberately implements only the coding side.

### 1.5 E-Sys Premium / E-Sys Launcher

- **E-Sys Launcher (e.g. by TokenMaster):** A wrapper around stock E-Sys that supplies the
  **token** and **PIN** that would otherwise gate access, and manages PSdZData paths, mapping
  files, and a launcher UI.
- **What "premium" unlocks:**
  - **Token-based coding:** A launcher token authorises coding/programming without a genuine
    BMW dealer login. Some launchers distinguish a free *expert mode* (FDL coding you can do
    with just the launcher) from *premium* features.
  - **Premium features** typically add: automated VO/FA coding helpers, "cheat sheets" that
    map friendly feature names to CAFD values, batch coding, guided procedures, and plugin
    support.
  - **Plugin ecosystem:** Community plugins add one-click codings (the same friendly-name →
    byte-value mapping this app hard-codes in JSON), FA editors, and profile managers.
- **Token vs expert mode:** *Expert mode* = you find and edit the values yourself in the CAFD
  tree. *Token/premium* = the tool applies a curated change for you. Our app is philosophically
  "curated" (JSON-defined codings) with an expert escape hatch (raw hex value type).

### 1.6 ISTA (Rheingold / ISTA-D / ISTA-P)

- **ISTA-D (Diagnosis), a.k.a. Rheingold:** The dealer diagnostic application. Guided
  fault-finding, test plans, live values, service functions, and *some* coding as part of
  repair procedures. It is diagnosis-led: you rarely free-code in ISTA-D; you run a procedure
  that codes as a side effect.
- **ISTA-P (Programming):** The programming/flashing side — brings modules to a target I-level,
  handles the full TAL (Technical Action List) of flash + code + calibrate steps. This is the
  heavy, high-risk path.
- **How ISTA handles F-series ECUs:** Over DoIP (ENET/ICOM), same transport family as E-Sys. It
  reads the car's actual module list and I-levels from the gateway and works against PSdZData.
- **Connection:** **ICOM A2/A3** (a professional VCI that bridges OBD to Ethernet and adds
  a stable programming-grade link) or a plain **ENET cable** for many F-series operations.
- **Difference from pure coding tools:** ISTA is a *service* tool — its mental model is "repair
  this car." BimmerCode/E-Sys-expert are *modification* tools — "change this feature." They
  share the UDS/DoIP plumbing but differ entirely in workflow and guard rails.

---

## 2. BMW F10 Coding Specifics

### 2.1 Module / ECU map (the ones this app targets and their neighbours)

The F10 is a FlexRay/CAN car with a central gateway (**ZGW**). Common codeable modules:

| Short | Full name (BimmerCode-style)     | Typical diag address | F10 notes |
|-------|----------------------------------|----------------------|-----------|
| ASD   | Active Sound Design              | 0x3F                 | Optional speaker |
| ACSM  | Advanced Crash Safety Module     | 0x01                 | Seat belt reminder |
| IHKA  | Air Conditioning                 | 0x78                 | Climate |
| TRSVC | Allround View Camera             | 0x06                 | Side/rear cameras if fitted |
| EGS   | Electronic Transmission Control  | 0x18                 | Shift character |
| FRM   | Front Electronic Module          | 0x72                 | **FRM3** on F10 (no FEM). Lighting, mirrors |
| HU    | Headunit (CIC on 2012)           | 0x63                 | iDrive, video, audio |
| KOMBI | Instrument Cluster               | 0x60                 | Gauges, warnings |
| ICM   | Integrated Chassis Management    | 0x1C                 | ACC, driving mode |
| JBBF  | Rear Electronic Module           | 0x00                 | **JBBF** on F10 (no REM). PDC, windows |
| FZD   | Roof Function Center             | 0x56                 | Siren, interior light |
| SM    | Seat Module Driver               | 0x6D                 | Seat heat |
| HKL   | Tailgate Function Module         | 0x6C                 | Power tailgate if fitted |
| PDC   | Park Distance Control            | 0x07                 | Sensors |
| CAS   | Car Access System                | 0x40                 | Keys, locking |
| HUD   | Head-Up Display                  | 0x68                 | Windshield projection |
| DME   | Engine Control                   | 0x12                 | Auto Start-Stop on F10 |
| ZGW   | Central Gateway                  | 0x10                 | HSFZ/DoIP entry; not user-coded |

> The diagnostic addresses above are the well-known F-series logical addresses; the app stores
> them per module and uses them as the DoIP **target address** when talking over ENET. They are
> not secret, but the *coding byte maps* inside each module are — supply your own verified maps.

### 2.2 Common codeable features (what owners actually change)

- **FRM (lighting):** Cornering lights via fog lamps, DRL on/off and brightness, welcome/
  farewell "goodbye" light animations, angel-eye/DRL behaviour, ambient light colour, comfort
  turn-signal blink count.
- **KOMBI (cluster):** Startup **gauge/needle sweep**, **digital speedometer**, oil-temp gauge
  in the spare dial, km/h↔mph, unlock a lap timer, startup gong on/off, service-interval text.
- **NBT (iDrive):** **Video in motion** (removes the in-motion video lock — passengers only,
  and illegal for the driver in most places), speed-limit-info display, reveal ambient-light
  menu, Sport Display page, screen auto-off timer, enable Apple CarPlay (region dependent).
- **HUD:** Speed unit, turn-by-turn nav arrows, default brightness, over-speed flash warning.
- **CAS (access):** Auto-lock-at-speed threshold, remote window open/close from the fob,
  comfort-access touch unlock, single-press driver-door-only unlock, horn chirp on lock,
  auto re-lock timer.
- **Assistance (KAFAS/others):** Lane-departure warning default state, etc.

### 2.3 Coding vs programming vs flashing — what is safe at the byte level

- **Safe (this app):** Editing a **configuration byte** in a module's coding data (NCD). Worst
  realistic case for a *wrong but in-range* value is a feature that misbehaves or a stored
  fault; re-coding the correct value fixes it. Always record the original bytes first.
- **Risky (not this app):** Programming to a new I-level or flashing firmware. A failure here
  (power loss, cable pull) can leave a module unbootable. Requires a battery charger and a
  rock-solid link.
- **Golden rules the app enforces:** never write without a confirmed connection; always show
  the before/after and require explicit confirmation; refuse to push an *unverified* byte map
  to real hardware; keep the safe default visible.

### 2.4 FA/VO coding vs SWE / FDL coding

- **FA (Fahrzeugauftrag / Vehicle Order):** The car's build sheet — a set of option codes. In
  **VO coding**, you change the order and let the tool regenerate each module's coding to match.
  Good for enabling a whole factory option; blunt for single tweaks.
- **SWE (Software Element)** and **FDL (function data list) coding:** Direct editing of a
  module's coding values by function name. Precise; this is what a "toggle one feature" app
  models. This project is an FDL-style tool with curated definitions.

### 2.5 NCD / CAFD — how allowed values are defined per module

- **CAFD (Coding Application Function Data):** The *definition* of a module's codeable functions
  for a given I-level — the tree of functions, each with allowed values and friendly names. It
  is the "schema."
- **NCD (Network Coding Data):** The *instance* — the actual coding bytes currently written to
  (or being written to) the module. Editing coding = producing a new NCD from the CAFD + your
  chosen values, then writing it.
- **Why per-module allowed values matter:** A value legal in one module/I-level may be invalid
  in another. That is exactly why this app's JSON carries per-coding `min`/`max`/`options` and a
  per-coding `ecuMap` with a `verified` flag — the definitions must match the specific car.

---

## 3. Connectivity Protocols

### 3.1 ENET (Ethernet) — the coding-grade link for F-series

- **What it is:** Direct 100BASE-TX Ethernet from the car's OBD-II connector to a laptop/phone.
  The car's gateway (ZGW) speaks **DoIP** over this link.
- **ENET cable pinout (OBD-II ↔ RJ45):** The cable crosses specific OBD pins to the Ethernet
  pairs and ties an activation line so the gateway enables its Ethernet interface:
  - OBD **pin 8** → Ethernet **TX+** (RD+)
  - OBD **pin 16 (battery +)** is also linked to **pin 8** through a resistor in many cables to
    signal "diagnostic Ethernet requested" (activation line)
  - OBD **pin 3** → Ethernet **RX+**
  - OBD **pin 11** → Ethernet **RX−**
  - OBD **pin 12** → Ethernet **TX−**
  - OBD **pin 4/5** → **GND**
  A plain patch cable will not work; the activation wiring is what makes the gateway present a
  DoIP endpoint.
- **IP addressing scheme:** F-series uses link-local / fixed private addressing:
  - The car/gateway typically answers at **`169.254.x.y`** (APIPA link-local) or a fixed
    **`192.168.x.x`** depending on tool config; E-Sys commonly targets **`192.168.0.10`** for
    the VCI/gateway with the laptop on the same subnet.
  - Real vehicle module addresses are derived from the last octets of the gateway's link-local
    address in some setups; for app purposes the user enters the gateway IP and the app uses
    DoIP logical addresses (§2.1) to reach modules.
  - **DoIP TCP port is `13400`.** UDP `13400` is used for vehicle announcement/discovery.
- **This app:** the ENET transport opens a TCP socket to the entered IP:13400, does the DoIP
  **routing activation** handshake, then carries UDS in DoIP diagnostic messages.

### 3.2 OBD-II WiFi adapters (OBDLink CX, vLinker MC+, OBDLink MX+)

- **How they present:** The adapter is a Bluetooth-LE or WiFi bridge between the phone and the
  car's OBD pins. WiFi models expose a TCP server (often `192.168.0.10:35000`) that speaks a
  serial command protocol (ELM327 AT commands plus STN extensions on OBDLink's STN chips).
- **How a coding app uses one over WiFi:** phone joins the adapter's hotspot → opens a TCP
  socket to the adapter → sends `ATSH`/`STP` style commands to select the CAN header/protocol →
  streams UDS requests, letting the adapter handle ISO-TP segmentation. Good STN-based adapters
  do multi-frame correctly, which is why coding tools whitelist them.
- **Why not every ELM327:** Cheap clones drop frames, mishandle ISO-TP flow control, and add
  latency — fine for reading a DTC, unsafe for writing coding blocks.

### 3.3 Bluetooth OBD adapters — pairing and why coding tools are cautious

- **Pairing flow (BLE):** scan for the adapter → connect GATT → discover the serial service
  (commonly the **Nordic UART Service**, UUID `6E400001-…`) → enable notifications on the TX
  characteristic → write commands to the RX characteristic. (Classic Bluetooth SPP uses an
  RFCOMM socket instead; most modern adapters are BLE.)
- **Latency considerations:** BLE connection interval and MTU limit throughput; a coding block
  is many round-trips (session control, read, write, verify), so BLE is slower and more prone to
  timeouts mid-write than WiFi/ENET.
- **Why some tools avoid BT for coding:** A dropped link *during a write* is the danger. WiFi
  and ENET give a steadier pipe, so tools steer heavy coding to those. This app reflects that:
  BLE is offered for connection/diagnostics, and coding **writes** are directed to ENET or demo
  mode (the BLE transport reports `supportsCoding = false`).

### 3.4 DoIP vs legacy K-line/CAN for F-series

- **K-line (ISO 9141 / KWP2000 over a serial line):** Old (E-series). Not used for F-series
  coding of the modules here.
- **CAN + ISO-TP + UDS:** The workhorse for much of the F-series. UDS requests are segmented
  into CAN frames by ISO 15765-2 (ISO-TP); the adapter or stack reassembles multi-frame
  responses. This is the path a good OBD adapter uses.
- **DoIP (ISO 13400):** Diagnostics over IP — UDS carried over TCP/IP via the ENET/ICOM link.
  Faster, no 8-byte-frame segmentation to worry about at the app layer (the gateway delivers the
  whole UDS payload in one diagnostic message), and the standard for programming-grade work.
  This is what E-Sys/ISTA use and what this app's coding-capable transport implements.

---

## 4. How the app applies this knowledge

- The JSON asset is an **FDL-style curated definition** (feature → module → byte/bit → allowed
  values), mirroring how BimmerCode and E-Sys "cheat sheets" work.
- The transport layer speaks **UDS over DoIP** for real coding (the E-Sys/ISTA path), with a
  full offline **demo** transport so the app is usable and safe to develop against with no car.
- Safety mirrors dealer-tool discipline: confirmed connection required, explicit before/after
  confirmation, safe defaults surfaced, and a hard block on writing **unverified** byte maps to
  real hardware.

See `README.md` for how to connect each adapter type and how to add or verify coding entries.

---

## 5. Diagnostics (reading what the car is doing)

The app also covers the *read-only* side that tools like BimmerLink focus on. This rides the same
UDS-over-DoIP transport as coding — no new protocol, just different services.

### 5.1 Fault codes (DTCs)

- **Read:** UDS `ReadDTCInformation` (`0x19`), sub-function `reportDTCByStatusMask` (`0x02`) with a
  status mask of `0xFF` to list everything. The response is `59 02 <availabilityMask>` followed by
  records of **3 bytes of DTC + 1 status byte**.
- **Clear:** UDS `ClearDiagnosticInformation` (`0x14`) with a 3-byte group-of-DTC (`0xFFFFFF` =
  all). This is standard and reversible — codes for faults still present simply reappear — so it is
  allowed on real hardware, behind a confirmation dialog.
- **Codes:** BMW modules number faults in their own space, so the raw 24-bit hex code is the most
  reliable identifier. The app also renders the ISO 15031-6 / SAE J2012 form (letter + 4 hex digits,
  e.g. `P2C6A`) from the first two bytes for familiarity, and looks up a plain-English description
  from a small catalog keyed on the high 16 bits.

### 5.2 Live data (measurement values)

- **Read:** UDS `ReadDataByIdentifier` (`0x22 <DID>`), then decode the payload as
  `raw * scale + offset` over a 1- or 2-byte big-endian window. Typical values: coolant/oil/intake
  temperature (`raw − 48` °C), engine RPM (`raw / 4`), vehicle speed, battery voltage, engine load,
  fuel level.
- Most engine live data comes from the **DME**; the app defines these per module in
  `diagnostics_f10.json`.

> As with the coding maps, the exact DIDs and scaling vary by ECU and I-level and are BMW
> proprietary. The values shipped in the asset are **illustrative** and drive the offline demo
> transport; matching them to a specific car is the same exercise as verifying a coding map.

