import type { DevicePushTokenStore } from "./device-tokens"
import type { PushSubscriptionStore } from "./subscriptions"
import type { Logger } from "../../shared/log"

/**
 * At boot: drop the push targets of devices that are no longer paired, then log
 * who can still be pushed to. A revoke through the broker deletes the targets as
 * it happens; one made while the broker was down, or by hand-editing
 * devices.json, did not, and the orphan kept receiving every push (card #86).
 */
export function auditPushTargets(o: {
  paired: ReadonlySet<string>
  deviceTokenStore?: DevicePushTokenStore
  pushStore?: PushSubscriptionStore
  log: Logger
}): void {
  // An unreadable devices.json reads as nobody paired; that must not cost every
  // phone its registration, and with nobody paired nothing can connect anyway.
  const orphan = (device: string) => o.paired.size > 0 && !o.paired.has(device)
  for (const r of o.deviceTokenStore?.all() ?? []) {
    if (!orphan(r.device)) continue
    o.deviceTokenStore!.remove(r.device)
    o.log.warn("push_target_orphan_removed", { device: r.device, kind: "native", platform: r.platform })
  }
  for (const r of o.pushStore?.all() ?? []) {
    if (!orphan(r.device)) continue
    o.pushStore!.remove(r.device)
    o.log.warn("push_target_orphan_removed", { device: r.device, kind: "web" })
  }
  o.log.info("push_targets", {
    web: (o.pushStore?.all() ?? []).map((r) => r.device),
    native: (o.deviceTokenStore?.all() ?? []).map((r) => ({ device: r.device, platform: r.platform })),
  })
}
