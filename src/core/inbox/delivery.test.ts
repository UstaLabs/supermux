import { afterEach, expect, spyOn, test } from "bun:test"
import { Database } from "bun:sqlite"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { InboxStore } from "./store"
import { InboxDelivery, envelope, type InboxDeliveryDeps } from "./delivery"
import type { Session } from "../session-manager/types"
import type { Message } from "../session-manager/messages"
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
const tick = () => new Promise<void>((resolve) => setTimeout(resolve, 0))

/** What the logger wrote to stderr (info level and up) while `fn` ran. */
async function stderrDuring(fn: () => Promise<void>): Promise<string> {
  const level = process.env.MUX_LOG_LEVEL
  process.env.MUX_LOG_LEVEL = "info"
  const stderr = spyOn(process.stderr, "write")
  try {
    await fn()
    return stderr.mock.calls.map((call) => String(call[0])).join("")
  } finally {
    stderr.mockRestore()
    if (level === undefined) delete process.env.MUX_LOG_LEVEL
    else process.env.MUX_LOG_LEVEL = level
  }
}

/** What SQLite throws for a row whose id is already there: a message logged twice. */
function duplicateRowError(): Error {
  const db = new Database(":memory:")
  try {
    db.run("CREATE TABLE messages (id TEXT PRIMARY KEY)")
    db.run("INSERT INTO messages (id) VALUES ('m')")
    db.run("INSERT INTO messages (id) VALUES ('m')")
  } catch (err) {
    return err as Error
  } finally {
    db.close()
  }
  throw new Error("expected a constraint error")
}

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

