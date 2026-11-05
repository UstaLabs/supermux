import { expect, test } from "bun:test"
import { CancelledError, SessionChain, WakeFailedError, deliverOnChain, ensureLiveWith, type LivenessDeps } from "./session-chain"

/** A chain whose sessions start asleep; ensureLive wakes one, optionally held or failing. */
function harness(opts: { fail?: (id: string, attempt: number) => Error | undefined; hold?: Promise<void> } = {}) {
  const live = new Set<string>()
  const wakes: string[] = []
  const edges: string[] = []
  const slept: string[] = []
  const failures: Array<{ id: string; err: unknown }> = []
  const chain = new SessionChain({
    isLive: (id) => live.has(id),
    ensureLive: async (id) => {
      wakes.push(id)
      if (opts.hold) await opts.hold
      const err = opts.fail?.(id, wakes.filter((w) => w === id).length)
      if (err) throw err
      live.add(id)
    },
    onWaking: (id, waking) => { edges.push(`${id}:${waking ? "on" : "off"}`) },
    putToSleep: async (id) => { slept.push(id); live.delete(id) },
    onWakeFailed: (id, err) => { failures.push({ id, err }) },
  })
  return { chain, live, wakes, edges, slept, failures }
}

test("work for one session runs in the order it was queued", async () => {
  const { chain } = harness()
  const order: string[] = []
  let releaseFirst!: () => void
  const first = chain.run("s", async () => { await new Promise<void>((r) => { releaseFirst = r }); order.push("a") })
  const second = chain.run("s", async () => { order.push("b") })
  await Bun.sleep(5)
  expect(order).toEqual([])
  releaseFirst()
  await Promise.all([first, second])
  expect(order).toEqual(["a", "b"])
})

test("two messages to a sleeping session share one wake", async () => {
  const { chain, wakes } = harness()
  const delivered: string[] = []
  await Promise.all([
    chain.run("s", async (t) => { await chain.wakeNow("s", t); delivered.push("m1") }),
    chain.run("s", async (t) => { await chain.wakeNow("s", t); delivered.push("m2") }),
  ])
  expect(wakes).toEqual(["s"])
  expect(delivered).toEqual(["m1", "m2"])
})

test("a failed wake fails what was queued behind it, once, and only a new message wakes again", async () => {
  const { chain, wakes } = harness({ fail: (_id, attempt) => (attempt === 1 ? new Error("keeper did not start") : undefined) })
  const m1 = chain.run("s", async (t) => { await chain.wakeNow("s", t); return "m1" })
  const m2 = chain.run("s", async (t) => { await chain.wakeNow("s", t); return "m2" })
  await expect(m1).rejects.toThrow("keeper did not start")
  const queued = await m2.catch((e) => e)
  expect(queued).toBeInstanceOf(WakeFailedError)
  expect((queued as WakeFailedError).cause).toBeInstanceOf(Error)
  expect(wakes).toEqual(["s"])
  // A new message after the failure: a new wake.
  expect(await chain.run("s", async (t) => { await chain.wakeNow("s", t); return "m3" })).toBe("m3")
  expect(wakes).toEqual(["s", "s"])
})

test("cancel drops what is queued, and a wake that finishes after it puts the session back to sleep", async () => {
  let release!: () => void
  const hold = new Promise<void>((r) => { release = r })
  const { chain, wakes, slept, live } = harness({ hold })
  const delivered: string[] = []
  const m1 = chain.run("s", async (t) => { await chain.wakeNow("s", t); delivered.push("m1") })
  const m2 = chain.run("s", async (t) => { await chain.wakeNow("s", t); delivered.push("m2") })
  await Bun.sleep(5)
  expect(wakes).toEqual(["s"])
  chain.cancel("s")
  release()
  expect(await m1.catch((e) => e)).toBeInstanceOf(CancelledError)
  expect(await m2.catch((e) => e)).toBeInstanceOf(CancelledError)
  expect(delivered).toEqual([])
  expect(slept).toEqual(["s"])
  expect(live.has("s")).toBe(false)
  // After a cancel the session takes new work again.
  expect(await chain.run("s", async () => "next")).toBe("next")
})

test("waking is true only while a session that was not up is being woken, with an edge each way", async () => {
  let release!: () => void
  const hold = new Promise<void>((r) => { release = r })
  const { chain, edges, live } = harness({ hold })
  const wake = chain.wake("s")
  await Bun.sleep(5)
  expect(chain.waking("s")).toBe(true)
  expect(chain.pending("s")).toBe(true)
  release()
  await wake
  expect(chain.waking("s")).toBe(false)
  expect(edges).toEqual(["s:on", "s:off"])
  // Already up: no wake, no edge.
  await chain.wake("s")
  expect(edges).toEqual(["s:on", "s:off"])
  expect(live.has("s")).toBe(true)
  expect(chain.pending("s")).toBe(false)
})

test("sessions do not wait for each other", async () => {
  const hold = new Promise<void>(() => {})
  const { chain } = harness({ hold })
  void chain.wake("slow")
  expect(await chain.run("other", async () => "ok")).toBe("ok")
})

