# Save point — BMW F10 coding app (repo: bmw-2)

Updated: 2026-09-02 (finalize pass)

## User's answers
- Car: **2012 BMW F10 5 Series** (CAS4 + FRM3 + JBBF; no FEM/REM). Screenshot categories mapped to F10 equivalents.
- Connection: **all options** — ENET cable (USB-C Ethernet), ENET WiFi adapter, Bluetooth/WiFi OBD dongle.

## Plan status
1. Audit — done
2. Research — done
3. Core fixes — done (HSFZ, DoIP, discovery, keep-alive, 0x78, maxRequestLength, ECU reset after write, seedVersion re-seed)
4. Connection UI — done (`ConnectionActivity` / `ConnectionViewModel` match the new layout and API)
5. F10 module catalog — done (screenshot control units + F10 extras). Maps remain `verified: false`.
6. Build APK — in progress on this branch
7. Commit/push — in progress

## Safety principle
Never mark fabricated coding byte maps as verified; hardware writes stay gated until the user imports maps from their car's CAFD/NCD.
