# RF Explorer 6G Combo — Android App: Build Spec / Kickoff

## Goal

Build a native Android app that connects to an **RF Explorer 6G Combo** over USB-OTG, drives it via its UART API, and renders live spectrum sweeps. No app currently exists on Android (the two that used to are gone), so this is greenfield. Target eventual **Play Store** release, so structure, signing, and store policy compliance matter from the start.

Build incrementally and validate each layer. The protocol layer must be unit-testable **without hardware**; the transport layer can only be confirmed against my physical unit, so flag clearly when we hit a step I need to test on-device.

---

## Hardware / connection facts (confirmed)

- **Device:** RF Explorer 6G Combo = 6G mainboard + WSUB3G expansion module (two RF circuits, switchable).
- **Serial bridge:** Silicon Labs **CP210x** USB-to-UART. Use `usb-serial-for-android` (mik3y), which ships a `Cp21xxSerialDriver`.
- **Line settings:** **500000 baud**, 8 data bits, no parity, 1 stop bit, no flow control. (2400 is the only other reliable rate and is fallback-only.)
- **VID/PID:** Silicon Labs VID is `0x10C4`; CP2102 PID is typically `0xEA60`. **Verify by enumerating my actual unit** — RF Explorer may have a customized EEPROM PID/description string. If it doesn't match the driver's built-in probe table, add a custom probe entry.
- Device must be **actively sweeping** (not sitting in its LCD menu) and at 500 Kbps, or you get silence.

---

## Protocol (from the RF Explorer UART API spec)

Reference: https://github.com/RFExplorer/RFExplorer-for-.NET/wiki/RF-Explorer-UART-API-interface-specification
Cross-check command framing against the authoritative `RFExplorer-for-.NET` and `RFExplorer-for-Python` libraries before trusting any byte layout.

### Command framing (PC → device)

Every command is: `#` + `<Size>` + payload, where `<Size>` is a **single binary byte = total message length in bytes including the `#` and the size byte itself** (max 64). Comma-separated fields in commands use **bare commas, no spaces** (the wiki shows `, ` only for readability — the real bytes omit the space). Confirm against the reference lib.

Key commands to implement:

| Command | Bytes | Size | Purpose |
|---|---|---|---|
| `Request_Config` | `#` `0x04` `C` `0` | 4 | Ask device to send `Current_Config` |
| `Request_Hold` | `#` `0x04` `C` `H` | 4 | Stop sweep dump |
| `AnalyzerConfig` | `#` `0x20` `C2-F:<Start_Freq>,<End_Freq>,<Amp_Top>,<Amp_Bottom>` | 32 | Set span + amplitude window |
| `SwitchModuleMain` | `#` `0x05` `C` `M` `0x00` | 5 | Activate 6G mainboard |
| `SwitchModuleExp` | `#` `0x05` `C` `M` `0x01` | 5 | Activate WSUB3G expansion |
| `SetSweepPoints` | `#` `0x05` `C` `J` `<pts_byte>` | 5 | Set sweep size (≤4096 pts) |
| `Change_baudrate` | `#` `0x04` `c` `<code>` | 4 | code `0`=500Kbps (lowercase `c`) |
| `Disable_LCD` / `Enable_LCD` | `#` `0x04` `L0` / `L1` | 4 | Optional, save device draw cycles |
| `Request_SN` | `#` `0x04` `C` `n` | 4 | Read serial number |

`AnalyzerConfig` field formats: `Start_Freq`/`End_Freq` = 7 ASCII digits in **KHz**; `Amp_Top`/`Amp_Bottom` = 4 ASCII digits in **dBm**. Verify the 32-byte size by counting: `#`+size+`C2-F:`+7+`,`+7+`,`+4+`,`+4 = 32.

### Messages (device → PC)

All lines terminate in EOL = `0x0D 0x0A` (CR+LF).

**CRITICAL FRAMING GOTCHA:** sweep payloads are **binary** and amplitude bytes can equal `0x0D`, `0x0A`, `$`, or `#`. **Do not `readLine()` or split on EOL.** Parse with a streaming state machine: scan for a marker (`$S`, `$s`, `$z`, `#C2-F:`, `#C2-M:`), then read a **length-prefixed** payload of exactly the expected byte count, then consume the trailing EOL. Buffer across USB read boundaries — a frame will routinely span multiple reads.

Messages to parse:

- **`Current_Setup`** — `#C2-M:<Main_Model>,<Expansion_Model>,<Firmware_Version>` + EOL
  - `Main_Model`: 6G = `6`. `Expansion_Model`: 2.4G=`4`, WSUB3G=`5`, NONE=`255` (3 ASCII digits each). `Firmware_Version` = 5 ASCII chars `xx.yy`.
