import type { WidgetRefreshPayload } from "../push/sender"
import { makeLogger } from "../../shared/log"

const log = makeLogger("applets/widget-push")

/**
 * #165: tells the owner's phones an applet's widget changed: a push that shows nothing. Android (an FCM data message)
 * redraws at once. iOS gets a background push through the relay's /push/silent, which iOS delivers when it chooses;
 * a relay without that route is never sent a normal push instead (it would show an empty alert). A busy applet is
 * coalesced to one push per [gapMs], sent at the end of the gap, so the last state always arrives.
 */
export function createWidgetPusher(opts: {
  devices: () => { device: string; platform: "ios" | "android" }[]
  send: (device: string, payload: WidgetRefreshPayload, platform: "ios" | "android") => Promise<unknown>
  hostId?: () => string | undefined
  gapMs?: number
  now?: () => number
}) {
  const gap = opts.gapMs ?? 15_000
  const now = opts.now ?? Date.now
  const last = new Map<string, number>()
  const pending = new Map<string, { except: Set<string | undefined>; timer: ReturnType<typeof setTimeout> }>()

  const fire = (applet: string, except: Set<string | undefined>) => {
    last.set(applet, now())
    // A device that caused the change already has the new widget; it is spared only when it alone caused it.
    const skip = except.size === 1 ? [...except][0] : undefined
    const targets = opts.devices().filter((d) => d.device !== skip)
    if (!targets.length) return
    log.info("applet_widget_push", { applet, devices: targets.length })
    const hostId = opts.hostId?.()
    const payload: WidgetRefreshPayload = { widget: applet, ...(hostId ? { hostId } : {}), ts: new Date(now()).toISOString() }
    for (const d of targets) void opts.send(d.device, payload, d.platform).catch(() => {})
  }

  return {
    changed(applet: string, except?: string): void {
      const p = pending.get(applet)
      if (p) { p.except.add(except); return }
      const since = now() - (last.get(applet) ?? -Infinity)
      if (since >= gap) { fire(applet, new Set([except])); return }
      const entry = { except: new Set<string | undefined>([except]), timer: setTimeout(() => { pending.delete(applet); fire(applet, entry.except) }, gap - since) }
      pending.set(applet, entry)
    },
    stop(): void { for (const p of pending.values()) clearTimeout(p.timer); pending.clear() },
  }
}
