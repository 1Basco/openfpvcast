import { readFileSync } from 'node:fs';
import { paraglideVitePlugin } from '@inlang/paraglide-js';
import { sveltekit } from '@sveltejs/kit/vite';
import tailwindcss from '@tailwindcss/vite';
import { defineConfig } from 'vite';

/**
 * The shipped app version. CI sets APP_VERSION from the release tag (the same value it passes to
 * Gradle as `-PversionName`); local builds fall back to the Gradle default, then to 0.0.0.
 */
function appVersion(): string {
	if (process.env.APP_VERSION) return process.env.APP_VERSION;
	try {
		const gradle = readFileSync(new URL('../../android/app/build.gradle', import.meta.url), 'utf8');
		const line = gradle.match(/^\s*versionName.*$/m)?.[0] ?? '';
		// Matches both `versionName "1.0"` and the `... ?: "0.0.0-dev"` fallback form.
		return line.match(/["']([^"']+)["']\s*$/)?.[1] ?? '0.0.0';
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
