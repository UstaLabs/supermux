import { expect, test } from "bun:test"
import { CancelledError, SessionChain, WakeFailedError } from "./session-chain"

/** A chain whose sessions start asleep; ensureLive wakes one, optionally held or failing. */
function harness(opts: { fail?: (id: string, attempt: number) => Error | undefined; hold?: Promise<void> } = {}) {
  const live = new Set<string>()
  const wakes: string[] = []
  const edges: string[] = []
  const slept: string[] = []
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
  })
  return { chain, live, wakes, edges, slept }
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
