import { expect, test } from "bun:test"
import type { Logger } from "../../shared/log"
import { FrpRelayProvider, parentBoundFrpcCommand, type FrpChild, type FrpProviderOpts } from "./frp-provider"

function fakeIdentity(hostId = "habc") {
  return { hostId, publicKeyRaw: Buffer.alloc(32), sign: (m: Buffer) => Buffer.concat([Buffer.from("sig:"), m]), verify: () => true }
}

type TimerRecord = { fn: () => void; delay: number; cleared: boolean }

function fakeTimers() {
  const timers: TimerRecord[] = []
  return {
    timers,
    setTimer(fn: () => void, delay: number) {
      const timer = { fn, delay, cleared: false }
      timers.push(timer)
      return timer as unknown as ReturnType<typeof setTimeout>
    },
    clearTimer(timer: ReturnType<typeof setTimeout>) {
      ;(timer as unknown as TimerRecord).cleared = true
    },
    active(delay: number) {
      return timers.filter((timer) => !timer.cleared && timer.delay === delay)
    },
  }
}

type LogRecord = { level: "info" | "warn"; event: string; fields?: Record<string, unknown> }

function fakeLogger() {
  const records: LogRecord[] = []
  const log: Pick<Logger, "info" | "warn"> = {
    info: (event, fields) => { records.push({ level: "info", event, fields }) },
    warn: (event, fields) => { records.push({ level: "warn", event, fields }) },
  }
  return { log, records }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}

function fakeChild(): FrpChild & { killed: boolean; exit(): void } {
  const done = deferred<unknown>()
  return {
    killed: false,
    kill() { this.killed = true },
    exited: done.promise,
    exit() { done.resolve(undefined) },
  }
}

async function settle(): Promise<void> {
  for (let i = 0; i < 8; i++) await Promise.resolve()
}

async function fire(timer: TimerRecord): Promise<void> {
  timer.cleared = true
  timer.fn()
  await settle()
}

function jsonResponse(body: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  } as Response
}

function providerOpts(overrides: Partial<FrpProviderOpts> = {}): FrpProviderOpts {
  return {
    identity: fakeIdentity(),
    relayBase: "https://control.relay.supermux.dev",
    relayDomain: "relay.supermux.dev",
    localPort: 9898,
    fetchImpl: async () => jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 }),
    getNonce: async () => "n1",
    spawn: () => fakeChild(),
    writeConfig: () => "/tmp/frpc.toml",
    now: () => 100_000,
    ...overrides,
  }
}

test("POSIX frpc is wrapped by a parent-death supervisor without shell-interpolating arguments", () => {
  const argv = ["frpc", "-c", "/tmp/path with spaces/frpc.toml"]
  const command = parentBoundFrpcCommand(argv, "linux")
  expect(command.slice(-argv.length)).toEqual(argv)
  expect(command.slice(0, 2)).toEqual(["/bin/sh", "-c"])
  expect(parentBoundFrpcCommand(argv, "win32")).toEqual(argv)
})

test("start acquires a lease, spawns frpc for the right subdomain, reports online", async () => {
  const spawned: string[][] = []
  let leaseRequest: Record<string, unknown> | undefined
  let writtenConfig = ""
  const timers = fakeTimers()
  const provider = new FrpRelayProvider(providerOpts({
    identity: fakeIdentity("habc"),
    fetchImpl: async (_url, init) => {
      leaseRequest = JSON.parse(init?.body ?? "{}")
      return jsonResponse({ lease: "habc.3700000.sig" })
    },
    spawn: (argv) => { spawned.push(argv); return fakeChild() },
    writeConfig: (config) => { writtenConfig = config; return "/tmp/frpc.toml" },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
  }))
  await provider.start()
  const st = provider.status()
  expect(st.state).toBe("online")
  expect(st.relayUrl).toBe("https://h-habc.relay.supermux.dev")
  expect(spawned).toHaveLength(1)
  expect(spawned[0]![0]).toContain("frpc")
  expect(leaseRequest?.publicKey).toBe(Buffer.alloc(32).toString("base64url"))
  expect(writtenConfig).toContain('serverAddr = "control.relay.supermux.dev"')
  expect(writtenConfig).toContain('name = "web-habc"')
  expect(writtenConfig).toContain("[metadatas]")
  await provider.stop()
})

