// src/core/inbox/delivery.test.ts
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { InboxStore } from "./store"
import { InboxDelivery, envelope, type InboxDeliveryDeps } from "./delivery"
import type { Session } from "../session-manager/types"
import type { InboundDeliveryResult } from "../session-manager/inbound-delivery"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

const A = { name: "Fix login", id: "aaaa-1" }

function harness(rows: Record<string, Partial<Session>>, over: Partial<InboxDeliveryDeps> = {}) {
  const dir = mkdtempSync(join(tmpdir(), "inbox-delivery-"))
  dirs.push(dir)
  const store = new InboxStore(dir)
  const cursor = new Map<string, string>()
  const delivered: Array<{ id: string; text: string; meta: Record<string, string> }> = []
  const logged: Array<{ id: string; entry: unknown }> = []
  const calls: string[] = []
  const deps: InboxDeliveryDeps = {
    store,
    cursor: { get: (id) => cursor.get(id), set: (id, m) => { cursor.set(id, m) } },
    messageLog: { append: (id, entry) => { logged.push({ id, entry }) } },
    session: (id) => (rows[id] ? ({ id, name: id, status: "active", ...rows[id] } as Session) : undefined),
    resumeFromArchive: async (id) => { calls.push(`archive:${id}`); rows[id]!.status = "active"; return { ok: true } },
    resumeSuspended: async (s) => { calls.push(`suspended:${s.id}`); rows[s.id]!.status = "active"; return true },
    adapterState: () => "alive",
    resumeAdapter: async (id) => { calls.push(`adapter:${id}`) },
    waitDeliverable: async () => true,
    deliver: async (id, text, meta) => { delivered.push({ id, text, meta }); return { ok: true } },
    ...over,
  }
  return { delivery: new InboxDelivery(deps), store, cursor, delivered, logged, calls }
}

const bodies = (d: Array<{ text: string }>) => d.map((x) => x.text.split("\n").slice(1).join("\n"))

test("send appends to the recipient's file and delivers it with the envelope, then marks it delivered", async () => {
  const h = harness({ "bbbb-2": {} })
  const { ids } = h.delivery.send(A, ["bbbb-2"], "review src/auth", "queue")
  await h.delivery.idle()
  const [m] = h.store.read("bbbb-2")
  expect(m).toMatchObject({ id: ids["bbbb-2"], sender: A, text: "review src/auth" })
  expect(h.delivered).toEqual([{
    id: "bbbb-2",
    text: envelope(m!),
    meta: { message_id: `inbox:${m!.id}`, ts: m!.at, user: "Fix login", user_id: "aaaa-1", system_generated: "inbox", busy: "queue" },
  }])
  expect(envelope(m!)).toBe(`Message from [Fix login:aaaa-1] (${m!.id}):\nreview src/auth`)
  expect(h.cursor.get("bbbb-2")).toBe(m!.id)
})

test("an unknown recipient fails the whole call and writes nothing", () => {
  const h = harness({ "bbbb-2": {} })
  expect(() => h.delivery.send(A, ["bbbb-2", "nope-9"], "x", "queue")).toThrow("unknown session id: nope-9")
  expect(h.store.read("bbbb-2")).toEqual([])
})

test("several recipients each get the message; a repeated id gets it once", async () => {
  const h = harness({ "bbbb-2": {}, "cccc-3": {} })
  h.delivery.send(A, ["bbbb-2", "cccc-3", "bbbb-2"], "hi", "queue")
  await h.delivery.idle()
  expect(h.delivered.map((d) => d.id).sort()).toEqual(["bbbb-2", "cccc-3"])
})

test("an archived recipient is restored from the archive before delivery", async () => {
  const h = harness({ "bbbb-2": { status: "archived" } })
  h.delivery.send(A, ["bbbb-2"], "x", "queue")
  await h.delivery.idle()
  expect(h.calls).toEqual(["archive:bbbb-2"])
  expect(h.delivered).toHaveLength(1)
})

test("a suspended recipient is resumed before delivery", async () => {
  const h = harness({ "bbbb-2": { status: "suspended" } })
  h.delivery.send(A, ["bbbb-2"], "x", "queue")
  await h.delivery.idle()
  expect(h.calls).toEqual(["suspended:bbbb-2"])
  expect(h.delivered).toHaveLength(1)
})

