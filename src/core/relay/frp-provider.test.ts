import { expect, test } from "bun:test"
import { FrpRelayProvider } from "./frp-provider"

function fakeIdentity(hostId = "habc") {
  return { hostId, publicKeyRaw: Buffer.alloc(32), sign: (m: Buffer) => Buffer.concat([Buffer.from("sig:"), m]), verify: () => true }
}

test("start acquires a lease, spawns frpc for the right subdomain, reports online", async () => {
  const spawned: string[][] = []
  const provider = new FrpRelayProvider({
    identity: fakeIdentity("habc"),
    relayBase: "https://relay.supermux.dev",
    relayDomain: "relay.supermux.dev",
    localPort: 9898,
    fetchImpl: async () => new Response(JSON.stringify({ lease: "habc.9999.sig", nonce: "n1" }), { status: 200 }),
    getNonce: async () => "n1",
    spawn: (argv) => { spawned.push(argv); return { kill: () => {}, exited: new Promise(() => {}) } },
    writeConfig: () => "/tmp/frpc.ini",
  })
  await provider.start()
  const st = provider.status()
  expect(st.state).toBe("online")
  expect(st.relayUrl).toBe("https://h-habc.relay.supermux.dev")
  expect(spawned.length).toBe(1)
  expect(spawned[0]![0]).toContain("frpc")
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
