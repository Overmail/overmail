import type { Handle } from '@sveltejs/kit';
import { LOCALE_COOKIE, pickLocale } from '$lib/i18n';

/** Fills `<html lang>` — the layout load cannot reach outside the app's own markup. */
export const handle: Handle = ({ event, resolve }) => {
	const locale = pickLocale(
		event.cookies.get(LOCALE_COOKIE) ?? event.request.headers.get('accept-language')
	);

	return resolve(event, {
		transformPageChunk: ({ html }) => html.replace('%lang%', locale)
	});
};
