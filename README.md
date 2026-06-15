# RF Explorer 6G Combo — Android

Native Android app for the **RF Explorer 6G Combo**: connect over USB-OTG via the
Silicon Labs CP210x bridge, drive the UART API, and render live spectrum sweeps.

Greenfield, targeting eventual Play Store release. See
[`CLAUDE_CODE_KICKOFF.md`](CLAUDE_CODE_KICKOFF.md) for the full build spec.

## Modules

| Module | Type | Responsibility |
|---|---|---|
| `:protocol` | Pure Kotlin/JVM | `Command` builders + binary-safe streaming `FrameParser`. **No Android deps; fully unit-tested without hardware.** |
| `:transport` | Android library | CP210x USB host link at 500000 8N1, USB permission flow, replay-from-file source. |
| `:app` | Android (Compose) | Spectrum Canvas view, controls, ViewModel wiring transport → parser → UI. |

Dependency direction: `:app` → `:transport` → `:protocol`. The protocol core has zero
Android imports so it stays testable and reusable.

## Build & test

```bash
# Hardware-free: protocol command framing + frame parser
./gradlew :protocol:test

# Full Android build (requires the Android SDK)
./gradlew :app:assembleDebug
```

## Status (phase plan)

- [x] **Phase 1** — multi-module Gradle scaffold, version catalog, CI stub.
- [x] **Phase 2** — `:protocol` `Command` types + `FrameParser` + unit suite (binary-safe
      framing, split frames, all sweep variants, version-dependent Config). 23 tests.
- [x] **Phase 3** — `:transport` `UsbSerialTransport`: CP210x enumeration (default +
      custom probe), USB permission flow, open at 500000 8N1, coroutine read loop.
      **Code complete, unverified — needs the on-device checkpoint.**
- [x] **Phase 4** — transport → parser wired in `SpectrumViewModel`; raw-hex tail in the UI.
- [x] **Phase 5** — Compose `Canvas` spectrum view + controls; **replay mode** drives the
      same path from `assets/sample_capture.bin` with no hardware.
- [x] **Phase 6** — module switching, client-side calc modes (Normal / Max-Hold / Avg via
      `SweepAccumulator`), cumulative-CSV export (`CumulativeCsvWriter`).
- [x] **Phase 7 (prep)** — `android.hardware.usb.host` feature, target SDK 35, release
      signing config (`keystore.properties`), [privacy policy](PRIVACY.md) (no data
      collected). Screenshots/store listing still TODO.

### Hardware-free demo

Launch the app and tap **Replay** — it loops a synthesized capture through the real
parser and renders a live trace with a moving peak. Use it to develop UI without a device.

### App features

- **Spectrum** view: filled trace, dBm/MHz axis labels, auto peak marker, tap-to-inspect
  marker with freq/dBm readout.
- **Waterfall** (spectrogram) view: scrolling heatmap of recent sweeps.
- Trace processing: **Normal / Max-Hold / Avg**, **Freeze**, **Clear**, **Autoscale**.
- **Top-peaks** table, sweeps/sec readout, raw-hex debug tail.
- Tuning: **Start/Stop** or **Center/Span** input, one-tap **band presets**
  (433/915 ISM, 2.4/5 GHz), amplitude window, module switch (6G ↔ WSUB3G), sweep points.
- **CSV record + export** (cumulative format).

> The Android layers (`:transport`, `:app`) are written but **could not be compiled in
> the build environment** (no Android SDK). Expect to open them in Android Studio and
> shake out any first-build issues; the `:protocol` layer is fully built and tested.

## Needs on-device verification

- **VID/PID** of the actual unit (Silabs `0x10C4` / CP2102 `0xEA60` assumed; RF Explorer
  may ship a customised EEPROM). Update `transport/.../res/xml/device_filter.xml` and add
  a custom `ProbeTable` entry if it differs.
- That the port opens at **500000 8N1** and bytes flow while the device is sweeping.
- `SetSweepPoints` (`CJ`) byte encoding — cross-check against RFExplorer-for-.NET/-Python.
