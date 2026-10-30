import { expect, test } from "bun:test"
import { ChildSettle, evaluate, nudgeText, quietText, type SettleInputs } from "./settle"

const idle: SettleInputs = { alive: true, running: false, queuedInput: false, backgroundWork: false, pendingRequest: false }

/** A child "kid" of "par" and a fake clock: `fire()` runs the debounce timer that is due. */
function setup(over: { parentOf?: (id: string) => string | undefined } = {}) {
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
      clear: (t) => { (t as { cleared: boolean }).cleared = true },
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
  const release = t.settle.hold("kid")
  t.turn()
  t.fire()
  expect(t.sent).toEqual([])
  t.settle.told("kid")
  release()
  t.fire()
  expect(t.sent).toEqual([])
})

test("a hold released without a notice lets the nudge go out", () => {
  const t = setup()
  t.settle.inbound("kid", "par")
  const release = t.settle.hold("kid")
  t.turn()
  t.fire()
  release()
  release() // twice is once
  t.fire()
  expect(t.sent).toEqual([{ kind: "nudge", child: "kid" }])
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
      "If you are blocked or waiting for something, tell it that instead.",
  )
  expect(quietText({ name: "Review", id: "kid-1" }, "Antigravity denied: ReadFile")).toBe(
    "Your subagent [Review:kid-1] is idle with nothing running and hasn't reported. Its last message:\nAntigravity denied: ReadFile",
  )
  expect(quietText({ name: "Review", id: "kid-1" }, undefined)).toBe(
    "Your subagent [Review:kid-1] is idle with nothing running and hasn't reported. It sent no message.",
  )
})
