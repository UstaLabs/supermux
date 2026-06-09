import type { VapidKeys } from "./vapid"
import type { PushSubscriptionStore } from "./subscriptions"
import webpushDefault from "web-push"
import { makeLogger } from "../../shared/log"

const log = makeLogger("core/push/sender")

export interface PushPayload {
  session: string
  sessionId?: string
  text?: string
  kind?: "photo" | "voice" | "audio" | "video_note" | "document"
  ts: string
}

export interface WebPushAdapter {
  sendNotification: (
    sub: { endpoint: string; keys: { p256dh: string; auth: string } },
    payload: string,
  ) => Promise<{ statusCode: number }>
}

export interface PushSender {
  sendToChat(chat_id: string, payload: PushPayload): Promise<{ ok: true } | { ok: false; gone: boolean }>
  sendToDevice(device: string, payload: PushPayload): Promise<{ ok: true } | { ok: false; gone: boolean }>
}

export function createPushSender(opts: {
  vapid: VapidKeys
  store: PushSubscriptionStore
  webpushAdapter?: WebPushAdapter
}): PushSender {
  const webpush: WebPushAdapter = opts.webpushAdapter ?? {
    sendNotification: (sub, payload) => webpushDefault.sendNotification(
      sub as any,
      payload,
      {
        vapidDetails: {
          subject: opts.vapid.subject,
          publicKey: opts.vapid.publicKey,
          privateKey: opts.vapid.privateKey,
        },
        TTL: 60,
      },
    ) as any,
  }

  async function sendToRecord(
    rec: { device: string; endpoint: string; keys: { p256dh: string; auth: string } },
    payload: PushPayload,
  ): Promise<{ ok: true } | { ok: false; gone: boolean }> {
    try {
      await webpush.sendNotification({ endpoint: rec.endpoint, keys: rec.keys }, JSON.stringify(payload))
      opts.store.markUsed(rec.device)
      return { ok: true }
    } catch (err: any) {
      const status: number | undefined = err?.statusCode
      if (status === 404 || status === 410) {
        log.info("push_subscription_gone", { device: rec.device, status })
        opts.store.remove(rec.device)
        return { ok: false, gone: true }
      }
      log.warn("push_send_failed", { device: rec.device, status: status ?? "?", err: err?.message ?? String(err) })
      return { ok: false, gone: false }
    }
  }

  async function sendToChat(chat_id: string, payload: PushPayload): Promise<{ ok: true } | { ok: false; gone: boolean }> {
    const rec = opts.store.forChatId(chat_id)
    if (!rec) return { ok: false, gone: false }
    return sendToRecord(rec, payload)
  }

  async function sendToDevice(device: string, payload: PushPayload): Promise<{ ok: true } | { ok: false; gone: boolean }> {
    const rec = opts.store.get(device)
    if (!rec) return { ok: false, gone: false }
    return sendToRecord(rec, payload)
  }

  return { sendToChat, sendToDevice }
}
