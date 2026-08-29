import { writable, derived, get, type Readable } from 'svelte/store';
import { Capacitor } from '@capacitor/core';
import { DjiAoa, type RecordingStateEvent } from 'capacitor-dji-aoa';
import { currentSettings } from '$lib/stores/settings';
import * as m from '$lib/paraglide/messages';

export type RecordingSource = 'manual' | 'auto';

interface RecordingSnapshot {
	id: string | null;
	state: RecordingStateEvent['state'];
	startedAtMs: number | null;
	durationMs: number;
	outputPath: string | null;
	/** Who pressed REC — auto-record only stops what auto started. */
	source: RecordingSource;
}

const _state = writable<RecordingSnapshot>({
	id: null,
	state: 'stopped',
	startedAtMs: null,
	durationMs: 0,
	outputPath: null,
	source: 'manual'
});

// Elapsed-time ticker: runs only while recording so idle screens pay nothing.
const _elapsedMs = writable(0);
let ticker: ReturnType<typeof setInterval> | null = null;

function syncTicker(s: RecordingSnapshot) {
	const active = s.state === 'recording' && s.startedAtMs !== null;
	if (active && !ticker) {
		ticker = setInterval(() => {
			const cur = get(_state);
			_elapsedMs.set(cur.startedAtMs ? Date.now() - cur.startedAtMs : 0);
		}, 500);
	} else if (!active && ticker) {
		clearInterval(ticker);
		ticker = null;
	}
	if (!active) _elapsedMs.set(s.durationMs);
}

async function init() {
	if (!Capacitor.isNativePlatform()) return;
	await DjiAoa.addListener('recordingState', (e) => {
		_state.update((prev) => {
			const next: RecordingSnapshot = {
				...prev,
				id: e.recordingId,
				state: e.state,
				startedAtMs:
					e.state === 'recording' && prev.state !== 'recording' ? Date.now() : prev.startedAtMs,
				durationMs: e.durationMs ?? prev.durationMs
			};
			syncTicker(next);
			return next;
		});
	});
}
init().catch((e) => console.warn('[recording] init failed', e));

export const recording: Readable<RecordingSnapshot> = _state;
export const isRecording = derived(_state, (s) => s.state === 'recording' || s.state === 'starting');
export const recordingElapsedMs: Readable<number> = _elapsedMs;

export function formatElapsed(ms: number): string {
	const total = Math.max(0, Math.floor(ms / 1000));
	const m = Math.floor(total / 60);
	const s = total % 60;
	return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}

/**
 * Start a recording. Audio and fps come from the persisted settings unless overridden.
 * Requests the RECORD_AUDIO runtime permission when audio is wanted: a manual start with
 * a denied mic throws (the UI should toast); an auto start silently falls back to no audio
 * so auto-record never dies on a permission dialog mid-flight.
 */
export async function startRecording(
	sessionId: string,
	opts?: { recordAudio?: boolean; fps?: number; source?: RecordingSource }
) {
	const cfg = currentSettings();
	const source: RecordingSource = opts?.source ?? 'manual';
	let recordAudio = opts?.recordAudio ?? cfg.recordPhoneAudio;
	const fps = opts?.fps ?? cfg.camera.fps;

	if (recordAudio) {
		const { granted } = await DjiAoa.requestAudioPermission();
		if (!granted) {
			if (source === 'manual') throw new Error(m.rec_micDenied());
			recordAudio = false;
		}
	}

	const r = await DjiAoa.startRecording({ sessionId, recordAudio, fps });
	_state.update((prev) => ({
		...prev,
		id: r.recordingId,
		outputPath: r.outputPath || prev.outputPath,
		source
	}));
}

export async function stopRecording() {
	const id = get(_state).id;
	if (!id) return;
	const r = await DjiAoa.stopRecording({ recordingId: id });
	_state.update((prev) => {
		const next: RecordingSnapshot = {
			...prev,
			state: 'stopped',
			durationMs: r.durationMs,
			outputPath: r.outputPath || prev.outputPath
		};
		syncTicker(next);
		return next;
	});
}
