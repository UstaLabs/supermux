import { expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { ChildSettle, evaluate, nudgeText, quietText, seedFor, taskOpenSince, type SettleInputs } from "./settle"
import { InboxDelivery } from "../inbox/delivery"
import { InboxStore } from "../inbox/store"
import type { Session } from "../session-manager/types"

const idle: SettleInputs = { alive: true, running: false, queuedInput: false, backgroundWork: false, pendingRequest: false }

/** A child "kid" of "par" and a fake clock: `fire()` runs the debounce timer that is due. */
function setup(over: { parentOf?: (id: string) => string | undefined; clearIgnored?: boolean } = {}) {
  const inputs = new Map<string, SettleInputs | undefined>([["kid", { ...idle }]])
  const sent: Array<{ kind: "nudge" | "notify"; child: string }> = []
  let timers: Array<{ fn: () => void; ms: number; cleared: boolean }> = []
  const settle = new ChildSettle({
    parentOf: over.parentOf ?? ((id) => (id === "kid" ? "par" : undefined)),
    inputs: (id) => inputs.get(id),
    nudge: (child) => { sent.push({ kind: "nudge", child }) },
    notify: (child) => { sent.push({ kind: "notify", child }) },
    timer: {
      set: (fn, ms) => { const t = { fn, ms, cleared: false }; timers.push(t); return t },
      // clearIgnored: a timer whose callback was already on its way when it was cleared.
      clear: (t) => { if (!over.clearIgnored) (t as { cleared: boolean }).cleared = true },
    },
    debounceMs: 2000,
  })
  const set = (patch: Partial<SettleInputs>) => inputs.set("kid", { ...inputs.get("kid")!, ...patch })
  const fire = () => {
    const due = timers.filter((t) => !t.cleared)
    timers = []
    for (const t of due) t.fn()
  }
  /** A turn runs and ends with a plain reply: running, then idle again. */
  const turn = () => {
    set({ running: true })
    settle.turnStarted("kid")
    set({ running: false })
    settle.changed("kid")
  }
  return { settle, sent, set, fire, turn, inputs, pending: () => timers.filter((t) => !t.cleared) }
}

test("evaluate: nudge first, then notify after another turn, then nothing", () => {
  const fresh = { reported: false, told: false, nudged: false, turnSinceNudge: false, notified: false }
  expect(evaluate(fresh, idle)).toBe("nudge")
  expect(evaluate({ ...fresh, nudged: true }, idle)).toBe("none")
  expect(evaluate({ ...fresh, nudged: true, turnSinceNudge: true }, idle)).toBe("notify")
  expect(evaluate({ ...fresh, nudged: true, turnSinceNudge: true, notified: true }, idle)).toBe("none")
  expect(evaluate({ ...fresh, reported: true }, idle)).toBe("none")
  expect(evaluate({ ...fresh, told: true }, idle)).toBe("none")
  expect(evaluate(undefined, idle)).toBe("none")
  expect(evaluate(fresh, undefined)).toBe("none")
  for (const busy of [{ running: true }, { queuedInput: true }, { backgroundWork: true }, { pendingRequest: true }, { alive: false }]) {
    expect(evaluate(fresh, { ...idle, ...busy })).toBe("none")
  }
})

test("the Antigravity case: the task's turn ends with a plain reply → one nudge; the child then reports → nothing more", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.turn()
  expect(t.pending().map((x) => x.ms)).toEqual([2000])
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
  // The nudge's turn: the child messages its parent.
  t.set({ running: true })
  t.settle.turnStarted("kid")
  t.settle.sent("kid", "par")
  t.set({ running: false })
  t.settle.changed("kid")
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("nudged, then settles again without a report → the parent is told once, then quiet until the next task", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.turn()
  t.fire()
  t.turn()
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }, { kind: "notify", child: "kid" }])
  t.turn()
  t.fire()
  t.settle.changed("kid")
  t.fire()
  expect(t.sent).toHaveLength(2)
  // A new task from the parent starts over.
  t.settle.inbound("kid", "par")
  t.turn()
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }, { kind: "notify", child: "kid" }, { kind: "nudge", child: "kid" }])
})

