import type { OutboundAction } from "../../channels/channel"
import type { PushSender, PushPayload } from "./sender"

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
  /** All subscribed web device names — the fan-out target. */
  devices: () => string[]
  /** True when the user is present for this session on ANY device (that session's
   * chat open, or the chat list, anywhere). */
  anyPresent: (sessionId: string) => boolean
}

// Web is one logical channel and the user is ONE person across their devices, so
// a notification is a single global decision: skip it entirely if the session is
// muted OR the user is already looking at it on any device (the session chat or
// the chat list); otherwise push to ALL subscribed devices.
export async function firePushForReply(args: FirePushArgs): Promise<void> {
  const { sender, action, sessionName, sessionId, isMuted, devices, anyPresent } = args
  if (action.op !== "reply") return
  if (action.chat_id !== "web") return
  if (isMuted(sessionId)) return
  if (anyPresent(sessionId)) return // present on some device → no push anywhere
  const payload: PushPayload = {
    session: sessionName,
    sessionId,
    text: extractPreview(action),
    kind: action.attachments?.[0]?.kind as any,
    ts: new Date().toISOString(),
  }
  for (const device of devices()) {
    await sender.sendToDevice(device, payload)
  }
}
