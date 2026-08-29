import { writable, derived, get, type Readable } from 'svelte/store';
import { Capacitor, CapacitorHttp } from '@capacitor/core';
import { DjiAoa, type PortFrameEvent, type DumlEvent } from 'capacitor-dji-aoa';
import { settings } from '$lib/stores/settings';

export type DebugRow =
	| ({ kind: 'port' } & PortFrameEvent)
	| ({ kind: 'portTx' } & PortFrameEvent)
	| ({ kind: 'duml' } & DumlEvent)
	| ({ kind: 'dumlTx' } & DumlEvent)
	| { kind: 'marker'; atMs: number; label: string };

const MAX_ROWS = 500;
const FLUSH_INTERVAL_MS = 200;

// Cloud diagnostics endpoint: filled in at CHECKPOINT C, once the Cloudflare Worker is
// deployed. While the URL is empty, upload is a no-op even with consent — nothing ever
// leaves the device.
const DIAGNOSTICS_ENDPOINT = '';
const DIAGNOSTICS_TOKEN = '';

// A batch that keeps failing is retried with exponential backoff; the pending queue
// is bounded so a long offline stretch can't grow memory without limit.
const MAX_UPLOAD_BATCH = 200;
const MAX_PENDING_ROWS = 2000;
const MAX_BACKOFF_MS = 30_000;

const INSTALL_ID_KEY = 'openfpvcast.installId';

const _rows = writable<DebugRow[]>([]);
const _enabled = writable<boolean>(false);
const _paused = writable<boolean>(false);
const _uploading = writable<boolean>(false);
const _uploadStats = writable<{ sent: number; failed: number; lastError: string | null }>({
	sent: 0,
	failed: 0,
	lastError: null
});

let pausedSnapshot = false;
_paused.subscribe((v) => (pausedSnapshot = v));

let uploadingSnapshot = false;
_uploading.subscribe((v) => (uploadingSnapshot = v));

let listenersWired = false;
const pendingUpload: DebugRow[] = [];
let flushTimer: ReturnType<typeof setInterval> | null = null;
let backoffUntil = 0;
let consecutiveFailures = 0;

async function ensureListeners() {
	if (listenersWired) return;
	if (!Capacitor.isNativePlatform()) return;
	await DjiAoa.addListener('portRaw', (e) => push({ kind: 'port', ...e }));
	await DjiAoa.addListener('duml', (e) => push({ kind: 'duml', ...e }));
	await DjiAoa.addListener('portRawTx', (e) => push({ kind: 'portTx', ...e }));
	await DjiAoa.addListener('dumlTx', (e) => push({ kind: 'dumlTx', ...e }));
	listenersWired = true;
}

function push(r: DebugRow) {
	if (pausedSnapshot) return;
	_rows.update((arr) => {
		arr.unshift(r);
		if (arr.length > MAX_ROWS) arr.length = MAX_ROWS;
		return arr;
	});
	if (uploadingSnapshot) {
		pendingUpload.push(r);
		if (pendingUpload.length > MAX_PENDING_ROWS) {
			// Drop the oldest overflow rather than growing without bound.
			pendingUpload.splice(0, pendingUpload.length - MAX_PENDING_ROWS);
		}
	}
}

/** Stable, random, non-identifying install id (a UUID) — persisted once per install. */
function installId(): string {
	try {
		if (typeof localStorage === 'undefined') return 'unknown';
		let id = localStorage.getItem(INSTALL_ID_KEY);
		if (!id) {
			id = globalThis.crypto?.randomUUID?.() ?? `x-${Date.now()}-${Math.round(Math.random() * 1e9)}`;
			localStorage.setItem(INSTALL_ID_KEY, id);
		}
		return id;
	} catch {
		return 'unknown';
	}
}

function appVersion(): string {
	return typeof __APP_VERSION__ === 'string' ? __APP_VERSION__ : '0.0.0';
}

/**
 * Reduce a capture row to exactly the fields disclosed in the consent copy: protocol
 * frames and camera-command metadata. Whitelisted field-by-field so a future addition
 * to DebugRow can never silently leak. Marker rows carry free-form user text, so they
 * are never uploaded (returns null).
 */
function sanitizeRow(r: DebugRow): Record<string, unknown> | null {
	switch (r.kind) {
		case 'port':
		case 'portTx':
			return { kind: r.kind, atMs: r.atMs, port: r.port, hex: r.hex };
		case 'duml':
		case 'dumlTx':
			return {
				kind: r.kind,
				atMs: r.atMs,
				src: r.src,
				dst: r.dst,
				cmdSet: r.cmdSet,
				cmdId: r.cmdId,
				cmdType: r.cmdType,
				seq: r.seq,
				payloadHex: r.payloadHex
			};
		case 'marker':
			return null;
	}
}

/**
 * POST a diagnostics batch to the cloud endpoint. On Android we use CapacitorHttp so the
 * request is issued natively (bypassing the WebView, which sidesteps CORS); on web we fall
 * back to fetch. The body carries an anonymous install id + app version and only the
 * disclosed row fields.
 */
