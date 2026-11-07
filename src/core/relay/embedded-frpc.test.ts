/**
 * The relay provider running the in-process frp client against a real frps (FRPS_BIN, else skipped) guarded by
 * the relay's real auth plugin, with real leases: what production runs, minus the network.
 */
import { afterAll, beforeAll, describe, expect, test, setDefaultTimeout } from "bun:test"
import { spawn, spawnSync, type ChildProcess } from "child_process"
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from "fs"
import { createServer } from "net"
import { tmpdir } from "os"
import { join } from "path"
import { handleAuthOp } from "./auth-plugin"
import { embeddedFrpc } from "./embedded-frpc"
import { FrpRelayProvider } from "./frp-provider"
import { mintLease } from "./lease"

setDefaultTimeout(30_000)
const FRPS = process.env.FRPS_BIN ?? ""
const enabled = FRPS !== "" && existsSync(FRPS) && spawnSync("openssl", ["version"]).status === 0
const SECRET = "relay-secret"

async function freePort(): Promise<number> {
  const server = createServer()
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r))
  const port = (server.address() as { port: number }).port
  await new Promise(r => server.close(r))
  return port
}

function identity(hostId: string) {
  return { hostId, publicKeyRaw: Buffer.alloc(32), sign: (m: Buffer) => m }
}

const quietLog = { info: () => {}, warn: () => {} }

describe.skipIf(!enabled)("relay provider with the embedded frp client", () => {
  const dir = mkdtempSync(join(tmpdir(), "relay-embedded-"))
  let frps: ChildProcess
  let plugin: ReturnType<typeof Bun.serve>
  let backend: ReturnType<typeof Bun.serve>
  let bindPort = 0
  let vhostPort = 0
  let ca = ""
  const rejections: string[] = []
  const providers: FrpRelayProvider[] = []

  /** A provider whose control plane mints a real lease for `leaseHost`, claiming h-<hostId>. */
  function provider(hostId: string, leaseHost = hostId, ttlMs = 600_000) {
    const p = new FrpRelayProvider({
      identity: identity(hostId),
      relayBase: "https://127.0.0.1",
      relayDomain: "relay.test",
      serverPort: bindPort,
      localPort: backend.port!,
      getNonce: async () => "nonce",
      fetchImpl: async () => {
        const now = Date.now()
        const lease = mintLease({ hostId: leaseHost, secret: SECRET, ttlMs, now })
        return new Response(JSON.stringify({ lease, expiresAt: now + ttlMs }), { headers: { "content-type": "application/json" } })
      },
      start: embeddedFrpc({ verifyTls: true, tls: { ca, serverName: "relay.test" }, log: quietLog }),
      log: quietLog,
    })
    providers.push(p)
    return p
  }

  const via = (hostId: string, path: string) =>
    fetch(`http://127.0.0.1:${vhostPort}${path}`, { headers: { Host: `h-${hostId}.relay.test` } })

  async function online(p: FrpRelayProvider) {
    for (let i = 0; i < 200 && p.status().state !== "online"; i++) await Bun.sleep(25)
    return p.status()
  }

  beforeAll(async () => {
    const run = (...args: string[]) => {
      const r = spawnSync("openssl", args, { cwd: dir })
      if (r.status !== 0) throw new Error(String(r.stderr))
    }
    run("req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-days", "1", "-subj", "/CN=test CA", "-keyout", "ca.key", "-out", "ca.crt")
    run("req", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-subj", "/CN=relay.test", "-keyout", "server.key", "-out", "server.csr")
    writeFileSync(join(dir, "ext.cnf"), "subjectAltName=DNS:relay.test\n")
    run("x509", "-req", "-in", "server.csr", "-CA", "ca.crt", "-CAkey", "ca.key", "-CAcreateserial", "-days", "1", "-extfile", "ext.cnf", "-out", "server.crt")
    ca = readFileSync(join(dir, "ca.crt"), "utf8")

    backend = Bun.serve({ hostname: "127.0.0.1", port: 0, fetch: req => new Response(`broker ${new URL(req.url).pathname}`) })
    plugin = Bun.serve({
      hostname: "127.0.0.1",
      port: 0,
      async fetch(req) {
        const op = await req.json() as Parameters<typeof handleAuthOp>[0]
        return Response.json(handleAuthOp(op, { secret: SECRET, onReject: e => rejections.push(e.reason) }))
      },
    })
    ;[bindPort, vhostPort] = await Promise.all([freePort(), freePort()])
    writeFileSync(join(dir, "frps.toml"), [
      'bindAddr = "127.0.0.1"', `bindPort = ${bindPort}`, `vhostHTTPPort = ${vhostPort}`, 'subdomainHost = "relay.test"',
      'proxyBindAddr = "127.0.0.1"', "transport.tls.force = true",
      `transport.tls.certFile = "${join(dir, "server.crt")}"`, `transport.tls.keyFile = "${join(dir, "server.key")}"`,
      "[[httpPlugins]]", 'name = "supermux-auth"', `addr = "127.0.0.1:${plugin.port}"`, 'path = "/handler"', 'ops = ["Login", "NewProxy"]',
    ].join("\n"))
    frps = spawn(FRPS, ["-c", join(dir, "frps.toml")], { stdio: "ignore" })
    for (let i = 0; i < 200; i++) {
      const listening = await new Promise<boolean>(r => {
        const s = createServer().once("error", () => r(true)).listen(bindPort, "127.0.0.1", () => s.close(() => r(false)))
      })
      if (listening) break
      await Bun.sleep(25)
    }
  })

  afterAll(async () => {
    for (const p of providers) await p.stop()
    frps?.kill()
    plugin?.stop(true)
    backend?.stop(true)
  })

  test("a host comes online through the auth plugin and serves its broker", async () => {
    const p = provider("hostaaa")
    await p.start()
    expect(await online(p)).toMatchObject({ state: "online" })
    expect(await (await via("hostaaa", "/me")).text()).toBe("broker /me")
  })

  test("a lease issued for another host is refused before anything reaches frps", async () => {
    const p = provider("hostbbb", "hostccc")
    await p.start()
    await Bun.sleep(300)
    expect(p.status()).toEqual({ state: "error", detail: "lease_response_invalid" })
    expect(rejections).toEqual([])
    expect((await via("hostbbb", "/x")).status).toBe(404)
    await p.stop()
  })

  test("renewing the lease swaps in a new session without losing the hostname", async () => {
    const p = provider("hostddd")
    await p.start()
    await online(p)
    // A wake re-acquires: a new lease and a new client replacing the old one.
    await p.refreshAfterWake()
    expect(await online(p)).toMatchObject({ state: "online" })
    expect(await (await via("hostddd", "/after")).text()).toBe("broker /after")
  })

  test("stop releases the hostname", async () => {
    const p = provider("hosteee")
    await p.start()
    await online(p)
    await p.stop()
    for (let i = 0; i < 40 && (await via("hosteee", "/gone")).status === 200; i++) await Bun.sleep(25)
    expect((await via("hosteee", "/gone")).status).toBe(404)
  })
})
