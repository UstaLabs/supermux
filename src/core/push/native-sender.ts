import type { PushPayload } from "./sender"
import type { DevicePushTokenStore } from "./device-tokens"

export interface PlatformPushAdapter {
  send(token: string, payload: PushPayload, opts?: { silent?: boolean }): Promise<{ ok: true } | { ok: false; gone: boolean }>
}

export interface NativePushSender {
  sendToDevice(device: string, payload: PushPayload): Promise<{ ok: true } | { ok: false; gone: boolean }>
}

export function createNativePushSender(opts: {
  store: DevicePushTokenStore
  apns: PlatformPushAdapter
  fcm: PlatformPushAdapter
}): NativePushSender {
  return {
    async sendToDevice(device, payload) {
      const row = opts.store.get(device)
      if (!row) return { ok: false, gone: true }
      const adapter = row.platform === "ios" ? opts.apns : opts.fcm
      const res = await adapter.send(row.token, payload)
      if (res.ok === false && res.gone) opts.store.remove(device)
      return res
    },
  }
}
