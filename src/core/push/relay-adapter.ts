import type { PushPayload, WidgetRefreshPayload } from "./sender"
import type { DevicePushTokenStore } from "./device-tokens"
import type { NativePushSender } from "./native-sender"
import { sealForDevice } from "./encrypt"
import { makeLogger, type Logger } from "../../shared/log"

const defaultLog = makeLogger("core/push/relay")

export function createRelayClient(opts: {
  store: DevicePushTokenStore
  relayUrl: string
  fetchImpl?: (url: string, init: RequestInit) => Promise<Response>
  log?: Logger
  now?: () => number
}): NativePushSender {
  const fetchFn = opts.fetchImpl ?? fetch
  const log = opts.log ?? defaultLog

  // #165: an older relay has no /push/silent (it answers 404); asked again after an hour, as relays get updated.
  let silentMissingUntil = 0
  const now = opts.now ?? Date.now

  return {
    async sendSilentToDevice(device: string, payload: WidgetRefreshPayload): Promise<{ ok: true } | { ok: false; gone: boolean }> {
      if (now() < silentMissingUntil) return { ok: false, gone: false }
      return send(device, payload, "/push/silent")
    },
    sendToDevice: (device: string, payload: PushPayload | WidgetRefreshPayload) => send(device, payload, "/push"),
  }

  // Every outcome is logged (card #86): a relay host answering 404 once made
  // res.json() throw into a bare catch, and pushes vanished for days unseen.
  async function send(device: string, payload: PushPayload | WidgetRefreshPayload, path: "/push" | "/push/silent"): Promise<{ ok: true } | { ok: false; gone: boolean }> {
    {
      const row = opts.store.get(device)
      if (!row?.routing_token || !row.device_pubkey) {
        log.warn("push_result", { device, transport: "relay", ok: false, reason: "not_registered" })
        return { ok: false, gone: true }
      }
      const target = { device, transport: "relay", platform: row.platform }

      const ciphertext = await sealForDevice(row.device_pubkey, JSON.stringify(payload))

      let res: Response
      let parsed: { ok?: boolean; gone?: boolean; reason?: string }
      try {
        res = await fetchFn(`${opts.relayUrl}${path}`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ routingToken: row.routing_token, ciphertext }),
        })
        if (path === "/push/silent" && res.status === 404) {
          silentMissingUntil = now() + 3_600_000
          log.info("push_result", { ...target, ok: false, silent: true, reason: "relay_has_no_silent_push" })
          return { ok: false, gone: false }
        }
        if (!res.ok) {
          log.warn("push_result", { ...target, ok: false, status: res.status, reason: `http_${res.status}` })
          return { ok: false, gone: false }
        }
        parsed = (await res.json()) as { ok?: boolean; gone?: boolean; reason?: string }
      } catch (err: any) {
        log.warn("push_result", { ...target, ok: false, reason: "network", err: err?.message ?? String(err) })
        return { ok: false, gone: false }
      }

      if (parsed.ok === true) {
        log.info("push_result", { ...target, ok: true })
        return { ok: true }
      }
      if (parsed.gone === true) {
        opts.store.remove(device)
        log.warn("push_target_removed", { ...target, reason: parsed.reason ?? "gone" })
        return { ok: false, gone: true }
      }
      log.warn("push_result", { ...target, ok: false, status: res.status, reason: parsed.reason ?? "relay_refused" })
      return { ok: false, gone: false }
    }
  }
}