test("successful startup schedules independent proactive renewal and recurring lease audit", async () => {
  const timers = fakeTimers()
  const provider = new FrpRelayProvider(providerOpts({ setTimer: timers.setTimer, clearTimer: timers.clearTimer }))
  await provider.start()
  expect(timers.active(3_300_000)).toHaveLength(1)
  expect(timers.active(300_000)).toHaveLength(1)
  await provider.stop()
})

test("proactive renewal clamps to the exact minimum and maximum timer delays", async () => {
  const cases = [
    { expiresAt: 200_000, expectedDelay: 30_000 },
    { expiresAt: 100_000 + 300_000 + 2_147_483_647 + 1, expectedDelay: 2_147_483_647 },
  ]
  for (const { expiresAt, expectedDelay } of cases) {
    const timers = fakeTimers()
    const provider = new FrpRelayProvider(providerOpts({
      fetchImpl: async () => jsonResponse({ lease: `habc.${expiresAt}.sig`, expiresAt }),
      setTimer: timers.setTimer,
      clearTimer: timers.clearTimer,
    }))
    await provider.start()
    expect(timers.active(expectedDelay)).toHaveLength(1)
    await provider.stop()
  }
})

test("healthy audits are silent and network-free, while an overdue audit starts one recovery", async () => {
  let now = 100_000
  let nonceCalls = 0
  let leaseCalls = 0
  const timers = fakeTimers()
  const logger = fakeLogger()
  const provider = new FrpRelayProvider(providerOpts({
    now: () => now,
    getNonce: async () => { nonceCalls++; return "n1" },
    fetchImpl: async () => { leaseCalls++; return jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 }) },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
    log: logger.log,
  }))
  await provider.start()
  logger.records.length = 0

  await fire(timers.active(300_000)[0]!)
  expect(nonceCalls).toBe(1)
  expect(leaseCalls).toBe(1)
  expect(logger.records).toEqual([])

  now = 3_400_000
  await fire(timers.active(300_000)[0]!)
  expect(nonceCalls).toBe(2)
  expect(leaseCalls).toBe(2)
  expect(logger.records.filter((record) => record.event === "relay_lease_audit_recovery")).toHaveLength(1)
  await provider.stop()
})

test("an overdue audit keeps recurring when its acquisition fails", async () => {
  let now = 100_000
  let leaseCalls = 0
  const timers = fakeTimers()
  const provider = new FrpRelayProvider(providerOpts({
    now: () => now,
    fetchImpl: async () => ++leaseCalls === 1
      ? jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 })
      : jsonResponse({}, 503),
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
  }))
  await provider.start()
  now = 3_400_000
  await fire(timers.active(300_000)[0]!)
  expect(timers.active(300_000)).toHaveLength(1)
  expect(timers.active(5_000)).toHaveLength(1)
  await provider.stop()
})

test("failed renewal preserves the child and URL, logs safely, and schedules a five-second retry", async () => {
  const child = fakeChild()
  const timers = fakeTimers()
  const logger = fakeLogger()
  let leaseCalls = 0
  const provider = new FrpRelayProvider(providerOpts({
    fetchImpl: async () => ++leaseCalls === 1
      ? jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 })
      : jsonResponse({}, 503),
    spawn: () => child,
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
    log: logger.log,
  }))
  await provider.start()
  logger.records.length = 0

  await fire(timers.active(3_300_000)[0]!)
  expect(child.killed).toBe(false)
  expect(provider.status()).toEqual({ state: "online", relayUrl: "https://h-habc.relay.supermux.dev" })
  expect(timers.active(5_000)).toHaveLength(1)
  expect(logger.records).toContainEqual({
    level: "warn",
    event: "relay_lease_acquire_failed",
    fields: { trigger: "renewal", error: "lease_http_error", status: 503, preservedChild: true, nextRetryMs: 5_000 },
  })
  await provider.stop()
})

test("retry backoff follows the exact sequence and remains capped at five minutes", async () => {
  const timers = fakeTimers()
  let leaseCalls = 0
  const provider = new FrpRelayProvider(providerOpts({
    fetchImpl: async () => ++leaseCalls === 1
      ? jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 })
      : jsonResponse({}, 503),
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
  }))
  await provider.start()
  await fire(timers.active(3_300_000)[0]!)

  for (const delay of [5_000, 10_000, 20_000, 30_000, 60_000, 120_000, 300_000, 300_000]) {
    const retry = timers.active(delay).at(-1)
    expect(retry).toBeDefined()
    await fire(retry!)
  }
  expect(leaseCalls).toBe(10)
  expect(timers.active(300_000).at(-1)).toBeDefined()
  await provider.stop()
})