test("a crashed recipient's adapter is resumed before delivery", async () => {
  const h = harness({ "bbbb-2": {} }, { adapterState: () => "dead" })
  h.delivery.send(A, ["bbbb-2"], "x", "queue")
  await h.delivery.idle()
  expect(h.calls).toEqual(["adapter:bbbb-2"])
  expect(h.delivered).toHaveLength(1)
})

test("each message keeps its own busy mode, in order", async () => {
  const h = harness({ "bbbb-2": {} })
  h.delivery.send(A, ["bbbb-2"], "one", "queue")
  h.delivery.send(A, ["bbbb-2"], "two", "interrupt")
  await h.delivery.idle()
  expect(bodies(h.delivered)).toEqual(["one", "two"])
  expect(h.delivered.map((d) => d.meta.busy)).toEqual(["queue", "interrupt"])
})

test("when the recipient can't be made deliverable nothing is marked delivered, and the next send retries", async () => {
  let ready = false
  const h = harness({ "bbbb-2": {} }, { waitDeliverable: async () => ready })
  h.delivery.send(A, ["bbbb-2"], "one", "interrupt")
  await h.delivery.idle()
  expect(h.delivered).toEqual([])
  expect(h.cursor.get("bbbb-2")).toBeUndefined()
  ready = true
  h.delivery.send(A, ["bbbb-2"], "two", "queue")
  await h.delivery.idle()
  expect(bodies(h.delivered)).toEqual(["one", "two"])
  expect(h.delivered.map((d) => d.meta.busy)).toEqual(["interrupt", "queue"])
})

test("recoverOnStartup delivers only what comes after the cursor, queued", async () => {
  const h = harness({ "bbbb-2": {} })
  const old = h.store.append("bbbb-2", { sender: A, text: "old" })
  h.store.append("bbbb-2", { sender: A, text: "new" })
  h.cursor.set("bbbb-2", old.id)
  await h.delivery.recoverOnStartup()
  expect(bodies(h.delivered)).toEqual(["new"])
  expect(h.delivered[0]!.meta.busy).toBe("queue")
})

test("recoverOnStartup skips inboxes whose session no longer exists", async () => {
  const h = harness({})
  h.store.append("gone-1", { sender: A, text: "x" })
  await h.delivery.recoverOnStartup()
  expect(h.delivered).toEqual([])
})

test("each delivered message is logged with an empty chat_id and the sender's id", async () => {
  const h = harness({ "bbbb-2": {} })
  h.delivery.send(A, ["bbbb-2"], "hello", "queue")
  await h.delivery.idle()
  const [m] = h.store.read("bbbb-2")
  expect(h.logged).toEqual([{
    id: "bbbb-2",
    entry: { id: `in:inbox:${m!.id}`, ts: m!.at, direction: "inbound", channel: "inbox", chat_id: "", message_id: m!.id, text: "hello", from_session_id: "aaaa-1" },
  }])
})

test("busy goes through as sent, even while earlier turns are open: the adapter picks the turn each interrupt ends", async () => {
  const seen: string[] = []
  const h = harness({ "bbbb-2": {} }, {
    deliver: (_id, _text, meta) => { seen.push(meta.busy ?? ""); return new Promise<InboundDeliveryResult>(() => {}) }, // turns stay open
  })
  h.delivery.send(A, ["bbbb-2"], "a", "interrupt")
  h.delivery.send(A, ["bbbb-2"], "b", "interrupt")
  await h.delivery.idle()
  h.delivery.send(A, ["bbbb-2"], "c", "interrupt")
  await h.delivery.idle()
  expect(seen).toEqual(["interrupt", "interrupt", "interrupt"])
})

// ---- the delivery passes ----

test("a burst of sends to a recipient that can't be reached leaves nothing running, and a later send delivers all of it in order", async () => {
  let ready = false
  const h = harness({ "bbbb-2": {} }, { waitDeliverable: async () => ready })
  for (const text of ["1", "2", "3"]) h.delivery.send(A, ["bbbb-2"], text, "queue")
  await h.delivery.idle() // hangs if a failed pass leaves its chain behind
  expect(h.delivered).toEqual([])
  ready = true
  h.delivery.send(A, ["bbbb-2"], "4", "queue")
  await h.delivery.idle()
  expect(bodies(h.delivered)).toEqual(["1", "2", "3", "4"])
  expect(h.cursor.get("bbbb-2")).toBe(h.store.read("bbbb-2").at(-1)!.id)
})
