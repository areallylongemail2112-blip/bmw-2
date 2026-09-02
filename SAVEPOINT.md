# Save point — BMW F10 coding app (repo: /home/user/workspace/bmw-2)

Updated: 2026-09-02 01:25 PDT

## User's answers
- Car: **2012 BMW F10 5 Series** (CAS4 + FRM3 + JBBF; no FEM/REM). Screenshot categories to be mapped to F10 equivalents.
- Connection: **all options** — ENET cable (USB-C Ethernet), ENET WiFi adapter, Bluetooth/WiFi OBD dongle.

## Key protocol facts (verified)
- 2012 F10 ENET = **HSFZ TCP 6801** (not DoIP 13400). Frame: 4-byte BE length (= payload + 2), 2-byte control word (0x0001 diag, 0x0002 ack, 0x0011 ident, 0x0012 alive, 0x0040+ errors), 1-byte src (tester 0xF4), 1-byte dst (ECU addr), then UDS. Discovery: UDP broadcast port 6811, control 0x0011.
  Source: scapy contrib automotive/bmw/hsfz.py; munich.dissec.to/kb/chapters/doip/doip.html
- D-CAN via OBD (ELM327): 500k 11-bit; tester CAN ID 0x6F1, ECU replies on 0x600+addr; extended addressing (first data byte = ECU addr on TX, 0xF1 on RX). Implemented as raw CAN (ATCAF0/ATCFC0) with software ISO-TP. Source: quantexlab.com/en/develop/elm327.html
- F-series diag addresses: ACSM 0x01, TRSVC 0x06, ZGW 0x10, DME 0x12, EGS 0x18, ICM 0x1C, DSC 0x29, ASD 0x3F, CAS 0x40, FZD 0x56, KOMBI 0x60, HU 0x63, SM 0x6D/0x6E, HUD 0x68, FRM 0x72, IHKA 0x78, JBBF 0x00. Source: github.com/packetpilot/bmw-f

## Files written so far (all uncommitted, in bmw-2)
- core/ecu/uds/Hsfz.kt, core/ecu/uds/Doip.kt (extended), core/ecu/EcuTransport.kt (description, maxRequestLength)
- core/ecu/EnetHsfzTransport.kt, core/ecu/EnetDoipTransport.kt (rewritten), core/ecu/EnetDiscovery.kt, core/ecu/TesterPresentKeepAlive.kt
- core/ecu/obd/SerialLink.kt (TcpSerialLink, BluetoothSppSerialLink, BleSerialLink)
- core/ecu/obd/IsoTp.kt (pure ISO-TP ext-addressing helpers, unit-testable)
- core/ecu/obd/Elm327Transport.kt (AT init, ISO-TP TX/RX, flow control, 0x78 via ATMA, keep-alive)
- core/ecu/ConnectionManager.kt: ConnectionType {DEMO, ENET_HSFZ, ENET_DOIP, OBD_BT, OBD_BLE, OBD_WIFI}; connectEnetHsfz/connectEnetDoip/connectObdBluetooth/connectObdBle/connectObdWifi; state carries supportsCoding/supportsDiagnostics from the transport; udsClient(), transportDescription()
- Deleted core/ecu/ble/BleObdTransport.kt (BleScanner kept)
- res/layout/activity_connection.xml rewritten. New view ids: progress, statusText, vehicleText, disconnectButton, demoButton, discoverButton, ipInput, protocolGroup, protoHsfz, protoDoip, enetButton, btPairedButton, scanButton, bleList, wifiIpInput, wifiPortInput, wifiObdButton

## Plan status
1. Audit — done
2. Research — done
3. Core fixes — mostly done (HSFZ, DoIP, discovery, keep-alive, 0x78). Still to do: CodingEngine maxRequestLength check + optional ECU reset after write; CodingRepository seedVersion re-seed fix (+ DAO deleteAll); optional fdlFunction column (Room v3 migration)
4. OBD adapter transports — code done; **NEXT: rewrite feature/connection/ConnectionViewModel.kt and ConnectionActivity.kt** to match the new layout ids and ConnectionManager API (old connectEnet/connectBle calls no longer exist). Need: discovery button → EnetDiscovery → fill ipInput + select protocol; paired-BT chooser via BluetoothAdapter.bondedDevices AlertDialog (BLUETOOTH_CONNECT permission on API 31+); BLE scan list via BleScanner + BleDeviceAdapter; WiFi OBD connect. Then grep for other users of removed API (`connectEnet`, `connectBle`, `WIFI_ENET`, `ConnectionType.BLE`).
5. F10 module catalog (assets/codings_f10.json) for ASD, ACSM, IHKA, TRSVC, EGS, HU, KOMBI, ICM, FZD, SM_FA, HKL, JBBF, FRM, CAS, PDC + icons in ui/common/Icons.kt + README/docs update + versionCode 3 / 1.1 — pending
6. Build APK (JDK17 at /home/user/jdk17, SDK at /home/user/android-sdk; check logs /home/user/jdk_install.log JDK_DONE, /home/user/sdk_install.log TOOLS_DONE; then sdkmanager licenses + platform-tools, platforms;android-34, build-tools;34.0.0; gradlew assembleDebug testDebugUnitTest). Fallback: push and download CI artifact `bmw-assistant-debug-apk` — pending
7. Commit/push, share APK, final summary — pending

## Safety principle
Never mark fabricated coding byte maps as verified; hardware writes stay gated until the user imports maps from their car's CAFD/NCD. Final answer must state this plainly.
