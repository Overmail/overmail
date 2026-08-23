// Served verbatim by `vite dev` and by the production build alike, as a plain
// classic script. SvelteKit's src/service-worker.ts cannot do that: Vite serves
// it as an ES module in dev (`import '/@fs/…'`) and as a classic script in a
// build, and a registration keeps the script type it was created with — so the
// first load after switching modes on one origin can never evaluate the script.
// That is fatal here because the werkbank origin serves the dev server, which is
// also where the app has to be installable.
//
// The trade-off: no precache list of hashed build assets, since `$service-worker`
// is not available outside src/. That costs little — in dev that list is empty
// anyway — and a cached page shell is all an install needs.

const CACHE = 'overmail-shell-v1';

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => {
	event.waitUntil(
		(async () => {
			for (const key of await caches.keys()) {
				if (key !== CACHE) await caches.delete(key);
			}
			await self.clients.claim();
		})()
	);
});

self.addEventListener('fetch', (event) => {
	const request = event.request;

	// Only page navigations. Everything else — the API, hashed assets, Vite's dev
	// modules — goes straight to the network, untouched and never cached.
	if (request.method !== 'GET' || request.mode !== 'navigate') return;

	// Network-first, so a cached shell never shadows a newer one. The copy exists
	// only so an installed app opens to something when the network is gone.
	event.respondWith(
		(async () => {
			try {
				const response = await fetch(request);
				if (response.ok && response.type === 'basic') {
					const cache = await caches.open(CACHE);
					await cache.put(request, response.clone());
				}
				return response;
			} catch (error) {
				const cached = await (await caches.open(CACHE)).match(request);
				if (cached) return cached;
				throw error;
			}
		})()
	);
});
