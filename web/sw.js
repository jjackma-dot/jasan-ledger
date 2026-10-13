/* 오프라인에서도 즉시 열리도록 앱 껍데기를 캐시한다.
   데이터는 캐시하지 않는다 — 항상 기기 저장소와 서버에서 읽는다. */
const CACHE = 'jasan-shell-v3';
const SHELL = ['./', './index.html', './manifest.webmanifest', './icon-192.png', './icon-512.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(caches.keys()
    .then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', e => {
  const u = new URL(e.request.url);
  if (e.request.method !== 'GET') return;
  if (u.origin !== location.origin) return;          // 시세·서버 요청은 건드리지 않는다
  // 새 버전이 있으면 받아 쓰고, 실패하면 캐시로 연다
  e.respondWith(
    fetch(e.request)
      .then(r => { const c = r.clone(); caches.open(CACHE).then(x => x.put(e.request, c)); return r; })
      .catch(() => caches.match(e.request).then(r => r || caches.match('./index.html')))
  );
});

/* 폰 알림(Web Push, 2026-10-13) — 자동매매 실행기(GitHub Actions)가 오류·빗장에 걸린 판을 민다. 웹앱이 닫혀 있어도 뜬다.
   글은 실행기가 정한다(금액 없음). 누르면 무매/VR › 자동 화면으로 연다(열린 창이 있으면 그 창으로) */
self.addEventListener('push', e => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch (x) { d = { body: e.data ? e.data.text() : '' }; }
  e.waitUntil(self.registration.showNotification(d.title || '자산 원장', {
    body: d.body || '', tag: d.tag || 'api', renotify: true,
    icon: 'icon-192.png', badge: 'icon-192.png', data: { url: d.url || './#api' }
  }));
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  const url = new URL((e.notification.data && e.notification.data.url) || './#api', self.registration.scope).href;
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(cs => {
    for (const c of cs) if (c.url.startsWith(self.registration.scope) && 'focus' in c) { c.postMessage({ api: 'open' }); return c.focus(); }
    return self.clients.openWindow(url);
  }));
});
/* 브라우저가 구독을 바꾸면(만료·갱신) 같은 키로 다시 구독해 둔다. 서버 목록(pushSub)에는 다음에 웹앱을 열 때 웹이 올린다(pushRefresh) —
   서비스 워커에는 로그인 토큰이 없다 */
self.addEventListener('pushsubscriptionchange', e => {
  const opt = e.oldSubscription && e.oldSubscription.options;
  if (opt && opt.applicationServerKey) e.waitUntil(self.registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: opt.applicationServerKey }));
});
