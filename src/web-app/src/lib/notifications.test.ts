import { test, expect } from "bun:test"
import { notificationTag, clearChatNotifications } from "./notifications"

test("notificationTag matches the service worker's per-session tag format", () => {
  // MUST stay in lockstep with sw.ts: `tag: cmux:${sessionId}`.
  expect(notificationTag("abc-123")).toBe("cmux:abc-123")
})

test("clearChatNotifications closes exactly the open chat's delivered notifications", async () => {
  const closed: string[] = []
  const makeNote = (tag: string) => ({ tag, close() { closed.push(tag) } })
  const store: Record<string, any[]> = {
    "cmux:abc-123": [makeNote("cmux:abc-123"), makeNote("cmux:abc-123")],
    "cmux:other": [makeNote("cmux:other")],
  }
  const reg = {
    getNotifications: async (opts: { tag?: string }) => store[opts.tag ?? ""] ?? [],
  }
  await clearChatNotifications(reg as any, "abc-123")
  // Both of abc-123's notifications closed; the other chat's untouched.
  expect(closed).toEqual(["cmux:abc-123", "cmux:abc-123"])
})

test("clearChatNotifications is a no-op for an empty session id (never touches the SW)", async () => {
  let called = false
  const reg = { getNotifications: async () => { called = true; return [] } }
  await clearChatNotifications(reg as any, "")
  expect(called).toBe(false)
})
