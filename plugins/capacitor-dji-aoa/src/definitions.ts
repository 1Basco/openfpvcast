import type { PluginListenerHandle } from '@capacitor/core';

export type CameraModel = 'o4-lite' | 'o4-pro' | 'unknown';

export interface AccessoryInfo {
	manufacturer: string;
	model: string;
	version: string;
	description: string;
	hasPermission: boolean;
}

export type ConnectionState =
	| { state: 'disconnected'; reason?: string }
	| { state: 'attached'; accessory: AccessoryInfo }
	| { state: 'requesting_permission' }
	| { state: 'permission_denied' }
	| { state: 'connected'; sessionId: string; goggleModel: string; droneModel?: string; cameraModel: CameraModel };

/**
 * The only flight telemetry the app consumes. Battery voltage, link strength, altitude and
 * the rest are already composited into the video by the goggles' own OSD, so the app does not
 * parse them — see the note on `Telemetry` in TelemetryParser.kt. `armed` is a signal, not a
 * display value: auto-record-on-arm needs it.
 */
export interface Telemetry {
	timestampMs: number;
	armed: boolean;
}

export interface VideoFrameEvent {
	/** Base64 of one or more concatenated H.264 NAL units (Annex-B framed). */
	data: string;
	/** Wall-clock milliseconds when received from the goggles. */
	receivedAtMs: number;
}

export interface ConnectOptions {
	/** If multiple accessories present, filter by manufacturer (default: any). */
	manufacturer?: string;
}

export interface SendCameraCommandOptions {
	sessionId: string;
	/** DUML cmdId on cmdSet 0x02 (e.g. 0x2A = ISO set). */
	cmdId: number;
	/** Base64-encoded payload bytes. */
	payload: string;
	/** DUML target address (defaults to 0x01 camera). */
	dst?: number;
	/** Timeout ms waiting for ACK (default 1500). */
	timeoutMs?: number;
	/** DUML command set (defaults to 0x02 camera). Set this to probe arbitrary subsystems. */
	cmdSet?: number;
	/** DUML command type (defaults to 0x40 REQUEST). */
	cmdType?: number;
}

export interface SendCameraCommandResult {
	/** Base64-encoded ACK payload, or empty on timeout. */
	ack: string;
	timedOut: boolean;
}

export interface StartRecordingOptions {
	sessionId: string;
	/** Absolute path or null to auto-generate under Movies/OpenFpvCast/. */
	outputPath?: string;
	/** Mix in phone microphone (default true). */
	recordAudio?: boolean;
	/**
	 * Frame rate used to synthesize presentation timestamps — the goggles stream carries none.
	 * Pass the camera's current video-format fps for correct playback speed. Default 60.
	 */
	fps?: number;
}

export interface StartRecordingResult {
	recordingId: string;
	outputPath: string;
}

export interface StopRecordingResult {
	outputPath: string;
	durationMs: number;
	sizeBytes: number;
}

export interface RecordingStateEvent {
	recordingId: string;
	state: 'starting' | 'recording' | 'stopping' | 'stopped' | 'error';
	error?: string;
	durationMs?: number;
}

/** A recording file on disk, as returned by `listRecordings`. */
export interface RecordingFile {
	/** `content://` (Android Q+) or `file://` URI, usable for playback/sharing. */
	uri: string;
	/** Human-readable path or the same URI on scoped storage. */
	path: string;
	name: string;
	sizeBytes: number;
	/** Duration in ms (0 if unknown, e.g. legacy File storage). */
	durationMs: number;
	createdAtMs: number;
	/** Optional thumbnail URI (not populated yet). */
	thumbnailUri?: string;
}

/** State of the LAN WebRTC broadcast (also emitted as the `broadcastState` event). */
export interface BroadcastState {
	running: boolean;
	/** Viewer page URL (`http://<phone-ip>:<port>/`), present while running. */
	url?: string;
	/** Connected WebRTC viewers (capped at ~3 — each costs a hardware encoder). */
	viewers: number;
}

export interface StartBroadcastOptions {
	/** HTTP/WS port for the viewer page + signaling (default 8888). */
	port?: number;
}

/**
 * Emitted on transitions of the native video stream: `active` flips to true when H.264
 * bytes are flowing from the goggles and back to false after ~2 s of silence. Drives the
 * app's disconnected/streaming layout switch without mirroring frames into JS.
 */
