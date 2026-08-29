import { writable, derived, type Readable } from 'svelte/store';
import { Capacitor } from '@capacitor/core';
import { DjiAoa, type Telemetry } from 'capacitor-dji-aoa';

const _telemetry = writable<Telemetry | null>(null);

async function init() {
	if (!Capacitor.isNativePlatform()) return;
	await DjiAoa.addListener('telemetry', (t) => _telemetry.set(t));
}
init().catch((e) => console.warn('[telemetry] init failed', e));

export const telemetry: Readable<Telemetry | null> = _telemetry;

export const armed = derived(_telemetry, (t) => t?.armed === true);
