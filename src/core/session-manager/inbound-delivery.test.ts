import { test, expect } from "bun:test"
import { deliverInbound } from "./inbound-delivery"
import { RecentInboundIds } from "./recent-inbound-ids"

function harness(opts: { adapter?: boolean }) {
  const adapterSends: Array<{ text: string; meta: any }> = []
  const delivered: string[] = []
  const seen = new RecentInboundIds(50)
  const deps = {
    getAdapter: (_id: string) => (opts.adapter ? { kind: "x", send: async (text: string, meta: any) => { adapterSends.push({ text, meta }) } } : undefined),
    seen,
    onDelivered: (id: string) => { delivered.push(id) },
  }
  return { deps, adapterSends, delivered, seen }
}

test("with adapter: adapter.send is called; marks seen", async () => {
  const h = harness({ adapter: true })
  const r = await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })
  expect(r).toEqual({ ok: true })
  expect(h.adapterSends).toEqual([{ text: "hi", meta: { message_id: "m1" } }])
  expect(h.seen.has("s1", "m1")).toBe(true)
})

test("no adapter (any agent, Claude included): returns adapter_not_ready", async () => {
  const h = harness({ adapter: false })
  const r = await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })
  expect(r).toEqual({ ok: false, reason: "adapter_not_ready" })
  expect(h.seen.has("s1", "m1")).toBe(false)
})

test("duplicate message_id is skipped (idempotent), no second send", async () => {
  const h = harness({ adapter: true })
  await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })
  const r = await deliverInbound(h.deps, "s1", "hi-again", { message_id: "m1" })
  expect(r).toEqual({ ok: true, deduped: true })
  expect(h.adapterSends.length).toBe(1)
})

test("no message_id: always delivers (cannot dedupe)", async () => {
  const h = harness({ adapter: true })
  await deliverInbound(h.deps, "s1", "a", {})
  await deliverInbound(h.deps, "s1", "b", {})
  expect(h.adapterSends.length).toBe(2)
})

// onDelivered lets the broker re-broadcast the session's current agent_state on every
// successful hand-off so clients clear their local "Sending…" bubble even if the turn-start
// hook is later dropped (the stuck-Sending bug). It must fire on a delivery, AND on an
// idempotent re-send (a retry still needs its sender's bubble cleared),
// but NOT when delivery isn't ready.
test("onDelivered fires on successful adapter delivery", async () => {
  const h = harness({ adapter: true })
  await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })
  expect(h.delivered).toEqual(["s1"])
})

test("onDelivered fires even on an idempotent re-send (the stuck-Sending case)", async () => {
  const h = harness({ adapter: true })
  await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })
  await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })   // deduped, but still reconciles
  expect(h.delivered).toEqual(["s1", "s1"])
})

test("onDelivered does NOT fire when delivery isn't ready", async () => {
  const h = harness({ adapter: false })
  await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })
  expect(h.delivered).toEqual([])
})

test("onAccepted fires once per hand-over, before adapter.send: not for a deduped retry, not without an adapter", async () => {
  const order: string[] = []
  const accepted: Array<{ id: string; meta: any }> = []
  const seen = new RecentInboundIds(50)
  const deps = {
    getAdapter: () => ({ kind: "x", send: async (text: string) => { order.push(`send:${text}`) } }),
    seen,
    onAccepted: (id: string, meta: any) => { accepted.push({ id, meta }); order.push("accepted") },
  }
  await deliverInbound(deps, "s1", "hi", { message_id: "m1" })
  await deliverInbound(deps, "s1", "hi", { message_id: "m1" })
  await deliverInbound({ ...deps, getAdapter: () => undefined }, "s1", "x", { message_id: "m2" })
  expect(order).toEqual(["accepted", "send:hi"])
  expect(accepted).toEqual([{ id: "s1", meta: { message_id: "m1" } }])
})

test("onAccepted says whether the adapter steers the input into its running turn", async () => {
  const seen = new RecentInboundIds(50)
  const accepted: unknown[] = []
  const deps = {
    getAdapter: () => ({ kind: "codex", send: async () => {}, willSteer: (meta: any) => !meta?.busy }),
    seen,
    onAccepted: (_id: string, _meta: any, how: { steered: boolean }) => { accepted.push(how) },
  }
  await deliverInbound(deps, "s1", "a", {})
  await deliverInbound(deps, "s1", "b", { busy: "queue" })
  await deliverInbound({ ...deps, getAdapter: () => ({ kind: "x", send: async () => {} }) }, "s1", "c", {})
  expect(accepted).toEqual([{ steered: true }, { steered: false }, { steered: false }])
})

test("a throwing onAccepted never loses the message", async () => {
  const sends: string[] = []
  const deps = {
    getAdapter: () => ({ kind: "x", send: async (text: string) => { sends.push(text) } }),
    seen: new RecentInboundIds(50),
    onAccepted: () => { throw new Error("boom") },
  }
  const r = await deliverInbound(deps, "s1", "hi", { message_id: "m1" })
  expect(r).toEqual({ ok: true })
  expect(sends).toEqual(["hi"])
})

// Card #75: a core adapter's send() resolves when its TURN ends, so an id marked only after it
// left a whole turn in which the same message, re-sent, was delivered again.
test("a re-send while the first hand-over's turn is still running is deduped (marked at hand-over)", async () => {
  const sends: string[] = []
  let endTurn!: () => void
  const turn = new Promise<void>((resolve) => { endTurn = resolve })
  const seen = new RecentInboundIds(50)
  const deps = { getAdapter: () => ({ kind: "x", send: async (text: string) => { sends.push(text); await turn } }), seen }
  const first = deliverInbound(deps, "s1", "hi", { message_id: "web-1" })
  await Bun.sleep(0)
  expect(await deliverInbound(deps, "s1", "hi", { message_id: "web-1" })).toEqual({ ok: true, deduped: true })
  endTurn()
  expect(await first).toEqual({ ok: true })
  expect(sends).toEqual(["hi"])
})

test("a hand-over that throws leaves the id unmarked, so the sender's retry goes through", async () => {
  let fail = true
  const sends: string[] = []
  const seen = new RecentInboundIds(50)
  const deps = { getAdapter: () => ({ kind: "x", send: async (text: string) => { sends.push(text); if (fail) throw new Error("agent gone") } }), seen }
  await expect(deliverInbound(deps, "s1", "hi", { message_id: "web-2" })).rejects.toThrow("agent gone")
  expect(seen.has("s1", "web-2")).toBe(false)
  fail = false
  expect(await deliverInbound(deps, "s1", "hi", { message_id: "web-2" })).toEqual({ ok: true })
  expect(sends).toEqual(["hi", "hi"])
})
