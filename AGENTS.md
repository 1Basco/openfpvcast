# AGENTS.md

Working notes for anyone — human or agent — touching this repo. It carries the protocol knowledge
the code depends on, so you don't have to re-derive it from the wire.

## What this is

An Android app that casts the video from DJI FPV goggles (N3 / ZV300, O4 air units) to the phone
over USB, decodes it, records it, and controls the air-unit camera. Three pieces:

| path | what |
|---|---|
| `apps/web` | SvelteKit UI (Svelte 5 runes), wrapped by Capacitor |
| `plugins/capacitor-dji-aoa` | the native half: USB transport, protocol codec, H.264 decode, MP4 muxing, WebRTC |
| `android/` | Capacitor Android shell |

Build: `pnpm sync` (web build + `cap sync`), `pnpm android` to run on a device. `pnpm --filter web
check` typechecks.

## Ground rules

**Every protocol constant in this repo was established by observing the hardware.** Byte maps,
command ids, offsets and CRC seeds are empirical findings, not documentation from a vendor. Two
consequences:

1. **Grade your claims, in the comment, where the value lives.** `[HARDWARE]` = confirmed by a live
   SET → GET round-trip on a real unit, with the date and the model. `[INFERRED]` = our reading, no
   confirmation. Never quietly promote an inference to a fact.
2. **Do not attribute findings to any third-party application, its binaries, symbol names or
   addresses.** Describe the studied case — what was sent, what came back, what that implies. A
   value that can only be justified by pointing somewhere else does not belong in the codebase; go
   confirm it on hardware or mark it `[INFERRED]`.

**Comments.** A comment earns its place only when the code cannot say it: why a decision was made,
where a magic value came from, the shape of a dense algorithm, or what breaks if you change it.
Delete anything that restates the code, decorates a section, or narrates edit history. Never delete
tool directives (`@ts-expect-error`, `svelte-ignore`, `eslint-disable`, `#region`), licences, or
`TODO`s. If a comment and the code disagree and you can't tell which is right, leave it and say so.

## Transport

The goggles expose one app-layer protocol (**DUML**) over three USB personalities. Only one streams
video to a phone:

| mode | USB host | device | framing | video |
|---|---|---|---|---|
| **AOA** (Android) | **the goggles** | phone re-enumerates as `18D1:2D00` | LogicLink | port `0x574A` |
| MFI (iPhone) | goggles | `2CA3:1002` | LogicLink | port `0x574A` |
| PC native | the PC | `2CA3:0020` | raw DUML, no LogicLink | gated shut |

The inversion is the thing to internalise: **in AOA the goggles are the USB host and the phone is
the accessory** (`UsbManager.openAccessory`). The firmware's video gate only opens for a phone that
authenticated over AOA/MFI, which is why a PC-side probe gets a STALL and why an ESP32 bridge that
completes the AOA negotiation still receives zero bulk IN tokens — the block is in the goggles' AOA
host state machine, below the app layer. Sending correct bytes cannot help when the host never
reads your endpoint.

### LogicLink envelope

```
off  size  field
0    2     magic 0x55 0xCC
2    2     port (u16 LE)
4    2     payload length (u16 LE)
6    2     reserved 0x00 0x00
8    N     payload
```

Three ports matter: **`0x5749`** session/registration and video control · **`0x574A`** H.264
Annex-B video · **`0x7530`** DUML telemetry and commands. Several envelopes can share one USB
transfer; the parser resyncs on `55 CC`.

The `0x574A` payload is fed **directly** to the decoder — pure H.264 Annex-B, no wrapper, no SEI
stripping.

### DUML packet

```
off    size  field
0      1     magic 0x55
1      2     length (bits 0-9) | version (bits 10-15)   length = total bytes incl. trailing CRC-16
3      1     CRC-8 of bytes 0..2
4      1     src address
5      1     dst address
6      2     sequence (u16 BE)
8      1     cmdType
9      1     cmdSet
10     1     cmdId
11..   N     payload
end-2  2     CRC-16 of bytes 0..end-3 (LE)
```

- **CRC-8**: init `0x77`, poly `0x8C` (reflected), over bytes 0..2.
- **CRC-16**: init `0x3692`, poly `0x8408` (reflected CCITT), over bytes 0..end-3.
- **cmdType**: bit 7 (`0x80`) = REPLY · bit 6 (`0x40`) = REQUEST · bits 5-6 also carry the ACK
  policy · bits 0-2 encryption. **Outgoing camera requests use exactly `0x40`.** Setting
  ACK-required makes the camera drop the packet silently — it surfaces as an ACK timeout on every
  apply, which is a miserable thing to debug. `[HARDWARE]`
