import type { LayoutServerLoad } from './$types';
import { LOCALE_COOKIE, pickLocale } from '$lib/i18n';
import { SIDEBAR_COOKIE_NAME } from '$lib/components/ui/sidebar/constants';

// The cookie wins over the browser's list, because it is the choice the user made in the ui.
export const load: LayoutServerLoad = ({ cookies, request }) => ({
	locale: pickLocale(cookies.get(LOCALE_COOKIE) ?? request.headers.get('accept-language')),
	// Read here, so the page arrives with the sidebar the way it was left instead of folding it
	// a moment after it rendered. Open unless it was closed: that is what a first visit gets.
	sidebarOpen: cookies.get(SIDEBAR_COOKIE_NAME) !== 'false'
});