test("a thrown nonce error uses the same preserve-and-retry path", async () => {
  const child = fakeChild()
  const timers = fakeTimers()
  const logger = fakeLogger()
  let nonceCalls = 0
  const provider = new FrpRelayProvider(providerOpts({
    getNonce: async () => {
      if (++nonceCalls > 1) throw new Error("network unavailable")
      return "n1"
    },
    spawn: () => child,
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
    log: logger.log,
  }))
  await provider.start()
  logger.records.length = 0
  await fire(timers.active(3_300_000)[0]!)
  expect(child.killed).toBe(false)
  expect(provider.status().state).toBe("online")
  expect(timers.active(5_000)).toHaveLength(1)
  expect(logger.records.find((record) => record.event === "relay_lease_acquire_failed")?.fields).toEqual({
    trigger: "renewal",
    error: "nonce_failed",
    preservedChild: true,
    nextRetryMs: 5_000,
  })
  await provider.stop()
})

test("a config write failure preserves the live child and retries", async () => {
  const child = fakeChild()
  const timers = fakeTimers()
  let writes = 0
  const provider = new FrpRelayProvider(providerOpts({
    spawn: () => child,
    writeConfig: () => {
      if (++writes > 1) throw new Error("config write failed")
      return "/tmp/frpc.toml"
    },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
  }))
  await provider.start()
  await fire(timers.active(3_300_000)[0]!)
  expect(child.killed).toBe(false)
  expect(provider.status().state).toBe("online")
  expect(timers.active(5_000)).toHaveLength(1)
  await provider.stop()
})

test("eventual retry success writes config before replacing the old child and resets scheduling", async () => {
  const timers = fakeTimers()
  const oldChild = fakeChild()
  const newChild = fakeChild()
  const events: string[] = []
  let leaseCalls = 0
  let spawns = 0
  oldChild.kill = () => { oldChild.killed = true; events.push("kill") }
  const provider = new FrpRelayProvider(providerOpts({
    fetchImpl: async () => {
      leaseCalls++
      if (leaseCalls === 2 || leaseCalls === 4) return jsonResponse({}, 503)
      return jsonResponse({ lease: "habc.7300000.sig", expiresAt: 7_300_000 })
    },
    writeConfig: () => { events.push("write"); return "/tmp/frpc.toml" },
    spawn: () => { events.push("spawn"); return spawns++ === 0 ? oldChild : newChild },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
  }))
  await provider.start()
  events.length = 0
  await fire(timers.active(6_900_000)[0]!)
  await fire(timers.active(5_000)[0]!)
  expect(events).toEqual(["write", "spawn", "kill"])
  expect(oldChild.killed).toBe(true)
  expect(spawns).toBe(2)
  expect(provider.status().state).toBe("online")
  expect(timers.active(6_900_000)).toHaveLength(1)
  expect(timers.active(300_000)).toHaveLength(1)

  await fire(timers.active(6_900_000)[0]!)
  expect(timers.active(5_000)).toHaveLength(1)
  await provider.stop()
})

test("a replacement spawn failure preserves the old child and online state", async () => {
  const child = fakeChild()
  const timers = fakeTimers()
  let spawns = 0
  const provider = new FrpRelayProvider(providerOpts({
    spawn: () => {
      if (++spawns > 1) throw new Error("spawn failed")
      return child
    },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
  }))
  await provider.start()
  await fire(timers.active(3_300_000)[0]!)
  expect(child.killed).toBe(false)
  expect(provider.status()).toEqual({ state: "online", relayUrl: "https://h-habc.relay.supermux.dev" })
  expect(timers.active(5_000)).toHaveLength(1)
  await provider.stop()
})

test("malformed successful lease responses preserve the existing child and retry", async () => {
  const malformed = [
    {},
    { lease: 42, expiresAt: 3_700_000 },
    { lease: "", expiresAt: 3_700_000 },
    { lease: "habc.not-a-number.sig" },
    { lease: "habc.3700000.sig", expiresAt: Number.NaN },
    { lease: "habc.3700000.sig", expiresAt: Number.POSITIVE_INFINITY },
    { lease: "habc.100000.sig", expiresAt: 100_000 },
    { lease: "habc.100000.sig" },
  ]

  for (const body of malformed) {
    const timers = fakeTimers()
    const child = fakeChild()
    let calls = 0
    const provider = new FrpRelayProvider(providerOpts({
      fetchImpl: async () => ++calls === 1
        ? jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 })
        : jsonResponse(body),
      spawn: () => child,
      setTimer: timers.setTimer,
      clearTimer: timers.clearTimer,
    }))
    await provider.start()
    await fire(timers.active(3_300_000)[0]!)
    expect(child.killed).toBe(false)
    expect(provider.status().state).toBe("online")
    expect(timers.active(5_000)).toHaveLength(1)
    await provider.stop()
  }
})

