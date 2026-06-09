// src/web-app/src/composables/useNotifications.ts
import { ref, onMounted, type Ref } from "vue"

export type NotificationStatus =
  | "loading"
  | "unsupported"
  | "denied"
  | "not-subscribed"
  | "subscribed"

export interface UseNotifications {
  status: Ref<NotificationStatus>
  bannerDismissed: Ref<boolean>
  enable: () => Promise<void>
  disable: () => Promise<void>
  dismissBanner: () => void
}

const BANNER_DISMISSED_KEY = "cmux:push:banner-dismissed"

function urlBase64ToUint8Array(b64: string): Uint8Array<ArrayBuffer> {
  const padding = "=".repeat((4 - (b64.length % 4)) % 4)
  const base64 = (b64 + padding).replace(/-/g, "+").replace(/_/g, "/")
  const raw = atob(base64)
  const buffer = new ArrayBuffer(raw.length)
  const out = new Uint8Array(buffer)
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

export function useNotifications(): UseNotifications {
  const status = ref<NotificationStatus>("loading")
  const bannerDismissed = ref<boolean>(localStorage.getItem(BANNER_DISMISSED_KEY) === "1")

  async function probe(): Promise<void> {
    if (typeof Notification === "undefined" || !("serviceWorker" in navigator) || !("PushManager" in window)) {
      status.value = "unsupported"
      return
    }
    if (Notification.permission === "denied") {
      status.value = "denied"
      return
    }
    const reg = await navigator.serviceWorker.ready
    const sub = await reg.pushManager.getSubscription()
    if (!sub) {
      status.value = "not-subscribed"
      return
    }
    // Sub exists browser-side — reconcile with the broker.
    // Re-POST is idempotent (ON CONFLICT REPLACE). If the broker rejects auth,
    // unsubscribe locally so the user sees the banner again.
    const subJson = sub.toJSON() as any
    try {
      const res = await fetch("/push/subscribe", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ endpoint: subJson.endpoint, keys: subJson.keys }),
      })
      if (res.ok) {
        status.value = "subscribed"
      } else if (res.status === 401 || res.status === 404) {
        await sub.unsubscribe().catch(() => {})
        status.value = "not-subscribed"
      } else {
        // transient — keep subscribed; broker will catch up on next push
        status.value = "subscribed"
      }
    } catch {
      // network blip — assume subscribed; next push call will reconcile
      status.value = "subscribed"
    }
  }

  onMounted(() => { void probe() })

  async function enable(): Promise<void> {
    if (status.value === "unsupported") return
    const perm = await Notification.requestPermission()
    if (perm !== "granted") {
      status.value = perm === "denied" ? "denied" : "not-subscribed"
      return
    }

    const reg = await navigator.serviceWorker.ready
    const res = await fetch("/push/vapid-public-key")
    if (!res.ok) throw new Error(`vapid key fetch failed: ${res.status}`)
    const { publicKey } = (await res.json()) as { publicKey: string }
    const applicationServerKey = urlBase64ToUint8Array(publicKey)

    const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey })
    const subJson = sub.toJSON() as any

    const post = await fetch("/push/subscribe", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        endpoint: subJson.endpoint,
        keys: subJson.keys,
      }),
    })
    if (!post.ok) {
      await sub.unsubscribe().catch(() => {})
      throw new Error(`subscribe POST failed: ${post.status}`)
    }
    status.value = "subscribed"
  }

  async function disable(): Promise<void> {
    if (!("serviceWorker" in navigator)) return
    const reg = await navigator.serviceWorker.ready
    const sub = await reg.pushManager.getSubscription()
    if (sub) await sub.unsubscribe().catch(() => {})
    await fetch("/push/subscribe", {
      method: "DELETE",

    }).catch(() => {})
    status.value = "not-subscribed"
  }

  function dismissBanner(): void {
    bannerDismissed.value = true
    localStorage.setItem(BANNER_DISMISSED_KEY, "1")
  }

  return { status, bannerDismissed, enable, disable, dismissBanner }
}