- **cmdSets** in play: `0x00` General · `0x02` Camera · `0x03` Flight Control · `0x09` HD Link ·
  `0x19` an O4-specific set, undocumented.

### Stream-start handshake

The N3 pushes video only after the mobile side registers, and it needs the registration **resent as
a ~1 Hz keepalive** — not sent once. Two LogicLink frames on port `0x5749`:

- **camcap_common** — DUML src `0x02` → dst `0x28`, cmdType `0x40`, cmdSet `0x00`, cmdId `0x99`
- **APP register** — DUML src `0x02` → dst `0x3C`, cmdType `0x40`, cmdSet `0x00`, cmdId `0x88`

Both are stored as verbatim byte strings in `UsbAoaService.kt` with their CRCs already baked in;
write them raw, do not re-encode. Without the keepalive only telemetry flows on `0x7530` and the
video port stays silent — which reads exactly like a decode bug if you don't know this.

## Camera control — cmdSet `0x02`

| cmdId | command |
|---|---|
| `0x18` / `0x19` | video format set / get |
| `0x1E` / `0x1F` | exposure mode set / get |
| `0x28` / `0x29` | shutter set / get |
| `0x2A` / `0x2B` | ISO set / get |
| `0x2C` / `0x2D` | white balance set / get |
| `0x2E` / `0x2F` | EV bias — **not implemented**, format never confirmed |

**ISO** — payload is one byte. `50→2, 100→3, 200→4, 400→5, 800→6, 1600→7, 3200→8, 6400→9,
12800→10, 25600→11`. Bytes `0x00` and `0x01` are AUTO and AUTO-HIGH — they are *not* discrete ISO
values, which is why there is no "ISO 25" despite it appearing in older third-party tables.

**Shutter** — the list holds *denominators* of 1/N s. Wire payload `[1, low, high, 0]` where the
16-bit value is `0x8000 | denominator`: bit 15 is the fractional-speed flag, bits 0..14 the value,
and the high byte would carry a mode we always leave at 0. All 22 denominators (1/8000 → 1/60)
round-trip. `[HARDWARE]`

**White balance** — `auto → [0, 0]`; custom Kelvin (2000–10000, step 100) → `[6, low, high, 0xFF,
0xFF]` with Kelvin/100 as u16 LE. Mode byte `0` = auto, `6` = custom. Presets (sunny/cloudy/…) are
not carried by this command, so the UI doesn't offer them — a Kelvin value approximates any preset.

*The auto-lock flow depends on one detail:* a WB **get** while in auto returns `mode=0` **plus the
Kelvin the camera actually settled on**. Keeping that value instead of discarding it is what makes
"hold the current auto white balance" possible — read what auto chose, re-send it as `mode=6` with
the same Kelvin, and the tint stops drifting. No new command involved.

**Exposure mode** — `auto → [1, 0]`, `manual → [4, 0]`.

**Video format** — payload `[formatByte, fpsByte, 0]`. The byte selects **aspect ratio ×
resolution**; the codec is not carried by this command at all.

| aspect | resolution | byte |
|---|---|---|
| 16:9 | 1080p | `0x0a` |
| 16:9 | 2.7K | `0x2d` |
| 16:9 | 4K | `0x10` |
| 4:3 | 1080p | `0x62` |
| 4:3 | 2.7K | `0x5f` |
| 4:3 | 4K | `0x67` |

fps bytes: `30→3 · 50→5 · 60→6 · 100→10 · 120→7`. Note `120→7` breaks the fps÷10 pattern the other
four follow — it was suspected wrong for that reason and round-tripped anyway.

**This table is exhaustive by enumeration, not by belief.** A census walked every format byte
`0x00`–`0x7F` on an O4 Pro: exactly these six were accepted, the other 122 refused with `0xE3`.

### Three corrections that table cost, each worth remembering

1. **The axes were wrong.** It had been read as *resolution × codec*. What gave it away was the fps
   ceiling: accepted combinations split into two groups whose ladders match the resolutions the
   goggles' own menu lists — nothing a codec would explain.
