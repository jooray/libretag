// LibreTag service worker. VERSION and FILES are filled in by the build (vite.config.js), so every
// release has a byte-different sw.js: the browser installs it by itself and the page reloads.
const VERSION = '__VERSION__';
const CACHE = 'lt-' + VERSION;
const FILES = __FILES__;

self.addEventListener('install', (e) => {
  e.waitUntil((async () => {
    const c = await caches.open(CACHE);
    await c.addAll(FILES.map((f) => new Request(new URL(f, self.registration.scope), { cache: 'reload' })));
    await self.skipWaiting();
  })());
});

self.addEventListener('activate', (e) => {
  e.waitUntil((async () => {
    for (const k of await caches.keys()) if (k.startsWith('lt-') && k !== CACHE) await caches.delete(k);
    await self.clients.claim();
  })());
});

self.addEventListener('message', (e) => { if (e.data === 'skipWaiting') self.skipWaiting(); });

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);
  if (req.method !== 'GET' || url.origin !== self.location.origin) return; // relays and map tiles go straight out
  if (url.pathname.endsWith('/version.json')) return; // always from the network
  if (req.mode === 'navigate') {
    // newest page when online, the cached one offline
    e.respondWith((async () => {
      try { return await fetch(req, { cache: 'no-cache' }); }
      catch { return (await caches.match(new URL('index.html', self.registration.scope))) || Response.error(); }
    })());
    return;
  }
  e.respondWith((async () => (await caches.match(req)) || fetch(req))());
});
