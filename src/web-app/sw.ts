/// <reference lib="webworker" />
import { precacheAndRoute } from "workbox-precaching"

declare const self: ServiceWorkerGlobalScope & {
  __WB_MANIFEST: Array<string | { url: string; revision: string | null }>
}

self.skipWaiting()
self.addEventListener("activate", (event) => {
  event.waitUntil(self.clients.claim())
})

precacheAndRoute(self.__WB_MANIFEST)

interface PushPayload {
  session: string
  sessionId?: string
  text?: string
  kind?: string
  ts: string
}

function isSessionPath(url: string, sessionId: string): boolean {
  try {
    const path = new URL(url).pathname
    return path === `/s/${sessionId}`
  } catch {
    return false
  }
}

self.addEventListener("push", (event) => {
  if (!event.data) return
  let data: PushPayload
  try {
    data = event.data.json() as PushPayload
  } catch {
    return
  }

  event.waitUntil(
    self.registration.showNotification(data.session, {
      body: data.text ?? "New message",
      icon: "/icons/icon-192.png",
      badge: "/icons/icon-192.png",
      tag: `cmux:${data.sessionId ?? data.session}`,
      data: { session: data.session, sessionId: data.sessionId, ts: data.ts },
      requireInteraction: false,
    }),
  )
})

self.addEventListener("notificationclick", (event) => {
  event.notification.close()
  const data = (event.notification.data ?? {}) as { session?: string; sessionId?: string }
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
