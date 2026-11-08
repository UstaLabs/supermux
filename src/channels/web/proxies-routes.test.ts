// POST /proxies: a named proxy can't be digits-only, because /p/<digits>/ means a port. The refusal is the registry's
// (every way of naming a proxy lands in Registry.addProxy); the route's part is turning the thrown error into a 400
// with the reason in the body, and that is what these pin.
import { afterEach, expect, test } from "bun:test"
import { randomBytes } from "crypto"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { buildProxyPublicUrl } from "./proxy"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { Registry } from "../../core/session-manager/registry"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

async function boot() {
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-proxies-routes-")), "devices.json")
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const registry = new Registry(db)
  registry.register({ name: "alpha", workdir: "/tmp", tmux_target: "t:alpha", pid: 1 })
  const token = new DeviceStore(devicesFile).mint("test-device").token
  const opts: WebChannelOpts = {
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    // main.ts's createProxy can't be imported (importing main.ts boots the broker), so this repeats its steps:
    // find the session, default the name to px-<8 hex>, hand it to registry.addProxy.
    createProxy: (args) => {
      const owner = registry.resolveName(args.sessionName)
      if (!owner) throw new Error(`no such session: ${args.sessionName}`)
      const domain = args.domain || "px-" + randomBytes(4).toString("hex")
      const entry = registry.addProxy({ domain, sessionId: owner.id, port: args.port })
      return { url: buildProxyPublicUrl(entry.domain, { publicUrl: "http://localhost" }), domain: entry.domain, port: entry.port }
    },
  }
  channel = new WebChannel(opts)
  await channel.start()
  const post = (body: unknown) => fetch(`http://127.0.0.1:${channel!.boundPort}/proxies`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify(body),
  })
  return { registry, post }
}

test("POST /proxies refuses a digits-only name with a 400 that says why", async () => {
  const { registry, post } = await boot()
  for (const domain of ["5173", "0", "007"]) {
    const res = await post({ sessionName: "alpha", port: 3000, domain })
    expect(res.status).toBe(400)
    expect(await res.json()).toEqual({ error: `proxy names can't be only digits: /p/${domain}/ means a port` })
  }
  expect(registry.listProxies()).toEqual([])
})

test("POST /proxies refuses a domain that is not a string, so a JSON number or boolean can't become a digits-only name", async () => {
  const { registry, post } = await boot()
  for (const domain of [5173, true]) {
    const res = await post({ sessionName: "alpha", port: 3000, domain })
    expect(res.status).toBe(400)
  }
  expect(registry.listProxies()).toEqual([])
})

test("POST /proxies still creates a name with digits plus anything else", async () => {
  const { registry, post } = await boot()
  for (const domain of ["px-1234", "app5173", "5173a"]) {
    const res = await post({ sessionName: "alpha", port: 3000, domain })
    expect(res.status).toBe(200)
    expect(await res.json()).toEqual({ url: `http://localhost/p/${domain}/`, domain, port: 3000 })
  }
  expect(registry.listProxies().map((p) => p.domain).sort()).toEqual(["5173a", "app5173", "px-1234"])
})

test("POST /proxies without a domain still gets the random px- default", async () => {
  const { post } = await boot()
  for (const body of [{ sessionName: "alpha", port: 3000 }, { sessionName: "alpha", port: 3001, domain: "" }]) {
    const res = await post(body)
    expect(res.status).toBe(200)
    expect(((await res.json()) as { domain: string }).domain).toMatch(/^px-[0-9a-f]{8}$/)
  }
})
