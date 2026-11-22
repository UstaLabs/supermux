// #163: the one push decision and the per-account settings it reads.
import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { NEW_ACCOUNT, NotificationPrefsStore, decidePush, parsePatch, type NotificationPrefs } from "./notification-prefs"

const prefs = (over: Partial<NotificationPrefs> = {}): NotificationPrefs => ({ ...NEW_ACCOUNT, overrides: { session: {}, applet: {} }, ...over })
const agent = { kind: "session" as const, id: "sage", agent: true }
const session = { kind: "session" as const, id: "s1", agent: false }
const applet = { kind: "applet" as const, id: "issues" }

test("a new account: every agent reply, a session only when it needs you, every applet notice", () => {
  const p = prefs()
  expect(decidePush(p, agent, "reply")).toEqual({ send: true, rule: "group", level: "all" })
  expect(decidePush(p, session, "reply")).toEqual({ send: false, rule: "group", level: "needs" })
  expect(decidePush(p, session, "request")).toEqual({ send: true, rule: "group", level: "needs" })
  expect(decidePush(p, applet, "applet")).toEqual({ send: true, rule: "group", level: "on" })
  expect(decidePush(p, { kind: "supermux" }, "notice")).toEqual({ send: true, rule: "notices" })
})

test("Needs me is only what blocks: a question or permission prompt, or an applet notice marked needsYou", () => {
  const p = prefs({ agents: "needs", applets: "off", needsYou: false })
  expect(decidePush(p, agent, "reply").send).toBe(false)
  expect(decidePush(p, agent, "request").send).toBe(true)
  // An applet at Off with needsYou off stays quiet even for a needsYou notice.
  expect(decidePush(p, applet, "applet", true).send).toBe(false)
  expect(decidePush(prefs({ applets: "off" }), applet, "applet", true)).toEqual({ send: true, rule: "breakthrough", level: "off" })
})

test("Off: a blocking request breaks through unless the person turned that off", () => {
  expect(decidePush(prefs({ sessions: "off" }), session, "request")).toEqual({ send: true, rule: "breakthrough", level: "off" })
  expect(decidePush(prefs({ sessions: "off" }), session, "reply")).toEqual({ send: false, rule: "group", level: "off" })
  expect(decidePush(prefs({ sessions: "off", needsYou: false }), session, "request").send).toBe(false)
})

test("an item's own level wins over its group, either way", () => {
  const p = prefs({ overrides: { session: { sage: "off", s1: "all" }, applet: { issues: "off" } } })
  expect(decidePush(p, agent, "reply")).toEqual({ send: false, rule: "override", level: "off" })
  expect(decidePush(p, session, "reply")).toEqual({ send: true, rule: "override", level: "all" })
  expect(decidePush(p, applet, "applet")).toEqual({ send: false, rule: "override", level: "off" })
  expect(decidePush({ ...p, notices: false }, { kind: "supermux" }, "notice").send).toBe(false)
})

test("the store: defaults without a row, a patch from one device is the account's, null clears an item", () => {
  const db = openDb(":memory:"); runMigrations(db, MIGRATIONS)
  const store = new NotificationPrefsStore(db)
  expect(store.get("owner")).toEqual(prefs())
  const after = store.apply("owner", { agents: "needs", needsYou: false, session: { s1: "off" }, applet: { issues: "off" } }, '{"kind":"device","name":"phone"}')
  expect(after).toMatchObject({ agents: "needs", sessions: "needs", applets: "on", needsYou: false, overrides: { session: { s1: "off" }, applet: { issues: "off" } }, updatedBy: '{"kind":"device","name":"phone"}' })
  expect(store.get("someone-else")).toEqual(prefs())
  const cleared = store.setItem("owner", "session", "s1", null)
  expect(cleared.overrides.session).toEqual({})
  expect(cleared.agents).toBe("needs")
  store.forget("applet", "issues")
  expect(store.get("owner").overrides.applet).toEqual({})
})

test("parsePatch takes only known values", () => {
  expect(parsePatch({ agents: "needs", session: { a: null, b: "off" }, applet: { x: "on" }, showTip: false })).toEqual({ ok: true, patch: { agents: "needs", session: { a: null, b: "off" }, applet: { x: "on" }, showTip: false } })
  expect(parsePatch({ agents: "on" }).ok).toBe(false)
  expect(parsePatch({ applets: "needs" }).ok).toBe(false)
  expect(parsePatch({ needsYou: "yes" }).ok).toBe(false)
  expect(parsePatch({ session: { a: "on" } }).ok).toBe(false)
  expect(parsePatch({ applet: ["x"] }).ok).toBe(false)
  expect(parsePatch(null).ok).toBe(false)
})
