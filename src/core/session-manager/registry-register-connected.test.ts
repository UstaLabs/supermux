import { describe, expect, test } from "bun:test"
import { Registry } from "./registry"

describe("registry.register connected flag", () => {
  test("defaults to connected=true (existing behavior)", () => {
    const registry = new Registry()
    const s = registry.register({ name: "a", workdir: "/tmp", pid: 1 })
    expect(registry.get(s.id)?.connected).toBe(true)
  })

  test("connected:false registers an unconnected row", () => {
    const registry = new Registry()
    const s = registry.register({ name: "b", workdir: "/tmp", pid: 1, connected: false })
    expect(registry.get(s.id)?.connected).toBe(false)
  })

  test("setConnectionStatus flips an unconnected row to connected", () => {
    const registry = new Registry()
    const s = registry.register({ name: "c", workdir: "/tmp", pid: 1, connected: false })
    registry.setConnectionStatus(s.id, true)
    expect(registry.get(s.id)?.connected).toBe(true)
  })
})
