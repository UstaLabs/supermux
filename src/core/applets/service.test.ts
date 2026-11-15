import { afterEach, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, writeFileSync, existsSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createHmac } from "crypto"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { AppletService } from "./service"
import { AppletSupervisor, restartDelayMs } from "./supervisor"
import { handleAppletRequest, type AppletCaller } from "../../channels/web/applet-route"

const cleanups: (() => void)[] = []
afterEach(() => { while (cleanups.length) cleanups.pop()!() })

function setup() {
  const root = mkdtempSync(join(tmpdir(), "applets-"))
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const appletsDir = join(root, "applets")
  const svc = new AppletService({ db, appletsDir, stateDir: join(root, "state"), backupsDir: join(root, "backups") })
  cleanups.push(() => { svc.shutdown(); for (const v of svc.list()) svc.supervisor.stop(v.row.id); rmSync(root, { recursive: true, force: true }) })
  const make = (name: string, manifest: object, files: Record<string, string> = {}) => {
    const dir = join(appletsDir, name)
    mkdirSync(dir, { recursive: true })
    writeFileSync(join(dir, "applet.json"), JSON.stringify(manifest))
    for (const [f, body] of Object.entries(files)) {
      mkdirSync(join(dir, f, ".."), { recursive: true })
      writeFileSync(join(dir, f), body)
    }
    return dir
  }
  return { root, db, svc, make, appletsDir }
}

const device: AppletCaller = { kind: "device", id: "0123456789abcdef", name: "laptop" }
const agent: AppletCaller = { kind: "agent", session: "s1" }
const get = (svc: AppletService, path: string, caller: AppletCaller | null = device, init: RequestInit = {}) =>
  handleAppletRequest(new Request(`http://broker${path}`, init), { applets: svc, caller: () => caller ?? undefined, own: { cookies: ["cmux_token", "cmux_preview"] } })

test("053: the applets table has no session id", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(applets)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toEqual(["id", "name", "title", "icon", "status", "updated_by", "updated_at", "created_at", "deleted_at"])
})

test("a folder with an applet.json becomes an applet with a stable id; title changes keep it; a gone folder is kept as missing", async () => {
  const { svc, make, appletsDir } = setup()
  make("notes", { title: "Notes" })
  mkdirSync(join(appletsDir, "not-yet")) // no applet.json: not an applet
  mkdirSync(join(appletsDir, "Bad_Name"))
  writeFileSync(join(appletsDir, "Bad_Name", "applet.json"), `{"title":"x"}`)
  await svc.scan()
  expect(svc.list().map((v) => v.row.name)).toEqual(["notes"])
  const id = svc.get("notes")!.row.id
  make("notes", { title: "My notes" })
  await svc.scan()
  expect(svc.get("notes")!.row).toMatchObject({ id, title: "My notes", status: "ok" })
  rmSync(join(appletsDir, "notes"), { recursive: true })
  await svc.scan()
  expect(svc.get("notes")!.row).toMatchObject({ id, status: "missing" })
  make("notes", { title: "Back" })
  await svc.scan()
  expect(svc.get("notes")!.row).toMatchObject({ id, status: "ok", title: "Back" })
})

test("a broken applet.json is listed as invalid with the reason, and its page says so", async () => {
  const { svc, make } = setup()
  make("bad", { title: "Bad", run: 5 })
  await svc.scan()
  expect(svc.get("bad")).toMatchObject({ row: { status: "invalid" }, error: `"run" must be a non-empty string` })
  const res = await get(svc, "/a/bad/")
  expect(res.status).toBe(503)
  expect(await res.text()).toContain("must be a non-empty string")
})

test("a static applet serves public/, never outside it", async () => {
  const { svc, make, root } = setup()
  make("notes", { title: "Notes" }, { "public/index.html": "<h1>hi</h1>", "public/a/b.js": "x()", "secret.txt": "no" })
  writeFileSync(join(root, "outside.txt"), "no")
  await svc.scan()
  expect((await get(svc, "/a/notes")).status).toBe(301)
  const idx = await get(svc, "/a/notes/")
  expect(idx.status).toBe(200)
  expect(idx.headers.get("content-type")).toContain("text/html")
  expect(await idx.text()).toBe("<h1>hi</h1>")
  expect(await (await get(svc, "/a/notes/a/b.js")).text()).toBe("x()")
  for (const p of ["/a/notes/../secret.txt", "/a/notes/%2e%2e/secret.txt", "/a/notes/..%2fsecret.txt", "/a/notes/nope.html"]) {
    expect([p, (await get(svc, p)).status]).toEqual([p, 404])
  }
  expect((await get(svc, "/a/notes/", device, { method: "POST" })).status).toBe(405)
  expect((await get(svc, "/a/notes/", null)).status).toBe(401)
  expect((await get(svc, "/a/nope/")).status).toBe(404)
})

const ECHO = `Bun.serve({ port: Number(process.env.PORT), hostname: "127.0.0.1", fetch(req) {
  const h = {}; req.headers.forEach((v, k) => { h[k] = v })
  return Response.json({ path: new URL(req.url).pathname, headers: h, dataDir: process.env.DATA_DIR, telegram: process.env.MUX_TELEGRAM_BOT_TOKEN ?? null })
} })`

