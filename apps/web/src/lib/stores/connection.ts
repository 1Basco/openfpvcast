import { writable, derived, type Readable } from 'svelte/store';
import { Capacitor } from '@capacitor/core';
import { DjiAoa, type ConnectionState } from 'capacitor-dji-aoa';
import type { CameraModel } from '$lib/aoa';

const initial: ConnectionState = { state: 'disconnected' };
const _state = writable<ConnectionState>(initial);

let unsubscribed = false;
async function init() {
	if (!Capacitor.isNativePlatform()) return;
	const current = await DjiAoa.getConnectionState();
	_state.set(current);
	await DjiAoa.addListener('connectionState', (s) => {
		if (!unsubscribed) _state.set(s);
	});
}
init().catch((e) => console.warn('[djiaoa] init failed', e));

export const connection: Readable<ConnectionState> = _state;

export const isConnected = derived(_state, (s) => s.state === 'connected');
export const goggleDescription = derived(_state, (s) => {
	if (s.state === 'attached') return s.accessory.description;
	if (s.state === 'connected') return s.goggleModel;
	return null;
});

export const cameraModel = derived<typeof _state, CameraModel | null>(_state, (s) => {
	if (s.state === 'connected') {
		const m = s.cameraModel;
		if (m === 'o4-lite' || m === 'o4-pro') return m;
	}
	return null;
});

export async function connect() {
	await DjiAoa.connect();
}

export async function disconnect() {
	if (!Capacitor.isNativePlatform()) return;
	const s = await DjiAoa.getConnectionState();
	if (s.state === 'connected') await DjiAoa.disconnect({ sessionId: s.sessionId });
}
