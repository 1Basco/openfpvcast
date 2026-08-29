import { get, derived } from 'svelte/store';
import { armed } from '$lib/stores/telemetry';
import { connection } from '$lib/stores/connection';
import { settings } from '$lib/stores/settings';
import { recording, isRecording, startRecording, stopRecording } from '$lib/stores/recording';

/**
 * Auto-record controller: watches the `armed` telemetry bit and drives recording.
 *
 * - arm (false→true): start a recording tagged `source: 'auto'` (unless one is already
 *   running, or the pilot manually stopped while still armed — that suppresses restart
 *   until the next disarm→arm cycle).
 * - disarm (true→false): schedule a stop after `autoRecord.stopDelayMs`; re-arming inside
 *   the window cancels it. Only recordings that auto started are auto stopped.
 *
 * Imported once from the root layout so it is live app-wide regardless of route.
 */

let inFlight = false;
let manualOverride = false;
let stopTimer: ReturnType<typeof setTimeout> | null = null;
let lastArmed = false;

function cancelStopTimer() {
	if (stopTimer) {
		clearTimeout(stopTimer);
		stopTimer = null;
	}
}

async function onArm() {
	cancelStopTimer();
	if (manualOverride) return;
	const cfg = get(settings);
	if (!cfg.autoRecord.enabled) return;
	if (get(isRecording) || inFlight) return;
	const conn = get(connection);
	if (conn.state !== 'connected') return;
	inFlight = true;
	try {
		await startRecording(conn.sessionId, { source: 'auto' });
	} catch (e) {
		console.warn('[autorecord] start failed', e);
	} finally {
		inFlight = false;
	}
}

function onDisarm() {
	manualOverride = false; // a new arm cycle gets a fresh chance
	const cfg = get(settings);
	if (!cfg.autoRecord.enabled) return;
	const rec = get(recording);
	if (rec.source !== 'auto' || !get(isRecording)) return;
	cancelStopTimer();
	stopTimer = setTimeout(async () => {
		stopTimer = null;
		// Re-check at fire time: the pilot may have re-armed or stopped manually meanwhile.
		if (get(armed)) return;
		const cur = get(recording);
		if (cur.source !== 'auto' || !get(isRecording)) return;
		try {
			await stopRecording();
		} catch (e) {
			console.warn('[autorecord] stop failed', e);
		}
	}, cfg.autoRecord.stopDelayMs);
}

armed.subscribe((isArmed) => {
	if (isArmed === lastArmed) return;
	lastArmed = isArmed;
	if (isArmed) void onArm();
	else onDisarm();
});

// A stop that lands while still armed can only be a manual one (the auto-stop timer
// re-checks `armed` before firing) — suppress auto-restart until the next arm cycle.
let lastRecActive = false;
recording.subscribe((r) => {
	const active = r.state === 'recording' || r.state === 'starting';
	if (lastRecActive && !active && lastArmed) {
		manualOverride = true;
	}
	lastRecActive = active;
});

/** Exposed for UI badges: true while an auto-started recording is active. */
export const autoRecordingActive = derived(
	[recording, isRecording],
	([r, rec]) => rec && r.source === 'auto'
);
