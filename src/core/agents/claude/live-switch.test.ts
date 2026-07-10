import { test, expect } from "bun:test"
import { applyClaudeLiveSwitch } from "./live-switch"

const WID = "@42"

const IDLE_PANE = [
  "  some earlier transcript output",
  "────────",
  "❯ ",
  "────────",
  "  ⏸ manual mode on · ? for shortcuts",
].join("\n")

const IDLE_WITH_DRAFT = IDLE_PANE.replace("❯ ", "❯ some stray draft")

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

/** Seams: instant timings, scripted captures (last frame held), recorded sends. */
function seams(captures: (string | null)[], opts?: { safetyWaitMs?: number; confirmTimeoutMs?: number }) {
  const sent: string[][] = []
  let i = 0
  return {
    seams: {
      pollIntervalMs: 0,
      typeDelayMs: 0,
      menuRetryMs: 0,
      safetyWaitMs: opts?.safetyWaitMs ?? 5_000,
      confirmTimeoutMs: opts?.confirmTimeoutMs ?? 5_000,
      sendKeysFn: async (_wid: string, keys: string[]) => { sent.push(keys) },
      capturePane: async () => {
        const r = captures[i]
        if (i < captures.length - 1) i++ // hold last frame (deadline decides)
        return r === undefined ? null : r
      },
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

test("model switch happy path: C-u, literal command, Enter, success on marker", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,   // safety check
    IDLE_PANE,   // post C-u empty-composer check
    MODEL_OK,    // verify poll → marker appeared
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toEqual([["C-u"], ["-l", "/model claude-opus-4-8"], ["Enter"]])
})

test("stale confirmation in scrollback does NOT count (baseline delta)", async () => {
  // Pane already contains an old "Set model to" from a previous switch.
  const stale = MODEL_OK
  const fresh = MODEL_OK + "\n  ⎿  Set model to Sonnet 5 and saved as your default for new sessions"
  const { seams: s } = seams([
    stale,  // safety check (baseline = 1)
    stale,  // post C-u check
    stale,  // poll: no NEW marker yet
    fresh,  // poll: delta → success
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-sonnet-5" }, s)
  expect(r).toEqual({ ok: true })
})

test("effort switch with confirm menu: Enter confirms, then success", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,    // safety
    IDLE_PANE,    // post C-u
    EFFORT_MENU,  // poll → menu up → Enter
    EFFORT_OK,    // poll → marker → success
  ])
  const r = await applyClaudeLiveSwitch(WID, { effort: "low" }, s)
  expect(r).toEqual({ ok: true })
  expect(sent).toEqual([["C-u"], ["-l", "/effort low"], ["Enter"], ["Enter"]])
})

test("model then effort sequentially, each verified", async () => {
  const MODEL_THEN_BASE = MODEL_OK
  const BOTH_OK = MODEL_OK + "\n  ⎿  Set effort level to max (saved as your default for new sessions)"
  const { seams: s, sent } = seams([
    IDLE_PANE,        // model: safety
    IDLE_PANE,        // model: post C-u
    MODEL_THEN_BASE,  // model: success
    MODEL_THEN_BASE,  // effort: safety
    MODEL_THEN_BASE,  // effort: post C-u
    BOTH_OK,          // effort: success
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
    PERMISSION_DIALOG, // unsafe
    IDLE_PANE,         // safe now
    IDLE_PANE,         // post C-u
    MODEL_OK,          // success
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r).toEqual({ ok: true })
})

test("draft that C-u cannot clear fails WITHOUT typing the command", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,        // safety ok
    IDLE_WITH_DRAFT,  // post C-u: draft still there
    IDLE_WITH_DRAFT,  // post retry C-u: still there
  ])
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  // Two C-u attempts, but the /model command was never typed.
  expect(sent).toEqual([["C-u"], ["C-u"]])
})

test("timeout without confirmation cleans up with C-u and fails", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE, // safety
    IDLE_PANE, // post C-u
    IDLE_PANE, // poll: never confirms (held frame)
  ], { confirmTimeoutMs: 0 })
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8" }, s)
  expect(r.ok).toBe(false)
  expect(sent[sent.length - 1]).toEqual(["C-u"]) // cleanup
})

test("timeout with menu still up cleans up with Escape and fails", async () => {
  const { seams: s, sent } = seams([
    IDLE_PANE,    // safety
    IDLE_PANE,    // post C-u
    EFFORT_MENU,  // poll: menu up forever (held frame)
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
    IDLE_PANE, // model: safety
    IDLE_PANE, // model: post C-u
    IDLE_PANE, // model: never confirms
  ], { confirmTimeoutMs: 0 })
  const r = await applyClaudeLiveSwitch(WID, { model: "claude-opus-4-8", effort: "low" }, s)
  expect(r.ok).toBe(false)
  expect(sent.some((k) => k[1] === "/effort low")).toBe(false)
})
