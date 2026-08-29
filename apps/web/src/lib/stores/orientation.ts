import { derived } from 'svelte/store';
import { Capacitor } from '@capacitor/core';
import { ScreenOrientation } from '@capacitor/screen-orientation';
import { isStreaming } from '$lib/stores/stream';
import { settings } from '$lib/stores/settings';

/**
 * Landscape lock for the live view. `ScreenOrientation.lock()` sets the Activity's
 * requested orientation, which overrides the system auto-rotate toggle — the stream
 * goes horizontal even when the phone's rotation is locked to portrait. 'landscape'
 * is sensor-based, so flipping the phone 180° still reorients between the two
 * landscape directions. Unlocking returns to the manifest default (`fullUser`).
 */

const wantLandscape = derived(
	[isStreaming, settings],
	([$streaming, $settings]) => $streaming && $settings.ui.forceLandscape
);

let locked = false;

if (Capacitor.isNativePlatform()) {
	wantLandscape.subscribe((want) => {
		if (want === locked) return;
		locked = want;
		const op = want
			? ScreenOrientation.lock({ orientation: 'landscape' })
			: ScreenOrientation.unlock();
		op.catch((e) => console.warn('[orientation] change failed', e));
	});
}
