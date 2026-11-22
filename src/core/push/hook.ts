import type { OutboundAction } from "../../channels/channel"
import type { PushSender, PushPayload } from "./sender"
import type { NativePushSender } from "./native-sender"
import { channelOf, isWebChat } from "../routing/address"
import { makeLogger, type Logger } from "../../shared/log"
import { brokerLang, t, type BrokerLang } from "../i18n/messages"
import type { PushVerdict } from "./notification-prefs"

const defaultLog = makeLogger("core/push/hook")

/** The notification's text: the reply's own text as written, else its attachment worded in [lang] (#123). */
export function extractPreview(action: OutboundAction & { op: "reply" }, lang: BrokerLang = "en"): string {
  if (action.text && action.text.length > 0) {
    return action.text.length > 120 ? action.text.slice(0, 117) + "…" : action.text
  }
  const first = action.attachments?.[0]
  if (first) {
    switch (first.kind) {
      case "photo":      return t(lang, "push.photo")
      case "voice":      return t(lang, "push.voice")
      case "audio":      return t(lang, "push.audio")
      case "video":
      case "video_note": return t(lang, "push.video")
      case "document":   return first.name ? t(lang, "push.file_named", { name: first.name }) : t(lang, "push.file")
    }
  }
  return t(lang, "push.new_message")
}

export interface FirePushArgs {
  sender: PushSender
  action: OutboundAction
  sessionName: string
  sessionId: string
  /** #163: the person's notification settings for this chat's reply (decidePush). Replaces [isMuted] when given. */
  decide?: () => PushVerdict
  isMuted?: (id: string) => boolean
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
  /** #123: a web device's language tag (its push subscription's); the preview is worded in it, English when unknown. */
  langOf?: (device: string) => string | undefined
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
  const present = presentOn(sessionId)
  if (present.length > 0) return decided("present", { presentOn: present }) // present on some device → no push anywhere
  const verdict = args.decide?.()
  if (verdict && !verdict.send) return decided("settings", { rule: verdict.rule, level: verdict.level })
  if (!verdict && isMuted?.(sessionId)) return decided("muted")
  const web = devices()
  const native = args.nativeSender && args.nativeDevices ? args.nativeDevices() : []
  if (web.length === 0 && native.length === 0) {
    log.warn("push_decision", { session: sessionName, sessionId, outcome: "no_targets" })
    return
  }
  decided("sent", { web, native, ...(verdict ? { rule: verdict.rule } : {}) })
  const payload: PushPayload = {
    session: sessionName,
    sessionId,
    text: extractPreview(action),
    kind: action.attachments?.[0]?.kind as any,
    ts: new Date().toISOString(),
    ...(args.hostId ? { hostId: args.hostId } : {}),
  }
  for (const device of web) {
    const lang = brokerLang(args.langOf?.(device))
    await sender.sendToDevice(device, lang === "en" ? payload : { ...payload, text: extractPreview(action, lang) })
  }
  // Native devices keep English: the apps word the preview themselves from `kind`.
  for (const device of native) await args.nativeSender!.sendToDevice(device, payload)
}

/**
 * #143: an agent's permission prompt or question. It blocks the agent until someone answers, so unlike a reply it is
 * pushed even when the session is muted; never for an internal worker, and not while the session is on screen
 * somewhere (its card is there). The payload carries the request id and the answers, so a client can answer it from
 * the notification.
 */
