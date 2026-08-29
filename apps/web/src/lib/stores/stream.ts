import { writable, derived, type Readable } from 'svelte/store';
import { Capacitor } from '@capacitor/core';
import { DjiAoa } from 'capacitor-dji-aoa';
import { isConnected } from '$lib/stores/connection';

/**
 * "Is video actually flowing?" — fed by the plugin's `previewState` event (native emits
 * on active/inactive transitions with a 2 s stall watchdog on its side). Combined with
 * the connection state this drives the app's two layouts: portrait settings when idle,
 * streaming layout with the side tab when live.
 */

const _videoActive = writable(false);
const _videoSize = writable<{ width: number; height: number } | null>(null);

async function init() {
	if (!Capacitor.isNativePlatform()) return;
	await DjiAoa.addListener('previewState', (e) => {
		_videoActive.set(e.active);
		if (e.width && e.height) _videoSize.set({ width: e.width, height: e.height });
	});
}
init().catch((e) => console.warn('[stream] init failed', e));

// A dropped USB session must never leave the UI stuck in streaming mode, even if the
// native transition event is missed — gate on the connection state as well.
isConnected.subscribe((c) => {
	if (!c) _videoActive.set(false);
});

export const videoActive: Readable<boolean> = _videoActive;
export const videoSize: Readable<{ width: number; height: number } | null> = _videoSize;

export const isStreaming = derived(
	[isConnected, _videoActive],
	([$connected, $active]) => $connected && $active
);
