import type { OutboundAction } from "../../channels/channel"
import type { PushSender, PushPayload } from "./sender"
import type { NativePushSender } from "./native-sender"
import { channelOf, isWebChat } from "../routing/address"
import { makeLogger, type Logger } from "../../shared/log"

const defaultLog = makeLogger("core/push/hook")

export function extractPreview(action: OutboundAction & { op: "reply" }): string {
  if (action.text && action.text.length > 0) {
    return action.text.length > 120 ? action.text.slice(0, 117) + "…" : action.text
  }
  const first = action.attachments?.[0]
  if (first) {
    switch (first.kind) {
      case "photo":      return "📷 Photo"
      case "voice":      return "🎙 Voice message"
      case "audio":      return "🎵 Audio"
      case "video":
      case "video_note": return "🎥 Video"
      case "document":   return `📎 ${first.name ?? "File"}`
    }
  }
  return "New message"
}

export interface FirePushArgs {
  sender: PushSender
  action: OutboundAction
  sessionName: string
  sessionId: string
  isMuted: (id: string) => boolean
  /** True for hidden internal RPC workers — they must never push to the user. */
  isInternal?: (id: string) => boolean
  /** All subscribed web device names — the fan-out target. */
  devices: () => string[]
  /** The devices that have this session's chat open; any one of them makes the push
   * redundant everywhere. The session list is not presence (card #86). */
  presentOn: (sessionId: string) => string[]
  /** Optional native push sender (APNs/FCM relay). When provided together with
   * nativeDevices, the reply is also fanned out to registered native devices
   * after the same mute + presence suppression guards that govern web push. */
  nativeSender?: NativePushSender
  /** Returns the list of registered native device ids for this user. */
  nativeDevices?: () => string[]
  /** This broker's host identity, echoed into the payload so multi-host clients
   * route the notification to the right host (spec §5). */
  hostId?: string
  log?: Logger
}

// Web is one logical channel and the user is ONE person across their devices, so
// a notification is a single global decision: skip it entirely if the session is
// muted OR the user has its chat open on any device; otherwise push to ALL
// subscribed devices. Every decision is
// logged as one `push_decision` line (card #86).
export async function firePushForReply(args: FirePushArgs): Promise<void> {
  const { sender, action, sessionName, sessionId, isMuted, isInternal, devices, presentOn } = args
  const log = args.log ?? defaultLog
  if (action.op !== "reply") return
  const decided = (outcome: string, extra: Record<string, unknown> = {}) =>
    log.info("push_decision", { session: sessionName, sessionId, outcome, ...extra })
  if (!isWebChat(action.chat_id)) return decided("other_channel", { channel: channelOf(action.chat_id) })   // telegram/whatsapp notify on their own
  if (isInternal?.(sessionId)) return decided("internal") // hidden RPC worker → never push
  if (isMuted(sessionId)) return decided("muted")
  const present = presentOn(sessionId)
  if (present.length > 0) return decided("present", { presentOn: present }) // present on some device → no push anywhere
  const web = devices()
  const native = args.nativeSender && args.nativeDevices ? args.nativeDevices() : []
  if (web.length === 0 && native.length === 0) {
    log.warn("push_decision", { session: sessionName, sessionId, outcome: "no_targets" })
    return
  }
  decided("sent", { web, native })
  const payload: PushPayload = {
    session: sessionName,
    sessionId,
    text: extractPreview(action),
    kind: action.attachments?.[0]?.kind as any,
    ts: new Date().toISOString(),
    ...(args.hostId ? { hostId: args.hostId } : {}),
  }
  for (const device of web) {
    await sender.sendToDevice(device, payload)
  }
  for (const device of native) await args.nativeSender!.sendToDevice(device, payload)
}

/**
 * A broker notice for the user rather than a session's reply (a finished
 * worktree, the nightly curator): no mute or presence gate, every device.
 */
export async function firePushToAll(args: {
  sender: PushSender
  nativeSender?: NativePushSender
  devices: () => string[]
  nativeDevices?: () => string[]
  payload: PushPayload
  trigger: string
  log?: Logger
}): Promise<void> {
  const log = args.log ?? defaultLog
  const web = args.devices()
  const native = args.nativeSender && args.nativeDevices ? args.nativeDevices() : []
  const subject = { session: args.payload.session, ...(args.payload.sessionId ? { sessionId: args.payload.sessionId } : {}), trigger: args.trigger }
  if (web.length === 0 && native.length === 0) {
    log.warn("push_decision", { ...subject, outcome: "no_targets" })
    return
  }
  log.info("push_decision", { ...subject, outcome: "sent", web, native })
  for (const device of web) await args.sender.sendToDevice(device, args.payload)
  for (const device of native) await args.nativeSender!.sendToDevice(device, args.payload)
}
