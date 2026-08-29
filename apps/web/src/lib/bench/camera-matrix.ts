/**
 * Camera value-matrix sweep — the bench harness behind `/debug/camera-matrix`.
 *
 * Purpose: answer "which ISO / shutter / white-balance / resolution / fps values does *this* air
 * unit actually accept?" with evidence, for both the O4 Lite and the O4 Pro, without a PC in the
 * loop. For every candidate value it does a SET → GET round-trip and records the raw ACK bytes of
 * both, then compares what came back with what went out.
 *
 * Why raw bytes matter: the decoders in `$lib/aoa/camera` only know the byte maps confirmed on the
 * O4 Lite. A Pro-only value (4K, say) will decode to `null` while still being a perfectly valid
 * reply — so the raw hex is the primary record and the decode is a convenience. Reading the hex of
 * an "unreadable" step is how the Pro's byte maps get filled in.
 *
 * Everything here is cmdSet 0x02 (camera) only — no flight, ESC or battery subsystems are touched,
 * so the sweep is safe to run on the bench with the air unit powered and props off.
 */

import {
	camera,
	readCameraState,
	decodeExposureMode,
	decodeIso,
	decodeShutter,
	decodeWb,
	decodeVideoFormat,
	wbReadingToValue,
	sendCameraRaw,
	ackToHex,
	CamCmd,
	isoValues,
	shutterDenominators,
	videoAspects,
	videoResolutions,
	videoFpsValues,
	exposureModes,
	type CameraState,
	type IsoState,
	type WbReading,
	type ShutterDenominator,
	type VideoAspect,
	type VideoFps,
	type VideoResolution,
	type SendCameraCommandResult
} from '$lib/aoa';

export const matrixGroups = ['exposure', 'iso', 'shutter', 'wb', 'videoFormat'] as const;
/**
 * `colorProfile` is not part of the value sweep — it is the discovery probe below, which shares this
 * step shape so its findings land in the same report.
 */
export type MatrixGroup =
	| (typeof matrixGroups)[number]
	| 'colorProfile'
	| 'fpsByte'
	| 'formatByte';
/** Groups the Markdown report renders, in order. */
const reportGroups: readonly MatrixGroup[] = [
	...matrixGroups,
	'formatByte',
	'fpsByte',
	'colorProfile'
];

/**
 * What the camera did with one candidate value.
 *
 * - `ok`          — readback equals what we asked for. The value is supported.
 * - `substituted` — the SET was accepted but the camera reports something else: it clamped or
 *                   snapped to its nearest legal value. Interesting — it marks a range edge.
 * - `rejected`    — the SET never came back, or came back with a non-zero status byte.
 * - `unreadable`  — the SET looked fine but the GET didn't decode. On a Pro this usually means a
 *                   byte map we don't have yet, NOT a failure — read `getAckHex`.
 * - `error`       — the call itself threw.
 */
export type MatrixVerdict = 'ok' | 'substituted' | 'rejected' | 'unreadable' | 'error';

export interface MatrixStep {
	group: MatrixGroup;
	/** Human label of the candidate, e.g. "ISO 800" or "16:9 1080p/60". */
	label: string;
	/** Canonical form of what we asked for, compared against `readback`. */
	requested: string;
	/** Canonical form of what the GET reported, or null if it didn't decode. */
	readback: string | null;
	verdict: MatrixVerdict;
	setAckHex: string;
	setTimedOut: boolean;
	getAckHex: string;
	getTimedOut: boolean;
	/** Populated when `verdict === 'error'`. */
	error?: string;
	atMs: number;
}

export interface MatrixReport {
	startedAtMs: number;
	finishedAtMs: number;
	/** Model as reported by the goggles' device-code lookup ('o4-lite' | 'o4-pro' | 'unknown'). */
	cameraModel: string;
	goggleModel: string;
	droneModel: string | null;
	appVersion: string;
	/**
	 * Operator's note on the conditions of the run. The goggles' own mode (race / normal) changes
	 * what the air unit will accept — a race-mode run pins the video format and refuses every other
	 * one — and nothing on the wire tells us which mode we were in. Without this the report is
	 * ambiguous after the fact.
	 */
	notes: string;
	/** State read before the sweep — also what gets restored afterwards. */
	stateBefore: CameraState | null;
	stateAfter: CameraState | null;
	aborted: boolean;
	steps: MatrixStep[];
}

