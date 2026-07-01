import { test, expect } from "bun:test"
import { sendChannelConsentEnter } from "./post-spawn-keys"

const WINDOW_ID = "@7"

// ─── helpers ────────────────────────────────────────────────────────────────

/** Build fast-timing opts with injected seams. */
function fastSeams(opts: {
  capturePane: (windowId: string) => Promise<string | null>
  sendKeysFn?: (windowId: string, keys: string[]) => Promise<void>
  maxWaitMs?: number
  retryAfterMs?: number
  keyDelayMs?: number
}) {
  return {
    pollIntervalMs: 0,
    maxWaitMs: opts.maxWaitMs ?? 5_000,
    retryAfterMs: opts.retryAfterMs ?? 0,
    keyDelayMs: opts.keyDelayMs ?? 0,
    sendKeysFn: opts.sendKeysFn ?? (async () => {}),
    capturePane: opts.capturePane,
  }
}

/** Build a capturePane stub that steps through `responses` sequentially. */
function makeCapture(responses: (string | null)[]) {
  const captureIds: string[] = []
  let call = 0
  return {
    capturePane: async (windowId: string): Promise<string | null> => {
      captureIds.push(windowId)
      const r = responses[call++]
      return r !== undefined ? r : null
    },
    get captureIds() { return captureIds },
    callCount: () => call,
  }
}

// ─── scenarios ──────────────────────────────────────────────────────────────

test("returns immediately when Listening marker is already present", async () => {
  const { capturePane, captureIds } = makeCapture(["Listening for channel messages"])
  const sentIds: string[] = []

  await sendChannelConsentEnter(WINDOW_ID, fastSeams({
    capturePane,
    sendKeysFn: async (windowId) => { sentIds.push(windowId) },
  }))

  // Only one capture — using the window id, not a name string
  expect(captureIds).toEqual([WINDOW_ID])
  // No keys sent
  expect(sentIds).toHaveLength(0)
})

test("sends Enter (and retries) when consent marker present, returns when it clears", async () => {
  const { capturePane, captureIds, callCount } = makeCapture([
    "Please Enter to confirm the dev-channel connection",  // marker → send Enter (sent=1)
    "Please Enter to confirm the dev-channel connection",  // still up → retry Enter (sent=2)
    "Claude is ready, channel loaded",                      // marker gone, sent>0 → return
  ])
  const sentKeys: Array<{ windowId: string; keys: string[] }> = []

  await sendChannelConsentEnter(WINDOW_ID, fastSeams({
    capturePane,
    sendKeysFn: async (windowId, keys) => { sentKeys.push({ windowId, keys }) },
    retryAfterMs: 0,
  }))

  // capturePane always called with window id
  expect(captureIds.every(id => id === WINDOW_ID)).toBe(true)
  // sendKeysFn always called with window id
  expect(sentKeys.every(s => s.windowId === WINDOW_ID)).toBe(true)
  // At least 2 Enters (initial + one retry)
  expect(sentKeys.filter(s => s.keys.includes("Enter")).length).toBeGreaterThanOrEqual(2)
  // Each send was a single ["Enter"]
  expect(sentKeys.every(s => JSON.stringify(s.keys) === JSON.stringify(["Enter"]))).toBe(true)
  expect(callCount()).toBe(3)
})

test("sends ['2','Enter'] to dismiss the resume menu, then returns on LISTENING", async () => {
  const { capturePane } = makeCapture([
    "Resume from summary\nEnter to confirm",  // resume menu → ["2","Enter"]
    "Listening for channel messages",           // cleared → return
  ])
  const sentKeys: Array<{ windowId: string; keys: string[] }> = []

  await sendChannelConsentEnter(WINDOW_ID, fastSeams({
    capturePane,
    sendKeysFn: async (windowId, keys) => { sentKeys.push({ windowId, keys }) },
  }))

  expect(sentKeys).toHaveLength(1)
  expect(sentKeys[0]).toEqual({ windowId: WINDOW_ID, keys: ["2", "Enter"] })
})

test("accepts bypass warning with Down then Enter (never a bare Enter for 'No, exit')", async () => {
  const { capturePane } = makeCapture([
    "Bypass Permissions mode\n> 1. No, exit\n> 2. Yes, I accept",  // bypass warning
    "Listening for channel messages",                                 // cleared → return
  ])
  const sentKeys: Array<{ windowId: string; keys: string[] }> = []

  await sendChannelConsentEnter(WINDOW_ID, fastSeams({
    capturePane,
    sendKeysFn: async (windowId, keys) => { sentKeys.push({ windowId, keys }) },
    keyDelayMs: 0,
  }))

  // Down first, then Enter — each addressed to window id
  expect(sentKeys).toHaveLength(2)
  expect(sentKeys[0]).toEqual({ windowId: WINDOW_ID, keys: ["Down"] })
  expect(sentKeys[1]).toEqual({ windowId: WINDOW_ID, keys: ["Enter"] })
})

test("capturePane returning null is treated as empty text — loop keeps polling, no crash", async () => {
  const { capturePane, captureIds, callCount } = makeCapture([
    null,                              // window not yet available → coalesced to ""
    null,                              // still not available
    "Listening for channel messages",  // window ready → return
  ])
  const sentKeys: string[][] = []

  // Must not throw
  await sendChannelConsentEnter(WINDOW_ID, fastSeams({
    capturePane,
    sendKeysFn: async (_id, keys) => { sentKeys.push(keys) },
  }))

  // All three polls used the window id
  expect(captureIds.every(id => id === WINDOW_ID)).toBe(true)
  expect(callCount()).toBe(3)
  // No keys sent — null text has no markers
  expect(sentKeys).toHaveLength(0)
})

test("times out and returns without throwing when no prompt ever clears", async () => {
  let captureCall = 0
  let sentCount = 0

  await sendChannelConsentEnter(WINDOW_ID, {
    pollIntervalMs: 0,
    maxWaitMs: 50,
    retryAfterMs: 0,
    keyDelayMs: 0,
    sendKeysFn: async (windowId) => {
      expect(windowId).toBe(WINDOW_ID)
      sentCount++
    },
    capturePane: async (windowId) => {
      expect(windowId).toBe(WINDOW_ID)
      captureCall++
      return ""  // no markers ever
    },
  })

  // Polled at least once before deadline
  expect(captureCall).toBeGreaterThanOrEqual(1)
  // No keys sent (empty text → no markers match)
  expect(sentCount).toBe(0)
})