test("wake restores an archived session once, leaves a live deliverable one alone, and rejects an unknown id", async () => {
  const h = harness({ "bbbb-2": { status: "archived" }, "cccc-3": {} })
  await h.delivery.wake("bbbb-2")
  expect(h.calls).toEqual(["archive:bbbb-2"])
  await h.delivery.wake("cccc-3")
  expect(h.calls).toEqual(["archive:bbbb-2"])
  await expect(h.delivery.wake("nope-9")).rejects.toThrow("no longer exists")
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

test("each delivered message is logged with an empty chat_id and the sender's id and name", async () => {
  const h = harness({ "bbbb-2": {} })
  h.delivery.send(A, ["bbbb-2"], "hello", "queue")
  await h.delivery.idle()
  const [m] = h.store.read("bbbb-2")
  expect(h.logged).toEqual([{
    id: "bbbb-2",
    entry: { id: `in:inbox:${m!.id}`, ts: expect.any(String), direction: "inbound", channel: "inbox", chat_id: "", message_id: m!.id, text: "hello", from_session_id: "aaaa-1", from_session_name: "Fix login" },
  }])
})

test("a message is logged when it is handed over, naming its sender and the sender's agent; supermux's notice has no agent", async () => {
  const h = harness({ "bbbb-2": {} }, { agentOf: (id) => (id === A.id ? "codex" : undefined) })
  // Sent a minute ago, handed over now: it waited behind a turn.
  const sentAt = new Date(Date.now() - 60_000)
  const m = h.store.append("bbbb-2", { sender: A, text: "hello" }, sentAt)
  h.store.append("bbbb-2", { sender: { name: "supermux", id: "supermux" }, text: "Your subagent crashed" }, sentAt)
  const handedOverAfterMs = Date.now()
  await h.delivery.recoverOnStartup()
  const [row, notice] = h.logged.map((l) => l.entry as Message)
  expect(row).toMatchObject({
    id: `in:inbox:${m.id}`,
    direction: "inbound",
    channel: "inbox",
    from_session_id: A.id,
    from_session_name: A.name,
    from_agent: "codex",
  })
  // Logged when it was handed over, not when it was sent: a message queued behind a turn sits after it.
  expect(Date.parse(row!.ts)).toBeGreaterThanOrEqual(handedOverAfterMs)
  expect(notice).toMatchObject({ from_session_id: "supermux", from_session_name: "supermux" })
  expect(notice).not.toHaveProperty("from_agent")
  // What the agent is told keeps the send time.
  expect(h.delivered[0]!.meta.ts).toBe(sentAt.toISOString())
})

test("a message sent with a council tag is logged with its council, round and letter; a plain send logs no council fields", async () => {
  const h = harness({ "bbbb-2": {} })
  h.delivery.send(A, ["bbbb-2"], "x", "queue", { id: "c1", round: 1, letter: "A" })
  h.delivery.send(A, ["bbbb-2"], "y", "queue")
  await h.delivery.idle()
  const [tagged, plain] = h.logged.map((l) => l.entry)
  expect(tagged).toMatchObject({ text: "x", council_id: "c1", council_round: 1, council_letter: "A" })
  expect(plain).toMatchObject({ text: "y" })
  for (const key of ["council_id", "council_round", "council_letter"]) expect(plain).not.toHaveProperty(key)
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

test("sends while a pass waits for the recipient line up one more pass, not one each", async () => {
  const waits: Array<(ready: boolean) => void> = []
  const h = harness({ "bbbb-2": {} }, { waitDeliverable: () => new Promise<boolean>((resolve) => { waits.push(resolve) }) })
  h.delivery.send(A, ["bbbb-2"], "1", "queue")
  await tick()
  expect(waits).toHaveLength(1) // the first pass waits
  for (const text of ["2", "3", "4"]) h.delivery.send(A, ["bbbb-2"], text, "queue")
  waits[0]!(false)
  await tick()
  expect(waits).toHaveLength(2) // one more pass, for all three
  waits[1]!(false)
  await tick()
  expect(waits).toHaveLength(2) // and no other
  await h.delivery.idle()
  expect(h.delivered).toEqual([])
  expect(h.cursor.get("bbbb-2")).toBeUndefined()
})

test("a session messaging itself never interrupts its own turn: that message queues", async () => {
  const h = harness({ "aaaa-1": {}, "bbbb-2": {} })
  h.delivery.send(A, ["aaaa-1", "bbbb-2"], "note", "interrupt")
  await h.delivery.idle()
  expect(h.delivered.map((d) => `${d.id}:${d.meta.busy}`).sort()).toEqual(["aaaa-1:queue", "bbbb-2:interrupt"])
})

for (const status of ["archived", "suspended"] as const) {
  test(`an ${status} recipient that can't be brought back marks nothing delivered, and the next send retries`, async () => {
    const rows: Record<string, Partial<Session>> = { "bbbb-2": { status } }
    let back = false
    const bringBack = () => { if (back) rows["bbbb-2"]!.status = "active"; return back }
    const h = harness(rows, {
      resumeFromArchive: async () => (bringBack() ? { ok: true } : { ok: false, error: "worktree is gone" }),
      resumeSuspended: async () => bringBack(),
    })
    h.delivery.send(A, ["bbbb-2"], "one", "queue")
    await h.delivery.idle()
    expect(h.delivered).toEqual([])
    expect(h.cursor.get("bbbb-2")).toBeUndefined()
    back = true
    h.delivery.send(A, ["bbbb-2"], "two", "queue")
    await h.delivery.idle()
    expect(bodies(h.delivered)).toEqual(["one", "two"])
    expect(h.cursor.get("bbbb-2")).toBe(h.store.read("bbbb-2").at(-1)!.id)
  })
}

test("a message already in the log (a redelivery) is still delivered and skipped at info; any other log failure is a warning", async () => {
  const failures = [duplicateRowError(), new Error("disk I/O error")]
  const h = harness({ "bbbb-2": {} }, { messageLog: { append: () => { throw failures.shift()! } } })
  const logged = await stderrDuring(async () => {
    h.delivery.send(A, ["bbbb-2"], "one", "queue")
    h.delivery.send(A, ["bbbb-2"], "two", "queue")
    await h.delivery.idle()
  })
  expect(bodies(h.delivered)).toEqual(["one", "two"])
  const lines = logged.split("\n")
  const skip = lines.find((line) => line.includes("inbox_log_skip"))
  const failed = lines.find((line) => line.includes("inbox_log_failed"))
  expect(skip).toContain("[INFO]")
  expect(skip).toContain("UNIQUE constraint failed")
  expect(failed).toContain("[WARN]")
  expect(failed).toContain("disk I/O error")
})

test("a cursor that can't be saved stops the pass, and the turn already handed over is still watched", async () => {
  const seen: Array<{ text: string }> = []
  let failTurn!: (err: Error) => void
  const h = harness({ "bbbb-2": {} }, {
    cursor: { get: () => undefined, set: () => { throw new Error("database is locked") } },
    deliver: (_id, text) => {
      seen.push({ text })
      return new Promise<InboundDeliveryResult>((_resolve, reject) => { failTurn = reject })
    },
  })
  const logged = await stderrDuring(async () => {
    h.delivery.send(A, ["bbbb-2"], "one", "queue")
    h.delivery.send(A, ["bbbb-2"], "two", "queue")
    await h.delivery.idle()
    failTurn(new Error("adapter went away")) // handled, not an unhandled rejection
    await tick()
  })
  expect(bodies(seen)).toEqual(["one"]) // the pass stopped at the cursor it couldn't save
  expect(logged).toContain("inbox_delivery_failed")
  expect(logged).toContain("database is locked")
  expect(logged).toContain("inbox_deliver_threw")
  expect(logged).toContain("adapter went away")
})
