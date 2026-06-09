import { readFileSync } from "fs"
import { basename } from "path"
import type { Registry } from "../session-manager/registry"
import type {
  ChannelCapabilities,
  OutboundAction,
  OutboundAttachmentRef,
} from "../../channels/channel"
import type { FileStore } from "../files/store"
import { kindFromMime } from "../files/kinds"
import { makeLogger } from "../../shared/log"

const log = makeLogger("core/routing/transform-outbound")

/**
 * Outbound transformation pipeline. Three concerns:
 *
 *   1. Tag prefix — when fromSession (a session UUID) is not the chat's active
 *      session, prefix reply/edit_message text with `[session-name] `. Keeps
 *      multiple sessions legible in a shared chat.
 *
 *   2. Push policy — reply pushes unless muted; edit/react/download never push.
 *      Stored on action.disable_notification.
 *
 *   3. Attachment rewriting (web outbound only) — when the destination channel
 *      `supportsAttachments` and chat_id is `web:*`, register each files[] path
 *      through FileStore.put({origin:'session-outbound'}) and rewrite the action
 *      to carry attachments[] (channel-agnostic refs) instead of files[]. When
 *      the destination is web but !supportsAttachments, strip files[] entirely.
 *      Telegram (non-web chat_id) keeps files[] intact — the telegram channel
 *      reads them directly via grammy.
 */
export async function transformOutbound(
  action: OutboundAction,
  fromSession: string,
  capabilities: ChannelCapabilities,
  fileStore: FileStore,
  registry?: Registry,
): Promise<OutboundAction> {
  // 1+2 — tag prefix + disable_notification — only meaningful when a registry
  // is provided. Callers that don't care (pure attachment-rewrite tests) can
  // omit it.
  let result: OutboundAction = action
  if (registry) {
    result = applyTagAndPush(result, fromSession, registry, capabilities.multiplexesSessions)
  }

  // 3 — attachment rewriting. Only the reply op carries files[]; other ops
  // pass through untouched.
  if (result.op !== "reply" || !result.files?.length) return result

  // The web channel uses the bare constant `web` (single-channel collapse,
  // commit 6d6f02e) AND still accepts the legacy `web:<device>` form. Match
  // both — keying on only `web:` here silently dropped every outbound
  // attachment (the WebChannel.send() ignores files[]; delivery is via the
  // rewritten attachments[]).
  const isWeb = result.chat_id === "web" || result.chat_id.startsWith("web:")

  if (isWeb && capabilities.supportsAttachments) {
    const refs: OutboundAttachmentRef[] = []
    for (const fp of result.files) {
      // Claude has full filesystem access via Read; files[] is a delivery channel,
      // not a sandbox boundary. We log each path for auditability — operators can
      // review the broker's logs to spot unexpected exfil patterns.
      const bytes = readFileSync(fp)
      log.info("outbound_file_registered", {
        session: fromSession,
        chat_id: result.chat_id,
        path: fp,
        size_hint: bytes.length,
      })
      const name = basename(fp)
      const mime = guessMimeFromName(name)
      const kind = kindFromMime(mime)
      const { file_id } = await fileStore.put({
        kind,
        mime,
        name,
        session: fromSession,
        origin: "session-outbound",
        bytes,
      })
      refs.push({ file_id, kind, mime, name, size: bytes.length })
    }
    const { files: _drop, ...rest } = result
    return { ...rest, attachments: refs }
  }

  if (isWeb && !capabilities.supportsAttachments) {
    const { files: _drop, ...rest } = result
    return rest
  }

  // Telegram (or any other non-web channel): keep files[] as-is.
  return result
}

function applyTagAndPush(
  action: OutboundAction,
  fromSession: string,
  registry: Registry,
  multiplexes: boolean,
): OutboundAction {
  // fromSession is the session UUID. Resolve once: mute + the active-session
  // comparison key off the UUID, but the visible tag shows the display name.
  const session = registry.get(fromSession)
  const muted = session?.mute ?? false
  const displayName = session?.name ?? fromSession

  if (action.op === "reply") {
    return { ...action, text: tagText(action.chat_id, fromSession, displayName, action.text, registry, multiplexes), disable_notification: muted }
  }
  if (action.op === "edit_message") {
    // edit_message stays silent regardless of mute.
    return { ...action, text: tagText(action.chat_id, fromSession, displayName, action.text, registry, multiplexes) }
  }
  // react / download_attachment — pass through; push policy applied at send
  // time. (Neither schema has disable_notification today.)
  return action
}

// The `[name]` prefix only makes sense on channels that multiplex many
// sessions into ONE visible chat (Telegram) — there it disambiguates who's
// speaking. Non-multiplexing channels (the web PWA) give each session its own
// timeline, so the prefix is pure noise; skip it there. `fromSession` is the
// UUID (matched against the active-session UUID); `displayName` is what shows.
function tagText(chat_id: string, fromSession: string, displayName: string, text: string, registry: Registry, multiplexes: boolean): string {
  if (!multiplexes) return text
  const activeId = registry.getActive(chat_id)
  return activeId !== fromSession ? `[${displayName}] ${text}` : text
}

function guessMimeFromName(name: string): string {
  const n = name.toLowerCase()
  if (n.endsWith(".png")) return "image/png"
  if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg"
  if (n.endsWith(".gif")) return "image/gif"
  if (n.endsWith(".webp")) return "image/webp"
  if (n.endsWith(".pdf")) return "application/pdf"
  if (n.endsWith(".mp3")) return "audio/mpeg"
  if (n.endsWith(".opus")) return "audio/opus"
  if (n.endsWith(".ogg")) return "audio/ogg"
  if (n.endsWith(".m4a")) return "audio/mp4"
  if (n.endsWith(".wav")) return "audio/wav"
  if (n.endsWith(".mp4")) return "video/mp4"
  if (n.endsWith(".webm")) return "video/webm"
  return "application/octet-stream"
}

