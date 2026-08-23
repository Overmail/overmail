import { base } from '$app/paths';

/**
 * The worker lives in static/ and is therefore a classic script in dev and in a
 * production build alike — see the comment at the top of static/service-worker.js
 * for why that matters. SvelteKit's own registration is off
 * (`serviceWorker.register: false` in vite.config.ts) because it only ever calls
 * `update()` on an existing registration, which cannot recover from a stale one.
 */
const SCRIPT = `${base}/service-worker.js`;

export async function registerServiceWorker() {
	if (!('serviceWorker' in navigator)) return;

	try {
		await navigator.serviceWorker.register(SCRIPT);
	} catch {
		// Most likely a registration left over from an earlier setup, whose script
		// type or scope no longer fits. Unregistering drops that state with it, so
		// the retry starts clean.
		for (const registration of await navigator.serviceWorker.getRegistrations()) {
			await registration.unregister();
		}

		try {
			await navigator.serviceWorker.register(SCRIPT);
		} catch (error) {
			// Nothing left to try. The app still works without a worker, it just is
			// not installable, so this stays a warning instead of breaking the page.
			console.warn('Could not register the service worker', error);
		}
	}
}
