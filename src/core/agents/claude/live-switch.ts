import { sendKeysToWindowId, capturePaneById } from "../../session-manager/tmux"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("claude-live-switch")

export const POLL_INTERVAL_MS = 500
export const CONFIRM_TIMEOUT_MS = 10_000
export const SAFETY_WAIT_MS = 2_000
// Pause between typing the command and Enter so the slash-autocomplete settles
// (verified cadence against 2.1.206; an instant Enter can race the popup).
export const TYPE_TO_ENTER_DELAY_MS = 500
export const MENU_RETRY_MS = 1_000

export const MODEL_SUCCESS_MARKER = "Set model to"
export const EFFORT_SUCCESS_MARKER = "Set effort level to"
export const EFFORT_CONFIRM_MARKER = "Change effort level?"
// TUI dialog states we must never type into. The select-menu pointer regex
// catches any Ink menu ("❯ 1. …" indented), incl. ones we don't know about.
const UNSAFE_MARKERS = ["Enter to confirm", "Bypass Permissions mode", "Resume from summary"]
const MENU_POINTER_RE = /^\s+❯ \d+\./m
// Composer prompt renders unindented at the bottom of the pane. Menus indent
// their pointer, so /^❯/ can't false-positive on them; old composer echoes in
// scrollback can't either because safety only looks at the capture TAIL.
const COMPOSER_RE = /^❯/m
const EMPTY_COMPOSER_RE = /^❯\s*$/m
const TAIL_LINES = 15

export type LiveSwitchTarget = { model?: string; effort?: string }

export type LiveSwitchSeams = {
  pollIntervalMs?: number
  confirmTimeoutMs?: number
  safetyWaitMs?: number
  typeDelayMs?: number
  menuRetryMs?: number
  sendKeysFn?: (windowId: string, keys: string[]) => Promise<void>
  capturePane?: (windowId: string) => Promise<string | null>
}

type Result = { ok: true } | { ok: false; error: string }

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

function tail(text: string): string {
  const lines = text.split("\n")
  return lines.slice(Math.max(0, lines.length - TAIL_LINES)).join("\n")
}

function paneIsSafe(text: string): boolean {
  const t = tail(text)
  if (UNSAFE_MARKERS.some((m) => t.includes(m))) return false
  if (MENU_POINTER_RE.test(t)) return false
  return COMPOSER_RE.test(t)
}

function composerEmpty(text: string): boolean {
  return EMPTY_COMPOSER_RE.test(tail(text))
}

function count(haystack: string, needle: string): number {
  let n = 0
  let i = haystack.indexOf(needle)
  while (i !== -1) { n++; i = haystack.indexOf(needle, i + needle.length) }
  return n
}

// Live model/effort switch on an IDLE claude TUI: type /model and/or /effort
// into the composer via tmux send-keys and verify the TUI's confirmation line
// by pane capture. Never restarts the process; any uncertainty is an explicit
// failure so the caller can roll back. Callers guarantee idleness (the
// pending-reapply queue drains on the idle transition), so Escape/C-u here can
// never interrupt a running turn.
export async function applyClaudeLiveSwitch(
  windowId: string,
  target: LiveSwitchTarget,
  seams?: LiveSwitchSeams,
): Promise<Result> {
  const parts: { command: string; marker: string; confirmMenu: boolean; label: string }[] = []
  if (target.model) parts.push({ command: `/model ${target.model}`, marker: MODEL_SUCCESS_MARKER, confirmMenu: false, label: "model" })
  if (target.effort) parts.push({ command: `/effort ${target.effort}`, marker: EFFORT_SUCCESS_MARKER, confirmMenu: true, label: "effort" })
  if (parts.length === 0) return { ok: true }

  for (const part of parts) {
    const r = await typeAndVerify(windowId, part, seams)
    if (!r.ok) {
      log.warn("live_switch_failed", { windowId, part: part.label, err: r.error })
      return r
    }
    log.info("live_switch_applied", { windowId, part: part.label })
  }
  return { ok: true }
}

async function typeAndVerify(
  windowId: string,
  part: { command: string; marker: string; confirmMenu: boolean; label: string },
  seams?: LiveSwitchSeams,
): Promise<Result> {
  const poll = seams?.pollIntervalMs ?? POLL_INTERVAL_MS
  const confirmTimeout = seams?.confirmTimeoutMs ?? CONFIRM_TIMEOUT_MS
  const safetyWait = seams?.safetyWaitMs ?? SAFETY_WAIT_MS
  const typeDelay = seams?.typeDelayMs ?? TYPE_TO_ENTER_DELAY_MS
  const menuRetry = seams?.menuRetryMs ?? MENU_RETRY_MS
  const send = seams?.sendKeysFn ?? sendKeysToWindowId
  const capture = seams?.capturePane ?? capturePaneById

  // 1. Pane safety gate: composer visible, no dialog/menu. Poll briefly — a
  //    transient repaint can hide the prompt for a frame.
  let text: string | null = null
  const safetyDeadline = Date.now() + safetyWait
  while (true) {
    text = await capture(windowId)
    if (text === null) return { ok: false, error: "session window gone (no pane to capture)" }
    if (paneIsSafe(text)) break
    if (Date.now() >= safetyDeadline) {
      return { ok: false, error: `pane not ready for ${part.label} switch (dialog or menu open)` }
    }
    await sleep(poll)
  }

  // 2. Clear any stray composer draft, and PROVE it's empty before typing —
  //    a leftover draft would turn our command into a chat message.
  const baseline = count(text, part.marker)
  for (let attempt = 0; ; attempt++) {
    await send(windowId, ["C-u"])
    const after = await capture(windowId)
    if (after === null) return { ok: false, error: "session window gone (no pane to capture)" }
    if (composerEmpty(after)) break
    if (attempt >= 1) return { ok: false, error: "composer draft could not be cleared; switch aborted" }
    await sleep(poll)
  }

  // 3. Type the command literally, let autocomplete settle, submit.
  await send(windowId, ["-l", part.command])
  await sleep(typeDelay)
  await send(windowId, ["Enter"])

  // 4. Verify by marker-count delta; confirm the effort menu if it appears.
  const deadline = Date.now() + confirmTimeout
  let lastMenuEnterAt = 0
  let menuWasUp = false
  while (true) {
    const now = Date.now()
    const cur = await capture(windowId)
    if (cur === null) return { ok: false, error: "session window gone (no pane to capture)" }
    if (count(cur, part.marker) > baseline) return { ok: true }
    menuWasUp = part.confirmMenu && tail(cur).includes(EFFORT_CONFIRM_MARKER)
    if (menuWasUp && (lastMenuEnterAt === 0 || now - lastMenuEnterAt >= menuRetry)) {
      await send(windowId, ["Enter"]) // default selection is "Yes" (verified)
      lastMenuEnterAt = now
    }
    if (now >= deadline) break
    await sleep(poll)
  }

  // 5. Timeout: clean up whatever half-state remains. Escape cancels a stuck
  //    menu (safe: session is idle, nothing to interrupt); C-u clears typed text.
  await send(windowId, [menuWasUp ? "Escape" : "C-u"])
  return { ok: false, error: `no confirmation for ${part.label} switch within ${confirmTimeout}ms` }
}
