import { readFileSync } from 'node:fs';
import { paraglideVitePlugin } from '@inlang/paraglide-js';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

/** The shipped app version = the Android versionName (falls back to 0.0.0 for web-only builds). */
function appVersion(): string {
	try {
		const gradle = readFileSync(new URL('../../android/app/build.gradle', import.meta.url), 'utf8');
		return gradle.match(/versionName\s+"([^"]+)"/)?.[1] ?? '0.0.0';
	} catch {
		return '0.0.0';
	}
}

export default defineConfig({
	define: {
		__APP_VERSION__: JSON.stringify(appVersion())
	},
	plugins: [
		tailwindcss(),
		paraglideVitePlugin({
			project: './project.inlang',
			outdir: './src/lib/paraglide',
			// Locale is owned by the app settings store (see src/lib/i18n.svelte.ts), which
			// overrides getLocale(). We keep Paraglide's own resolution deterministic here.
			strategy: ['baseLocale']
		}),
		sveltekit()
	],
	server: {
		host: '0.0.0.0',
		port: 5173
	}
});