async function postDiagnostics(
	rows: DebugRow[]
): Promise<{ ok: boolean; status: number; error?: string }> {
	if (!DIAGNOSTICS_ENDPOINT) return { ok: false, status: 0, error: 'no endpoint configured' };
	const body = {
		installId: installId(),
		appVersion: appVersion(),
		platform: Capacitor.getPlatform(),
		sentAt: Date.now(),
		rows: rows.map(sanitizeRow).filter((r): r is Record<string, unknown> => r !== null)
	};
	const headers: Record<string, string> = { 'Content-Type': 'application/json' };
	if (DIAGNOSTICS_TOKEN) headers['Authorization'] = `Bearer ${DIAGNOSTICS_TOKEN}`;

	if (Capacitor.isNativePlatform()) {
		try {
			const res = await CapacitorHttp.post({ url: DIAGNOSTICS_ENDPOINT, headers, data: body });
			const ok = res.status >= 200 && res.status < 300;
			return { ok, status: res.status, error: ok ? undefined : `HTTP ${res.status}` };
		} catch (e) {
			return { ok: false, status: 0, error: e instanceof Error ? e.message : String(e) };
		}
	}
	try {
		const res = await fetch(DIAGNOSTICS_ENDPOINT, {
			method: 'POST',
			headers,
			body: JSON.stringify(body)
		});
		return { ok: res.ok, status: res.status, error: res.ok ? undefined : `HTTP ${res.status}` };
	} catch (e) {
		return { ok: false, status: 0, error: e instanceof Error ? e.message : String(e) };
	}
}

async function flushUpload() {
	if (!uploadingSnapshot) {
		pendingUpload.length = 0;
		return;
	}
	if (pendingUpload.length === 0) return;
	if (Date.now() < backoffUntil) return; // still backing off from a failed attempt

	const batch = pendingUpload.slice(0, MAX_UPLOAD_BATCH);
	const r = await postDiagnostics(batch);
	if (r.ok) {
		pendingUpload.splice(0, batch.length);
		consecutiveFailures = 0;
		backoffUntil = 0;
		_uploadStats.update((s) => ({ ...s, sent: s.sent + batch.length, lastError: null }));
	} else {
		// Keep the batch queued for retry; back off exponentially so we don't hammer.
		consecutiveFailures++;
		backoffUntil = Date.now() + Math.min(MAX_BACKOFF_MS, 500 * 2 ** consecutiveFailures);
		_uploadStats.update((s) => ({ ...s, lastError: r.error ?? `HTTP ${r.status}` }));
		if (pendingUpload.length > MAX_PENDING_ROWS) {
			const dropped = pendingUpload.length - MAX_PENDING_ROWS;
			pendingUpload.splice(0, dropped);
			_uploadStats.update((s) => ({ ...s, failed: s.failed + dropped }));
		}
	}
}

function startUpload() {
	uploadingSnapshot = true;
	_uploading.set(true);
	backoffUntil = 0;
	consecutiveFailures = 0;
	// Capture has to be on for there to be rows to upload; enable it (harmless if already on).
	setDebugCapture(true).catch((e) => console.warn('[debug] capture enable failed', e));
	if (!flushTimer) flushTimer = setInterval(flushUpload, FLUSH_INTERVAL_MS);
}

function stopUpload() {
	uploadingSnapshot = false;
	_uploading.set(false);
	if (flushTimer) {
		clearInterval(flushTimer);
		flushTimer = null;
	}
	pendingUpload.length = 0;
	// Capture is left as-is: the /debug viewer may still want it running.
}

export const debugRows: Readable<DebugRow[]> = _rows;
export const debugEnabled: Readable<boolean> = _enabled;
export const debugPaused = _paused;
export const diagnosticsUploading: Readable<boolean> = _uploading;
export const diagnosticsStats: Readable<{ sent: number; failed: number; lastError: string | null }> =
	_uploadStats;
export const debugRowCount = derived(_rows, (a) => a.length);
export const DEBUG_MAX_ROWS = MAX_ROWS;
/** Whether a cloud endpoint is compiled in (see CHECKPOINT C). */
export const DIAGNOSTICS_ENDPOINT_CONFIGURED = DIAGNOSTICS_ENDPOINT !== '';

export async function setDebugCapture(enabled: boolean) {
	await ensureListeners();
	if (Capacitor.isNativePlatform()) {
		await DjiAoa.setDebug({ enabled, skipVideo: true });
	}
	_enabled.set(enabled);
}

export function clearDebugRows() {
	_rows.set([]);
}

/** Push a labeled marker into the rows — a local annotation for the viewer (never uploaded). */
export function pushMarker(label: string) {
	push({ kind: 'marker', atMs: Date.now(), label });
}

/** Read current rows synchronously (for one-shot exports). */
export function snapshotRows(): DebugRow[] {
	return get(_rows).slice();
}

// Drive the upload lifecycle from settings: consent + opt-in switch both required.
settings.subscribe((s) => {
	const shouldUpload = s.diagnostics.consented && s.diagnostics.uploadEnabled;
	if (shouldUpload === uploadingSnapshot) return;
	if (shouldUpload) startUpload();
	else stopUpload();
});

ensureListeners().catch((e) => console.warn('[debug] listener init failed', e));