export interface PreviewStateEvent {
	active: boolean;
	/** Coded picture size from the stream SPS (absent until the first SPS is parsed). */
	width?: number;
	height?: number;
}

export interface SetDebugOptions {
	/** Enable raw frame mirroring to JS. Off by default — costly. */
	enabled: boolean;
	/** Skip video frames (LogicLink port 0x574A) when mirroring. Default true. */
	skipVideo?: boolean;
}

/** A raw LogicLink frame (non-video) surfaced when debug is on. */
export interface PortFrameEvent {
	/** LogicLink port number. */
	port: number;
	/** Hex string of the payload. */
	hex: string;
	/** Wall-clock ms. */
	atMs: number;
}

/** A decoded DUML packet surfaced when debug is on. */
export interface DumlEvent {
	src: number;
	dst: number;
	seq: number;
	cmdType: number;
	cmdSet: number;
	cmdId: number;
	payloadHex: string;
	atMs: number;
}

/**
 * Position the native SurfaceView used for low-latency H.264 preview. Coordinates and
 * dimensions are in **physical pixels** (multiply CSS pixels by `window.devicePixelRatio`).
 * The view is added as a sibling on top of the WebView; outside the rect, the WebView
 * shows through, so the SurfaceView punches a video window into the layout.
 */
export interface ShowPreviewOptions {
	x: number;
	y: number;
	width: number;
	height: number;
}

export interface DjiAoaPlugin {
	listAccessories(): Promise<{ accessories: AccessoryInfo[] }>;
	connect(options?: ConnectOptions): Promise<{ sessionId: string }>;
	disconnect(options: { sessionId: string }): Promise<void>;
	getConnectionState(): Promise<ConnectionState>;
	sendCameraCommand(options: SendCameraCommandOptions): Promise<SendCameraCommandResult>;
	startRecording(options: StartRecordingOptions): Promise<StartRecordingResult>;
	stopRecording(options: { recordingId: string }): Promise<StopRecordingResult>;
	listRecordings(): Promise<{ recordings: RecordingFile[] }>;
	deleteRecording(options: { uri: string }): Promise<void>;
	/** Ask for the RECORD_AUDIO runtime permission (no-op if already granted). */
	requestAudioPermission(): Promise<{ granted: boolean }>;
	startBroadcast(options?: StartBroadcastOptions): Promise<{ url: string }>;
	stopBroadcast(): Promise<void>;
	getBroadcastState(): Promise<BroadcastState>;
	setDebug(options: SetDebugOptions): Promise<void>;
	showPreview(options: ShowPreviewOptions): Promise<void>;
	hidePreview(): Promise<void>;

	addListener(
		eventName: 'connectionState',
		listenerFunc: (state: ConnectionState) => void
	): Promise<PluginListenerHandle>;
	addListener(
		eventName: 'telemetry',
		listenerFunc: (telemetry: Telemetry) => void
	): Promise<PluginListenerHandle>;
	addListener(
		eventName: 'video',
		listenerFunc: (frame: VideoFrameEvent) => void
	): Promise<PluginListenerHandle>;
	addListener(
		eventName: 'recordingState',
		listenerFunc: (event: RecordingStateEvent) => void
	): Promise<PluginListenerHandle>;
	addListener(
		eventName: 'previewState',
		listenerFunc: (event: PreviewStateEvent) => void
	): Promise<PluginListenerHandle>;
	addListener(
		eventName: 'broadcastState',
		listenerFunc: (event: BroadcastState) => void
	): Promise<PluginListenerHandle>;
	addListener(
		eventName: 'portRaw',
		listenerFunc: (event: PortFrameEvent) => void
	): Promise<PluginListenerHandle>;
	addListener(
		eventName: 'portRawTx',
		listenerFunc: (event: PortFrameEvent) => void
	): Promise<PluginListenerHandle>;
	addListener(
		eventName: 'duml',
		listenerFunc: (event: DumlEvent) => void
	): Promise<PluginListenerHandle>;
	addListener(
		eventName: 'dumlTx',
		listenerFunc: (event: DumlEvent) => void
	): Promise<PluginListenerHandle>;

	removeAllListeners(): Promise<void>;
}