export async function firePushForRequest(args: {
  sender: PushSender
  sessionName: string
  sessionId: string
  request: { requestId: string; kind: "permission" | "question"; title: string; body: string; options: { id: string; label: string }[]; reason?: string }
  isInternal?: (id: string) => boolean
  /** #163: the person's notification settings for a blocking request; it breaks through Off unless they turned that off. */
  decide?: () => PushVerdict
  devices: () => string[]
  presentOn: (sessionId: string) => string[]
  nativeSender?: NativePushSender
  nativeDevices?: () => string[]
  hostId?: string
  log?: Logger
}): Promise<void> {
  const { sessionName, sessionId, request } = args
  const log = args.log ?? defaultLog
  const decided = (outcome: string, extra: Record<string, unknown> = {}) =>
    log.info("push_decision", { session: sessionName, sessionId, trigger: "request", outcome, ...extra })
  if (args.isInternal?.(sessionId)) return decided("internal")
  const present = args.presentOn(sessionId)
  if (present.length > 0) return decided("present", { presentOn: present })
  const verdict = args.decide?.()
  if (verdict && !verdict.send) return decided("settings", { rule: verdict.rule, level: verdict.level })
  const web = args.devices()
  const native = args.nativeSender && args.nativeDevices ? args.nativeDevices() : []
  if (web.length === 0 && native.length === 0) {
    log.warn("push_decision", { session: sessionName, sessionId, trigger: "request", outcome: "no_targets" })
    return
  }
  decided("sent", { web, native, ...(verdict ? { rule: verdict.rule } : {}) })
  const firstLine = request.body.split("\n").find((line) => line.trim())?.trim() ?? ""
  // The agent's own reason (a safety check) says more than the command does.
  const text = `${request.title} ${request.reason ? `(${request.reason}) ` : ""}${firstLine}`.trim()
  const payload: PushPayload = {
    session: sessionName,
    sessionId,
    text: text.length > 120 ? text.slice(0, 117) + "…" : text,
    ts: new Date().toISOString(),
    ...(args.hostId ? { hostId: args.hostId } : {}),
    request: { requestId: request.requestId, kind: request.kind, options: request.options.map((o) => ({ id: o.id, label: o.label })) },
  }
  for (const device of web) await args.sender.sendToDevice(device, payload)
  for (const device of native) await args.nativeSender!.sendToDevice(device, payload)
}

/**
 * A notice for the user rather than a session's reply (a finished worktree, the nightly curator, an applet's
 * supermux.notify): no presence gate, every device; [decide] applies the person's notification settings (#163).
 */
export async function firePushToAll(args: {
  sender: PushSender
  nativeSender?: NativePushSender
  devices: () => string[]
  nativeDevices?: () => string[]
  payload: PushPayload
  trigger: string
  /** #123: a web device's language tag; with [textFor], each web device gets the notice in its language. */
  langOf?: (device: string) => string | undefined
  /** #123: the notice in a language. Web devices get it in theirs, native ones in English; absent → payload.text. */
  textFor?: (lang: BrokerLang) => string
  /** #163: the person's notification settings (an applet's notice, a supermux notice). Absent: always sent. */
  decide?: () => PushVerdict
  log?: Logger
}): Promise<void> {
  const log = args.log ?? defaultLog
  const subject = { session: args.payload.session, ...(args.payload.sessionId ? { sessionId: args.payload.sessionId } : {}), trigger: args.trigger }
  const verdict = args.decide?.()
  if (verdict && !verdict.send) {
    log.info("push_decision", { ...subject, outcome: "settings", rule: verdict.rule, level: verdict.level })
    return
  }
  const web = args.devices()
  const native = args.nativeSender && args.nativeDevices ? args.nativeDevices() : []
  if (web.length === 0 && native.length === 0) {
    log.warn("push_decision", { ...subject, outcome: "no_targets" })
    return
  }
  log.info("push_decision", { ...subject, outcome: "sent", web, native, ...(verdict ? { rule: verdict.rule } : {}) })
  const textFor = args.textFor
  const worded = (lang: BrokerLang): PushPayload => (textFor ? { ...args.payload, text: textFor(lang) } : args.payload)
  for (const device of web) await args.sender.sendToDevice(device, worded(brokerLang(args.langOf?.(device))))
  for (const device of native) await args.nativeSender!.sendToDevice(device, worded("en"))
}
