import { test, expect } from "bun:test"
import { ViewingTracker } from "./viewing-tracker"

test("isViewing returns false when no entry exists", () => {
  const t = new ViewingTracker()
  expect(t.isViewing("web:iphone", "ana")).toBe(false)
})

test("isViewing returns true when entry matches session and visible=true", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "ana", visible: true })
  expect(t.isViewing("web:iphone", "ana")).toBe(true)
})

test("isViewing returns false when visible is false even if session matches", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "ana", visible: false })
  expect(t.isViewing("web:iphone", "ana")).toBe(false)
})

test("isViewing returns false when session differs", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "other", visible: true })
  expect(t.isViewing("web:iphone", "ana")).toBe(false)
})

test("session=null + visible means 'on the list' → present (suppresses)", () => {
  // New presence semantics: a foregrounded device sitting on the chat list
  // (session=null) is present for any session, so a push would be redundant.
  const t = new ViewingTracker()
  t.update("iphone", { session: null, visible: true })
  expect(t.isViewing("web:iphone", "ana")).toBe(true)
})

test("isViewing returns false when entry is older than ttlMs", async () => {
  const t = new ViewingTracker({ ttlMs: 20 })
  t.update("iphone", { session: "ana", visible: true })
  expect(t.isViewing("web:iphone", "ana")).toBe(true)
  await new Promise((r) => setTimeout(r, 40))
  expect(t.isViewing("web:iphone", "ana")).toBe(false)
})

test("clear removes the entry", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "ana", visible: true })
  expect(t.isViewing("web:iphone", "ana")).toBe(true)
  t.clear("iphone")
  expect(t.isViewing("web:iphone", "ana")).toBe(false)
})

test("chat_id not starting with web: always returns false", () => {
  const t = new ViewingTracker()
  t.update("8264", { session: "ana", visible: true })
  expect(t.isViewing("telegram:8264", "ana")).toBe(false)
  expect(t.isViewing("8264", "ana")).toBe(false)
  expect(t.isViewing("", "ana")).toBe(false)
})

test("update replaces previous state for same device", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "ana", visible: true })
  t.update("iphone", { session: "other", visible: true })
  expect(t.isViewing("web:iphone", "ana")).toBe(false)
  expect(t.isViewing("web:iphone", "other")).toBe(true)
})

test("isPresentFor: viewing the target session suppresses", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "ana", visible: true })
  expect(t.isPresentFor("iphone", "ana")).toBe(true)
})

test("isPresentFor: on the list (session=null, visible) suppresses any session", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: null, visible: true })
  expect(t.isPresentFor("iphone", "ana")).toBe(true)
  expect(t.isPresentFor("iphone", "zoom")).toBe(true)
})

test("isPresentFor: viewing a DIFFERENT session does NOT suppress", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "zoom", visible: true })
  expect(t.isPresentFor("iphone", "ana")).toBe(false)
})

test("isPresentFor: backgrounded does NOT suppress", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: null, visible: false })
  expect(t.isPresentFor("iphone", "ana")).toBe(false)
})

test("isPresentFor: TTL expiry → not present", () => {
  const t = new ViewingTracker({ ttlMs: -1 })
  t.update("iphone", { session: "ana", visible: true })
  expect(t.isPresentFor("iphone", "ana")).toBe(false)
})