test("a re-check while still idle after the nudge (no turn yet) does not notify", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.turn()
  t.fire()
  t.settle.changed("kid")
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("an open background task holds it until it closes", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.set({ backgroundWork: true })
  t.turn()
  t.fire()
  expect(t.sent).toEqual([])
  t.set({ backgroundWork: false })
  t.settle.changed("kid")
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("a pending approval holds it", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.set({ pendingRequest: true })
  t.turn()
  t.fire()
  expect(t.sent).toEqual([])
  t.set({ pendingRequest: false })
  t.settle.changed("kid")
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("queued input holds it", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.set({ queuedInput: true })
  t.turn()
  t.fire()
  expect(t.sent).toEqual([])
})

test("a new task from the parent (or the user) resets a reported task", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.settle.turnStarted("kid")
  t.settle.sent("kid", "par")
  t.turn()
  t.fire()
  expect(t.sent).toEqual([])
  t.settle.inbound("kid", undefined) // the user, from the card
  t.turn()
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("a message from another session or from supermux is not a new task", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.settle.turnStarted("kid")
  t.settle.sent("kid", "par")
  t.settle.inbound("kid", "sibling")
  t.settle.inbound("kid", "supermux")
  t.turn()
  t.fire()
  expect(t.sent).toEqual([])
})

test("a message to someone other than the parent is not a report", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.settle.sent("kid", "sibling")
  t.turn()
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("a failed-turn notice counts as the parent having been told; a hold keeps it until the notice is decided", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  const hold = t.settle.hold("kid")
  t.turn()
  t.fire()
  expect(t.sent).toEqual([])
  hold.told()
  hold.release()
  t.fire()
  expect(t.sent).toEqual([])
})

test("a hold released without a notice lets the nudge go out", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  const hold = t.settle.hold("kid")
  t.turn()
  t.fire()
  hold.release()
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("overlapping holds: it stays held until each is released, and a release twice counts once", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  const first = t.settle.hold("kid")
  const second = t.settle.hold("kid")
  t.turn()
  first.release()
  first.release()
  t.fire()
  expect(t.sent).toEqual([])
  second.release()
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("a hold's told marks the task it was taken for, not one begun since", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  const hold = t.settle.hold("kid")
  t.settle.inbound("kid", "par") // a new task inside the failed turn's grace
  hold.told()
  hold.release()
  t.turn()
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("told (the crash notice) marks the current task", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.settle.told("kid")
  t.turn()
  t.fire()
  expect(t.sent).toEqual([])
})

test("a report from the previous task's turn, before the new task's turn starts, is not this task's report", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.set({ running: true })
  t.settle.turnStarted("kid")
  t.settle.inbound("kid", "par") // queued behind the running turn
  t.settle.sent("kid", "par") // the running turn reports the first task
  t.set({ running: false })
  t.turn() // the second task's turn: a plain reply
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("a timer of a task replaced since does nothing when it fires anyway", () => {
  const t = setup({ clearIgnored: true })
  t.settle.inbound("kid", "par")
  t.turn()
  t.settle.inbound("kid", "par")
  t.turn()
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
})

test("seed (startup): an open task is watched again; a live task is left alone; a top-level session is not", () => {
  const t = setup()
  t.settle.seed("kid")
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
  const live = setup()
  live.settle.inbound("kid", "par")
  live.settle.turnStarted("kid")
  live.settle.sent("kid", "par")
  live.settle.seed("kid")
  live.fire()
  expect(live.sent).toEqual([])
  const top = setup({ parentOf: () => undefined })
  top.settle.seed("kid")
  expect(top.pending()).toEqual([])
})

test("taskOpenSince: the newest input from the parent or the user is newer than the newest report", () => {
  expect(taskOpenSince(["2026-10-30T10:00:00.000Z", undefined], undefined)).toBe(true)
  expect(taskOpenSince(["2026-10-30T10:00:00.000Z", "2026-10-30T11:00:00.000Z"], "2026-10-30T10:30:00.000Z")).toBe(true)
  expect(taskOpenSince(["2026-10-30T10:00:00.000Z"], "2026-10-30T10:30:00.000Z")).toBe(false)
  expect(taskOpenSince([undefined, undefined], undefined)).toBe(false)
})

test("skipped: no eligible inputs (council member, archived child or parent), and top-level sessions", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.inputs.set("kid", undefined)
  t.turn()
  t.fire()
  expect(t.sent).toEqual([])
  const top = setup({ parentOf: () => undefined })
  top.settle.inbound("kid", undefined)
  top.turn()
  top.fire()
  expect(top.sent).toEqual([])
  expect(top.pending()).toEqual([])
})

test("a child with no task begun in this process is never nudged", () => {
  const t = setup()
  t.turn()
  t.fire()
  expect(t.sent).toEqual([])
})

test("debounce: idle then running again within the window does not fire", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.settle.changed("kid") // idle for a moment
  t.set({ running: true })
  t.settle.turnStarted("kid")
  expect(t.pending()).toHaveLength(1) // only the newest timer stays armed
  t.fire()
  expect(t.sent).toEqual([])
})

test("forget drops the task and its timer", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.turn()
  t.settle.forget("kid")
  expect(t.pending()).toEqual([])
  t.settle.changed("kid")
  t.fire()
  expect(t.sent).toEqual([])
})

test("the texts", () => {
  expect(nudgeText({ name: "Fix login", id: "par-1" })).toBe(
    "You haven't reported to your parent [Fix login:par-1] yet. Send it your result with message_session({ to: [\"par-1\"], text, interrupt: false }). " +
      "If you are blocked or waiting for something, tell it that instead. If there is nothing to report, say so.",
  )
  expect(quietText({ name: "Review", id: "kid-1" }, "Antigravity denied: ReadFile")).toBe(
    "Your subagent [Review:kid-1] is idle with nothing running and hasn't reported. Its last message:\nAntigravity denied: ReadFile",
  )
  expect(quietText({ name: "Review", id: "kid-1" }, undefined)).toBe(
    "Your subagent [Review:kid-1] is idle with nothing running and hasn't reported. It sent no message.",
  )
})

test("through the real inbox: delegate → a plain-reply turn → nudge delivered → a plain reply → one parent notice", async () => {
  const dir = mkdtempSync(join(tmpdir(), "settle-inbox-"))
  try {
    const rows: Record<string, Partial<Session>> = { par: { name: "Fix login" }, kid: { name: "Review", parent_session_id: "par" } }
    const cursor = new Map<string, string>()
    let running = false
    let pendingTurns = 0
    const handed: Array<{ to: string; from: string; text: string }> = []
    const timers: Array<() => void> = []
    let settle!: ChildSettle
    let inbox!: InboxDelivery
    // The child's turn: running, then a plain reply and idle (the Antigravity "denied" case).
    const runTurn = () => {
      running = true
      settle.turnStarted("kid")
      running = false
      settle.changed("kid")
    }
    inbox = new InboxDelivery({
      store: new InboxStore(dir),
      cursor: { get: (id) => cursor.get(id), set: (id, m) => { cursor.set(id, m) } },
      messageLog: { append: () => {} },
      session: (id) => (rows[id] ? ({ id, status: "active", ...rows[id] } as Session) : undefined),
      resumeFromArchive: async () => ({ ok: true }),
      resumeSuspended: async () => true,
      adapterState: () => "alive",
      resumeAdapter: async () => {},
      waitDeliverable: async () => true,
      deliver: async (id, text, meta) => {
        settle.inbound(id, meta.user_id)
        handed.push({ to: id, from: meta.user_id!, text })
        if (id === "kid") pendingTurns++
        return { ok: true }
      },
      sent: (from, to) => settle.sent(from, to),
      changed: (id) => settle.changed(id),
    })
    settle = new ChildSettle({
      parentOf: (id) => rows[id]?.parent_session_id,
      inputs: (id) => ({ ...idle, running, queuedInput: inbox.pending(id) || pendingTurns > 0 }),
      nudge: (child) => { inbox.send({ name: "supermux", id: "supermux" }, [child], nudgeText({ name: "Fix login", id: "par" }), "queue") },
      notify: (child) => { inbox.send({ name: "supermux", id: "supermux" }, ["par"], quietText({ name: "Review", id: child }, "Antigravity denied: ReadFile"), "queue") },
      timer: { set: (fn) => { timers.push(fn); return fn }, clear: (fn) => { const i = timers.indexOf(fn as () => void); if (i >= 0) timers.splice(i, 1) } },
    })
    const fireAll = () => { for (const fn of timers.splice(0)) fn() }
    const childRuns = () => { while (pendingTurns > 0) { pendingTurns--; runTurn() } }

    inbox.send({ name: "Fix login", id: "par" }, ["kid"], "review src/auth", "queue") // delegate's task
    await inbox.idle()
    childRuns()
    fireAll()
    await inbox.idle()
    expect(handed.filter((h) => h.to === "kid").map((h) => h.from)).toEqual(["par", "supermux"])
    childRuns() // the nudge's turn: another plain reply
    fireAll()
    await inbox.idle()
    fireAll()
    const toParent = handed.filter((h) => h.to === "par")
    expect(toParent).toHaveLength(1)
    expect(toParent[0]!.text).toContain("Your subagent [Review:kid] is idle with nothing running and hasn't reported. Its last message:\nAntigravity denied: ReadFile")
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test("a message steered into the running turn begins the task there: that turn's report counts", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  t.set({ running: true })
  t.settle.turnStarted("kid")
  t.settle.inbound("kid", undefined, { steered: true }) // the user's message, steered into the turn
  t.settle.sent("kid", "par")
  t.set({ running: false })
  t.settle.changed("kid")
  t.fire()
  expect(t.sent).toEqual([])
})

test("seed with nudged: the next settle after a turn tells the parent, without a second nudge", () => {
  const t = setup()
  t.settle.seed("kid", { nudged: true })
  t.settle.changed("kid")
  t.fire()
  expect(t.sent).toEqual([])
  t.turn()
  t.fire()
  expect(t.sent).toEqual([{ kind: "notify", child: "kid" }])
})

test("seedFor: open, already told, already nudged", () => {
  const start = "2026-10-30T10:00:00.000Z"
  const before = "2026-10-30T09:00:00.000Z"
  const after = "2026-10-30T11:00:00.000Z"
  expect(seedFor({ inputs: [start], lastReport: undefined, toldAt: undefined, nudgedAt: undefined })).toBe("watch")
  expect(seedFor({ inputs: [start], lastReport: after, toldAt: undefined, nudgedAt: undefined })).toBe("none")
  expect(seedFor({ inputs: [start], lastReport: undefined, toldAt: after, nudgedAt: after })).toBe("none")
  expect(seedFor({ inputs: [start], lastReport: undefined, toldAt: before, nudgedAt: after })).toBe("nudged")
  expect(seedFor({ inputs: [start], lastReport: undefined, toldAt: before, nudgedAt: before })).toBe("watch")
  expect(seedFor({ inputs: [undefined], lastReport: undefined, toldAt: undefined, nudgedAt: undefined })).toBe("none")
})

test("seedFor: a reply after the nudge means its turn ran: the next settle tells the parent", () => {
  const start = "2026-10-30T10:00:00.000Z"
  const nudge = "2026-10-30T11:00:00.000Z"
  const o = { inputs: [start], lastReport: undefined, toldAt: undefined, nudgedAt: nudge }
  expect(seedFor({ ...o, repliedAt: "2026-10-30T11:05:00.000Z" })).toBe("nudged-turn")
  expect(seedFor({ ...o, repliedAt: "2026-10-30T10:30:00.000Z" })).toBe("nudged")
  expect(seedFor({ ...o, repliedAt: undefined })).toBe("nudged")
})

test("seed with nudged and turnSinceNudge: the parent is told at the first settle, once", () => {
  const t = setup()
  t.settle.seed("kid", { nudged: true, turnSinceNudge: true })
  t.fire()
  expect(t.sent).toEqual([{ kind: "notify", child: "kid" }])
  t.turn()
  t.fire()
  expect(t.sent).toHaveLength(1)
})
