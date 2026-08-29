import { writable, get, type Readable } from 'svelte/store';
import { connection } from '$lib/stores/connection';
import { patchSettings, currentSettings } from '$lib/stores/settings';
import {
	camera,
	readCameraState,
	decodeWb,
	type ExposureMode,
	type IsoState,
	type ShutterDenominator,
	type VideoAspect,
	type VideoFps,
	type VideoResolution,
	type SendCameraCommandResult
} from '$lib/aoa';

/**
 * The camera settings the UI shows. One shared snapshot instead of per-component `$state`:
 * the home screen renders it read-only, the camera screen and the in-stream panel edit it.
 *
 * Values start at the app defaults and are replaced by whatever the goggles report on connect
 * (`readCameraState`), so the UI reflects the real camera instead of hardcoded literals.
 */
export interface CameraUiState {
	exposureMode: ExposureMode;
	/** A discrete ISO, or one of the camera's two auto modes — all three are settable from the UI. */
	iso: IsoState;
	shutter: ShutterDenominator;
	wbMode: 'auto' | 'custom';
	wbKelvin: number;
	aspect: VideoAspect;
	resolution: VideoResolution;
	fps: VideoFps;
	/** True once at least one field came back from the goggles. */
	synced: boolean;
}

const DEFAULTS: CameraUiState = {
	exposureMode: 'auto',
	iso: 100,
	shutter: 120,
	wbMode: 'auto',
	wbKelvin: 5500,
	aspect: '16:9',
	resolution: '1080p',
	fps: 60,
	synced: false
};

const _state = writable<CameraUiState>({ ...DEFAULTS, fps: readPersistedFps() });

function readPersistedFps(): VideoFps {
	// Recording synthesizes PTS from this fps, so the persisted value is the better default.
	return currentSettings().camera.fps as VideoFps;
}

export const cameraState: Readable<CameraUiState> = _state;

export function patchCameraState(patch: Partial<CameraUiState>) {
	_state.update((s) => ({ ...s, ...patch }));
}

export function currentCameraState(): CameraUiState {
	return get(_state);
}

/** Ask the goggles for their current settings and fold whatever decodes into the store. */
export async function syncCameraState(sessionId: string): Promise<boolean> {
	const s = await readCameraState(sessionId);
	const patch: Partial<CameraUiState> = {};
	if (s.exposureMode) patch.exposureMode = s.exposureMode;
	if (s.iso !== null) patch.iso = s.iso;
	if (s.shutter !== null) patch.shutter = s.shutter;
	if (s.wb) {
		patch.wbMode = s.wb.mode;
		// Even in auto the camera reports the Kelvin it settled on — keep it, so the slider starts
		// at the real value and "lock" has something sane to fall back on.
		if (s.wb.kelvin !== null) patch.wbKelvin = s.wb.kelvin;
	}
	if (s.videoFormat) {
		patch.aspect = s.videoFormat.aspect;
		patch.resolution = s.videoFormat.resolution;
		patch.fps = s.videoFormat.fps;
		patchSettings({ camera: { fps: s.videoFormat.fps } });
	}
	const got = Object.keys(patch).length > 0;
	if (got) patchCameraState({ ...patch, synced: true });
	return got;
}

// Seed on connect. The camera may still be booting right after the AOA session opens, so retry a
// couple of times; every field that never answers just keeps its default.
// NOTE: the GET round-trips are hardware-confirmed on O4 Lite only (see AGENTS.md).
let seededSession: string | null = null;
connection.subscribe((c) => {
	if (c.state !== 'connected') {
		seededSession = null;
		patchCameraState({ synced: false });
		return;
	}
	if (seededSession === c.sessionId) return;
	seededSession = c.sessionId;
	void seedWithRetry(c.sessionId);
});

async function seedWithRetry(sessionId: string) {
	for (let attempt = 0; attempt < 3; attempt++) {
		if (seededSession !== sessionId) return; // session changed underneath us
		try {
			if (await syncCameraState(sessionId)) return;
		} catch (e) {
			console.warn('[camera] state read failed', e);
		}
		await new Promise((r) => setTimeout(r, 2000));
	}
}

/**
 * Apply one camera setting: optimistically update the store, send the command, roll back if the
 * goggles never acknowledge it.
 */
export async function applyCameraSetting<K extends keyof CameraUiState>(
	sessionId: string,
	patch: Pick<CameraUiState, K>,
	send: () => Promise<SendCameraCommandResult>
): Promise<SendCameraCommandResult> {
	const before = currentCameraState();
	patchCameraState(patch);
	try {
		const r = await send();
		if (r.timedOut) {
			patchCameraState(pick(before, Object.keys(patch) as K[]));
		}
		return r;
	} catch (e) {
		patchCameraState(pick(before, Object.keys(patch) as K[]));
		throw e;
	}
}

function pick<K extends keyof CameraUiState>(s: CameraUiState, keys: K[]): Partial<CameraUiState> {
	const out: Partial<CameraUiState> = {};
	for (const k of keys) out[k] = s[k];
	return out;
}

/** Convenience wrappers used by the camera panels. */
export const cameraActions = {
	exposureMode: (sessionId: string, mode: ExposureMode) =>
		applyCameraSetting(sessionId, { exposureMode: mode }, () =>
			camera.setExposureMode(sessionId, mode)
		),
	iso: (sessionId: string, iso: IsoState) =>
		applyCameraSetting(sessionId, { iso }, () => camera.setIso(sessionId, iso)),
	shutter: (sessionId: string, shutter: ShutterDenominator) =>
		applyCameraSetting(sessionId, { shutter }, () => camera.setShutter(sessionId, shutter)),
	wb: (sessionId: string, wbMode: 'auto' | 'custom', wbKelvin: number) =>
		applyCameraSetting(sessionId, { wbMode, wbKelvin }, () =>
			camera.setWb(sessionId, wbMode === 'auto' ? { mode: 'auto' } : { mode: 'custom', kelvin: wbKelvin })
		),
	/**
	 * Freeze the white balance auto is currently using: read back the Kelvin the camera settled on
	 * and re-send it as a custom value. Without this, going auto → manual would snap the tint to
	 * whatever the slider happened to hold; this keeps the picture looking exactly as it does now
	 * and only stops it from drifting.
	 *
	 * If the read doesn't decode, fall back to the last Kelvin we know — still better than a jump.
	 */
	lockWb: async (sessionId: string) => {
		const reading = decodeWb(await camera.getWb(sessionId));
		const kelvin = reading?.kelvin ?? currentCameraState().wbKelvin;
		return cameraActions.wb(sessionId, 'custom', kelvin);
	},
	videoFormat: async (
		sessionId: string,
		aspect: VideoAspect,
		resolution: VideoResolution,
		fps: VideoFps
	) => {
		const r = await applyCameraSetting(sessionId, { aspect, resolution, fps }, () =>
			camera.setVideoFormat(sessionId, aspect, resolution, fps)
		);
		if (!r.timedOut) patchSettings({ camera: { fps } });
		return r;
	}
};
