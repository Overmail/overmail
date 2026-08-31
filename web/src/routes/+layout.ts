import type { LayoutLoad } from './$types';
import { waitLocale } from 'svelte-i18n';
import { setupI18n } from '$lib/i18n';

// Server and client both go through here, so the locale is set before the first render on either
// side and hydration sees the same strings the server produced.
export const load: LayoutLoad = async ({ data }) => {
	await setupI18n(data.locale);
	await waitLocale();
	return data;
};
