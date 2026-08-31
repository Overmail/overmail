import type { LayoutServerLoad } from './$types';
import { LOCALE_COOKIE, pickLocale } from '$lib/i18n';

// The cookie wins over the browser's list, because it is the choice the user made in the ui.
export const load: LayoutServerLoad = ({ cookies, request }) => ({
	locale: pickLocale(cookies.get(LOCALE_COOKIE) ?? request.headers.get('accept-language'))
});
