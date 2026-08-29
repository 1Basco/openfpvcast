/**
 * Thin re-export of the Capacitor plugin and a few helpers tailored for this app.
 */
export { DjiAoa } from 'capacitor-dji-aoa';
export type {
	ConnectionState,
	AccessoryInfo,
	Telemetry,
	VideoFrameEvent,
	RecordingStateEvent,
	SendCameraCommandOptions,
	SendCameraCommandResult,
	CameraModel
} from 'capacitor-dji-aoa';

export * from './camera';
