// Host file system over the web channel (spec 2026-09-27 §4.4): the /fs/* HTTP routes and
// the fs_sub / fs_unsub WebSocket frames. Live WebChannel on an ephemeral port, a real
// DeviceStore bearer token and real temp folders.
//
// Trust: /fs/* takes any absolute path — the same trust as a terminal — so it must be
// reachable ONLY with a paired device's token. The auth tests below pin that: no
// credential, a forged cookie, and requests addressed to a proxied port (subdomain and
// path mode, where the proxy's own auth or a public link applies) never reach the host fs.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync, realpathSync, existsSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
let upstream: ReturnType<typeof Bun.serve> | undefined
afterEach(async () => {
  if (channel) { await channel.stop(); channel = undefined }
  if (upstream) { upstream.stop(true); upstream = undefined }
})

async function boot(extra: Partial<WebChannelOpts> = {}) {
  const dir = mkdtempSync(join(tmpdir(), "mux-fs-routes-"))
  const devicesFile = join(dir, "devices.json")
  const opts: WebChannelOpts = {
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    updateChecker: null,
    ...extra,
  }
  channel = new WebChannel(opts)
  await channel.start()
  const token = new DeviceStore(devicesFile).mint("t").token
  const base = `http://127.0.0.1:${channel.boundPort}`
  const auth = { Authorization: `Bearer ${token}` }
  return { base, auth, token }
}

const tmp = () => realpathSync(mkdtempSync(join(tmpdir(), "fs-http-")))
const until = async (pred: () => boolean, ms = 3_000) => {
  const end = Date.now() + ms
  while (!pred() && Date.now() < end) await new Promise((r) => setTimeout(r, 20))
}

test("GET /fs/list returns a snapshot for an absolute path", async () => {
  const { base, auth } = await boot()
  const d = tmp()
  writeFileSync(join(d, "a.txt"), "hi")
  const r = await fetch(`${base}/fs/list?path=${encodeURIComponent(d)}`, { headers: auth })
  expect(r.status).toBe(200)
  const body: any = await r.json()
  expect(body).toMatchObject({ path: d, real: d })
  expect(body.entries.map((e: any) => e.name)).toEqual(["a.txt"])
})

test("/fs/* maps errors to status codes and requires a token", async () => {
  const { base, auth } = await boot()
  expect((await fetch(`${base}/fs/list?path=relative`, { headers: auth })).status).toBe(400)
  const missing = await fetch(`${base}/fs/list?path=/no/such/dir`, { headers: auth })
  expect(missing.status).toBe(404)
  expect(((await missing.json()) as any).error).toBe("ENOENT")
  expect((await fetch(`${base}/fs/list?path=/tmp`)).status).toBe(401)
})

test("every /fs/* route refuses a request without a device credential", async () => {
  const { base } = await boot()
  const d = tmp()
  const f = join(d, "x.txt")
  const q = `path=${encodeURIComponent(f)}`
  const cases: Array<[string, RequestInit]> = [
    [`/fs/list?path=${encodeURIComponent(d)}`, {}],
    [`/fs/stat?${q}`, {}],
    [`/fs/read?${q}`, {}],
    [`/fs/search?scope=${encodeURIComponent(d)}&q=x`, {}],
    [`/fs/write?${q}`, { method: "PUT", body: "pwned" }],
    [`/fs/ops`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ op: "touch", path: f }) }],
  ]
  for (const [p, init] of cases) {
    // No credential at all.
    expect((await fetch(base + p, init)).status).toBe(401)
    // A cookie that is not a paired device's token (e.g. a stale or forged one).
    const forged = { ...init, headers: { ...(init.headers as any), cookie: "cmux_token=not-a-device-token" } }
    expect((await fetch(base + p, forged)).status).toBe(401)
  }
  expect(existsSync(f)).toBe(false)
})

test("a proxied port's host or path never reaches the host fs, even with a device cookie", async () => {
  upstream = Bun.serve({ port: 0, fetch: () => new Response("upstream") })
  const up = upstream.port!
  // Subdomain mode: a *public* proxy (shareable, no auth) on px.example.test.
  const sub = await boot({
    proxyBaseDomain: "example.test", proxyMainHost: "app.example.test",
    proxyLookup: (slug) => (slug === "px" ? { port: up, sessionName: "s", isPublic: true } : undefined),
    proxyAuth: () => false,
  })
  const d = tmp()
  const r = await fetch(`${sub.base}/fs/list?path=${encodeURIComponent(d)}`, {
    headers: { host: "px.example.test", cookie: `cmux_token=${sub.token}` },
  })
  expect(await r.text()).toBe("upstream")
  await channel!.stop(); channel = undefined

  // Path mode: /p/<slug>/fs/list is the proxied app's own /fs/list.
  const path = await boot({
    proxyLookup: (slug) => (slug === "px" ? { port: up, sessionName: "s", isPublic: true } : undefined),
    proxyAuth: () => false,
  })
  const r2 = await fetch(`${path.base}/p/px/fs/list?path=${encodeURIComponent(d)}`, { headers: path.auth })
  expect(await r2.text()).toBe("upstream")
})

