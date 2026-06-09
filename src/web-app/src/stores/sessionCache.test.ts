import { beforeEach, describe, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useSessionCache } from "./sessionCache"

beforeEach(() => setActivePinia(createPinia()))

describe("sessionCache", () => {
  test("visit adds id once; drop removes from liveIds", () => {
    const cache = useSessionCache()
    cache.visit("a")
    cache.visit("a")
    cache.visit("b")
    expect(cache.liveIds).toEqual(["a", "b"])
    expect(cache.isDropped("a")).toBe(false)

    cache.drop("a")
    expect(cache.isDropped("a")).toBe(true)
    expect(cache.liveIds).toEqual(["b"])
    cache.drop("a")
    expect(cache.liveIds).toEqual(["b"])
  })

  test("cannot revisit dropped session without refresh", () => {
    const cache = useSessionCache()
    cache.visit("x")
    cache.drop("x")
    cache.visit("x")
    expect(cache.liveIds).toEqual([])
    expect(cache.isDropped("x")).toBe(true)
  })
})
