import { WebPlugin } from '@capacitor/core';

import type {
	DjiAoaPlugin,
	ConnectionState,
	SendCameraCommandOptions,
	SendCameraCommandResult,
	StartRecordingOptions,
	StartRecordingResult,
	StopRecordingResult,
	RecordingFile,
	AccessoryInfo,
	SetDebugOptions,
	BroadcastState
} from './definitions';

/**
 * Web fallback. Used only during `pnpm dev` in a browser. All operations throw —
 * the real implementation lives on Android.
 */
export class DjiAoaWeb extends WebPlugin implements DjiAoaPlugin {
	async listAccessories(): Promise<{ accessories: AccessoryInfo[] }> {
		return { accessories: [] };
	}

	async connect(): Promise<{ sessionId: string }> {
		throw this.unavailable('DJI AOA is only available on Android.');
	}

	async disconnect(): Promise<void> {
		// no-op
	}

	async getConnectionState(): Promise<ConnectionState> {
		return { state: 'disconnected', reason: 'web-fallback' };
	}

	async sendCameraCommand(_options: SendCameraCommandOptions): Promise<SendCameraCommandResult> {
		throw this.unavailable('DJI AOA is only available on Android.');
	}

	async startRecording(_options: StartRecordingOptions): Promise<StartRecordingResult> {
		throw this.unavailable('DJI AOA is only available on Android.');
	}

	async stopRecording(_options: { recordingId: string }): Promise<StopRecordingResult> {
		throw this.unavailable('DJI AOA is only available on Android.');
	}

	async listRecordings(): Promise<{ recordings: RecordingFile[] }> {
		return { recordings: [] };
	}

	async deleteRecording(_options: { uri: string }): Promise<void> {
		// no-op on web
	}

	async requestAudioPermission(): Promise<{ granted: boolean }> {
		return { granted: false };
	}

	async startBroadcast(): Promise<{ url: string }> {
		throw this.unavailable('DJI AOA is only available on Android.');
	}

	async stopBroadcast(): Promise<void> {
		// no-op
	}

	async getBroadcastState(): Promise<BroadcastState> {
		return { running: false, viewers: 0 };
	}

	async setDebug(_options: SetDebugOptions): Promise<void> {
		// no-op on web
	}

	async showPreview(): Promise<void> {
		// no-op on web — preview only renders inside the Android native SurfaceView
	}

	async hidePreview(): Promise<void> {
		// no-op
	}
}
