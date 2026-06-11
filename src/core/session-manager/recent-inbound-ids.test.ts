import { test, expect } from "bun:test"
import { RecentInboundIds } from "./recent-inbound-ids"

test("unseen id is not present; after mark it is", () => {
  const r = new RecentInboundIds(3)
  expect(r.has("s1", "a")).toBe(false)
  r.mark("s1", "a")
  expect(r.has("s1", "a")).toBe(true)
})

test("ids are isolated per session", () => {
  const r = new RecentInboundIds(3)
  r.mark("s1", "a")
  expect(r.has("s2", "a")).toBe(false)
})

test("evicts oldest beyond the cap (FIFO)", () => {
  const r = new RecentInboundIds(2)
  r.mark("s1", "a"); r.mark("s1", "b"); r.mark("s1", "c") // "a" evicted
  expect(r.has("s1", "a")).toBe(false)
  expect(r.has("s1", "b")).toBe(true)
  expect(r.has("s1", "c")).toBe(true)
})

test("marking the same id twice does not grow / evict", () => {
  const r = new RecentInboundIds(2)
  r.mark("s1", "a"); r.mark("s1", "a"); r.mark("s1", "b")
  expect(r.has("s1", "a")).toBe(true)
  expect(r.has("s1", "b")).toBe(true)
})

test("clear drops a session's ids", () => {
  const r = new RecentInboundIds(3)
  r.mark("s1", "a")
  r.clear("s1")
  expect(r.has("s1", "a")).toBe(false)
})
