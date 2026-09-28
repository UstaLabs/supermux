import { test, expect } from "bun:test"
import { SubscriptionRegistry } from "./subscriptions"

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

function reg(graceMs = 30, maxPerSocket = 500) {
  const events: string[] = []
  const r = new SubscriptionRegistry<string>({
    onFirst: (real) => events.push(`first ${real}`),
    onLast: (real) => events.push(`last ${real}`),
  }, { graceMs, maxPerSocket })
  return { r, events }
}

test("first subscriber starts, others share, the last leaves after the grace period", async () => {
  const { r, events } = reg()
  expect(r.add("s1", "/a", "/real/a")).toBe("ok")
  expect(r.add("s2", "/link/a", "/real/a")).toBe("ok")
  expect(events).toEqual(["first /real/a"])
  expect(r.subscribersOf("/real/a")).toEqual([{ sock: "s1", path: "/a" }, { sock: "s2", path: "/link/a" }])
  r.remove("s1", "/a")
  r.remove("s2", "/link/a")
  expect(events).toEqual(["first /real/a"])
  await sleep(60)
  expect(events).toEqual(["first /real/a", "last /real/a"])
})

test("re-subscribing within the grace period cancels the teardown", async () => {
  const { r, events } = reg()
  r.add("s1", "/a", "/real/a")
  r.remove("s1", "/a")
  r.add("s1", "/a", "/real/a")
  await sleep(60)
  expect(events).toEqual(["first /real/a"])
  expect(r.isWatched("/real/a")).toBe(true)
})

test("dropSocket removes every subscription of that socket", async () => {
  const { r, events } = reg()
  r.add("s1", "/a", "/real/a")
  r.add("s1", "/b", "/real/b")
  r.dropSocket("s1")
  expect(r.count("s1")).toBe(0)
  await sleep(60)
  expect(events.sort()).toEqual(["first /real/a", "first /real/b", "last /real/a", "last /real/b"])
})

test("adding the same subscription twice is idempotent; the per-socket cap is enforced", () => {
  const { r } = reg(30, 2)
  expect(r.add("s1", "/a", "/real/a")).toBe("ok")
  expect(r.add("s1", "/a", "/real/a")).toBe("ok")
  expect(r.count("s1")).toBe(1)
  expect(r.add("s1", "/b", "/real/b")).toBe("ok")
  expect(r.add("s1", "/c", "/real/c")).toBe("limit")
})

test("removeAllFor drops a folder for everyone at once without a grace period", () => {
  const { r, events } = reg()
  r.add("s1", "/a", "/real/a")
  r.add("s2", "/a", "/real/a")
  const removed = r.removeAllFor("/real/a")
  expect(removed).toEqual([{ sock: "s1", path: "/a" }, { sock: "s2", path: "/a" }])
  expect(events).toEqual(["first /real/a", "last /real/a"])
  expect(r.count("s1")).toBe(0)
})

test("re-adding a (sock, path) that pointed at a different real re-points it, tearing down the old real (with grace) and starting the new one", async () => {
  const { r, events } = reg()
  expect(r.add("s1", "/a", "/real/a")).toBe("ok")
  expect(r.realOf("s1", "/a")).toBe("/real/a")
  expect(r.add("s1", "/a", "/real/b")).toBe("ok")
  expect(r.realOf("s1", "/a")).toBe("/real/b")
  expect(r.subscribersOf("/real/a")).toEqual([])
  expect(r.subscribersOf("/real/b")).toEqual([{ sock: "s1", path: "/a" }])
  expect(r.count("s1")).toBe(1)
  expect(events).toEqual(["first /real/a", "first /real/b"])
  await sleep(60)
  expect(events).toEqual(["first /real/a", "first /real/b", "last /real/a"])
  expect(r.isWatched("/real/a")).toBe(false)
  expect(r.isWatched("/real/b")).toBe(true)
})
