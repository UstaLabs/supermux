import { test, expect } from "bun:test"
import { applyClaudeLiveSwitch } from "./live-switch"

const WID = "@42"
const ESC = "\x1b"

// Real mux panes render the composer as "❯" + NBSP (captured 2026-07-10).
const IDLE_PANE = [
  "  some earlier transcript output",
  "────────",
  "❯ ",
  "────────",
  "  ⏵⏵ bypass permissions on (shift+tab to  · ←…",
].join("\n")

const IDLE_WITH_DRAFT = IDLE_PANE.replace("❯ ", "❯ some stray draft")

// Ghost autosuggestion: dim (SGR 2) suggested prompt — NOT real input. Byte
// pattern taken from a live capture (`tmux capture-pane -e`).
const IDLE_WITH_GHOST_RAW = IDLE_PANE.replace(
  "❯ ",
  `${ESC}[39m❯ ${ESC}[2mclone the repo and read the orchestration co…`,
)
// The same pane WITHOUT -e: ghost is indistinguishable from a draft.
const IDLE_WITH_GHOST_PLAIN = IDLE_PANE.replace("❯ ", "❯ clone the repo and read the orchestration co…")

const typed = (cmd: string) => IDLE_PANE.replace("❯ ", `❯ ${cmd}`)

const MODEL_OK = IDLE_PANE + "\n  ⎿  Set model to Opus 4.8 and saved as your default for new sessions"
const EFFORT_OK = IDLE_PANE + "\n  ⎿  Set effort level to low (saved as your default for new sessions)"

const EFFORT_MENU = [
  "   Change effort level?",
  "   Your next response will be slower and use more tokens",
  "   ❯ 1. Yes, switch to low",
  "     2. No, go back",
].join("\n")

const PERMISSION_DIALOG = [
  "  Do you want to run this command?",
  "   ❯ 1. Yes",
  "     2. No",
].join("\n")

/** Seams: instant timings, scripted captures (last frame held), recorded sends.
 * The single capture script serves both plain and raw (-e) captures, in call
 * order — plain text is valid raw output with zero escapes. */
function seams(captures: (string | null)[], opts?: { safetyWaitMs?: number; confirmTimeoutMs?: number }) {
  const sent: string[][] = []
  let i = 0
  const capture = async () => {
    const r = captures[i]
    if (i < captures.length - 1) i++ // hold last frame (deadline decides)
    return r === undefined ? null : r
  }
  return {
    seams: {
      pollIntervalMs: 0,
      typeDelayMs: 0,
      menuRetryMs: 0,
      safetyWaitMs: opts?.safetyWaitMs ?? 5_000,
      confirmTimeoutMs: opts?.confirmTimeoutMs ?? 5_000,
      sendKeysFn: async (_wid: string, keys: string[]) => { sent.push(keys) },
      capturePane: capture,
    },
    sent,
  }
}

test("no-op target: ok, nothing sent or captured", async () => {
  const { seams: s, sent } = seams([IDLE_PANE])
  const r = await applyClaudeLiveSwitch(WID, {}, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toHaveLength(0)
})

test("model switch happy path: C-u, type, verify composer, Enter, success on marker", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,                        // safety check (plain)
    IDLE_PANE,                        // post C-u empty-composer check (raw)
    typed("/model claude-opus-4-8"),  // post-type composer verification (raw)
    MODEL_OK,                         // verify poll → marker appeared (plain)
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toEqual([["C-u"], ["-l", "/model claude-opus-4-8"], ["Enter"]])
})

test("ghost autosuggestion counts as empty: switch proceeds and succeeds", async () => {
  const { seams: s, sent } = seams([
    IDLE_WITH_GHOST_PLAIN,            // safety (plain: ghost looks like a draft — must not block)
    IDLE_WITH_GHOST_RAW,              // post C-u (raw: dim ghost → stripped → empty)
    typed("/effort max"),             // post-type verify (typing dismissed the ghost)
    IDLE_PANE + "\n  ⎿  Set effort level to max (saved as your default for new sessions)",
  ])
  const r = await applyClaudeLiveSwitch(WID, { effort: "max" }, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toEqual([["C-u"], ["-l", "/effort max"], ["Enter"]])
})

test("post-type composer mismatch aborts BEFORE Enter", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,                              // safety
    IDLE_PANE,                              // post C-u: empty
    typed("leftover junk /model claude-opus-4-8"), // post-type: composer does NOT show exactly the command (held)
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  expect(sent.some((k) => k[0] === "Enter")).toBe(false) // never submitted
  expect(sent[sent.length - 1]).toEqual(["C-u"])         // cleaned up the typed text
})

