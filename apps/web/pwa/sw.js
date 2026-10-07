// Supermux service worker. Committed as plain JS (no build step) and staged to the
// root of the served tree by `apps/web/build.gradle.kts` — NOT via
// `src/wasmJsMain/resources/`, whose files webpack copies into the dist root where
// the staging pass would content-hash them into `assets/sw-<hash>.js`.
//
// This worker deliberately has NO fetch handler. A fetch handler (the old
// workbox precache) puts service-worker startup on the critical path of every
// navigation; on cold-started Android Chrome that startup is a lottery
// (0.1s–14s measured, occasionally wedging forever) and was the root cause of
// minutes-long blank screens on phones whose OS kills Chrome between uses.
// Without a fetch handler the browser skips the worker for all requests:
// navigations go straight to network, assets ride the immutable HTTP cache +
// CDN edge. The worker exists only for Web Push.

self.skipWaiting()
self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      await self.clients.claim()
      // Drop the old precache storage (~3MB/device) left behind by the
      // previous workbox worker — nothing reads it anymore.
      for (const key of await caches.keys()) {
        if (key.includes("precache")) await caches.delete(key)
      }
    })(),
  )
})

function isSessionPath(url, sessionId) {
  try {
    const path = new URL(url).pathname
    return path === `/s/${sessionId}`
  } catch {
    return false
  }
}

// iOS Safari REVOKES the push subscription when a delivered push shows no notification, so
// every `push` event must end in a `showNotification` — including the ones we cannot read.
// "Supermux" is the app's own name as `index.html`'s <title> spells it; the manifest keeps the
// lowercase form for install-label parity with the Vue app, and these two are allowed to differ.
function fallbackNotification() {
  return self.registration.showNotification("Supermux", { body: "New activity" })
}

self.addEventListener("push", (event) => {
  if (!event.data) {
    event.waitUntil(fallbackNotification())
    return
  }
  let data
  try {
    data = event.data.json()
  } catch {
    event.waitUntil(fallbackNotification())
    return
  }

  event.waitUntil(
    // A payload with no `session` would title the notification "undefined".
    self.registration.showNotification(data.session ?? "Supermux", {
      body: data.text ?? "New message",
      icon: "/icons/icon-192.png",
      badge: "/icons/icon-192.png",
      // One notification per chat: a newer message replaces the older (kept in lockstep with
      // `lib/notifications.ts` `notificationTag`, which clears this set when the chat opens).
      tag: `cmux:${data.sessionId ?? data.session}`,
      // Re-alert (sound/vibrate) when a replacement lands, so a new message in an already-shown
      // chat isn't a silent in-place swap.
      renotify: true,
      data: { session: data.session, sessionId: data.sessionId, ts: data.ts },
      requireInteraction: false,
    }),
  )
})

self.addEventListener("notificationclick", (event) => {
  event.notification.close()
  const data = event.notification.data ?? {}
  const sessionId = data.sessionId
  if (!sessionId) {
    event.waitUntil(self.clients.openWindow("/"))
    return
  }
  const targetUrl = `/s/${sessionId}`

  const promise = (async () => {
    const clients = await self.clients.matchAll({ type: "window", includeUncontrolled: true })
    const exact = clients.find((c) => isSessionPath(c.url, sessionId))
    if (exact) {
      await exact.focus()
      return
    }
    const anyTab = clients[0]
    if (anyTab) {
      await anyTab.focus()
      anyTab.postMessage({ type: "navigate", to: targetUrl })
    } else {
      await self.clients.openWindow(targetUrl)
    }
  })()

  event.waitUntil(promise)
})
