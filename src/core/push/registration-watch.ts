import type { DevicePushTokenStore } from "./device-tokens"
import type { Logger } from "../../shared/log"

const PHONE_SURFACES = new Set(["android", "ios"])

/**
 * Says so when a phone app is connected but cannot be pushed to. From 10-27 no
 * phone could register (card #86) and the broker's log showed nothing.
 */
export class PushRegistrationWatch {
  private readonly warned = new Set<string>()

  constructor(private readonly store: DevicePushTokenStore, private readonly log: Logger) {}

  connected(device: string, surface: string | undefined): void {
    if (!surface || !PHONE_SURFACES.has(surface)) return
    if (this.store.get(device)?.routing_token) {
      this.warned.delete(device)
      return
    }
    if (this.warned.has(device)) return
    this.warned.add(device)
    this.log.warn("push_device_unregistered", { device, surface })
  }

  registered(device: string, platform: string): void {
    this.warned.delete(device)
    this.log.info("push_device_registered", { device, platform })
  }
}