test("a server applet gets $PORT and $DATA_DIR, a clean env, and a signed X-Mux-Via; forged X-Mux-* and supermux's cookie never reach it", async () => {
  process.env.MUX_TELEGRAM_BOT_TOKEN = "leak-me"
  cleanups.push(() => { delete process.env.MUX_TELEGRAM_BOT_TOKEN })
  const { svc, make } = setup()
  const dir = make("echo", { title: "Echo", run: `${process.execPath} server.ts` }, { "server.ts": ECHO })
  await svc.scan()
  const res = await get(svc, "/a/echo/hello?x=1", device, { headers: { "x-mux-via": "device:forged", "X-Mux-Other": "1", cookie: "cmux_token=secret; mine=1" } })
  expect(res.status).toBe(200)
  const body = await res.json() as { path: string; headers: Record<string, string>; dataDir: string; telegram: string | null }
  expect(body.path).toBe("/hello")
  expect(body.dataDir).toBe(join(dir, "data"))
  expect(existsSync(join(dir, "data"))).toBe(true)
  expect(body.telegram).toBeNull()
  expect(body.headers["x-mux-via"]).toBe("device:0123456789abcdef")
  expect(body.headers["x-mux-device-name"]).toBe("laptop")
  expect(body.headers["x-mux-other"]).toBeUndefined()
  expect(body.headers["cookie"]).toBe("mine=1")
  expect(body.headers["x-forwarded-prefix"]).toBe("/a/echo")
  const id = svc.get("echo")!.row.id
  expect(body.headers["x-mux-via-sig"]).toBe(createHmac("sha256", svc.viaKey(id)).update("device:0123456789abcdef").digest("hex"))
})

test("ownerOnly paths answer 403 to an agent and pass for a device", async () => {
  const { svc, make } = setup()
  make("echo", { title: "Echo", run: `${process.execPath} server.ts`, ownerOnly: ["/api/settings"] }, { "server.ts": ECHO })
  await svc.scan()
  expect((await get(svc, "/a/echo/api/settings", agent)).status).toBe(403)
  expect((await get(svc, "/a/echo/api/%73ettings", agent)).status).toBe(403)
  const ok = await get(svc, "/a/echo/api/other", agent)
  expect(((await ok.json()) as { headers: Record<string, string> }).headers["x-mux-via"]).toBe("agent:s1")
  expect((await get(svc, "/a/echo/api/settings", device)).status).toBe(200)
})

test("a crashed server is restarted, and its output is in the log", async () => {
  const { svc, make } = setup()
  make("flaky", { title: "Flaky", run: `printf 'b%s\\n' ooted; ${process.execPath} server.ts` }, { "server.ts": ECHO })
  await svc.scan()
  const id = svc.get("flaky")!.row.id
  expect((await get(svc, "/a/flaky/")).status).toBe(200)
  const pid = svc.supervisor.state(id)!.pid!
  process.kill(-pid, "SIGKILL")
  for (let i = 0; i < 60 && svc.supervisor.state(id)?.restarts !== 1; i++) await Bun.sleep(100)
  expect(svc.supervisor.state(id)!.restarts).toBe(1)
  for (let i = 0; i < 60 && svc.supervisor.state(id)?.state !== "running"; i++) await Bun.sleep(50)
  expect((await get(svc, "/a/flaky/")).status).toBe(200)
  expect(svc.supervisor.logs(id).match(/^booted$/gm)?.length).toBe(2)
})

test("a new broker adopts the server the last one left running, and starts over when it is gone", async () => {
  const { svc, make, db, root } = setup()
  make("echo", { title: "Echo", run: `${process.execPath} server.ts` }, { "server.ts": ECHO })
  await svc.scan()
  const id = svc.get("echo")!.row.id
  const first = svc.supervisor.state(id)!
  svc.shutdown() // the broker goes; the server stays

  const svc2 = new AppletService({ db, appletsDir: join(root, "applets"), stateDir: join(root, "state"), backupsDir: join(root, "backups") })
  cleanups.push(() => { svc2.shutdown(); svc2.supervisor.stop(id) })
  await svc2.scan()
  expect(svc2.supervisor.state(id)).toMatchObject({ state: "running", pid: first.pid, port: first.port })
  expect((await get(svc2, "/a/echo/")).status).toBe(200)

  // Stop it the way a reboot would, then a third broker starts a new one.
  svc2.shutdown()
  process.kill(-first.pid!, "SIGKILL")
  await Bun.sleep(100)
  const svc3 = new AppletService({ db, appletsDir: join(root, "applets"), stateDir: join(root, "state"), backupsDir: join(root, "backups") })
  cleanups.push(() => { svc3.shutdown(); svc3.supervisor.stop(id) })
  await svc3.scan()
  expect(svc3.supervisor.state(id)!.pid).not.toBe(first.pid)
  expect((await get(svc3, "/a/echo/")).status).toBe(200)
})

test("removing run, or the folder, stops the server", async () => {
  const { svc, make, appletsDir } = setup()
  make("echo", { title: "Echo", run: `${process.execPath} server.ts` }, { "server.ts": ECHO, "public/index.html": "static now" })
  await svc.scan()
  const id = svc.get("echo")!.row.id
  const pid = svc.supervisor.state(id)!.pid!
  make("echo", { title: "Echo" })
  await svc.scan()
  for (let i = 0; i < 40; i++) { try { process.kill(pid, 0); await Bun.sleep(50) } catch { break } }
  expect(() => process.kill(pid, 0)).toThrow()
  expect(await (await get(svc, "/a/echo/")).text()).toBe("static now")
})

test("restart delays grow to a minute", () => {
  expect([1, 2, 3, 7, 20].map(restartDelayMs)).toEqual([1000, 2000, 4000, 60000, 60000])
})