test("wrapped typed command across two lines still verifies", async () => {
  const wrapped = [
    "  some earlier transcript output",
    "────────",
    "❯ /model claude-haiku-",
    "4-5-20251001",
    "────────",
    "  ⏵⏵ bypass permissions on",
  ].join("\n")
  const { seams: s } = seams([
    IDLE_PANE,  // safety
    IDLE_PANE,  // post C-u
    wrapped,    // post-type verify (wrapped)
    IDLE_PANE + "\n  ⎿  Set model to Haiku 4.5 and saved as your default for new sessions",
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-haiku-4-5-20251001" }, s)
  expect(r).toEqual({ ok: true })
})

test("stale confirmation in scrollback does NOT count (baseline delta)", async () => {
  const stale = MODEL_OK
  const fresh = MODEL_OK + "\n  ⎿  Set model to Sonnet 5 and saved as your default for new sessions"
  const { seams: s } = seams([
    stale,                            // safety (baseline = 1)
    stale,                            // post C-u check
    typed("/model claude-sonnet-5") + "\n  ⎿  Set model to Opus 4.8 and saved as your default for new sessions", // post-type (stale marker still visible)
    stale,                            // poll: no NEW marker yet
    fresh,                            // poll: delta → success
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-sonnet-5" }, s)
  expect(r).toEqual({ ok: true })
})

test("effort switch with confirm menu: Enter confirms, then success", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,             // safety
    IDLE_PANE,             // post C-u
    typed("/effort low"),  // post-type verify
    EFFORT_MENU,           // poll → menu up → Enter
    EFFORT_OK,             // poll → marker → success
  ])
  const r = await applyClaudeLiveSwitch(WID, { effort: "low" }, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toEqual([["C-u"], ["-l", "/effort low"], ["Enter"], ["Enter"]])
})

test("model then effort sequentially, each verified", async () => {
  const BOTH_OK = MODEL_OK + "\n  ⎿  Set effort level to max (saved as your default for new sessions)"
  const { seams: s, sent } = seams([
    IDLE_PANE,                                      // model: safety
    IDLE_PANE,                                      // model: post C-u
    typed("/model claude-opus-4-8"),                // model: post-type verify
    MODEL_OK,                                       // model: success
    MODEL_OK,                                       // effort: safety
    MODEL_OK,                                       // effort: post C-u (composer empty again)
    MODEL_OK.replace("❯ ", "❯ /effort max"),   // effort: post-type verify
    BOTH_OK,                                        // effort: success
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8", effort: "max" }, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toEqual([
    ["C-u"], ["-l", "/model claude-opus-4-8"], ["Enter"],
    ["C-u"], ["-l", "/effort max"], ["Enter"],
  ])
})

test("unsafe pane (permission dialog) fails without typing", async () => {
  const { seams: s, sent } = seams([PERMISSION_DIALOG], { safetyWaitMs: 0 })
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  expect(sent).toHaveLength(0)
})

test("unsafe pane that clears within the safety window proceeds", async () => {
  const { seams: s } = seams([
    PERMISSION_DIALOG,                // unsafe
    IDLE_PANE,                        // safe now
    IDLE_PANE,                        // post C-u
    typed("/model claude-opus-4-8"),  // post-type verify
    MODEL_OK,                         // success
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r).toEqual({ ok: true })
})

test("real draft that C-u cannot clear fails WITHOUT typing the command", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,        // safety ok
    IDLE_WITH_DRAFT,  // post C-u: real (non-dim) draft still there
    IDLE_WITH_DRAFT,  // post retry C-u: still there
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  expect(sent).toEqual([["C-u"], ["C-u"]])
})

test("timeout without confirmation cleans up with C-u and fails", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,                        // safety
    IDLE_PANE,                        // post C-u
    typed("/model claude-opus-4-8"),  // post-type verify
    IDLE_PANE,                        // poll: never confirms (held frame)
  ], { confirmTimeoutMs: 0 })
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  expect(sent[sent.length - 1]).toEqual(["C-u"]) // cleanup
})

test("timeout with menu still up cleans up with Escape and fails", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,             // safety
    IDLE_PANE,             // post C-u
    typed("/effort low"),  // post-type verify
    EFFORT_MENU,           // poll: menu up forever (held frame)
  ], { confirmTimeoutMs: 0 })
  const r = await applyClaudeLiveSwitch(WID, { effort: "low" }, s)
  expect(r.ok).toBe(false)
  expect(sent[sent.length - 1]).toEqual(["Escape"]) // cancel the menu
})

test("null capture fails with window-gone error", async () => {
  const { seams: s } = seams([null])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  if (!r.ok) expect(r.error).toContain("window")
})

test("first part failing aborts the second (no effort typing after model failure)", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,                        // model: safety
    IDLE_PANE,                        // model: post C-u
    typed("/model claude-opus-4-8"),  // model: post-type verify
    IDLE_PANE,                        // model: never confirms
  ], { confirmTimeoutMs: 0 })
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8", effort: "low" }, s)
  expect(r.ok).toBe(false)
  expect(sent.some((k) => k[1] === "/effort low")).toBe(false)
})
