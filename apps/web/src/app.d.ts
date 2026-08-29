// See https://svelte.dev/docs/kit/types#app.d.ts
// for information about these interfaces
declare global {
	namespace App {
	}

	/** Injected at build time by Vite (see vite.config.ts) — the Android versionName. */
	const __APP_VERSION__: string;
}

export {};
