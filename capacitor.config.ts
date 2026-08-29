import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
	// Must match the Gradle `applicationId`. Technical identity — never rename: an installed
	// app that changes it becomes a different app and loses its data.
	appId: 'io.basco.openfpvcast',
	appName: 'Open FPV Cast',
	webDir: 'apps/web/build',
	bundledWebRuntime: false,
	android: {
		captureInput: true,
		// WebView remote debugging is a debug-only affordance — never leave it on in a
		// release build. Set NODE_ENV=production for release syncs/builds to disable it.
		// (Diagnostics upload now goes over HTTPS via CapacitorHttp, so `allowMixedContent`
		// is no longer needed — the WebView never fetches a plain-http target.)
		webContentsDebuggingEnabled: process.env.NODE_ENV !== 'production'
	},
	server: {
		androidScheme: 'https'
	}
};

export default config;
