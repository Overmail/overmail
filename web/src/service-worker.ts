/// <reference types="@sveltejs/kit" />
/// <reference lib="esnext" />
/// <reference lib="webworker" />

import { base, build, files, prerendered, version } from '$service-worker';

// The worker runs outside the DOM, so the default `self` typing is wrong here.
const sw = self as unknown as ServiceWorkerGlobalScope;

// One cache per deployment: `version` changes on every build, so activating a new
// worker throws the old assets away instead of mixing hashed bundles.
const CACHE = `overmail-cache-${version}`;

/** Everything the build produces and can be served from cache forever. */
const PRECACHE = [...build, ...files, ...prerendered];
const PRECACHED = new Set(PRECACHE);

sw.addEventListener('install', (event) => {
	event.waitUntil(
		caches
			.open(CACHE)
			.then((cache) => cache.addAll(PRECACHE))
			.then(() => sw.skipWaiting())
	);
});

sw.addEventListener('activate', (event) => {
	event.waitUntil(
		caches
			.keys()
			.then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
			.then(() => sw.clients.claim())
	);
});

sw.addEventListener('fetch', (event) => {
	const { request } = event;
	if (request.method !== 'GET') return;

	const url = new URL(request.url);
	if (url.origin !== sw.location.origin) return;

	// Mail, sessions and agent state are never useful stale, and range requests
	// (attachments, audio) break when they are answered from a full cached body.
	if (url.pathname.startsWith(`${base}/api/`) || request.headers.has('range')) return;

	const pathname = url.pathname;

	if (PRECACHED.has(pathname)) {
		event.respondWith(
			caches
				.open(CACHE)
				.then((cache) => cache.match(pathname))
				.then((cached) => cached ?? fetch(request))
		);
		return;
	}

	// Pages are network-first: the cached copy only exists so an installed app
	// opens to something instead of the browser's offline error.
	event.respondWith(
		fetch(request)
			.then(async (response) => {
				if (response.ok && response.type === 'basic') {
					const cache = await caches.open(CACHE);
					cache.put(request, response.clone());
				}
				return response;
			})
			.catch(async () => {
				const cache = await caches.open(CACHE);
				const cached = await cache.match(request);
				if (cached) return cached;
				throw new Error(`Offline and ${url.pathname} is not cached`);
			})
	);
});
