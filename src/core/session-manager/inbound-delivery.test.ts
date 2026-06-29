import { test, expect } from "bun:test"
import { deliverInbound } from "./inbound-delivery"
import { RecentInboundIds } from "./recent-inbound-ids"

function harness(opts: { adapter?: boolean; isClaude: boolean }) {
  const adapterSends: Array<{ text: string; meta: any }> = []
  const socketSends: Array<{ content: string; meta: any }> = []
  const seen = new RecentInboundIds(50)
  const deps = {
    getAdapter: (_id: string) => (opts.adapter ? { kind: "x", send: async (text: string, meta: any) => { adapterSends.push({ text, meta }) } } : undefined),
    isClaude: (_id: string) => opts.isClaude,
    sendInboundSocket: async (_id: string, payload: { content: string; meta: any }) => { socketSends.push(payload) },
    seen,
  }
  return { deps, adapterSends, socketSends, seen }
}

test("with adapter: adapter.send is called; marks seen", async () => {
  const h = harness({ adapter: true, isClaude: false })
  const r = await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })
  expect(r).toEqual({ ok: true })
  expect(h.adapterSends).toEqual([{ text: "hi", meta: { message_id: "m1" } }])
  expect(h.seen.has("s1", "m1")).toBe(true)
})

test("claude with no adapter: falls back to sendInboundSocket (queue)", async () => {
  const h = harness({ adapter: false, isClaude: true })
  const r = await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })
  expect(r).toEqual({ ok: true })
  expect(h.socketSends).toEqual([{ content: "hi", meta: { message_id: "m1" } }])
})

test("adapter-driven with no adapter: returns adapter_not_ready", async () => {
  const h = harness({ adapter: false, isClaude: false })
  const r = await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })
  expect(r).toEqual({ ok: false, reason: "adapter_not_ready" })
  expect(h.seen.has("s1", "m1")).toBe(false)
})

test("duplicate message_id is skipped (idempotent), no second send", async () => {
  const h = harness({ adapter: true, isClaude: false })
  await deliverInbound(h.deps, "s1", "hi", { message_id: "m1" })
  const r = await deliverInbound(h.deps, "s1", "hi-again", { message_id: "m1" })
  expect(r).toEqual({ ok: true, deduped: true })
  expect(h.adapterSends.length).toBe(1)
})

test("no message_id: always delivers (cannot dedupe)", async () => {
  const h = harness({ adapter: true, isClaude: false })
  await deliverInbound(h.deps, "s1", "a", {})
  await deliverInbound(h.deps, "s1", "b", {})
  expect(h.adapterSends.length).toBe(2)
})
