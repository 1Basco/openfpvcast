import { writable, get, type Readable } from 'svelte/store';
import { Capacitor } from '@capacitor/core';
import { DjiAoa, type BroadcastQuality, type BroadcastState } from 'capacitor-dji-aoa';
import { isStreaming } from '$lib/stores/stream';
import { patchSettings, settings } from '$lib/stores/settings';

const _state = writable<BroadcastState>({ running: false, viewers: 0 });

async function init() {
	if (!Capacitor.isNativePlatform()) return;
	const cur = await DjiAoa.getBroadcastState();
	_state.set(cur);
	await DjiAoa.addListener('broadcastState', (s) => _state.set(s));
}
init().catch((e) => console.warn('[broadcast] init failed', e));

export const broadcast: Readable<BroadcastState> = _state;

let busy = false;

export async function startBroadcast(): Promise<string> {
	if (busy) throw new Error('broadcast start already in flight');
	busy = true;
	const quality = get(settings).broadcast.quality;
	try {
		const r = await DjiAoa.startBroadcast({ quality });
		_state.update((s) => ({ ...s, running: true, url: r.url, quality }));
		return r.url;
	} finally {
		busy = false;
	}
}

/**
 * Switch the quality profile. The native encoder factory and video source are fixed when the
 * broadcast starts, so a live broadcast has to be cycled — viewers reconnect on their own via
 * the viewer page's retry loop.
 */
export async function setBroadcastQuality(quality: BroadcastQuality): Promise<void> {
	patchSettings({ broadcast: { ...get(settings).broadcast, quality } });
	if (!get(_state).running) return;
	await stopBroadcast();
	await startBroadcast();
}

export async function stopBroadcast() {
	await DjiAoa.stopBroadcast();
	_state.update((s) => ({ ...s, running: false, url: undefined, viewers: 0 }));
}

// Auto-start: when video starts flowing and the setting is on, bring the broadcast up.
// Native side tears everything down on USB detach, so no auto-stop is needed here.
isStreaming.subscribe((live) => {
	if (!live) return;
	if (!get(settings).broadcast.autoStart) return;
	if (get(_state).running || busy) return;
	startBroadcast().catch((e) => console.warn('[broadcast] auto-start failed', e));
});
