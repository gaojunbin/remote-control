/**
 * remote-control service worker.
 * Network-first for navigations, cache-first for hashed build assets, and a
 * generic notification for every push (payloads never carry message content).
 */
const CACHE = 'rc-shell-v1';
const SHELL = ['/', '/manifest.webmanifest', '/icon.svg', '/icon-192.png'];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

function isBypassed(url) {
  return (
    url.pathname.startsWith('/api') ||
    url.pathname.startsWith('/ws') ||
    url.pathname.startsWith('/install.sh') ||
    url.pathname.startsWith('/dist/')
  );
}

// A 502 from the reverse proxy must never become the offline app shell.
function cacheable(response) {
  return Boolean(response) && response.ok && response.type === 'basic';
}

function putInCache(key, response) {
  const copy = response.clone();
  caches
    .open(CACHE)
    .then((cache) => cache.put(key, copy))
    .catch(() => {});
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin || isBypassed(url)) return;

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (cacheable(response)) putInCache('/', response);
          return response;
        })
        .catch(() => caches.match('/').then((cached) => cached ?? Response.error())),
    );
    return;
  }

  if (url.pathname.startsWith('/assets/')) {
    event.respondWith(
      caches.match(request).then(
        (cached) =>
          cached ??
          fetch(request).then((response) => {
            if (cacheable(response)) putInCache(request, response);
            return response;
          }),
      ),
    );
  }
});

const TITLES = {
  needs_approval: 'approval needed',
  needs_input: 'a question is waiting',
  turn_completed: 'finished a turn',
  error: 'hit an error',
};

self.addEventListener('push', (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch (err) {
    payload = {};
  }
  const rc = payload.rc || {};
  const device = rc.device_name || 'A device';
  const body = TITLES[rc.kind] || 'needs your attention';
  event.waitUntil(
    self.registration.showNotification('Remote Control', {
      body: `${device}: ${body}`,
      icon: '/icon-192.png',
      badge: '/icon-192.png',
      tag: rc.session_id ? `rc-${rc.session_id}` : 'rc',
      renotify: true,
      data: { rc },
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const rc = (event.notification.data && event.notification.data.rc) || {};
  const path =
    rc.device_id && rc.session_id ? `/sessions/${rc.device_id}/${rc.session_id}` : '/sessions';
  const target = new URL(path, self.location.origin).href;
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        if (client.url.startsWith(self.location.origin) && 'focus' in client) {
          client.postMessage({ type: 'rc.navigate', path });
          return client.focus();
        }
      }
      return self.clients.openWindow(target);
    }),
  );
});