test("read, write, stat, search and ops over HTTP", async () => {
  const { base, auth } = await boot()
  const d = tmp()
  const f = join(d, "src", "FileTree.kt")
  let r = await fetch(`${base}/fs/write?path=${encodeURIComponent(f)}`, { method: "PUT", headers: auth, body: "hello" })
  expect(r.status).toBe(200)
  expect(((await r.json()) as any).size).toBe(5)
  expect(await (await fetch(`${base}/fs/read?path=${encodeURIComponent(f)}`, { headers: auth })).text()).toBe("hello")
  expect(((await (await fetch(`${base}/fs/stat?path=${encodeURIComponent(f)}`, { headers: auth })).json()) as any).type).toBe("file")
  const hits: any = await (await fetch(`${base}/fs/search?scope=${encodeURIComponent(d)}&q=ftree`, { headers: auth })).json()
  expect(hits[0].path).toBe(f)
  r = await fetch(`${base}/fs/ops`, {
    method: "POST", headers: { ...auth, "content-type": "application/json" },
    body: JSON.stringify({ op: "rename", path: f, to: join(d, "src", "Tree.kt") }),
  })
  expect(r.status).toBe(204)
  expect(existsSync(join(d, "src", "Tree.kt"))).toBe(true)
  r = await fetch(`${base}/fs/ops`, {
    method: "POST", headers: { ...auth, "content-type": "application/json" },
    body: JSON.stringify({ op: "touch", path: join(d, "src", "Tree.kt") }),
  })
  expect(r.status).toBe(409)
})

test("/fs/ops rejects a malformed body and an unknown op with 400", async () => {
  const { base, auth } = await boot()
  const d = tmp()
  const post = (body: string) => fetch(`${base}/fs/ops`, { method: "POST", headers: { ...auth, "content-type": "application/json" }, body })
  expect((await post("not json")).status).toBe(400)
  expect((await post(JSON.stringify({ op: "touch" }))).status).toBe(400)
  expect((await post(JSON.stringify({ op: "chmod", path: join(d, "x") }))).status).toBe(400)
  expect((await post(JSON.stringify({ op: "rename", path: join(d, "x") }))).status).toBe(400)
})

test("/fs/ops delete: permanent:true is a real delete; permanent is boolean-only and delete-only", async () => {
  const { base, auth } = await boot()
  const d = tmp()
  const post = (body: unknown) => fetch(`${base}/fs/ops`, { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify(body) })
  writeFileSync(join(d, "x"), "1")
  expect((await post({ op: "delete", path: join(d, "x"), permanent: "yes" })).status).toBe(400)
  expect((await post({ op: "touch", path: join(d, "y"), permanent: true })).status).toBe(400)
  expect(existsSync(join(d, "y"))).toBe(false)
  expect(existsSync(join(d, "x"))).toBe(true)
  expect((await post({ op: "delete", path: join(d, "x"), permanent: true })).status).toBe(204)
  expect(existsSync(join(d, "x"))).toBe(false)
})

test("/fs/ops forwards permanent only when true, and a trash EXDEV answers 409 with the code", async () => {
  const { base, auth } = await boot()
  const seen: unknown[] = []
  const fss = (channel as any).fss
  fss.op = async (op: unknown) => {
    seen.push(op)
    const { FsError } = await import("../../core/fs/errors")
    throw new FsError("EXDEV", "Can't move to the trash across filesystems; delete permanently instead?")
  }
  const post = (body: unknown) => fetch(`${base}/fs/ops`, { method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify(body) })
  const r = await post({ op: "delete", path: "/tmp/whatever" })
  expect(r.status).toBe(409)
  expect(await r.json()).toMatchObject({ error: "EXDEV" })
  await post({ op: "delete", path: "/tmp/whatever", permanent: false })
  expect(seen).toEqual([{ op: "delete", path: "/tmp/whatever" }, { op: "delete", path: "/tmp/whatever" }])
})

test("/fs/search clamps limit to the service max of 200", async () => {
  const { base, auth } = await boot()
  let got = -1
  ;(channel as any).fss.search = async (_s: string, _q: string, limit: number) => { got = limit; return [] }
  await fetch(`${base}/fs/search?scope=/tmp&q=a&limit=999`, { headers: auth })
  expect(got).toBe(200)
})

test("reading a binary file → 415, a directory → 400, wrong method → 405", async () => {
  const { base, auth } = await boot()
  const d = tmp()
  writeFileSync(join(d, "b.bin"), Buffer.from([1, 0, 2]))
  expect((await fetch(`${base}/fs/read?path=${encodeURIComponent(join(d, "b.bin"))}`, { headers: auth })).status).toBe(415)
  expect((await fetch(`${base}/fs/read?path=${encodeURIComponent(d)}`, { headers: auth })).status).toBe(400)
  expect((await fetch(`${base}/fs/list?path=${encodeURIComponent(d)}`, { method: "DELETE", headers: auth })).status).toBe(405)
})