export interface MatrixOptions {
	sessionId: string;
	groups: readonly MatrixGroup[];
	cameraModel: string;
	goggleModel: string;
	droneModel?: string | null;
	appVersion: string;
	/** Free text describing the run's conditions — above all the goggles' race/normal mode. */
	notes?: string;
	/** Pause between ordinary steps, giving the camera time to settle. */
	stepDelayMs?: number;
	/** Longer pause after a video-format change — it restarts the encoder and the stream. */
	videoSettleMs?: number;
	onStep?: (step: MatrixStep) => void;
	onProgress?: (done: number, total: number) => void;
	/** Polled between steps; return true to stop early (state is still restored). */
	shouldAbort?: () => boolean;
}

/**
 * A SET counts as accepted when it answered at all and the leading status byte is 0x00. Some SETs
 * reply with a bare status byte and some echo the value back; either way byte 0 is the status.
 */
function setAccepted(res: SendCameraCommandResult): boolean {
	if (res.timedOut) return false;
	const hex = ackToHex(res);
	return hex === '' || hex.startsWith('00');
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * White-balance Kelvin ladder. The wire accepts 2000–10000 in steps of 100, but sweeping all 81
 * would add minutes for no new information — the interesting question is where the camera starts
 * clamping, so we walk in 500 K steps and pin both ends.
 */
export const wbKelvinLadder: number[] = (() => {
	const out: number[] = [];
	for (let k = 2000; k <= 10000; k += 500) out.push(k);
	return out;
})();

/**
 * ISO candidates: the discrete ladder plus the two auto modes. `auto` / `auto-high` are the only
 * values in this whole sweep we have never written to real hardware — the byte map for them is
 * [DISSECTOR]-only — which is precisely why they belong here.
 */
export const isoCandidates: readonly IsoState[] = ['auto', 'auto-high', ...isoValues];

/** How many steps the selected groups add up to, for the progress bar. */
export function stepCount(groups: readonly MatrixGroup[]): number {
	let n = 0;
	for (const g of groups) {
		if (g === 'exposure') n += exposureModes.length;
		if (g === 'iso') n += isoCandidates.length;
		if (g === 'shutter') n += shutterDenominators.length;
		if (g === 'wb') n += 1 + wbKelvinLadder.length;
		if (g === 'videoFormat')
			n += videoAspects.length * videoResolutions.length * videoFpsValues.length;
	}
	return n;
}

export async function runCameraMatrix(opts: MatrixOptions): Promise<MatrixReport> {
	const {
		sessionId,
		groups,
		stepDelayMs = 250,
		videoSettleMs = 1200,
		onStep,
		onProgress,
		shouldAbort = () => false
	} = opts;

	const steps: MatrixStep[] = [];
	const total = stepCount(groups);
	let aborted = false;

	const record = (s: MatrixStep) => {
		steps.push(s);
		onStep?.(s);
		onProgress?.(steps.length, total);
	};

	/**
	 * One candidate: send it, read it back, judge it. `canonical` turns the decoded readback into
	 * the same string shape as `requested`, so each group decides what "the same value" means.
	 */
	async function probe<T>(
		group: MatrixGroup,
		label: string,
		requested: string,
		set: () => Promise<SendCameraCommandResult>,
		get: () => Promise<SendCameraCommandResult>,
		decode: (res: SendCameraCommandResult) => T | null,
		canonical: (v: T) => string,
		settleMs: number
	) {
		const atMs = Date.now();
		try {
			const setRes = await set();
			await sleep(settleMs);
			const getRes = await get();
			const decoded = decode(getRes);
			const readback = decoded === null ? null : canonical(decoded);

			let verdict: MatrixVerdict;
			if (!setAccepted(setRes)) verdict = 'rejected';
			else if (readback === null) verdict = 'unreadable';
			else if (readback === requested) verdict = 'ok';
			else verdict = 'substituted';

			record({
				group,
				label,
				requested,
				readback,
				verdict,
				setAckHex: ackToHex(setRes),
				setTimedOut: setRes.timedOut,
				getAckHex: ackToHex(getRes),
				getTimedOut: getRes.timedOut,
				atMs
			});
		} catch (e) {
			record({
				group,
				label,
				requested,
				readback: null,
				verdict: 'error',
				setAckHex: '',
				setTimedOut: false,
				getAckHex: '',
				getTimedOut: false,
				error: e instanceof Error ? e.message : String(e),
				atMs
			});
		}
	}

	const startedAtMs = Date.now();
	const stateBefore = await readCameraState(sessionId).catch(() => null);

	try {
		if (groups.includes('exposure')) {
			for (const mode of exposureModes) {
				if (shouldAbort()) {
					aborted = true;
					break;
				}
				await probe(
					'exposure',
					`exposure ${mode}`,
					mode,
					() => camera.setExposureMode(sessionId, mode),
					() => camera.getExposureMode(sessionId),
					decodeExposureMode,
					(v) => v,
					stepDelayMs
				);
			}
		}

		// ISO and shutter are only honoured in manual exposure — in auto the camera picks its own
		// and every readback would report the auto-chosen value, making the sweep meaningless.
		const needsManual = groups.includes('iso') || groups.includes('shutter');
		if (needsManual && !aborted) {
			await camera.setExposureMode(sessionId, 'manual');
			await sleep(stepDelayMs);
		}

		if (groups.includes('iso') && !aborted) {
			for (const iso of isoCandidates) {
				if (shouldAbort()) {
					aborted = true;
					break;
				}
				await probe(
					'iso',
					`ISO ${iso}`,
					String(iso),
					() => camera.setIso(sessionId, iso),
					() => camera.getIso(sessionId),
					decodeIso,
					(v) => String(v),
					stepDelayMs
				);
			}
		}

		if (groups.includes('shutter') && !aborted) {
			for (const denom of shutterDenominators) {
				if (shouldAbort()) {
					aborted = true;
					break;
				}
				await probe(
					'shutter',
					`1/${denom}`,
					String(denom),
					() => camera.setShutter(sessionId, denom as ShutterDenominator),
					() => camera.getShutter(sessionId),
					decodeShutter,
					(v) => String(v),
					stepDelayMs
				);
			}
		}

		if (groups.includes('wb') && !aborted) {
			const wbCanonical = (v: WbReading) => (v.mode === 'auto' ? 'auto' : String(v.kelvin));
			if (!shouldAbort()) {
				await probe(
					'wb',
					'WB auto',
					'auto',
					() => camera.setWb(sessionId, { mode: 'auto' }),
					() => camera.getWb(sessionId),
					decodeWb,
					wbCanonical,
					stepDelayMs
				);
			}
			for (const kelvin of wbKelvinLadder) {
				if (shouldAbort()) {
					aborted = true;
					break;
				}
				await probe(
					'wb',
					`WB ${kelvin}K`,
					String(kelvin),
					() => camera.setWb(sessionId, { mode: 'custom', kelvin }),
					() => camera.getWb(sessionId),
					decodeWb,
					wbCanonical,
					stepDelayMs
				);
			}
		}

		if (groups.includes('videoFormat') && !aborted) {
			// This only covers the four format bytes the Lite proved. A unit with modes we have no
			// byte for — the O4 Pro is the obvious candidate — will not show them here at all; that
			// is what `sweepFormatByte` is for.
			outer: for (const aspect of videoAspects) {
				for (const resolution of videoResolutions) {
					for (const fps of videoFpsValues) {
						if (shouldAbort()) {
							aborted = true;
							break outer;
						}
						const want = `${aspect} ${resolution}/${fps}`;
						await probe(
							'videoFormat',
							want,
							want,
							() =>
								camera.setVideoFormat(
									sessionId,
									aspect as VideoAspect,
									resolution as VideoResolution,
									fps as VideoFps
								),
							() => camera.getVideoFormat(sessionId),
							decodeVideoFormat,
							(v) => `${v.aspect} ${v.resolution}/${v.fps}`,
							videoSettleMs
						);
					}
				}
			}
		}
	} finally {
		await restore(sessionId, stateBefore, videoSettleMs);
	}

	const stateAfter = await readCameraState(sessionId).catch(() => null);

	return {
		startedAtMs,
		finishedAtMs: Date.now(),
		cameraModel: opts.cameraModel,
		goggleModel: opts.goggleModel,
		droneModel: opts.droneModel ?? null,
		appVersion: opts.appVersion,
		notes: opts.notes ?? '',
		stateBefore,
		stateAfter,
		aborted,
		steps
	};
}

/**
 * Put the camera back the way we found it. Video format goes first — it is the disruptive one and
 * restarts the stream — and exposure mode last, so the ISO/shutter writes before it still land
 * while the camera is in manual, where they mean something.
 */
async function restore(sessionId: string, before: CameraState | null, videoSettleMs: number) {
	if (!before) return;
	try {
		if (before.videoFormat) {
			await camera.setVideoFormat(
				sessionId,
				before.videoFormat.aspect,
				before.videoFormat.resolution,
				before.videoFormat.fps
			);
			await sleep(videoSettleMs);
		}
		if (before.wb) await camera.setWb(sessionId, wbReadingToValue(before.wb));
		if (before.shutter !== null) await camera.setShutter(sessionId, before.shutter);
		// `iso` may be 'auto' / 'auto-high', not just a number — restoring only the numeric case
		// would leave a camera that was on auto ISO pinned to the sweep's last value.
		if (before.iso !== null) await camera.setIso(sessionId, before.iso);
		if (before.exposureMode) await camera.setExposureMode(sessionId, before.exposureMode);
	} catch (e) {
		console.warn('[camera-matrix] restore failed', e);
	}
}

/** Per-group tallies, for the on-screen summary and the report header. */
export function summarize(steps: MatrixStep[]): Record<string, Record<MatrixVerdict, number>> {
	const out: Record<string, Record<MatrixVerdict, number>> = {};
	for (const s of steps) {
		out[s.group] ??= { ok: 0, substituted: 0, rejected: 0, unreadable: 0, error: 0 };
		out[s.group][s.verdict]++;
	}
	return out;
}

/**
 * Render the report as Markdown, ready to paste into the protocol notes. The raw SET/GET hex is
 * kept in the table on purpose: it is the evidence, and for anything the decoders
 * don't know yet it is the only usable record.
 */
export function reportToMarkdown(r: MatrixReport): string {
	const iso = (ms: number) => new Date(ms).toISOString();
	const lines: string[] = [];
	const durationS = Math.round((r.finishedAtMs - r.startedAtMs) / 1000);

	lines.push(`# Camera matrix — ${r.cameraModel}`);
	lines.push('');
	lines.push(`- **Camera model**: \`${r.cameraModel}\` (aircraft \`${r.droneModel ?? '?'}\`)`);
	lines.push(`- **Goggles**: ${r.goggleModel}`);
	lines.push(`- **App version**: ${r.appVersion}`);
	lines.push(`- **Conditions**: ${r.notes || '⚠ not recorded — goggle mode unknown'}`);
	lines.push(`- **Run**: ${iso(r.startedAtMs)} → ${iso(r.finishedAtMs)} (${durationS}s)`);
	if (r.aborted) lines.push('- **⚠ ABORTED** — the sweep was stopped early; coverage is partial.');
	lines.push(`- **State before**: \`${JSON.stringify(r.stateBefore)}\``);
	lines.push(`- **State after**: \`${JSON.stringify(r.stateAfter)}\``);
	lines.push('');

	const sum = summarize(r.steps);
	lines.push('| group | ok | substituted | rejected | unreadable | error |');
	lines.push('|---|---|---|---|---|---|');
	for (const [g, c] of Object.entries(sum)) {
		lines.push(`| ${g} | ${c.ok} | ${c.substituted} | ${c.rejected} | ${c.unreadable} | ${c.error} |`);
	}
	lines.push('');

	for (const group of reportGroups) {
		const rows = r.steps.filter((s) => s.group === group);
		if (rows.length === 0) continue;
		lines.push(`## ${group}`);
		lines.push('');
		lines.push('| value | verdict | readback | SET ack | GET ack |');
		lines.push('|---|---|---|---|---|');
		for (const s of rows) {
			const setCell = s.setTimedOut ? '*timeout*' : `\`${s.setAckHex || '(empty)'}\``;
			const getCell = s.getTimedOut ? '*timeout*' : `\`${s.getAckHex || '(empty)'}\``;
			const note = s.error ? ` (${s.error})` : '';
			lines.push(
				`| ${s.label} | ${s.verdict}${note} | ${s.readback ?? '—'} | ${setCell} | ${getCell} |`
			);
		}
		lines.push('');
	}

	return lines.join('\n');
}


// `FPS_TO_BYTE` is {30:3, 50:5, 60:6, 100:10, 120:7}. Only 100→10 is hardware-confirmed; the rest
// is [INFERRED]. Four of the five entries are exactly fps/10 — and 120→7 is the
// one that is not, which makes it the obvious suspect: by that pattern it should be 12 (0x0c).
//
// The race-mode run is what raised the question. Race mode allows 1080p at 100 *and* 120 fps, yet
// `low/h264/120` came back accepted-then-ignored: SET acked 0x00 and the format stayed at 100. If
// 120 is legal in that mode, the byte we sent for it is wrong.
//
// This walks the fps byte over a range at whatever resCodec the camera currently reports, so it
// answers "which byte actually selects 120" directly. It leaves the format byte alone — only the
// fps field moves.

/** Read the current `[resCodec, fps]` pair as raw bytes, without decoding it. */
async function currentFormatBytes(sessionId: string): Promise<[number, number] | null> {
	const hex = ackToHex(await sendCameraRaw(sessionId, CamCmd.VIDEO_FORMAT_GET));
	if (hex.length < 6) return null;
	return [parseInt(hex.slice(2, 4), 16), parseInt(hex.slice(4, 6), 16)];
}

/**
 * Sweep the fps byte from `from` to `to` inclusive, holding resCodec at its current value, and
 * restore the original pair afterwards. Reports raw bytes — there is deliberately no decoding,
 * since the whole point is bytes the maps may not know.
 */
export async function sweepFpsByte(
	sessionId: string,
	from: number,
	to: number,
	onStep?: (s: MatrixStep) => void,
	shouldAbort: () => boolean = () => false
): Promise<MatrixStep[]> {
	const original = await currentFormatBytes(sessionId);
	if (!original) throw new Error('could not read the current video format');
	const [resCodec, originalFps] = original;

	const steps: MatrixStep[] = [];
	try {
		for (let b = from; b <= to; b++) {
			if (shouldAbort()) break;
			const atMs = Date.now();
			const setRes = await sendCameraRaw(
				sessionId,
				CamCmd.VIDEO_FORMAT_SET,
				new Uint8Array([resCodec, b, 0])
			);
			await sleep(1200);
			const getRes = await sendCameraRaw(sessionId, CamCmd.VIDEO_FORMAT_GET);
			const setHex = ackToHex(setRes);
			const getHex = ackToHex(getRes);
			const readBack = getHex.length >= 6 ? parseInt(getHex.slice(4, 6), 16) : null;

			let verdict: MatrixVerdict;
			if (setRes.timedOut || (setHex !== '' && !setHex.startsWith('00'))) verdict = 'rejected';
			else if (readBack === null) verdict = 'unreadable';
			else if (readBack === b) verdict = 'ok';
			else verdict = 'substituted';

			const step: MatrixStep = {
				group: 'fpsByte',
				label: `fps byte 0x${b.toString(16).padStart(2, '0')} (${b})`,
				requested: String(b),
				readback: readBack === null ? null : String(readBack),
				verdict,
				setAckHex: setHex,
				setTimedOut: setRes.timedOut,
				getAckHex: getHex,
				getTimedOut: getRes.timedOut,
				atMs
			};
			steps.push(step);
			onStep?.(step);
		}
	} finally {
		await sendCameraRaw(
			sessionId,
			CamCmd.VIDEO_FORMAT_SET,
			new Uint8Array([resCodec, originalFps, 0])
		).catch(() => {});
	}
	return steps;
}

// The Lite taught the expensive lesson: our `FORMAT_BYTE` table was guessed and both of its axis
// labels were wrong. A sweep can only ever exercise bytes we already believe in,
// so on a unit with modes we have no byte for it reports nothing at all — a silent blind spot.
//
// This closes it by enumerating instead of guessing: walk the format byte across a range at one
// fixed fps and record which values the camera accepts and reads back. Every byte that round-trips
// is a real mode of this air unit, named or not. Run it on the O4 Pro before trusting any sweep
// there — a 2.7K mode, or the still-unexplained bytes 45 / 95, would show up here and nowhere else.

/**
 * Walk the format byte from `from` to `to` inclusive at a fixed `fps`, restoring the original pair
 * afterwards. Raw bytes only — decoding is the very thing that cannot be trusted here.
 *
 * Pick an fps every mode supports (30) for a full census; pick 120 to isolate the high-fps modes,
 * since a resolution that cannot reach it will refuse.
 */
export async function sweepFormatByte(
	sessionId: string,
	from: number,
	to: number,
	fpsByte: number,
	onStep?: (s: MatrixStep) => void,
	shouldAbort: () => boolean = () => false
): Promise<MatrixStep[]> {
	const original = await currentFormatBytes(sessionId);
	if (!original) throw new Error('could not read the current video format');

	const steps: MatrixStep[] = [];
	try {
		for (let b = from; b <= to; b++) {
			if (shouldAbort()) break;
			const atMs = Date.now();
			const setRes = await sendCameraRaw(
				sessionId,
				CamCmd.VIDEO_FORMAT_SET,
				new Uint8Array([b, fpsByte, 0])
			);
			await sleep(900);
			const getRes = await sendCameraRaw(sessionId, CamCmd.VIDEO_FORMAT_GET);
			const setHex = ackToHex(setRes);
			const getHex = ackToHex(getRes);
			const readBack = getHex.length >= 4 ? parseInt(getHex.slice(2, 4), 16) : null;

			let verdict: MatrixVerdict;
			if (setRes.timedOut || (setHex !== '' && !setHex.startsWith('00'))) verdict = 'rejected';
			else if (readBack === null) verdict = 'unreadable';
			else if (readBack === b) verdict = 'ok';
			else verdict = 'substituted';

			const step: MatrixStep = {
				group: 'formatByte',
				label: `format 0x${b.toString(16).padStart(2, '0')} @ fps byte ${fpsByte}`,
				requested: String(b),
				readback: readBack === null ? null : String(readBack),
				verdict,
				setAckHex: setHex,
				setTimedOut: setRes.timedOut,
				getAckHex: getHex,
				getTimedOut: getRes.timedOut,
				atMs
			};
			steps.push(step);
			onStep?.(step);
		}
	} finally {
		await sendCameraRaw(
			sessionId,
			CamCmd.VIDEO_FORMAT_SET,
			new Uint8Array([original[0], original[1], 0])
		).catch(() => {});
	}
	return steps;
}

// Can the O4 be switched from the normal picture profile to D-Log / HLG? Unknown: nothing in the
// app models it and `colorProfilesPro` in the schemas is a placeholder with no cmdId behind it.
//
// What we do have is a handful of cmdSet-0x02 cmdIds seen in this territory, none with a verified
// encoding. The point of the probe is to find out which ones this air unit even answers, and then
// whether a value written to them sticks.
//
// The probe is built read-first on purpose: a candidate is only ever written to after its Get
// answered, so we can both verify the change and put the original value back. Candidates with no Get
// pair are reported but never written automatically — see `manualOnly`.

export interface ProfileCandidate {
	/** Working name for the command. */
	name: string;
	setId: number;
	/** null when the command has no known Get pair — then we cannot read back or restore. */
	getId: number | null;
	/** Single-byte values to try. Small on purpose: these are guesses at an unknown enum. */
	values: readonly number[];
	/** Why this one is a candidate at all. */
	note: string;
}

export const colorProfileCandidates: readonly ProfileCandidate[] = [
	{
		name: 'Scene Mode',
		setId: 0x20,
		getId: 0x21,
		values: [0, 1, 2, 3, 4],
		note: 'On older DJI cameras the picture profile lives here (Normal / Art / D-Cinelike…).'
	},
	{
		name: 'Digital Effect',
		setId: 0x42,
		getId: 0x43,
		values: [0, 1, 2, 3],
		note: "DJI's 'digital filter' — None / Art / B&W / film-look on several generations."
	},
	{
		name: 'Video Adaptive Gamma',
		setId: 0x9b,
		getId: 0x9c,
		values: [0, 1, 2],
		note: 'A gamma-curve switch is the shape a log profile would take.'
	},
	{
		name: 'Video Encode',
		setId: 0xab,
		getId: 0xac,
		values: [0, 1, 2],
		note: 'May be where the encoder profile lives. The video-format command turned out not to carry the codec at all, so if the codec is settable anywhere it is a command like this one.'
	},
	{
		name: 'Log Mode',
		setId: 0xc1,
		getId: null,
		values: [0, 1],
		note: 'The most directly named candidate — but the dictionary lists no Get, so only your eyes can confirm it.'
	}
];

/** Candidates the probe refuses to write on its own. */
export const manualOnly = (c: ProfileCandidate) => c.getId === null;

export interface ProfileScanRow {
	name: string;
	setId: number;
	getId: number | null;
	result: 'answered' | 'error-status' | 'silent' | 'no-get';
	/** Raw reply bytes, hex. The value is whatever follows the leading status byte. */
	ackHex: string;
}

/**
 * Read-only pass: ask every candidate's Get what it holds. Completely safe — it writes nothing —
 * and it is what tells you which of these commands this air unit implements at all.
 */
export async function scanColorProfiles(sessionId: string): Promise<ProfileScanRow[]> {
	const out: ProfileScanRow[] = [];
	for (const c of colorProfileCandidates) {
		if (c.getId === null) {
			out.push({ name: c.name, setId: c.setId, getId: null, result: 'no-get', ackHex: '' });
			continue;
		}
		const res = await sendCameraRaw(sessionId, c.getId);
		const hex = ackToHex(res);
		const result = res.timedOut ? 'silent' : hex === '' || hex.startsWith('00') ? 'answered' : 'error-status';
		out.push({ name: c.name, setId: c.setId, getId: c.getId, result, ackHex: hex });
		await sleep(150);
	}
	return out;
}

/**
 * Write pass for one candidate: capture what it currently holds, try each value, read each one
 * back, then put the original bytes back.
 *
 * Only call this for a candidate whose Get answered — `scanColorProfiles` is the gate. The value
 * range is small and the command set is limited to camera imaging, but this is still writing to a
 * command whose meaning we are guessing at, so it is opt-in in the UI and never part of the main
 * sweep.
 */
export async function sweepColorProfile(
	sessionId: string,
	candidate: ProfileCandidate,
	onStep?: (s: MatrixStep) => void
): Promise<MatrixStep[]> {
	const { name, setId, getId, values } = candidate;
	if (getId === null) throw new Error(`${name}: no Get pair — not safe to write automatically`);

	const steps: MatrixStep[] = [];
	const push = (s: MatrixStep) => {
		steps.push(s);
		onStep?.(s);
	};

	const originalHex = ackToHex(await sendCameraRaw(sessionId, getId));
	const originalValue = originalHex.length >= 4 ? parseInt(originalHex.slice(2, 4), 16) : null;

	try {
		for (const v of values) {
			const atMs = Date.now();
			const setRes = await sendCameraRaw(sessionId, setId, new Uint8Array([v]));
			await sleep(400);
			const getRes = await sendCameraRaw(sessionId, getId);
			const setHex = ackToHex(setRes);
			const getHex = ackToHex(getRes);
			const readBack = getHex.length >= 4 ? parseInt(getHex.slice(2, 4), 16) : null;

			let verdict: MatrixVerdict;
			if (setRes.timedOut || (setHex !== '' && !setHex.startsWith('00'))) verdict = 'rejected';
			else if (readBack === null) verdict = 'unreadable';
			else if (readBack === v) verdict = 'ok';
			else verdict = 'substituted';

			push({
				group: 'colorProfile',
				label: `${name} = ${v}`,
				requested: String(v),
				readback: readBack === null ? null : String(readBack),
				verdict,
				setAckHex: setHex,
				setTimedOut: setRes.timedOut,
				getAckHex: getHex,
				getTimedOut: getRes.timedOut,
				atMs
			});
		}
	} finally {
		if (originalValue !== null) {
			await sendCameraRaw(sessionId, setId, new Uint8Array([originalValue])).catch(() => {});
		}
	}
	return steps;
}

/**
 * Fire one raw byte at a candidate with no Get pair (Log Mode). There is no readback and no
 * automatic restore: the operator watches the video for a flat, washed-out picture, and sends the
 * other value to undo it. Kept separate from everything above precisely because it is not reversible
 * by code.
 */
export async function setProfileByteManually(
	sessionId: string,
	candidate: ProfileCandidate,
	value: number
): Promise<{ ackHex: string; timedOut: boolean }> {
	const res = await sendCameraRaw(sessionId, candidate.setId, new Uint8Array([value]));
	return { ackHex: ackToHex(res), timedOut: res.timedOut };
}