2. **The middle slot was wrong.** Bytes `0x2d` / `0x5f` were labelled H.265, and the Lite refuses
   both at every fps in both goggle modes. They are **2.7K** — a resolution the Lite doesn't have
   and the Pro does, where they round-trip fine.
3. **The aspect labels were backwards**, and only the Pro exposed it. The `0x0a` family reaches 120
   fps at every resolution while `0x62` caps at 60 above 1080p — impossible if `0x62` were 16:9,
   since 4:3 carries ~33% more pixels at the same resolution name.

The general lesson: a sweep can only exercise bytes you already believe in, so on a unit with modes
you have no byte for, it reports nothing at all — a silent blind spot. Enumerate.

### GET responses

Every reply opens with a `0x00` status byte; the rest mirrors the SET encoding.

| get | cmdId | bytes | decode |
|---|---|---|---|
| exposure mode | `0x1F` | `00 <mode> 00` | 1 = auto, 4 = manual |
| ISO | `0x2B` | `00 <byte>` | invert the ISO map; `00` = auto, `01` = auto-high |
| shutter | `0x29` | `00 01 <low> <high> 00` | denominator = `(low \| high<<8) & 0x7FFF` |
| white balance | `0x2D` | `00 <mode> <low> <high> ff ff` | mode 0 = auto / 6 = custom; K = `(low \| high<<8) × 100` |
| video format | `0x19` | `00 <format> <fps> 00 00 00` | invert both maps |

In auto exposure the ISO and shutter gets report the auto-*chosen* value, not "auto" — reading them
back on connect is how the UI shows real state instead of hardcoded defaults.

**Refusal statuses:** `0xE3` = command understood, this combination isn't available on this unit or
in this goggle mode. `0xE0` = the unit doesn't implement this command at all. Telling them apart is
what separates "wrong byte" from "not supported here".

### Per-unit capabilities

`ZA530` = O4 Air Unit (Lite), `ZA5305` = O4 Air Unit Pro. ISO, shutter, white balance and exposure
mode are **identical on both**. The difference is confined to video modes:

| mode | Lite | Pro |
|---|---|---|
| 16:9 1080p | 30–120 | 30–120 |
| 16:9 2.7K | — | 30–120 |
| 16:9 4K | 30/50/60 | 30–120 |
| 4:3 1080p | 30–120 | 30–120 |
| 4:3 2.7K | — | 30/50/60 |
| 4:3 4K | 30/50/60 | 30/50/60 |

What a given unit offers is a *capability of that unit*, not a property of the wire — it lives in
`$lib/schemas/camera-capabilities`, not in the encoder.

### Two traps when measuring

**Goggle mode matters and is invisible on the wire.** In race mode the goggles pin the format and
silently ignore every other write, acking `0x00` without moving — a sweep run there reported 1 ok /
15 substituted / 14 rejected. A run whose goggle mode wasn't written down cannot be interpreted.

**Some constraints are not observable through readback.** The camera accepted 1/60 shutter while the
stream ran at 100 fps, without clamping or substituting. The shutter×fps conflict is real but the
hardware won't tell you about it, so the UI cannot derive it.

## Telemetry

The N3 does **not** emit the classic OSD push (`cmdSet 0x03` / `cmdId 0x43`). The app takes `armed`
from the camera-status push `0x02` / `0x80` and parses nothing else, deliberately: battery, link
strength and the rest of the flight data are already composited into the video by the goggles' own
OSD, so re-drawing them would only duplicate what the pilot can see.

## Open

- **Colour profile (D-Log / HLG) on the Pro** — no cmdId known, nothing implemented. A read-first
  scan on the Lite found only `0x42`/`0x43` (digital effect, value 0) and `0xAB`/`0xAC` (video
  encode, value 1) answering; `0x20`/`0x21` and `0x9B`/`0x9C` return `0xE0`. Since the video-format
  command turned out not to carry the codec, video encode is the most interesting lead.
- **EV bias** (`0x2E`/`0x2F`) — encoder was removed rather than ship an unverified format. Re-add
  only when a live round-trip pins it.
- **Unexplained:** 2.7K readbacks end in `d0` where the others end `00` (payload byte 5). Seen on
  both units. Probably a bitrate field; not chased.
- Observed but unnamed: `0x0F`/`0xA3` (14-byte request), `0x09`/`0x43` (2 bytes), `0x19`/`0x67`
  (9 bytes, `00 02 01 00 01 00 04 02 00`).
