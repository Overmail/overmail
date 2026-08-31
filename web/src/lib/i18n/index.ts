import { init, locale, register } from 'svelte-i18n';
import en from './locales/en.json';
import de from './locales/de.json';

export const locales = ['en', 'de'] as const;
export type Locale = (typeof locales)[number];

/** English is the fallback: every key exists here, other catalogs may lag behind. */
export const defaultLocale: Locale = 'en';

/** Endonyms — a language is named in its own language, in every ui language. */
export const localeNames: Record<Locale, string> = {
	en: 'English',
	de: 'Deutsch'
};

export const LOCALE_COOKIE = 'overmail_locale';

// Registered synchronously rather than through a dynamic import, so a locale is complete the
// moment init() runs. With two small catalogs there is nothing to code-split, and it saves the
// server render from having to await anything before it can format a string.
register('en', () => Promise.resolve(en));
register('de', () => Promise.resolve(de));

/**
 * First supported language in an `Accept-Language` header or a bare locale from the cookie.
 * Browsers send the header in preference order, so q-values add nothing here.
 */
export function pickLocale(value: string | null | undefined): Locale {
	for (const tag of (value ?? '').split(',')) {
		const language = tag.split(';')[0].trim().slice(0, 2).toLowerCase();
		if ((locales as readonly string[]).includes(language)) return language as Locale;
	}
	return defaultLocale;
}

let initialized = false;

/**
 * Runs in the layout load, so the locale is decided before anything renders — on the server too,
 * where `getLocaleFromNavigator()` has nothing to look at.
 */
export function setupI18n(initial: Locale): Promise<void> {
	// Both `init` and `locale.set` resolve once the catalog is in the dictionary; awaiting them is
	// what keeps `$locale` from still being the previous one when the first render reads it.
	if (initialized) return Promise.resolve(locale.set(initial));

	initialized = true;
	return Promise.resolve(init({ fallbackLocale: defaultLocale, initialLocale: initial }));
}

/** Switches the ui and remembers the choice, so the next server render picks the same one. */
export function setLocale(next: Locale) {
	locale.set(next);
	document.documentElement.lang = next;
	document.cookie = `${LOCALE_COOKIE}=${next}; path=/; max-age=${60 * 60 * 24 * 365}; samesite=lax`;
}
