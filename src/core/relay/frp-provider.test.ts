import { expect, test } from "bun:test"
import { FrpRelayProvider, parentBoundFrpcCommand } from "./frp-provider"

function fakeIdentity(hostId = "habc") {
  return { hostId, publicKeyRaw: Buffer.alloc(32), sign: (m: Buffer) => Buffer.concat([Buffer.from("sig:"), m]), verify: () => true }
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
  const provider = new FrpRelayProvider({
    identity: fakeIdentity("habc"),
    relayBase: "https://relay.supermux.dev",
    relayDomain: "relay.supermux.dev",
    localPort: 9898,
    fetchImpl: async (_url, init) => {
      leaseRequest = JSON.parse(init?.body ?? "{}")
      return new Response(JSON.stringify({ lease: "habc.9999.sig", nonce: "n1" }), { status: 200 })
    },
    getNonce: async () => "n1",
    spawn: (argv) => { spawned.push(argv); return { kill: () => {}, exited: new Promise(() => {}) } },
    writeConfig: (config) => { writtenConfig = config; return "/tmp/frpc.ini" },
  })
  await provider.start()
  const st = provider.status()
  expect(st.state).toBe("online")
  expect(st.relayUrl).toBe("https://h-habc.relay.supermux.dev")
  expect(spawned.length).toBe(1)
  expect(spawned[0]![0]).toContain("frpc")
  expect(leaseRequest?.publicKey).toBe(Buffer.alloc(32).toString("base64url"))
  expect(writtenConfig).toContain('serverAddr = "relay.supermux.dev"')
  expect(writtenConfig).toContain("[metadatas]")
  await provider.stop()
})

test("a live tunnel schedules proactive lease renewal before expiry", async () => {
  let scheduledDelay = 0
  const provider = new FrpRelayProvider({
    identity: fakeIdentity(), relayBase: "https://control.relay.supermux.dev", relayDomain: "relay.supermux.dev", localPort: 9898,
    fetchImpl: async () => new Response(JSON.stringify({ lease: "habc.9999999.sig", expiresAt: 3_700_000 }), { status: 200 }),
    getNonce: async () => "n1",
    spawn: () => ({ kill: () => {}, exited: new Promise(() => {}) }),
    writeConfig: () => "/tmp/frpc.ini",
    now: () => 100_000,
    setTimer: (_fn, delay) => { scheduledDelay = delay; return 1 as unknown as ReturnType<typeof setTimeout> },
    clearTimer: () => {},
  })
  await provider.start()
  expect(scheduledDelay).toBe(3_300_000)
  await provider.stop()
})

test("the FRP control connection uses the relayBase hostname", async () => {
  let writtenConfig = ""
  const provider = new FrpRelayProvider({
    identity: fakeIdentity(), relayBase: "https://control.relay.supermux.dev", relayDomain: "relay.supermux.dev", localPort: 9898,
    fetchImpl: async () => new Response(JSON.stringify({ lease: "habc.9999999999999.sig" }), { status: 200 }),
    getNonce: async () => "n1",
    spawn: () => ({ kill: () => {}, exited: new Promise(() => {}) }),
    writeConfig: (config) => { writtenConfig = config; return "/tmp/frpc.ini" },
  })
  await provider.start()
  expect(writtenConfig).toContain('serverAddr = "control.relay.supermux.dev"')
  await provider.stop()
})

test("a failed lease request reports error, does not spawn", async () => {
  const spawned: string[][] = []
  const provider = new FrpRelayProvider({
    identity: fakeIdentity(),
    relayBase: "https://relay.supermux.dev", relayDomain: "relay.supermux.dev", localPort: 9898,
    fetchImpl: async () => new Response("no", { status: 500 }),
    getNonce: async () => "n1",
    spawn: (argv) => { spawned.push(argv); return { kill: () => {}, exited: new Promise(() => {}) } },
    writeConfig: () => "/tmp/frpc.ini",
  })
  await provider.start()
  expect(provider.status().state).toBe("error")
  expect(spawned.length).toBe(0)
})

test("stop kills the sidecar and reports disabled", async () => {
  let killed = false
  const provider = new FrpRelayProvider({
    identity: fakeIdentity(),
    relayBase: "https://relay.supermux.dev", relayDomain: "relay.supermux.dev", localPort: 9898,
    fetchImpl: async () => new Response(JSON.stringify({ lease: "habc.9999.sig" }), { status: 200 }),
    getNonce: async () => "n1",
    spawn: () => ({ kill: () => { killed = true }, exited: new Promise(() => {}) }),
    writeConfig: () => "/tmp/frpc.ini",
  })
  await provider.start()
  await provider.stop()
  expect(killed).toBe(true)
  expect(provider.status().state).toBe("disabled")
})