test("lease expiry falls back to the signed-token component when expiresAt is absent", async () => {
  const timers = fakeTimers()
  const provider = new FrpRelayProvider(providerOpts({
    fetchImpl: async () => jsonResponse({ lease: "habc.3700000.sig" }),
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
  }))
  await provider.start()
  expect(provider.status().state).toBe("online")
  expect(timers.active(3_300_000)).toHaveLength(1)
  await provider.stop()
})

test("current child exit logs and schedules one-second recovery, but replaced child exit is ignored", async () => {
  const timers = fakeTimers()
  const logger = fakeLogger()
  const first = fakeChild()
  const second = fakeChild()
  let spawnCount = 0
  const provider = new FrpRelayProvider(providerOpts({
    fetchImpl: async () => jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 }),
    spawn: () => spawnCount++ === 0 ? first : second,
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
    log: logger.log,
  }))
  await provider.start()
  await fire(timers.active(3_300_000)[0]!)
  logger.records.length = 0
  first.exit()
  await settle()
  expect(timers.active(1_000)).toHaveLength(0)
  expect(logger.records.filter((record) => record.event === "relay_frpc_exited")).toHaveLength(0)

  second.exit()
  await settle()
  expect(provider.status().state).toBe("connecting")
  expect(timers.active(1_000)).toHaveLength(1)
  expect(logger.records.filter((record) => record.event === "relay_frpc_exited")).toHaveLength(1)
  await provider.stop()
})

test("stop clears every timer and stale callbacks cannot fetch or spawn", async () => {
  const timers = fakeTimers()
  let fetches = 0
  let spawns = 0
  const provider = new FrpRelayProvider(providerOpts({
    fetchImpl: async () => { fetches++; return jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 }) },
    spawn: () => { spawns++; return fakeChild() },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
  }))
  await provider.start()
  const callbacks = timers.timers.map((timer) => timer.fn)
  await provider.stop()
  expect(timers.timers.every((timer) => timer.cleared)).toBe(true)
  for (const callback of callbacks) callback()
  await settle()
  expect(fetches).toBe(1)
  expect(spawns).toBe(1)
  expect(provider.status().state).toBe("disabled")
})

test("stop invalidates an acquisition blocked on nonce before it can fetch or spawn", async () => {
  const nonce = deferred<string>()
  let fetches = 0
  let spawns = 0
  const provider = new FrpRelayProvider(providerOpts({
    getNonce: () => nonce.promise,
    fetchImpl: async () => { fetches++; return jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 }) },
    spawn: () => { spawns++; return fakeChild() },
  }))
  const starting = provider.start()
  await settle()
  await provider.stop()
  nonce.resolve("stale")
  await starting
  expect(fetches).toBe(0)
  expect(spawns).toBe(0)
  expect(provider.status().state).toBe("disabled")
})

test("restart can acquire while a stopped generation is still blocked on nonce", async () => {
  const staleNonce = deferred<string>()
  let nonceCalls = 0
  let fetches = 0
  let spawns = 0
  const provider = new FrpRelayProvider(providerOpts({
    getNonce: () => ++nonceCalls === 1 ? staleNonce.promise : Promise.resolve("fresh"),
    fetchImpl: async () => { fetches++; return jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 }) },
    spawn: () => { spawns++; return fakeChild() },
  }))
  const staleStart = provider.start()
  await settle()
  await provider.stop()
  await provider.start()
  expect(fetches).toBe(1)
  expect(spawns).toBe(1)
  expect(provider.status().state).toBe("online")

  staleNonce.resolve("stale")
  await staleStart
  expect(fetches).toBe(1)
  expect(spawns).toBe(1)
  expect(provider.status().state).toBe("online")
  await provider.stop()
})

