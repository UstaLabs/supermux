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

test("two concurrent chats both suppress when sent as one set", () => {
  // The multi-chat workspace layout shows two chats at once. It sends the whole
  // visible set in ONE frame rather than two `true` frames — accumulating on
  // bare `true` would break every single-session client (see the replace test
  // at the bottom of this file).
  const t = new ViewingTracker()
  t.setSessions("iphone", ["ana", "other"], true)
  expect(t.isViewing("web:iphone", "ana")).toBe(true)
  expect(t.isViewing("web:iphone", "other")).toBe(true)
})

test("clear then add rebuilds the set (classic switch)", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "ana", visible: true })
  t.update("iphone", { session: null, visible: false })
  t.update("iphone", { session: "other", visible: true })
  expect(t.isViewing("web:iphone", "ana")).toBe(false)
  expect(t.isViewing("web:iphone", "other")).toBe(true)
})

test("setSessions installs a concurrent pair in one shot", () => {
  const t = new ViewingTracker()
  t.setSessions("iphone", ["s1", "s2"], true)
  expect(t.isAnyExactViewing("s1")).toBe(true)
  expect(t.isAnyExactViewing("s2")).toBe(true)
  expect(t.isPresentFor("iphone", "s3")).toBe(false)
})

test("visible=false for one session removes only that session", () => {
  const t = new ViewingTracker()
  t.setSessions("iphone", ["s1", "s2"], true)
  t.update("iphone", { session: "s2", visible: false })
  expect(t.isAnyExactViewing("s1")).toBe(true)
  expect(t.isAnyExactViewing("s2")).toBe(false)
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

// isAnyExactViewing drives read-status: stricter than push suppression. Sitting
// on the chat list (session=null) suppresses push but must NOT mark a chat read.
test("isAnyExactViewing: exact session + visible → true", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "ana", visible: true })
  expect(t.isAnyExactViewing("ana")).toBe(true)
})

test("isAnyExactViewing: on the list (session=null) does NOT count as reading", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: null, visible: true })
  expect(t.isAnyExactViewing("ana")).toBe(false)
})

test("isAnyExactViewing: a different session → false", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "zoom", visible: true })
  expect(t.isAnyExactViewing("ana")).toBe(false)
})

test("isAnyExactViewing: backgrounded (not visible) → false", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: "ana", visible: false })
  expect(t.isAnyExactViewing("ana")).toBe(false)
})

test("isAnyExactViewing: expired entry → false", () => {
  const t = new ViewingTracker({ ttlMs: -1 })
  t.update("iphone", { session: "ana", visible: true })
  expect(t.isAnyExactViewing("ana")).toBe(false)
})

test("isAnyExactViewing: true when any one of several devices exact-views", () => {
  const t = new ViewingTracker()
  t.update("iphone", { session: null, visible: true })   // on the list
  t.update("laptop", { session: "ana", visible: true })  // actually viewing
  expect(t.isAnyExactViewing("ana")).toBe(true)
})

// ── Regression guard: shipped clients rely on replace-on-true ────────────────
// Web useViewing, iOS, Android and macOS all switch chats by sending only
// Viewing(s2, true). If that ADDED instead of REPLACING, s1 would stay in the
// set forever and its push notifications would be suppressed on that device for
// the rest of the session — silent, and only noticed as "I stopped getting
// notified about that chat".

test("switching chats with a bare visible=true replaces, never accumulates", () => {
  const t = new ViewingTracker()
  t.update("dev1", { session: "s1", visible: true })
  t.update("dev1", { session: "s2", visible: true })

  expect(t.isPresentFor("dev1", "s2")).toBe(true)
  expect(t.isPresentFor("dev1", "s1")).toBe(false)
})

test("a multi-chat client sets its whole visible set at once", () => {
  const t = new ViewingTracker()
  t.setSessions("dev1", ["s1", "s2"], true)

  expect(t.isPresentFor("dev1", "s1")).toBe(true)
  expect(t.isPresentFor("dev1", "s2")).toBe(true)
  expect(t.isPresentFor("dev1", "s3")).toBe(false)
})

test("a multi-chat client shrinking its set drops the chat that left the screen", () => {
  const t = new ViewingTracker()
  t.setSessions("dev1", ["s1", "s2"], true)
  t.setSessions("dev1", ["s2"], true)

  expect(t.isPresentFor("dev1", "s1")).toBe(false)
  expect(t.isPresentFor("dev1", "s2")).toBe(true)
})

test("visible=false removes only that session", () => {
  const t = new ViewingTracker()
  t.setSessions("dev1", ["s1", "s2"], true)
  t.update("dev1", { session: "s1", visible: false })

  expect(t.isPresentFor("dev1", "s1")).toBe(false)
  expect(t.isPresentFor("dev1", "s2")).toBe(true)
})
