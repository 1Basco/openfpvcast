/**
 * Camera control helpers — encode DUML payloads for cmdSet 0x02 and dispatch via the
 * native plugin's `sendCameraCommand`. Payload formats follow the camcap camera commands
 * (camcap_iso / camcap_shutter / camcap_wb / camcap_exposure_mode).
 *
 * Each setter returns the raw `SendCameraCommandResult`; the caller decides UX (toast on
 * success / failure, etc).
 */

import { DjiAoa, type SendCameraCommandResult } from 'capacitor-dji-aoa';

export const CamCmd = {
	VIDEO_FORMAT_SET: 0x18,
	VIDEO_FORMAT_GET: 0x19,
	EXPOSURE_MODE_SET: 0x1e,
	EXPOSURE_MODE_GET: 0x1f,
	SHUTTER_SET: 0x28,
	SHUTTER_GET: 0x29,
	ISO_SET: 0x2a,
	ISO_GET: 0x2b,
	WB_SET: 0x2c,
	WB_GET: 0x2d
	// EV (Exposition Bias, 0x2e/0x2f) intentionally omitted — the byte format was never confirmed
	// on hardware, so EV control was removed rather than ship an unverified command.
} as const;

// camcap_iso. Payload = single byte = ISO_TO_BYTE[iso].
// Byte map corroborated by the dji-firmware-tools DUML dissector
// (CAMERA_SHOT_PARAMS_ISO_TYPE_ENUM): 0x02=ISO50 … 0x0b=ISO25600. Bytes 0x00/0x01 are
// AUTO / AUTO-HIGH in that enum (not discrete ISO), so ISO 25 is NOT a real setting here —
// byte 1 = auto-high — and is intentionally omitted. See AGENTS.md.

export const isoValues = [50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600] as const;
export type Iso = (typeof isoValues)[number];

const ISO_TO_BYTE: Record<Iso, number> = {
	50: 2, 100: 3, 200: 4, 400: 5, 800: 6,
	1600: 7, 3200: 8, 6400: 9, 12800: 10, 25600: 11
};

/**
 * The two auto modes. Same enum as the discrete values — `0x00` = AUTO, `0x01` = AUTO-HIGH in the
 * dissector's CAMERA_SHOT_PARAMS_ISO_TYPE_ENUM, which is also why ISO 25 doesn't exist. We have
 * *read* these bytes back from a real camera, but writing them is [DISSECTOR]-only so far — the
 * `/debug/camera-matrix` sweep covers them.
 */
const ISO_AUTO_BYTE = 0;
const ISO_AUTO_HIGH_BYTE = 1;

export type IsoState = Iso | 'auto' | 'auto-high';

export function encodeIso(iso: IsoState): Uint8Array {
	if (iso === 'auto') return new Uint8Array([ISO_AUTO_BYTE]);
	if (iso === 'auto-high') return new Uint8Array([ISO_AUTO_HIGH_BYTE]);
	return new Uint8Array([ISO_TO_BYTE[iso]]);
}

// camcap_shutter. The list holds the *denominators* of 1/N second shutter
// speeds. The wire payload is 4 bytes: [1, low, high, 0] where
//   value = 0x8000 | denominator
// The 0x8000 bit is the fractional-speed flag. The 16-bit field packs as
//   bit 15 = fractional flag | bits 0..14 = value | bits 16..23 = mode
// so we set flag=1 and leave the mode byte 0 (whole-second speeds, which clear
// bit 15, aren't exposed by this enum).

export const shutterDenominators = [
	8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000,
	800, 640, 500, 400, 320, 240, 200, 160, 120, 100, 80, 60
] as const;
export type ShutterDenominator = (typeof shutterDenominators)[number];

export function encodeShutter(denominator: ShutterDenominator): Uint8Array {
	const v = 0x8000 | denominator;
	return new Uint8Array([1, v & 0xff, (v >> 8) & 0xff, 0]);
}

// camcap_wb. Two modes:
//   - Auto: [0, 0]
//   - Custom Kelvin (2000..10000 step 100): [6, (K/100) & 0xFF, ((K/100) >> 8) & 0xFF, 0xFF, 0xFF]
//
// Preset modes (sunny/cloudy/etc.) are not sent via this command, so we don't
// expose them either — anyone who needs presets can set the Kelvin value
// that approximates the preset (sunny ≈ 5500K, cloudy ≈ 6500K, …).