test("a failed wake is reported once, whoever ran it, and a cancelled one is not", async () => {
  const { chain, failures } = harness({ fail: () => new Error("keeper did not start") })
  const m1 = chain.run("s", async (t) => { await chain.wakeNow("s", t) })
  const m2 = chain.run("s", async (t) => { await chain.wakeNow("s", t) })
  await Promise.allSettled([m1, m2])
  expect(failures.map((f) => [f.id, (f.err as Error).message])).toEqual([["s", "keeper did not start"]])

  let release!: () => void
  const held = harness({ hold: new Promise<void>((r) => { release = r }), fail: () => new Error("late") })
  const m3 = held.chain.wake("s")
  await Bun.sleep(5)
  held.chain.cancel("s")
  release()
  expect(await m3.catch((e) => e)).toBeInstanceOf(CancelledError)
  expect(held.failures).toEqual([])
})

test("wakingSince is when the wake in flight began, and nothing once it is over", async () => {
  let release!: () => void
  const { chain } = harness({ hold: new Promise<void>((r) => { release = r }) })
  expect(chain.wakingSince("s")).toBeUndefined()
  const before = Date.now()
  const wake = chain.wake("s")
  await Bun.sleep(5)
  const since = chain.wakingSince("s")!
  expect(since).toBeGreaterThanOrEqual(before)
  expect(since).toBeLessThanOrEqual(Date.now())
  release()
  await wake
  expect(chain.wakingSince("s")).toBeUndefined()
})

test("deliverOnChain wakes the session, hands the message over and moves on without waiting for the turn", async () => {
  const { chain, wakes, live } = harness()
  let endTurn!: () => void
  const turnEnds = new Promise<void>((r) => { endTurn = r })
  const handed: string[] = []
  const first = await deliverOnChain(chain, "s", async () => { handed.push("m1"); await turnEnds; return "done-1" })
  expect(first.handed).toBe(true)
  expect(live.has("s")).toBe(true)
  // The turn is still running, and the next message is handed over now (the agent steers or queues it).
  const second = await deliverOnChain(chain, "s", async () => { handed.push("m2"); return "done-2" })
  expect(handed).toEqual(["m1", "m2"])
  expect(wakes).toEqual(["s"])
  endTurn()
  expect(first.handed && await first.turn).toBe("done-1")
  expect(second.handed && await second.turn).toBe("done-2")
})

test("deliverOnChain: a failed wake hands nothing over, for the message that ran it and the ones behind it", async () => {
  let release!: () => void
  const { chain } = harness({ hold: new Promise<void>((r) => { release = r }), fail: () => new Error("no worktree") })
  const handed: string[] = []
  const a = deliverOnChain(chain, "s", async () => { handed.push("a") })
  const b = deliverOnChain(chain, "s", async () => { handed.push("b") })
  release()
  const [ra, rb] = await Promise.all([a, b])
  expect(ra).toMatchObject({ handed: false, why: "wake_failed" })
  expect(rb).toMatchObject({ handed: false, why: "wake_failed" })
  expect(handed).toEqual([])
})

test("deliverOnChain: Stop during the wake drops the message (it stays in the chat) and says cancelled", async () => {
  let release!: () => void
  const { chain, slept } = harness({ hold: new Promise<void>((r) => { release = r }) })
  const handed: string[] = []
  const a = deliverOnChain(chain, "s", async () => { handed.push("a") })
  await Bun.sleep(5)
  chain.cancel("s")
  release()
  expect(await a).toMatchObject({ handed: false, why: "cancelled" })
  expect(handed).toEqual([])
  expect(slept).toEqual(["s"])
})

test("deliverOnChain: a hand-over that throws is the turn's failure, not the wake's", async () => {
  const { chain } = harness()
  const r = await deliverOnChain(chain, "s", () => { throw new Error("adapter gone") })
  expect(r.handed).toBe(true)
  if (r.handed) expect(await r.turn.catch((e) => (e as Error).message)).toBe("adapter gone")
})

/** A row and an agent that resume can start or fail. */
function liveness(row: { id: string; status: string }, opts: { resumeError?: unknown } = {}) {
  let state: "none" | "alive" | "dead" = "none"
  const deps: LivenessDeps = {
    session: (id) => (id === row.id ? row : undefined),
    resumeFromArchive: async () => ({ ok: false, error: "the archive could not be restored" }),
    resumeSuspended: async () => { if (opts.resumeError !== undefined) return false; state = "alive"; row.status = "active"; return true },
    resumeFailure: () => opts.resumeError,
    adapterState: () => state,
    resumeAdapter: async () => { state = "alive" },
    waitDeliverable: async () => state === "alive",
  }
  return deps
}

test("ensureLiveWith throws what made the resume fail, so the chat can say why", async () => {
  const cause = new Error("git worktree add failed: invalid reference: gone")
  const err = await ensureLiveWith(liveness({ id: "s", status: "suspended" }, { resumeError: cause }), "s").catch((e) => e)
  expect(err).toBe(cause)
  const archived = await ensureLiveWith(liveness({ id: "s", status: "archived" }), "s").catch((e) => e)
  expect((archived as Error).message).toContain("the archive could not be restored")
  await ensureLiveWith(liveness({ id: "s", status: "suspended" }), "s")
})
