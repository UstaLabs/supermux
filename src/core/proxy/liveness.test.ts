import { describe, expect, test } from "bun:test"
import { ProxyLivenessMonitor, type ProxyTarget } from "./liveness"

// A fake `connect` driven by a per-port boolean map, recording every probe so
// tests can assert call counts (e.g. that a pruned domain stops being probed).
function fakeConnect(ports: Map<number, boolean>, calls?: number[]) {
  return async (port: number): Promise<boolean> => {
    calls?.push(port)
    return ports.get(port) ?? false
  }
}

describe("ProxyLivenessMonitor.getStatus", () => {
  test("unprobed domain is 'unknown'", () => {
    const mon = new ProxyLivenessMonitor({ listTargets: () => [], onChange: () => {} })
    expect(mon.getStatus("nope")).toBe("unknown")
  })
})

describe("ProxyLivenessMonitor.refresh — real sockets", () => {
  test("a listening port probes 'up' and a closed port probes 'down'", async () => {
    // Bind an ephemeral port we control: it is up while listening, down after stop().
    const server = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } })
    const upPort = server.port

    // A separate ephemeral port that we close immediately → nothing listening → down.
    const closed = Bun.listen({ hostname: "127.0.0.1", port: 0, socket: { data() {} } })
    const downPort = closed.port
    closed.stop()

    const changes: [string, string][] = []
    const mon = new ProxyLivenessMonitor({
      listTargets: () => [
        { domain: "up.test", port: upPort },
        { domain: "down.test", port: downPort },
      ],
      onChange: (domain, status) => changes.push([domain, status]),
      timeoutMs: 500,
    })

    await mon.refresh()

    expect(mon.getStatus("up.test")).toBe("up")
    expect(mon.getStatus("down.test")).toBe("down")
    expect(changes).toContainEqual(["up.test", "up"])
    expect(changes).toContainEqual(["down.test", "down"])

    server.stop()
  })
})

describe("ProxyLivenessMonitor.refresh — change detection (fake connect)", () => {
  test("onChange fires on up→down and down→up, and not on an unchanged tick", async () => {
    const ports = new Map<number, boolean>([[1000, true]])
    const targets: ProxyTarget[] = [{ domain: "a", port: 1000 }]
    const changes: [string, string][] = []
    const mon = new ProxyLivenessMonitor({
      listTargets: () => targets,
      onChange: (domain, status) => changes.push([domain, status]),
      connect: fakeConnect(ports),
    })

    await mon.refresh() // up
    await mon.refresh() // still up — no new change
    expect(changes).toEqual([["a", "up"]])
    expect(mon.getStatus("a")).toBe("up")

    ports.set(1000, false)
    await mon.refresh() // up → down
    await mon.refresh() // still down — no new change
    expect(changes).toEqual([["a", "up"], ["a", "down"]])

    ports.set(1000, true)
    await mon.refresh() // down → up
    expect(changes).toEqual([["a", "up"], ["a", "down"], ["a", "up"]])
  })
})

describe("ProxyLivenessMonitor.refresh — prune", () => {
  test("a domain dropped from listTargets is removed from the cache and re-probed on re-add", async () => {
    const ports = new Map<number, boolean>([[2000, true]])
    let targets: ProxyTarget[] = [{ domain: "ghost", port: 2000 }]
    const calls: number[] = []
    const changes: [string, string][] = []
    const mon = new ProxyLivenessMonitor({
      listTargets: () => targets,
      onChange: (domain, status) => changes.push([domain, status]),
      connect: fakeConnect(ports, calls),
    })

    await mon.refresh()
    expect(mon.getStatus("ghost")).toBe("up")

    // Drop it: status returns to "unknown" and it is no longer probed.
    targets = []
    await mon.refresh()
    expect(mon.getStatus("ghost")).toBe("unknown")
    const callsAfterPrune = calls.length

    await mon.refresh()
    expect(calls.length).toBe(callsAfterPrune) // not probed while absent

    // Re-add: it re-probes from scratch and emits a fresh transition.
    targets = [{ domain: "ghost", port: 2000 }]
    await mon.refresh()
    expect(mon.getStatus("ghost")).toBe("up")
    expect(changes).toEqual([["ghost", "up"], ["ghost", "up"]])
  })
})

describe("ProxyLivenessMonitor.refresh — resilience", () => {
  test("a connect that throws does not blow up refresh()", async () => {
    const mon = new ProxyLivenessMonitor({
      listTargets: () => [{ domain: "boom", port: 3000 }],
      onChange: () => { throw new Error("onChange should not be reached") },
      connect: async () => { throw new Error("probe exploded") },
    })

    await expect(mon.refresh()).resolves.toBeUndefined()
    expect(mon.getStatus("boom")).toBe("unknown")
  })
})

describe("ProxyLivenessMonitor.start/stop", () => {
  test("start() kicks an immediate refresh and is idempotent; stop() halts polling", async () => {
    const ports = new Map<number, boolean>([[4000, true]])
    const calls: number[] = []
    const mon = new ProxyLivenessMonitor({
      listTargets: () => [{ domain: "x", port: 4000 }],
      onChange: () => {},
      connect: fakeConnect(ports, calls),
      intervalMs: 60_000, // long, so only the immediate refresh runs in this test
    })

    mon.start()
    mon.start() // idempotent — must not schedule a second interval or extra refresh
    // Let the immediate (async) refresh settle.
    await mon.refresh()
    expect(mon.getStatus("x")).toBe("up")

    mon.stop()
    mon.stop() // idempotent
  })
})
