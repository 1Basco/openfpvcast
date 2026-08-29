import { baseLocale, locales, overwriteGetLocale, type Locale } from '$lib/paraglide/runtime';
import { currentSettings, patchSettings } from '$lib/stores/settings';

/**
 * Locale is a single source of truth: it lives in the app settings store (persisted in
 * localStorage) and is mirrored here into a Svelte rune. `overwriteGetLocale` points
 * Paraglide's message functions at that rune, so calling `m.foo()` in a component template
 * subscribes to it — switching locale re-renders the affected text live, with NO page reload
 * (a reload would tear down the native goggles connection and video window).
 */

let current = $state<Locale>(baseLocale);

overwriteGetLocale(() => current);

export const availableLocales = locales;

/** Human-readable name for each locale, shown in the language switcher. */
export const localeLabels: Record<Locale, string> = {
	en: 'English',
	'pt-BR': 'Português (BR)'
};

function isKnownLocale(value: string): value is Locale {
	return (locales as readonly string[]).includes(value);
}

/** Best-effort match of the device's preferred languages against our supported locales. */
function detectDeviceLocale(): Locale {
	if (typeof navigator === 'undefined') return baseLocale;
	const prefs = navigator.languages?.length ? navigator.languages : [navigator.language];
	for (const pref of prefs) {
		if (!pref) continue;
		if (isKnownLocale(pref)) return pref; // exact, e.g. "pt-BR"
		const base = pref.split('-')[0];
		const match = (locales as readonly string[]).find((l) => l.split('-')[0] === base);
		if (match) return match as Locale;
	}
	return baseLocale;
}

/**
 * Seed the active locale from the persisted setting, falling back to the device locale and
 * finally the base locale. Call once at app boot (before the first render).
 */
export function initLocale(): void {
	const saved = currentSettings().locale;
	current = saved && isKnownLocale(saved) ? saved : detectDeviceLocale();
}

export function getActiveLocale(): Locale {
	return current;
}

/** Switch the UI language and persist the choice. Reactive — no reload. */
export function setActiveLocale(locale: Locale): void {
	current = locale;
	patchSettings({ locale });
}