export const wbKelvinMin = 2000;
export const wbKelvinMax = 10000;
export const wbKelvinStep = 100;

export type WbValue = { mode: 'auto' } | { mode: 'custom'; kelvin: number };

/**
 * What a WB *GET* reports, which is richer than what a SET takes: in auto the camera still returns
 * the Kelvin it settled on (mode byte `0`), and that value is the whole point of the "lock the auto
 * white balance" flow — read what auto chose, re-send it as custom so it stops drifting.
 */
export interface WbReading {
	mode: 'auto' | 'custom';
	/** null when the reply carried no plausible Kelvin. */
	kelvin: number | null;
}

/** Turn a reading back into something `setWb` accepts. */
export function wbReadingToValue(r: WbReading): WbValue {
	return r.mode === 'auto' || r.kelvin === null ? { mode: 'auto' } : { mode: 'custom', kelvin: r.kelvin };
}

export function encodeWb(wb: WbValue): Uint8Array {
	if (wb.mode === 'auto') return new Uint8Array([0, 0]);
	const k = clampStep(wb.kelvin, wbKelvinMin, wbKelvinMax, wbKelvinStep);
	const v = (k / 100) & 0xffff;
	return new Uint8Array([6, v & 0xff, (v >> 8) & 0xff, 0xff, 0xff]);
}

// camcap_exposure_mode. Two real modes wire-side:
//   AUTO    → [1, 0]
//   MANUAL  → [4, 0]

export const exposureModes = ['auto', 'manual'] as const;
export type ExposureMode = (typeof exposureModes)[number];

export function encodeExposureMode(mode: ExposureMode): Uint8Array {
	return new Uint8Array(mode === 'manual' ? [4, 0] : [1, 0]);
}

// camcap video-format. Payload = [formatByte, fpsByte, 0].
//
// [HARDWARE] O4 Lite + O4 Pro, 2026-08-29 — `/debug/camera-matrix`, goggles in normal mode.
//
// This byte was originally read as *resolution × codec*. Both labels were wrong. The byte selects **aspect ratio × resolution**; the codec is not in this command at all.
//
//   | aspect | resolution | byte |
//   |--------|------------|------|
//   | 16:9   | 1080p      | 0x0a |
//   | 16:9   | 2.7K       | 0x2d |
//   | 16:9   | 4K         | 0x10 |
//   | 4:3    | 1080p      | 0x62 |
//   | 4:3    | 2.7K       | 0x5f |
//   | 4:3    | 4K         | 0x67 |
//
// **Exhaustive by enumeration, not by belief.** The `formatByte` census walked every byte
// 0x00–0x7f on an O4 Pro: exactly these six were accepted, the other 122 refused with `0xe3`.
//
// Three corrections the bench forced, each worth keeping as a warning:
//
//  1. *The axes.* Read as resolution × codec, it is aspect × resolution. What gave it away was the
//     fps ceiling: the accepted combinations split into two groups whose ladders match the
//     resolutions the goggles' own menu lists, not anything a codec would explain.
//  2. *The middle slot.* Bytes 45 / 95 were labelled H.265 and are refused by the Lite at every
//     fps, in both goggle modes. They are **2.7K**, a resolution the Lite does not have and the Pro
//     does — they round-trip there. The slot order agrees: each aspect family runs 1080p → 2.7K
//     → 4K, and we had read the middle entry as a codec.
//  3. *The aspect labels were backwards*, and only the Pro exposed it. The `0x0a` family reaches
//     120 fps at every resolution while the `0x62` family caps at 60 above 1080p — impossible if
//     `0x62` were 16:9, since 4:3 carries ~33% more pixels at the same resolution name. Confirmed
//     on the goggles: `0x0a…` is 16:9, `0x62…` is 4:3, and 4:3 only reaches 100/120 at 1080p.
//
// fps bytes, all [HARDWARE]: 30→3 · 50→5 · 60→6 · 100→10 · 120→7. `120→7` breaks the fps÷10
// pattern the other four follow, was suspected wrong for exactly that reason, and round-tripped.
//
// Which of these modes a given air unit actually offers, and up to which fps, is a *capability* of
// that unit rather than a property of the wire: see `$lib/schemas/camera-capabilities`.

