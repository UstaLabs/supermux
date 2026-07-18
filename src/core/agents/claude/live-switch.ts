import { getSessionBackend } from "../../runtime"
import type { SessionBackend } from "../../runtime/session-backend"
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
const TAIL_LINES = 15
// How many times to re-check the composer for the typed command before
// declaring a mismatch (each check is one typeDelay apart — covers Ink
// repaint lag on a loaded host).
const TYPED_VERIFY_ATTEMPTS = 4

export type LiveSwitchTarget = { model?: string; effort?: string }

export type LiveSwitchSeams = {
  pollIntervalMs?: number
  confirmTimeoutMs?: number
  safetyWaitMs?: number
  typeDelayMs?: number
  menuRetryMs?: number
  backend?: SessionBackend
  sendKeysFn?: (targetId: string, keys: string[]) => Promise<void>
  capturePane?: (targetId: string) => Promise<string | null>
  /** Escape-preserving capture (tmux -e) for composer checks; falls back to capturePane in tests. */
  capturePaneRaw?: (targetId: string) => Promise<string | null>
}

type Result = { ok: true } | { ok: false; error: string }

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

function tail(text: string): string {
  const lines = text.split("\n")
  return lines.slice(Math.max(0, lines.length - TAIL_LINES)).join("\n")
}

function paneIsSafe(text: string): boolean {
  return paneUnsafeReason(text) === null
}

function paneUnsafeReason(text: string): string | null {
  const t = tail(text)
  const marker = UNSAFE_MARKERS.find((m) => t.includes(m))
  if (marker) return `marker: ${marker}`
  if (MENU_POINTER_RE.test(t)) return "menu pointer"
  if (!COMPOSER_RE.test(t)) return "no composer prompt"
  return null
}

// Strip ANSI escapes from one captured (-e) line. With dropDim, characters
// styled dim (SGR 2) are removed too — that's how Claude renders the ghost
// autosuggestion in the composer, which is NOT real input (C-u can't clear it,
// typing replaces it) yet is indistinguishable from a draft in a plain capture.
function stripLine(line: string, opts: { dropDim: boolean }): string {
  let out = ""
  let dim = false
  let i = 0
  while (i < line.length) {
    if (line[i] === "\x1b") {
      const m = /^\x1b\[([0-9;]*)([A-Za-z])/.exec(line.slice(i))
      if (m) {
        if (m[2] === "m") {
          const params = m[1] === "" ? ["0"] : m[1]!.split(";")
          for (const p of params) {
            if (p === "0" || p === "") dim = false
            else if (p === "2") dim = true
            else if (p === "22") dim = false
          }
        }
        i += m[0].length
        continue
      }
      i++ // lone/unknown ESC: drop it
      continue
    }
    if (!(opts.dropDim && dim)) out += line[i]
    i++
  }
  return out
}

// The composer's REAL content from an escape-preserving capture: the last
// unindented ❯ line in the tail plus wrapped continuation lines (until the
// next border rule), ghost text dropped, ALL whitespace removed. "❯" = empty.
function composerContent(rawText: string): string | null {
  const lines = rawText.split("\n")
  const tailStart = Math.max(0, lines.length - TAIL_LINES)
  let idx = -1
  for (let i = lines.length - 1; i >= tailStart; i--) {
    if (stripLine(lines[i]!, { dropDim: false }).startsWith("❯")) { idx = i; break }
  }
  if (idx < 0) return null
  let joined = stripLine(lines[idx]!, { dropDim: true })
  for (let i = idx + 1; i < Math.min(lines.length, idx + 4); i++) {
    const visible = stripLine(lines[i]!, { dropDim: false })
    if (visible.startsWith("─") || visible.startsWith("━")) break
    joined += stripLine(lines[i]!, { dropDim: true })
  }
  return joined.replace(/[\s ]+/g, "")
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
  const backend = () => seams?.backend ?? getSessionBackend()
  const send = seams?.sendKeysFn ?? ((id: string, keys: string[]) => backend().sendKeys(id, keys))
  const capture = seams?.capturePane ?? ((id: string) => backend().capture(id))
  const captureRaw = seams?.capturePaneRaw ?? seams?.capturePane ?? ((id: string) => backend().capture(id, true))

  // 1. Pane safety gate: composer visible, no dialog/menu. Poll briefly — a
  //    transient repaint can hide the prompt for a frame.
  let text: string | null = null
  const safetyDeadline = Date.now() + safetyWait
  while (true) {
    text = await capture(windowId)
    if (text === null) return { ok: false, error: "session window gone (no pane to capture)" }
    if (paneIsSafe(text)) break
    if (Date.now() >= safetyDeadline) {
      // Log the evidence — "why was it unsafe" must be answerable from the log.
      log.warn("live_switch_pane_unsafe", {
        windowId,
        part: part.label,
        reason: paneUnsafeReason(text),
        tail: tail(text).slice(-500),
      })
      return { ok: false, error: `pane not ready for ${part.label} switch (dialog or menu open)` }
    }
    await sleep(poll)
  }

  // 2. Clear any stray composer draft and PROVE the composer is empty before
  //    typing — a leftover draft would turn our command into a chat message.
  //    Uses the escape-preserving capture so a dim ghost autosuggestion (not
  //    real input; C-u can't clear it, typing replaces it) counts as empty.
  const baseline = count(text, part.marker)
  for (let attempt = 0; ; attempt++) {
    await send(windowId, ["C-u"])
    const after = await captureRaw(windowId)
    if (after === null) return { ok: false, error: "session window gone (no pane to capture)" }
    const cc = composerContent(after)
    if (cc === "❯") break
    if (attempt >= 1) {
      log.warn("live_switch_composer_not_empty", { windowId, part: part.label, composer: (cc ?? "<none>").slice(0, 120) })
      return { ok: false, error: "composer draft could not be cleared; switch aborted" }
    }
    await sleep(poll)
  }

  // 3. Type the command literally, then VERIFY the composer shows exactly the
  //    typed command before submitting — makes a garbage submit impossible no
  //    matter what unknown TUI state we're in. First wait doubles as the
  //    slash-autocomplete settle delay.
  await send(windowId, ["-l", part.command])
  const want = "❯" + part.command.replace(/\s+/g, "")
  let verified = false
  for (let attempt = 0; attempt < TYPED_VERIFY_ATTEMPTS; attempt++) {
    await sleep(typeDelay)
    const afterType = await captureRaw(windowId)
    if (afterType === null) return { ok: false, error: "session window gone (no pane to capture)" }
    if (composerContent(afterType) === want) { verified = true; break }
  }
  if (!verified) {
    await send(windowId, ["C-u"])
    log.warn("live_switch_composer_mismatch", { windowId, part: part.label, wanted: part.command })
    return { ok: false, error: `composer did not show the typed ${part.label} command; aborted before submit` }
  }
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
