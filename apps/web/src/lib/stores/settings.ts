import { writable, get, type Writable } from 'svelte/store';
import type { BroadcastQuality } from 'capacitor-dji-aoa';

/**
 * App-wide persisted settings (localStorage). One store, one key — every field has a
 * default so old persisted blobs survive schema growth (unknown keys are dropped,
 * missing keys fall back to defaults via deep-merge).
 */

export type SideTabSection = 'camera' | 'app';

export interface AppSettings {
	/** UI language (BCP-47 tag, e.g. "en" / "pt-BR"). `null` = follow the device locale. */
	locale: string | null;
	recordPhoneAudio: boolean;
	autoRecord: {
		/** Start recording when the aircraft arms, stop after the disarm delay. */
		enabled: boolean;
		/** How long to keep recording after disarm (ms). */
		stopDelayMs: number;
	};
	broadcast: {
		/** Start the WebRTC broadcast automatically when video starts flowing. */
		autoStart: boolean;
		/**
		 * Latency-vs-picture-quality profile for the WebRTC re-encode. `fidelity` buys the
		 * best picture the downlink allows with a ~1 s viewer-side buffer.
		 */
		quality: BroadcastQuality;
	};
	camera: {
		/** Last video-format fps applied — used to synthesize recording PTS. */
		fps: number;
	};
	ui: {
		sideTabOpen: boolean;
		sideTabSection: SideTabSection;
		/** Lock the screen to landscape while video is streaming (overrides system auto-rotate). */
		forceLandscape: boolean;
	};
	/** Show the developer tools (raw frame/DUML viewer at /debug). Off by default. */
	developerMode: boolean;
	diagnostics: {
		/** The user has seen and accepted the diagnostics consent dialog at least once. */
		consented: boolean;
		/** Upload anonymized diagnostic captures to the cloud. Requires `consented`. */
		uploadEnabled: boolean;
	};
}

const DEFAULTS: AppSettings = {
	locale: null,
	recordPhoneAudio: true,
	autoRecord: { enabled: false, stopDelayMs: 5000 },
	broadcast: { autoStart: false, quality: 'balanced' },
	camera: { fps: 60 },
	ui: { sideTabOpen: false, sideTabSection: 'camera', forceLandscape: false },
	developerMode: false,
	diagnostics: { consented: false, uploadEnabled: false }
};

const LS_KEY = 'openfpvcast.settings.v1';

function load(): AppSettings {
	try {
		if (typeof localStorage === 'undefined') return structuredClone(DEFAULTS);
		const raw = localStorage.getItem(LS_KEY);
		if (!raw) return structuredClone(DEFAULTS);
		const parsed = JSON.parse(raw) as Partial<AppSettings>;
		return {
			locale: parsed.locale ?? DEFAULTS.locale,
			recordPhoneAudio: parsed.recordPhoneAudio ?? DEFAULTS.recordPhoneAudio,
			autoRecord: { ...DEFAULTS.autoRecord, ...parsed.autoRecord },
			broadcast: { ...DEFAULTS.broadcast, ...parsed.broadcast },
			camera: { ...DEFAULTS.camera, ...parsed.camera },
			ui: { ...DEFAULTS.ui, ...parsed.ui },
				developerMode: parsed.developerMode ?? DEFAULTS.developerMode,
				diagnostics: { ...DEFAULTS.diagnostics, ...parsed.diagnostics }
		};
	} catch {
		return structuredClone(DEFAULTS);
	}
}

const _settings: Writable<AppSettings> = writable(load());

_settings.subscribe((s) => {
	try {
		if (typeof localStorage !== 'undefined') localStorage.setItem(LS_KEY, JSON.stringify(s));
	} catch {}
});

export const settings = _settings;

/** Shallow-patch a top-level section, e.g. `patchSettings({ recordPhoneAudio: false })`. */
export function patchSettings(patch: Partial<AppSettings>) {
	_settings.update((s) => ({
		...s,
		...patch,
		autoRecord: { ...s.autoRecord, ...patch.autoRecord },
		broadcast: { ...s.broadcast, ...patch.broadcast },
		camera: { ...s.camera, ...patch.camera },
		ui: { ...s.ui, ...patch.ui },
		diagnostics: { ...s.diagnostics, ...patch.diagnostics }
	}));
}

/** Read the current settings synchronously (for imperative call sites). */
export function currentSettings(): AppSettings {
	return get(_settings);
}
