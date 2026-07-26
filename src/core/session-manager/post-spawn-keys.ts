import { getSessionBackend } from "../runtime"
import type { SessionBackend } from "../runtime/session-backend"
import { makeLogger } from "../../shared/log"

const log = makeLogger("post-spawn-keys")

export const POLL_INTERVAL_MS = 500
export const MAX_WAIT_MS = 30_000
// How long to wait after an Enter before re-sending it if the prompt is still up.
export const RETRY_AFTER_MS = 1_000
// Pause between the Down and the Enter that accept the bypass warning. Sending
// them together is too fast: Enter confirms before Ink moves the selection off
// "1. No, exit" → claude QUITS. The pause lets the selection land on "2. Yes, I
// accept" first. Verified: ~1s reliably accepts.
export const BYPASS_KEY_DELAY_MS = 1_000
const CHANNEL_CONSENT_MARKER = "Enter to confirm"
const RESUME_MENU_MARKER = "Resume from summary"
const LISTENING_MARKER = "Listening for channel messages"
// The "Bypass Permissions mode" warning. Normally pre-accepted in ~/.claude.json
// (preAcceptTrust) + suppressed by IS_SANDBOX, but some claude versions re-show
// it when loading dev-channels (observed: 2.1.161, fixed in 2.1.162). Its default
// option is "No, exit" — so a bare consent Enter would QUIT claude (this was a
// real PA-death). We detect it and accept it instead.
const BYPASS_WARNING_MARKER = "Bypass Permissions mode"

// Poll the tmux pane and dismiss Claude's startup prompts so a freshly-spawned
// session reaches its working prompt and loads the inbound channel:
//
//   1. --resume "Resume from summary" menu (also shows "Enter to confirm") —
//      send "2" to resume the FULL session, not the summary.
//   2. development-channel consent ("Enter to confirm" without the resume menu).
//
// The consent Enter races against two startup quirks, so we don't send it once:
//   • render race — on cold boots Claude can take >3s to render the prompt, so a
//     fixed-delay Enter arrives too early and is lost. We poll for the prompt.
//   • input-handler race — Claude renders the prompt a beat BEFORE its Ink input
//     handler mounts, so even an Enter sent the instant it appears gets dropped
//     (observed in Docker: prompt at +1.5s, lone Enter dropped, session stuck).
//     So we re-send Enter every RETRY_AFTER_MS until the prompt actually clears.
//     A dropped Enter leaves the marker on screen; an Enter that lands replaces
//     it with the working UI; an extra Enter on the cleared prompt is a harmless
//     empty submit — so over-sending is safe.
//
// Precondition: Bypass Permissions mode is pre-accepted in ~/.claude.json (see
// preAcceptTrust), so the only non-resume prompt showing "Enter to confirm" is
// the dev-channels one (default = accept), never the bypass warning (default
// "No, exit"), which a blind Enter would turn into an exit.
export async function sendChannelConsentEnter(
  targetId: string,
  opts?: {
    pollIntervalMs?: number
    maxWaitMs?: number
    retryAfterMs?: number
    keyDelayMs?: number
    backend?: SessionBackend
    sendKeysFn?: (targetId: string, keys: string[]) => Promise<void>
    capturePane?: (targetId: string) => Promise<string | null>
  },
): Promise<void> {
  const interval = opts?.pollIntervalMs ?? POLL_INTERVAL_MS
  const maxWait = opts?.maxWaitMs ?? MAX_WAIT_MS
  const retryAfter = opts?.retryAfterMs ?? RETRY_AFTER_MS
  const keyDelay = opts?.keyDelayMs ?? BYPASS_KEY_DELAY_MS
  const backend = () => opts?.backend ?? getSessionBackend()
  const send = opts?.sendKeysFn ?? ((id: string, keys: string[]) => backend().sendKeys(id, keys))
  const capture = opts?.capturePane ?? ((id: string) => backend().capture(id))

  let resumeMenuDismissed = false
  const deadline = Date.now() + maxWait
  let sent = 0
  let lastSentAt = 0
  let lastBypassAt = 0
  while (Date.now() < deadline) {
    try {
      const text = (await capture(targetId)) ?? ""
      // Claude is live on the channel → past all startup prompts.
      if (text.includes(LISTENING_MARKER)) {
        log.debug("channel_consent_already_past", { targetId })
        return
      }
      // Bypass Permissions warning: default is "No, exit", so a bare Enter would
      // QUIT claude. Accept option "2. Yes, I accept": Down to move the selection,
      // a pause so Ink processes it, THEN Enter (sending them together confirms
      // before the Down registers → "No, exit" → death). Never fall through to the
      // bare-Enter consent handling below. Gated strictly on the bypass marker
      // (the dev-channels prompt lacks it, and ITS option 2 is "Exit" — so this
      // must never fire there). Retry until the warning clears.
      if (text.includes(BYPASS_WARNING_MARKER)) {
        const now = Date.now()
        if (lastBypassAt === 0 || now - lastBypassAt >= retryAfter) {
          await send(targetId, ["Down"])
          await new Promise<void>(r => setTimeout(r, keyDelay))
          await send(targetId, ["Enter"])
          lastBypassAt = now
          log.info("bypass_warning_accepted", { targetId })
        }
        await new Promise<void>(r => setTimeout(r, interval))
        continue
      }
      // --resume "summary vs full session" menu (also contains "Enter to
      // confirm"): "2" = resume the full session. Dismiss once.
      if (text.includes(RESUME_MENU_MARKER) && !resumeMenuDismissed) {
        await send(targetId, ["2", "Enter"])
        resumeMenuDismissed = true
        log.info("resume_menu_full_session_sent", { targetId })
        await new Promise<void>(r => setTimeout(r, interval))
        continue
      }
      // dev-channels consent (not the resume menu): re-send Enter until it clears.
      if (text.includes(CHANNEL_CONSENT_MARKER) && !text.includes(RESUME_MENU_MARKER)) {
        const now = Date.now()
        if (sent === 0 || now - lastSentAt >= retryAfter) {
          await send(targetId, ["Enter"])
          sent++
          lastSentAt = now
          log.info(sent === 1 ? "channel_consent_enter_sent" : "channel_consent_enter_retried", {
            targetId,
            attempt: sent,
          })
        }
      } else if (sent > 0) {
        // The consent prompt we were dismissing is gone → an Enter landed and
        // Claude accepted. (A dropped Enter would have left the marker up.)
        log.info("channel_consent_accepted", { targetId, attempts: sent })
        return
      }
    } catch (err) {
      log.debug("channel_consent_capture_failed", { targetId, err: String(err) })
    }
    await new Promise<void>(r => setTimeout(r, interval))
  }
  log.warn("channel_consent_timeout", { targetId, maxWaitMs: maxWait, enters: sent, resumeMenuDismissed })
}