- **`Current_Config`** — `#C2-F:<Start_Freq>,<Freq_Step>,<Amp_Top>,<Amp_Bottom>,<Sweep_points>,<ExpModuleActive>,<CurrentMode>,<Min_Freq>,<Max_Freq>,<Max_Span>,<RBW>,<AmpOffset>,<CalculatorMode>` + EOL
  - **Version-dependent:** 1.08, 1.11, and 1.12 variants have different field counts. Detect by **splitting on commas and checking field count**, not by assuming.
  - `Start_Freq` = 7 ASCII digits **KHz**; `Freq_Step` = 7 ASCII digits **Hz** (note the unit mismatch — normalize carefully). `Sweep_points` = 4 ASCII digits (5 if >9999).
- **`Sweep_data`** — `$S<Sample_points><AdBm>…<AdBm>` + EOL
  - `Sample_points` is a **binary byte**; real point count = **(byte + 1) × 16** (0→16, 255→4096).
  - `Sweep_data_ext` `$s` uses the same (byte+1)×16 rule. `Sweep_data_large` `$z` uses a **16-bit MSB-first** count.
- **`AdBm` decode:** treat each byte as **unsigned**, divide by 2, negate. `0x11` (17) → **−8.5 dBm**. This is normalized across all modules.

### Frequency reconstruction

`freq[i] = Start_Freq + i * Freq_Step`, with `Start_Freq` in KHz and `Freq_Step` in **Hz** — convert to a common unit before computing or your axis will be off by 1000×.

---

## Architecture

Three layers, dependency-inverted so the protocol core has zero Android imports:

1. **`:protocol` (pure Kotlin/JVM, fully unit-tested)**
   - `Command` sealed type → `toBytes(): ByteArray` for each command above.
   - `FrameParser`: streaming state machine, binary-safe, length-prefixed framing as described. Input `ByteArray` chunks, output a `Flow`/list of parsed `RfeMessage` (`Setup`, `Config`, `Sweep`).
   - Models: `DeviceSetup`, `AnalyzerConfig`, `Sweep(startFreqHz, stepFreqHz, amplitudesDbm: FloatArray)`.
   - Unit tests built from **captured byte fixtures** (hex logs) covering: partial frames split across reads, amplitude bytes that equal `0x0D`/`0x0A`/`$`/`#`, all three sweep variants, all three Config versions.

2. **`:transport` (Android, USB host)**
   - `usb-serial-for-android` CP210x driver at 500000 8N1.
   - USB **permission flow**: `PendingIntent` request, handle `ACTION_USB_DEVICE_ATTACHED`/`DETACHED`, `device_filter.xml` with the confirmed VID/PID, manifest `intent-filter`.
   - Expose `Flow<ByteArray>` of raw reads; feed straight into `FrameParser`. Reads on a dedicated coroutine dispatcher.
   - Optional **foreground service** for logging while screen-off.

3. **`:app` (Compose UI)**
   - ViewModel: transport bytes → parser → `StateFlow<Sweep>` → UI.
   - Spectrum view on `Canvas` (live trace, peak-hold/max-hold, optional averaging — mirror the device's `CalcMode` values).
   - Controls: start/stop, start/end freq, amplitude window, module switch (main 6G ↔ WSUB3G expansion), sweep-point count.
   - **Replay mode:** read a recorded raw-byte stream from a file so the UI can be developed and demoed with **no hardware attached**. Build this early — it doubles as a test harness.

---

## Suggested phase plan

1. Repo scaffold: multi-module Gradle, `:protocol` `:transport` `:app`, Compose, version catalog, `.editorconfig`, CI stub.
2. `:protocol` command builders + `FrameParser` + full unit test suite against synthetic fixtures. **No hardware needed.**
3. `:transport` USB enumeration + permission + open at 500000. **First on-device checkpoint** — I confirm VID/PID and that the port opens and bytes flow.
4. Wire transport → parser → a raw text/hex debug view. Confirm real `$S` frames decode to sane dBm values against the device's own LCD.
5. Compose spectrum view + controls. Replay mode in parallel.
6. Module switching, calc modes, CSV export (match RF Explorer's cumulative-CSV column convention so my existing tooling can read it).
7. Play Store prep: `android.hardware.usb.host` feature, target SDK, signing config, privacy policy (aim for "no data collected"), screenshots.

---

## Constraints / preferences

- Verify every byte layout, library API, and pin/VID/PID against datasheet or reference lib before relying on it — don't assume. Where something can only be confirmed on my hardware, say so and tell me exactly what to plug in and what to look for.
- Keep the protocol layer free of Android dependencies so it stays testable and reusable.
- Frank, mechanistic explanations in commit messages and comments; no filler.
- Kotlin + Compose, current stable Gradle/AGP.

First task: scaffold the repo (phase 1) and stub `:protocol` with the `Command` types and `FrameParser` skeleton, then we iterate.
