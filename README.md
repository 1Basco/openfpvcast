# Open FPV Cast

An open-source Android app that casts the video from **DJI FPV goggles (N3)** to your phone
over USB, lets you **record** the feed (with optional phone-mic audio), **change camera
settings**, and **re-broadcast the live view over WebRTC** so anyone on the same WiFi can watch
in a browser.

> Not affiliated with or endorsed by DJI. "DJI" and product names are trademarks of their
> respective owners and are used here only to describe hardware compatibility.

## How it works

The goggles expose themselves as a USB Android Open Accessory (AOA). The app opens the
accessory and speaks DJI's transport stack:

```
USB AOA  →  LogicLink (55 CC envelope)  →  DUML + camcap commands  →  H.264 Annex-B video
```

- **Video** arrives as an H.264 Annex-B byte stream on LogicLink port `0x574A` and is decoded
  with hardware MediaCodec straight to a Surface for low-latency preview.
- **Camera control** (ISO, shutter, white balance, exposure mode, video format) is sent as
  DUML `cmdSet 0x02` (camcap) commands.
- **Armed state** is read from the camera-status push the goggles emit on port `0x7530`, and drives
  auto-record. It is the *only* flight data the app parses — battery voltage, link strength and the
  rest are already drawn into the video by the goggles' own OSD, so the app doesn't duplicate them.

Every protocol constant here was established by observing the hardware — the transport, the DUML
packet layout, the camera byte maps and their confidence grades are written up in
[`AGENTS.md`](AGENTS.md).

## Features

- Low-latency live preview of the goggles feed
- Record to MP4 on the phone, with an optional phone-microphone audio track
- Auto-record when the aircraft is armed (configurable stop delay after disarm)
- Camera settings (ISO / shutter / WB / exposure mode / resolution / codec / fps), with the
  supported values verified per camera model (O4 Lite confirmed on hardware). The current
  settings are read back from the camera on connect, and changing one applies it right away
- Two UI modes: a portrait home screen when no video is flowing, and a streaming layout
  with a persistent side tab when connected — with an optional landscape lock that works even
  when the phone's auto-rotate is off
- True WebRTC broadcast to any browser on the same WiFi — the phone serves a viewer page at
  `http://<phone-ip>:8888/` (QR + copyable URL in the app; capped at 3 viewers, one hardware
  encoder each)
- English + Portuguese (pt-BR) UI; opt-in anonymous diagnostics (off by default)

> **Hardware-validation status**: connect / camera control / recording / auto-record were
> validated on real hardware (Goggles N3 + O4 Lite); the O4 Pro value ranges are being validated.
> The `armed` flag comes from the goggles' camera-status push, confirmed against live N3 captures. A raw frame/DUML viewer for further
> protocol work lives at `/debug`, and a camera value-sweep bench at `/debug/camera-matrix` (both
> behind **Settings → Developer mode**).

## Repository layout

| Path | What |
|---|---|
| `apps/web/` | SvelteKit frontend (the app UI) — see its README |
| `plugins/capacitor-dji-aoa/` | Capacitor plugin — Kotlin USB/AOA/DUML/H.264 hardware layer |
| `android/` | Capacitor Android host project |
| `AGENTS.md` | Protocol reference and working rules for contributors |

## Build

```sh
pnpm install
pnpm --filter web build     # build the SvelteKit frontend
pnpm exec cap sync android  # sync web + plugins into the Android project
pnpm exec cap run android   # build & run on a connected device
```

Requires a phone that supports USB Android Open Accessory and DJI FPV goggles (N3).

## License

[MIT](LICENSE) © Open FPV Cast contributors
