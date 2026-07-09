import { describe, expect, test } from "bun:test"
import { createInboundGate, type InboundPayload } from "./inbound-gate"

type Task = { fn: () => void; ms: number; cancelled: boolean }

function makeScheduler() {
  const tasks: Task[] = []
  return {
    tasks,
    schedule: (fn: () => void, ms: number) => {
      const t: Task = { fn, ms, cancelled: false }
      tasks.push(t)
      return () => { t.cancelled = true }
    },
    fire(t: Task) {
      if (!t.cancelled) t.fn()
    },
  }
}

function makeGate(opts?: { graceMs?: number; initTimeoutMs?: number }) {
  const sched = makeScheduler()
  const notified: { payload: InboundPayload; trigger: string }[] = []
  const gate = createInboundGate({
    graceMs: opts?.graceMs ?? 2500,
    initTimeoutMs: opts?.initTimeoutMs ?? 30_000,
    notify: (payload, trigger) => notified.push({ payload, trigger }),
    schedule: sched.schedule,
  })
  return { gate, sched, notified }
}

const msg = (content: string): InboundPayload => ({ content, meta: {} })

describe("inbound-gate", () => {
  test("buffers pre-init inbound; flushes in order only after initialized + grace", () => {
    const { gate, sched, notified } = makeGate()
    gate.inbound(msg("one"))
    gate.inbound(msg("two"))
    expect(notified).toEqual([])

    gate.initialized()
    // initialize handshake done, but the client hasn't wired its channel
    // handler yet — still nothing until the grace timer fires.
    expect(notified).toEqual([])

    const grace = sched.tasks.find(t => t.ms === 2500)!
    sched.fire(grace)
    expect(notified.map(n => n.payload.content)).toEqual(["one", "two"])
    expect(notified.every(n => n.trigger === "initialized_grace")).toBe(true)
  })

  test("inbound arriving between initialized and grace expiry joins the same flush", () => {
    const { gate, sched, notified } = makeGate()
    gate.inbound(msg("early"))
    gate.initialized()
    gate.inbound(msg("mid-grace"))
    expect(notified).toEqual([])

    sched.fire(sched.tasks.find(t => t.ms === 2500)!)
    expect(notified.map(n => n.payload.content)).toEqual(["early", "mid-grace"])
  })

  test("after the gate opens, inbound notifies immediately", () => {
    const { gate, sched, notified } = makeGate()
    gate.initialized()
    sched.fire(sched.tasks.find(t => t.ms === 2500)!)

    gate.inbound(msg("live"))
    expect(notified.map(n => ({ c: n.payload.content, t: n.trigger }))).toEqual([
      { c: "live", t: "immediate" },
    ])
  })

  test("initialize never completing: init-timeout flushes loudly as last resort", () => {
    const { gate, sched, notified } = makeGate()
    gate.inbound(msg("stranded"))

    const timeout = sched.tasks.find(t => t.ms === 30_000)!
    sched.fire(timeout)
    expect(notified.map(n => ({ c: n.payload.content, t: n.trigger }))).toEqual([
      { c: "stranded", t: "init_timeout" },
    ])
    // Gate is open now — later messages flow immediately rather than piling up.
    gate.inbound(msg("later"))
    expect(notified[1]).toEqual({ payload: msg("later"), trigger: "immediate" })
  })

  test("initialized cancels the init-timeout so it cannot double-flush", () => {
    const { gate, sched, notified } = makeGate()
    gate.inbound(msg("only-once"))
    gate.initialized()

    const timeout = sched.tasks.find(t => t.ms === 30_000)!
    expect(timeout.cancelled).toBe(true)
    sched.fire(timeout) // no-op even if it somehow fired
    expect(notified).toEqual([])

    sched.fire(sched.tasks.find(t => t.ms === 2500)!)
    expect(notified.map(n => n.payload.content)).toEqual(["only-once"])
  })

  test("initialized is idempotent — no second grace timer, no double flush", () => {
    const { gate, sched, notified } = makeGate()
    gate.inbound(msg("x"))
    gate.initialized()
    gate.initialized()
    expect(sched.tasks.filter(t => t.ms === 2500).length).toBe(1)

    sched.fire(sched.tasks.find(t => t.ms === 2500)!)
    expect(notified.length).toBe(1)

    gate.initialized() // after open — ignored
    expect(notified.length).toBe(1)
  })

  test("exposes state for logging", () => {
    const { gate, sched } = makeGate()
    expect(gate.isOpen()).toBe(false)
    gate.inbound(msg("a"))
    expect(gate.pendingCount()).toBe(1)
    gate.initialized()
    sched.fire(sched.tasks.find(t => t.ms === 2500)!)
    expect(gate.isOpen()).toBe(true)
    expect(gate.pendingCount()).toBe(0)
  })
})