test("fs_sub over the WebSocket gets a snapshot and live updates", async () => {
  const { base, token } = await boot()
  const d = tmp()
  const ws = new WebSocket(base.replace("http", "ws") + "/ws", { headers: { Authorization: `Bearer ${token}` } } as any)
  const frames: any[] = []
  ws.onmessage = (e) => { const f = JSON.parse(String(e.data)); if (String(f.type).startsWith("fs_")) frames.push(f) }
  await new Promise((r) => (ws.onopen = r))
  ws.send(JSON.stringify({ type: "fs_sub", path: d }))
  await until(() => frames.length >= 1)
  expect(frames[0]).toMatchObject({ type: "fs_dir", path: d, entries: [] })
  writeFileSync(join(d, "new.txt"), "")
  await until(() => frames.length >= 2)
  expect(frames[1].entries.map((e: any) => e.name)).toEqual(["new.txt"])

  // fs_unsub stops the pushes.
  ws.send(JSON.stringify({ type: "fs_unsub", path: d }))
  await new Promise((r) => setTimeout(r, 100))
  const n = frames.length
  writeFileSync(join(d, "later.txt"), "")
  await new Promise((r) => setTimeout(r, 600))
  expect(frames.length).toBe(n)

  // A bad path answers with fs_err, not a dropped socket.
  ws.send(JSON.stringify({ type: "fs_sub", path: "relative" }))
  await until(() => frames.some((f) => f.type === "fs_err"))
  expect(frames.find((f) => f.type === "fs_err")).toMatchObject({ path: "relative", code: "EINVAL" })
  ws.close()
})

test("closing the socket releases its folder subscriptions", async () => {
  const { base, token } = await boot()
  const d = tmp()
  const ws = new WebSocket(base.replace("http", "ws") + "/ws", { headers: { Authorization: `Bearer ${token}` } } as any)
  let got = false
  ws.onmessage = (e) => { if (JSON.parse(String(e.data)).type === "fs_dir") got = true }
  await new Promise((r) => (ws.onopen = r))
  ws.send(JSON.stringify({ type: "fs_sub", path: d }))
  await until(() => got)
  // White-box: the service's subscription registry (no public per-folder count).
  const held = () => ((channel!.fss as any).subs.subscribersOf(d) as unknown[]).length
  expect(held()).toBe(1)
  ws.close()
  await until(() => held() === 0, 2_000)
  expect(held()).toBe(0)
})

test("editor_open / editor_close from an older app are ignored: no error, no fs_changed", async () => {
  const wd = tmp()
  const { base, token } = await boot({ getSessionWorkdir: (s) => (s === "a" ? wd : undefined) })
  const ws = new WebSocket(base.replace("http", "ws") + "/ws", { headers: { Authorization: `Bearer ${token}` } } as any)
  const types: string[] = []
  ws.onmessage = (e) => { types.push(JSON.parse(String(e.data)).type) }
  await new Promise((r) => (ws.onopen = r))
  ws.send(JSON.stringify({ type: "editor_open", session: "a" }))
  writeFileSync(join(wd, "x.txt"), "changed")
  ws.send(JSON.stringify({ type: "editor_close", session: "a" }))
  await until(() => false, 300)
  expect(types).not.toContain("error")
  expect(types).not.toContain("fs_changed")
  ws.close()
})

test("a cookie-authenticated /ws from a foreign origin is refused; own host and bearer are fine", async () => {
  const { base, token } = await boot({ publicUrl: "https://app.example.test" })
  const wsUrl = base.replace("http", "ws") + "/ws"
  const open = (headers: Record<string, string>) => new Promise<"open" | "closed">((resolve) => {
    const ws = new WebSocket(wsUrl, { headers } as any)
    ws.onopen = () => { resolve("open"); ws.close() }
    ws.onerror = () => resolve("closed")
    ws.onclose = () => resolve("closed")
  })
  expect(await open({ cookie: `cmux_token=${token}`, origin: "https://px.example.test" })).toBe("closed")
  expect(await open({ cookie: `cmux_token=${token}`, origin: "https://app.example.test" })).toBe("open")
  expect(await open({ cookie: `cmux_token=${token}`, origin: base })).toBe("open") // the request's own host
  expect(await open({ Authorization: `Bearer ${token}`, origin: "https://px.example.test" })).toBe("open")
})

test("PUT /fs/write refuses an oversized body with 413", async () => {
  const { base, auth } = await boot()
  const d = tmp()
  const big = "x".repeat(8 * 1024 * 1024 + 1)
  const r = await fetch(`${base}/fs/write?path=${encodeURIComponent(join(d, "big.txt"))}`, { method: "PUT", headers: auth, body: big })
  expect(r.status).toBe(413)
  expect(existsSync(join(d, "big.txt"))).toBe(false)
})
