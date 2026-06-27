import { test, expect } from "bun:test"
import { watchRowExtras } from "./watch-session-row"

test("phase and tool pass through", () => {
  const e = watchRowExtras({ phase: "running", tool: "Bash" }, undefined, undefined)
  expect(e.phase).toBe("running")
  expect(e.tool).toBe("Bash")
})

test("unread: true when there's a last message and no read pointer", () => {
  const e = watchRowExtras(undefined, { ts: "2026-06-27T04:00:00Z", direction: "outbound", text: "hi" }, undefined)
  expect(e.unread).toBe(true)
})

test("unread: false when read pointer is at/after the last message", () => {
  const e = watchRowExtras(undefined, { ts: "2026-06-27T04:00:00Z", direction: "outbound", text: "hi" }, "2026-06-27T04:00:00Z")
  expect(e.unread).toBe(false)
})

test("unread: true when last message is newer than the read pointer", () => {
  const e = watchRowExtras(undefined, { ts: "2026-06-27T05:00:00Z", direction: "outbound", text: "hi" }, "2026-06-27T04:00:00Z")
  expect(e.unread).toBe(true)
})

test("lastFrom derives from the direction prefix", () => {
  expect(watchRowExtras(undefined, { ts: "t", direction: "inbound" }, undefined).lastFrom).toBe("in")
  expect(watchRowExtras(undefined, { ts: "t", direction: "outbound" }, undefined).lastFrom).toBe("out")
})

test("lastText truncates to the preview cap with an ellipsis", () => {
  const e = watchRowExtras(undefined, { ts: "t", direction: "outbound", text: "x".repeat(200) }, undefined)
  expect(e.lastText!.length).toBeLessThanOrEqual(120)
  expect(e.lastText!.endsWith("…")).toBe(true)
})

test("no last message → undefined preview and not unread", () => {
  const e = watchRowExtras({ phase: "idle" }, undefined, undefined)
  expect(e.lastText).toBeUndefined()
  expect(e.unread).toBe(false)
})
