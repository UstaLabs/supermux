import type { PushPayload } from "./sender"
import type { DevicePushTokenStore } from "./device-tokens"
import type { NativePushSender } from "./native-sender"
import { sealForDevice } from "./encrypt"

export function createRelayClient(opts: {
  store: DevicePushTokenStore
  relayUrl: string
  fetchImpl?: (url: string, init: RequestInit) => Promise<Response>
}): NativePushSender {
  const fetchFn = opts.fetchImpl ?? fetch

  return {
    async sendToDevice(device: string, payload: PushPayload): Promise<{ ok: true } | { ok: false; gone: boolean }> {
      const row = opts.store.get(device)
      if (!row?.routing_token || !row.device_pubkey) return { ok: false, gone: true }

      const ciphertext = await sealForDevice(row.device_pubkey, JSON.stringify(payload))

      let parsed: { ok?: boolean; gone?: boolean }
      try {
        const res = await fetchFn(`${opts.relayUrl}/push`, {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ routingToken: row.routing_token, ciphertext }),
        })
        parsed = (await res.json()) as { ok?: boolean; gone?: boolean }
      } catch {
        return { ok: false, gone: false }
      }

      if (parsed.ok === false && parsed.gone === true) {
        opts.store.remove(device)
        return { ok: false, gone: true }
      }

      if (parsed.ok === true) return { ok: true }
      return { ok: false, gone: false }
    },
  }
}