export const videoAspects = ['16:9', '4:3'] as const;
export const videoResolutions = ['1080p', '2.7k', '4k'] as const;
export const videoFpsValues = [30, 50, 60, 100, 120] as const;

export type VideoAspect = (typeof videoAspects)[number];
export type VideoResolution = (typeof videoResolutions)[number];
export type VideoFps = (typeof videoFpsValues)[number];

/**
 * `${aspect}|${resolution}` → the single byte that selects it. The separator is `|` and not `.`
 * because a resolution name contains a dot ("2.7k") and an aspect contains a colon ("16:9").
 */
const FORMAT_BYTE: Record<string, number> = {
	'16:9|1080p': 0x0a,
	'16:9|2.7k': 0x2d,
	'16:9|4k': 0x10,
	'4:3|1080p': 0x62,
	'4:3|2.7k': 0x5f,
	'4:3|4k': 0x67
};

const FPS_TO_BYTE: Record<VideoFps, number> = { 30: 3, 50: 5, 60: 6, 100: 10, 120: 7 };

export function encodeVideoFormat(
	aspect: VideoAspect,
	resolution: VideoResolution,
	fps: VideoFps
): Uint8Array {
	const fb = FORMAT_BYTE[`${aspect}|${resolution}`];
	if (fb === undefined) throw new Error(`unsupported video format: ${aspect} ${resolution}`);
	return new Uint8Array([fb, FPS_TO_BYTE[fps], 0]);
}

function clampStep(v: number, min: number, max: number, step: number): number {
	const clamped = Math.max(min, Math.min(max, v));
	return Math.round(clamped / step) * step;
}

function b64(bytes: Uint8Array): string {
	let s = '';
	for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
	return btoa(s);
}

async function send(sessionId: string, cmdId: number, payload: Uint8Array): Promise<SendCameraCommandResult> {
	return DjiAoa.sendCameraCommand({ sessionId, cmdId, payload: b64(payload) });
}

/**
 * Escape hatch for bench work: send an arbitrary cmdSet-0x02 command the app doesn't model.
 *
 * Deliberately narrow — it cannot reach another cmdSet, so it can't touch flight, ESC or battery
 * subsystems. It exists for `/debug/camera-matrix` to probe imaging commands we have names for but
 * no verified encoding (color profile / log mode / scene mode). Nothing in the shipping UI calls it.
 */
export function sendCameraRaw(
	sessionId: string,
	cmdId: number,
	payload: Uint8Array = new Uint8Array()
): Promise<SendCameraCommandResult> {
	return send(sessionId, cmdId, payload);
}

/** Raw ACK bytes as hex, for probes whose reply we have no decoder for. */
export function ackToHex(res: SendCameraCommandResult): string {
	if (!res.ack) return '';
	const b = b64ToBytes(res.ack);
	let out = '';
	for (let i = 0; i < b.length; i++) out += b[i].toString(16).padStart(2, '0');
	return out;
}

export const camera = {
	setIso: (sessionId: string, iso: IsoState) =>
		send(sessionId, CamCmd.ISO_SET, encodeIso(iso)),
	setShutter: (sessionId: string, denominator: ShutterDenominator) =>
		send(sessionId, CamCmd.SHUTTER_SET, encodeShutter(denominator)),
	setWb: (sessionId: string, wb: WbValue) =>
		send(sessionId, CamCmd.WB_SET, encodeWb(wb)),
	setExposureMode: (sessionId: string, mode: ExposureMode) =>
		send(sessionId, CamCmd.EXPOSURE_MODE_SET, encodeExposureMode(mode)),
	setVideoFormat: (
		sessionId: string,
		aspect: VideoAspect,
		resolution: VideoResolution,
		fps: VideoFps
	) =>
		send(sessionId, CamCmd.VIDEO_FORMAT_SET, encodeVideoFormat(aspect, resolution, fps)),
	getIso: (sessionId: string) => send(sessionId, CamCmd.ISO_GET, new Uint8Array()),
	getShutter: (sessionId: string) => send(sessionId, CamCmd.SHUTTER_GET, new Uint8Array()),
	getWb: (sessionId: string) => send(sessionId, CamCmd.WB_GET, new Uint8Array()),
	getExposureMode: (sessionId: string) => send(sessionId, CamCmd.EXPOSURE_MODE_GET, new Uint8Array()),
	getVideoFormat: (sessionId: string) => send(sessionId, CamCmd.VIDEO_FORMAT_GET, new Uint8Array())
};

