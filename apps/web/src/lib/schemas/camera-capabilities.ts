/**
 * What each air unit actually accepts — measured, not assumed.
 *
 * The wire layer (`$lib/aoa/camera`) knows how to *encode* every value the protocol has. This file
 * says which of them a given camera will take, so the UI can offer exactly that and nothing more.
 */

import {
	isoValues,
	shutterDenominators,
	wbKelvinMin,
	wbKelvinMax,
	wbKelvinStep,
	type IsoState,
	type ShutterDenominator,
	type VideoAspect,
	type VideoFps,
	type VideoResolution
} from '$lib/aoa/camera';
import type { CameraModel } from 'capacitor-dji-aoa';

/** One selectable video mode and the frame rates it accepts. */
export interface VideoMode {
	aspect: VideoAspect;
	resolution: VideoResolution;
	fps: readonly VideoFps[];
}

export interface CameraCapabilities {
	iso: readonly IsoState[];
	shutter: readonly ShutterDenominator[];
	wbKelvin: { min: number; max: number; step: number };
	video: readonly VideoMode[];
	/** Which bench run this table came from — keep it truthful when editing. */
	source: string;
}

// ISO, shutter and white balance came back **identical on both units**: the full ISO ladder
// including 25600 and both auto modes, all 22 shutter denominators, and the whole 2000–10000 K
// range. The per-model difference everyone expected exists only in the video modes.
const ISO_ALL: readonly IsoState[] = ['auto', 'auto-high', ...isoValues];
const WB_ALL = { min: wbKelvinMin, max: wbKelvinMax, step: wbKelvinStep };

const FPS_TO_120: readonly VideoFps[] = [30, 50, 60, 100, 120];
const FPS_TO_60: readonly VideoFps[] = [30, 50, 60];

export const cameraCapabilities: Record<'o4-lite' | 'o4-pro', CameraCapabilities> = {
	/** O4 Air Unit Lite (`ZA530`). No 2.7K — both of its bytes are refused with `0xe3`. */
	'o4-lite': {
		iso: ISO_ALL,
		shutter: shutterDenominators,
		wbKelvin: WB_ALL,
		video: [
			{ aspect: '16:9', resolution: '1080p', fps: FPS_TO_120 },
			{ aspect: '16:9', resolution: '4k', fps: FPS_TO_60 },
			{ aspect: '4:3', resolution: '1080p', fps: FPS_TO_120 },
			{ aspect: '4:3', resolution: '4k', fps: FPS_TO_60 }
		],
		source: 'O4 Lite sweep 2026-08-29 (normal mode)'
	},

	/**
	 * O4 Air Unit Pro (`ZA5305`). Adds 2.7K, and lifts the 16:9 ceiling to 120 fps at every
	 * resolution. 4:3 still only reaches 100/120 at 1080p — it carries ~33% more pixels.
	 */
	'o4-pro': {
		iso: ISO_ALL,
		shutter: shutterDenominators,
		wbKelvin: WB_ALL,
		video: [
			{ aspect: '16:9', resolution: '1080p', fps: FPS_TO_120 },
			{ aspect: '16:9', resolution: '2.7k', fps: FPS_TO_120 },
			{ aspect: '16:9', resolution: '4k', fps: FPS_TO_120 },
			{ aspect: '4:3', resolution: '1080p', fps: FPS_TO_120 },
			{ aspect: '4:3', resolution: '2.7k', fps: FPS_TO_60 },
			{ aspect: '4:3', resolution: '4k', fps: FPS_TO_60 }
		],
		source: 'O4 Pro sweep + format-byte census 2026-08-29 (normal mode)'
	}
};

/**
 * What an unrecognised camera gets: the **intersection** of the units we have measured, which is
 * the Lite's set. Deliberately not "everything" — offering a mode the attached unit refuses is how
 * the old UI shipped an H.265 control that never once worked. The UI must say when this is in
 * effect rather than pass it off as a real capability table; `CameraControls` uses
 * `cam_modelNotDetected` for that.
 */
export const fallbackCapabilities: CameraCapabilities = {
	...cameraCapabilities['o4-lite'],
	source: 'fallback — camera model not detected; showing what both known units accept'
};

export function capabilitiesFor(model: CameraModel | string | null | undefined): CameraCapabilities {
	if (model === 'o4-lite' || model === 'o4-pro') return cameraCapabilities[model];
	return fallbackCapabilities;
}

/** Whether `capabilitiesFor` had to fall back, so the UI can surface it. */
export function isFallback(model: CameraModel | string | null | undefined): boolean {
	return model !== 'o4-lite' && model !== 'o4-pro';
}

/** The aspects this camera offers, in `videoAspects` order. */
export function aspectsOf(caps: CameraCapabilities): VideoAspect[] {
	return [...new Set(caps.video.map((m) => m.aspect))];
}

/** The resolutions available for one aspect. */
export function resolutionsFor(caps: CameraCapabilities, aspect: VideoAspect): VideoResolution[] {
	return caps.video.filter((m) => m.aspect === aspect).map((m) => m.resolution);
}

/** The frame rates available for one aspect + resolution; empty if the pair isn't offered. */
export function fpsFor(
	caps: CameraCapabilities,
	aspect: VideoAspect,
	resolution: VideoResolution
): readonly VideoFps[] {
	return caps.video.find((m) => m.aspect === aspect && m.resolution === resolution)?.fps ?? [];
}

/**
 * Nearest legal mode to a requested one, for when a change to aspect or resolution invalidates the
 * current selection — picking 4:3 4K on a Pro while at 120 fps, say. Keeps the frame rate if it
 * survives, otherwise takes that mode's highest.
 */
export function nearestMode(
	caps: CameraCapabilities,
	aspect: VideoAspect,
	resolution: VideoResolution,
	fps: VideoFps
): { aspect: VideoAspect; resolution: VideoResolution; fps: VideoFps } | null {
	const exact = caps.video.find((m) => m.aspect === aspect && m.resolution === resolution);
	const mode = exact ?? caps.video.find((m) => m.aspect === aspect) ?? caps.video[0];
	if (!mode) return null;
	const chosen = mode.fps.includes(fps) ? fps : mode.fps[mode.fps.length - 1];
	return { aspect: mode.aspect, resolution: mode.resolution, fps: chosen };
}