test("audit and retry callbacks never create concurrent acquisitions", async () => {
  let now = 100_000
  let nonceCalls = 0
  let leaseCalls = 0
  const pendingNonce = deferred<string>()
  const timers = fakeTimers()
  const logger = fakeLogger()
  const provider = new FrpRelayProvider(providerOpts({
    now: () => now,
    getNonce: async () => {
      nonceCalls++
      if (nonceCalls === 3) return pendingNonce.promise
      return "n1"
    },
    fetchImpl: async () => {
      leaseCalls++
      return leaseCalls === 2
        ? jsonResponse({}, 503)
        : jsonResponse({ lease: "habc.3700000.sig", expiresAt: 3_700_000 })
    },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
    log: logger.log,
  }))
  await provider.start()
  now = 3_400_000
  await fire(timers.active(3_300_000)[0]!)
  expect(timers.active(5_000)).toHaveLength(1)

  await fire(timers.active(5_000)[0]!)
  expect(nonceCalls).toBe(3)
  await fire(timers.active(300_000)[0]!)
  expect(nonceCalls).toBe(3)
  expect(logger.records.filter((record) => record.event === "relay_lease_audit_recovery")).toHaveLength(0)

  pendingNonce.resolve("n2")
  await settle()
  expect(leaseCalls).toBe(3)
  await provider.stop()
})

test("initial acquisition failure reports error and retries without spawning", async () => {
  const timers = fakeTimers()
  let spawns = 0
  const provider = new FrpRelayProvider(providerOpts({
    fetchImpl: async () => jsonResponse({}, 500),
    spawn: () => { spawns++; return fakeChild() },
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
  }))
  await provider.start()
  expect(provider.status().state).toBe("error")
  expect(spawns).toBe(0)
  expect(timers.active(5_000)).toHaveLength(1)
  await provider.stop()
})

test("acquisition failure logs use stable codes and never serialize external secrets", async () => {
  const secret = "TOP_SECRET_LEASE_AND_CONFIG"
  const lease = `habc.3700000.${secret}`
  const cases: Array<{
    expectedCode: string
    overrides: (logger: ReturnType<typeof fakeLogger>) => Partial<FrpProviderOpts>
  }> = [
    {
      expectedCode: "nonce_failed",
      overrides: (logger) => ({
        getNonce: async () => { throw new Error(secret) },
        log: logger.log,
      }),
    },
    {
      expectedCode: "config_write_failed",
      overrides: (logger) => ({
        fetchImpl: async () => jsonResponse({ lease, expiresAt: 3_700_000 }),
        writeConfig: (toml) => { throw new Error(`${secret}:${lease}:${toml}`) },
        log: logger.log,
      }),
    },
    {
      expectedCode: "frpc_spawn_failed",
      overrides: (logger) => ({
        fetchImpl: async () => jsonResponse({ lease, expiresAt: 3_700_000 }),
        spawn: () => { throw new Error(`${secret}:${lease}:serverAddr = secret`) },
        log: logger.log,
      }),
    },
  ]

  for (const { expectedCode, overrides } of cases) {
    const logger = fakeLogger()
    const provider = new FrpRelayProvider(providerOpts(overrides(logger)))
    await provider.start()
    const serialized = JSON.stringify(logger.records)
    expect(serialized).not.toContain(secret)
    expect(serialized).not.toContain(lease)
    expect(serialized).not.toContain("serverAddr")
    expect(logger.records.find((record) => record.event === "relay_lease_acquire_failed")?.fields?.error).toBe(expectedCode)
    await provider.stop()
  }
})

test("successful acquisition and shutdown emit the specified lifecycle fields", async () => {
  const timers = fakeTimers()
  const logger = fakeLogger()
  const provider = new FrpRelayProvider(providerOpts({
    setTimer: timers.setTimer,
    clearTimer: timers.clearTimer,
    log: logger.log,
  }))
  await provider.start()
  expect(logger.records).toContainEqual({
    level: "info",
    event: "relay_lease_acquire_started",
    fields: { trigger: "startup" },
  })
  expect(logger.records).toContainEqual({
    level: "info",
    event: "relay_lease_acquired",
    fields: { hostId: "habc", expiresAt: 3_700_000, trigger: "startup" },
  })
  expect(logger.records).toContainEqual({
    level: "info",
    event: "relay_frpc_started",
    fields: { hostId: "habc" },
  })
  await provider.stop()
  expect(logger.records).toContainEqual({
    level: "info",
    event: "relay_stopped",
    fields: { hostId: "habc" },
  })
})

test("stop kills the sidecar, reports disabled, and logs without observer failures escaping", async () => {
  const child = fakeChild()
  const provider = new FrpRelayProvider(providerOpts({
    spawn: () => child,
    log: {
      info: () => { throw new Error("observer failed") },
      warn: () => { throw new Error("observer failed") },
    },
  }))
  await provider.start()
  await provider.stop()
  expect(child.killed).toBe(true)
  expect(provider.status().state).toBe("disabled")
})