// Every GET reply is `[0x00 status, ...value]`, where the value mirrors the SET encoding.
// Response formats confirmed on real O4 Lite hardware (2026-08-15) — see
// AGENTS.md § "GET responses".

function b64ToBytes(s: string): Uint8Array {
	const bin = atob(s);
	const out = new Uint8Array(bin.length);
	for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
	return out;
}

/** Strip the leading status byte from a GET reply; null on timeout or non-zero status. */
function ackValue(res: SendCameraCommandResult): Uint8Array | null {
	if (res.timedOut || !res.ack) return null;
	const b = b64ToBytes(res.ack);
	if (b.length < 1 || b[0] !== 0x00) return null;
	return b.subarray(1);
}

const BYTE_TO_ISO = Object.fromEntries(
	Object.entries(ISO_TO_BYTE).map(([iso, byte]) => [byte, Number(iso)])
) as Record<number, Iso>;

export function decodeExposureMode(res: SendCameraCommandResult): ExposureMode | null {
	const v = ackValue(res);
	if (!v || v.length < 1) return null;
	return v[0] === 4 ? 'manual' : v[0] === 1 ? 'auto' : null;
}

export function decodeIso(res: SendCameraCommandResult): IsoState | null {
	const v = ackValue(res);
	if (!v || v.length < 1) return null;
	if (v[0] === 0) return 'auto';
	if (v[0] === 1) return 'auto-high';
	return BYTE_TO_ISO[v[0]] ?? null;
}

export function decodeShutter(res: SendCameraCommandResult): ShutterDenominator | null {
	const v = ackValue(res);
	if (!v || v.length < 3) return null; // [1, low, high, 0]
	const denom = (v[1] | (v[2] << 8)) & 0x7fff;
	return (shutterDenominators as readonly number[]).includes(denom)
		? (denom as ShutterDenominator)
		: null;
}

export function decodeWb(res: SendCameraCommandResult): WbReading | null {
	const v = ackValue(res);
	if (!v || v.length < 3) return null; // [mode, low, high, 0xff, 0xff]
	const kelvin = (v[1] | (v[2] << 8)) * 100;
	const plausible = kelvin >= wbKelvinMin && kelvin <= wbKelvinMax;
	return { mode: v[0] === 0 ? 'auto' : 'custom', kelvin: plausible ? kelvin : null };
}

export function decodeVideoFormat(
	res: SendCameraCommandResult
): { aspect: VideoAspect; resolution: VideoResolution; fps: VideoFps } | null {
	const v = ackValue(res);
	if (!v || v.length < 2) return null; // [formatByte, fps, 0, 0, 0]
	const key = Object.keys(FORMAT_BYTE).find((k) => FORMAT_BYTE[k] === v[0]);
	const fpsEntry = (Object.entries(FPS_TO_BYTE) as [string, number][]).find(([, b]) => b === v[1]);
	if (!key || !fpsEntry) return null;
	const [aspect, resolution] = key.split('|') as [VideoAspect, VideoResolution];
	return { aspect, resolution, fps: Number(fpsEntry[0]) as VideoFps };
}

export interface CameraState {
	exposureMode: ExposureMode | null;
	iso: IsoState | null;
	shutter: ShutterDenominator | null;
	wb: WbReading | null;
	videoFormat: { aspect: VideoAspect; resolution: VideoResolution; fps: VideoFps } | null;
}

/**
 * Read the camera's current settings so the UI can reflect real state on connect instead of
 * hardcoded defaults. Sent sequentially — the DUML control channel is a single serialized pipe,
 * so concurrent GETs risk ACK cross-talk. Any field that times out / fails to decode comes back null.
 */
export async function readCameraState(sessionId: string): Promise<CameraState> {
	return {
		exposureMode: decodeExposureMode(await camera.getExposureMode(sessionId)),
		iso: decodeIso(await camera.getIso(sessionId)),
		shutter: decodeShutter(await camera.getShutter(sessionId)),
		wb: decodeWb(await camera.getWb(sessionId)),
		videoFormat: decodeVideoFormat(await camera.getVideoFormat(sessionId))
	};
}
