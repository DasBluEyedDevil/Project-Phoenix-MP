# Protocol Verification Test Sheet

**Purpose:** Compare Project Phoenix BLE commands against official Vitruvian app via HCI packet capture.

**Date:** 2025-11-30
**Tester:** _______________
**Device:** _______________
**Official App Version:** _______________
**Firmware Version:** _______________

---

## Setup Instructions

### Enable HCI Snoop Logging (Android)
1. Settings → Developer Options → Enable Bluetooth HCI snoop log
2. Toggle Bluetooth off/on to start fresh capture
3. Perform test operation in official app
4. Pull log: `adb pull /data/misc/bluetooth/logs/btsnoop_hci.log`
5. Open in Wireshark, filter: `btatt.opcode == 0x12` (Write Command) or `btatt.opcode == 0x52` (Write Request)

### Identifying Vitruvian Traffic
- Look for writes to handle associated with NUS TX UUID: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- Device name starts with `Vee_` or `VIT`

---

## Test Matrix

### 1. CONNECTION & INITIALIZATION

| Operation | Phoenix Bytes | Size | Official App Bytes | Match? | Notes |
|-----------|---------------|------|-------------------|--------|-------|
| **INIT/Reset** | `0A 00 00 00` | 4 | | | Sent on connect |
| **INIT Preset** | `11 00 00 00 00 00 00 00 00 00 00 00 CD CC CC 3E FF 00 4C FF 23 8C FF 8C 8C FF 00 4C FF 23 8C FF 8C 8C` | 34 | | | Coefficient table |

**Test procedure:**
1. Start HCI capture
2. Open official app, connect to machine
3. Stop capture immediately after "Connected" state
4. Look for initial command sequence

---

### 2. WORKOUT START SEQUENCE

| Operation | Phoenix Bytes | Size | Official App Bytes | Match? | Notes |
|-----------|---------------|------|-------------------|--------|-------|
| **Program Params (0x04)** | `04 00 00 00 [reps] 03 03 00 ...` | 96 | | | Full workout config |
| **Echo Control (0x4E)** | `4E 00 00 00 [warmup] [reps] 00 00 ...` | 32 | | | Echo mode config |
| **START** | `03 00 00 00` | 4 | | | Begin workout |

**Test procedure - Old School mode:**
1. Start HCI capture
2. In official app: Select exercise, Old School mode, 50kg, 10 reps
3. Press Start
4. Look for 96-byte write followed by 4-byte `03` command

**Test procedure - Echo mode:**
1. Start HCI capture
2. In official app: Select exercise, Echo mode, any level
3. Press Start
4. Look for 32-byte write starting with `4E`

---

### 3. WORKOUT STOP COMMANDS

| Operation | Phoenix Bytes | Size | Official App Bytes | Match? | Notes |
|-----------|---------------|------|-------------------|--------|-------|
| **STOP (primary)** | `05 00 00 00` | 4 | | | v0.5.0 verified working |
| **STOP (official packet)** | `50 00` | 2 | | | Clears faults, releases tension |
| **RESET (web app style)** | `0A 00 00 00` | 4 | | | Recovery/init |

**Test procedure:**
1. Start workout in official app
2. Start HCI capture
3. Press Stop button
4. Record bytes sent

**Test procedure - fault clear:**
1. Trigger a deload (drop handles suddenly)
2. Start HCI capture
3. Observe how app clears the fault state

---

### 4. LED/COLOR COMMANDS

| Operation | Phoenix Bytes | Size | Official App Bytes | Match? | Notes |
|-----------|---------------|------|-------------------|--------|-------|
| **Color Scheme** | `11 00 00 00 00 00 00 00 00 00 00 00 [brightness f32] [RGB x6]` | 34 | | | Same as INIT Preset structure |

**Test procedure:**
1. Connect to machine in official app
2. Start HCI capture
3. Change LED color in settings
4. Record bytes sent

---

### 5. UNDISCOVERED COMMANDS (if they exist)

These operations are in the official app but NOT in Phoenix. Capture to discover opcodes.

| Operation | Official App Bytes | Size | Opcode | Notes |
|-----------|-------------------|------|--------|-------|
| **Pause Workout** | | | | Press pause during active workout |
| **Resume Workout** | | | | Press resume after pause |
| **Adjust Weight Mid-Workout** | | | | Change weight without stopping |
| **Skip Set** | | | | If routine mode exists |
| **Firmware Update Trigger** | | | | If discoverable |

**Test procedure - Pause/Resume:**
1. Start a workout in official app
2. Start HCI capture
3. Press Pause
4. Wait 2 seconds
5. Press Resume
6. Stop capture, analyze

---

## Quick Reference: Known Phoenix Protocol

### Command Opcodes (First Byte)
```
0x03 - START (4 bytes)
0x04 - PROGRAM params (96 bytes)
0x05 - STOP primary (4 bytes)
0x0A - INIT/RESET (4 bytes)
0x11 - INIT Preset / Color (34 bytes)
0x4E - ECHO control (32 bytes)
0x4F - REGULAR command (25 bytes) - official app deob, not in web app
0x50 - STOP packet (2 bytes) - official app
```

### Characteristic UUIDs
```
NUS Service:  6e400001-b5a3-f393-e0a9-e50e24dcca9e
NUS TX (Write): 6e400002-b5a3-f393-e0a9-e50e24dcca9e  <- Commands go here
NUS RX (Notify): 6e400003-b5a3-f393-e0a9-e50e24dcca9e
Monitor:      90e991a6-c548-44ed-969b-eb541014eae3
Reps:         8308f2a6-0875-4a94-a86f-5c5c5e1b068a
```

### Weight Encoding
- Stored as `u16 little-endian, kg × 10` in some packets
- Stored as `f32 little-endian` in Program params (offsets 0x54, 0x58)
- Example: 44.1 kg = `0x1B9` = bytes `B9 01`

### Reps Encoding
- `0xFF` = unlimited (Just Lift / AMRAP mode)
- Otherwise: `warmup_reps + target_reps` as single byte

---

## Results Summary

| Command | Phoenix | Official | Status |
|---------|---------|----------|--------|
| INIT | `0x0A` | | ⬜ Untested |
| INIT Preset | `0x11` | | ⬜ Untested |
| Program | `0x04` | | ⬜ Untested |
| Echo | `0x4E` | | ⬜ Untested |
| Start | `0x03` | | ⬜ Untested |
| Stop | `0x05` | | ⬜ Untested |
| Stop Packet | `0x50` | | ⬜ Untested |
| Color | `0x11` | | ⬜ Untested |
| Pause | ❌ None | | ⬜ Untested |
| Resume | ❌ None | | ⬜ Untested |
| Adjust Load | ❌ None | | ⬜ Untested |

**Legend:** ✅ Match | ⚠️ Different | ❌ Not found | ⬜ Untested

---

## Notes & Observations

_Use this space to record any interesting findings during testing._

```




```
