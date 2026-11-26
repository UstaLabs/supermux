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

// These run POSIX shell command lines (sh -c, $VARS, printf), signal process groups and check file modes; the
// Windows supervisor (cmd.exe /c, taskkill, the PowerShell start fingerprint) is covered by the platform layer's tests.
const posix = test.skipIf(process.platform === "win32")

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

posix("054: the applets table has no session id", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(applets)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toEqual(["id", "name", "title", "icon", "status", "updated_by", "updated_at", "created_at", "deleted_at", "stopped_at"])
})

posix("a folder with an applet.json becomes an applet with a stable id; title changes keep it; a gone folder is kept as missing", async () => {
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

posix("a broken applet.json is listed as invalid with the reason, and its page says so", async () => {
  const { svc, make } = setup()
  make("bad", { title: "Bad", run: 5 })
  await svc.scan()
  expect(svc.get("bad")).toMatchObject({ row: { status: "invalid" }, error: `"run" must be a non-empty string` })
  const res = await get(svc, "/a/bad/")
  expect(res.status).toBe(503)
  expect(await res.text()).toContain("must be a non-empty string")
})

posix("a static applet serves public/, never outside it", async () => {
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

posix("a server applet gets $PORT and $DATA_DIR, a clean env, and a signed X-Mux-Via; forged X-Mux-* and supermux's cookie never reach it", async () => {
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

posix("ownerOnly paths answer 403 to an agent and pass for a device", async () => {
  const { svc, make } = setup()
  make("echo", { title: "Echo", run: `${process.execPath} server.ts`, ownerOnly: ["/api/settings"] }, { "server.ts": ECHO })
  await svc.scan()
  expect((await get(svc, "/a/echo/api/settings", agent)).status).toBe(403)
  expect((await get(svc, "/a/echo/api/%73ettings", agent)).status).toBe(403)
  const ok = await get(svc, "/a/echo/api/other", agent)
  expect(((await ok.json()) as { headers: Record<string, string> }).headers["x-mux-via"]).toBe("agent:s1")
  expect((await get(svc, "/a/echo/api/settings", device)).status).toBe(200)
})

posix("a crashed server is restarted, and its output is in the log", async () => {
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

posix("a new broker adopts the server the last one left running, and starts over when it is gone", async () => {
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

posix("removing run, or the folder, stops the server", async () => {
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

posix("restart delays grow to a minute", () => {
  expect([1, 2, 3, 7, 20].map(restartDelayMs)).toEqual([1000, 2000, 4000, 60000, 60000])
})

posix("_supermux: the page API script, and notify through the applet's own route (devices only)", async () => {
  const sent: string[] = []
  const root = mkdtempSync(join(tmpdir(), "applets-api-"))
  const db = openDb(":memory:"); runMigrations(db, MIGRATIONS)
  const svc = new AppletService({ db, appletsDir: join(root, "applets"), stateDir: join(root, "state"), backupsDir: join(root, "backups"), notify: async (a, t, b) => { sent.push(`${a.title}|${t}|${b}`) } })
  cleanups.push(() => { svc.shutdown(); rmSync(root, { recursive: true, force: true }) })
  mkdirSync(join(root, "applets", "mail", "public"), { recursive: true })
  writeFileSync(join(root, "applets", "mail", "applet.json"), JSON.stringify({ title: "Mail" }))
  await svc.scan()
  const js = await get(svc, "/a/mail/_supermux/api.js")
  expect(js.headers.get("content-type")).toContain("javascript")
  expect(await js.text()).toContain("window.supermux")
  const ok = await get(svc, "/a/mail/_supermux/notify", device, { method: "POST", body: JSON.stringify({ title: "Hi", body: "there" }) })
  expect(ok.status).toBe(200)
  expect((await get(svc, "/a/mail/_supermux/notify", agent, { method: "POST", body: "{}" })).status).toBe(403)
  expect(sent).toEqual(["Mail|Hi|there"])
})

posix("widget summaries: a static applet's file, a server's route, trimmed to fit, refreshed on request", async () => {
  let changes = 0
  const root = mkdtempSync(join(tmpdir(), "applets-"))
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const appletsDir = join(root, "applets")
  const svc = new AppletService({ db, appletsDir, stateDir: join(root, "state"), backupsDir: join(root, "backups"), onChange: () => { changes++ } })
  cleanups.push(() => { svc.shutdown(); for (const v of svc.list()) svc.supervisor.stop(v.row.id); rmSync(root, { recursive: true, force: true }) })
  const write = (name: string, manifest: object, files: Record<string, string>) => {
    const dir = join(appletsDir, name)
    for (const [f, body] of Object.entries({ "applet.json": JSON.stringify(manifest), ...files })) {
      mkdirSync(join(dir, f, ".."), { recursive: true })
      writeFileSync(join(dir, f), body)
    }
    return dir
  }
  const issues = write("issues", { title: "Issues", widget: "/widget.json" }, {
    "public/widget.json": JSON.stringify({ value: 31, label: "open", lines: ["2 waiting for testing", "", 5, "a", "b"] }),
  })
  write("plain", { title: "Plain" }, { "public/index.html": "hi" })
  write("jobs", { title: "Jobs", run: `${process.execPath} server.ts`, widget: "/widget.json" }, {
    "server.ts": `Bun.serve({ port: Number(process.env.PORT), hostname: "127.0.0.1", fetch: (r) => new URL(r.url).pathname === "/widget.json"
      ? Response.json({ value: "1", label: "interview", via: r.headers.get("x-mux-via") }) : new Response("no", { status: 404 }) })`,
  })
  await svc.scan()
  const { waitForPort } = await import("../../channels/web/applet-route")
  expect(await waitForPort(svc.supervisor.port(svc.get("jobs")!.row.id)!, 5_000)).toBe(true)
  for (let i = 0; i < 50 && svc.get("jobs")!.process?.state !== "running"; i++) await Bun.sleep(100)

  changes = 0
  await svc.refreshWidgets()
  expect(changes).toBe(1)
  expect(svc.widgetSummary(svc.get("issues")!.row.id)).toEqual({ value: "31", label: "open", lines: ["2 waiting for testing", "5", "a"] })
  expect(svc.widgetSummary(svc.get("jobs")!.row.id)).toEqual({ value: "1", label: "interview" })
  expect(svc.widgetSummary(svc.get("plain")!.row.id)).toBeUndefined()

  // Nothing moved: no change notice. The applet changes its file and asks: one.
  await svc.refreshWidgets()
  expect(changes).toBe(1)
  writeFileSync(join(issues, "public/widget.json"), JSON.stringify({ value: 30, label: "open" }))
  const r = await svc.refreshWidget("issues")
  expect(r).toEqual({ ok: true, summary: { value: "30", label: "open" }, problems: [] })
  expect(changes).toBe(2)

  // Gone or broken: no summary (the widget shows just the icon and name).
  writeFileSync(join(issues, "public/widget.json"), "{not json")
  await svc.refreshWidget("issues")
  expect(svc.widgetSummary(svc.get("issues")!.row.id)).toBeUndefined()
  expect(await svc.refreshWidget("nope")).toEqual({ ok: false, error: `no applet named "nope"` })
})

test("parseWidgetSummary keeps what fits a widget and drops the rest", async () => {
  const { parseWidgetSummary } = await import("./service")
  expect(parseWidgetSummary(null)).toBeNull()
  expect(parseWidgetSummary([1])).toBeNull()
  expect(parseWidgetSummary({ other: 1 })).toBeNull()
  expect(parseWidgetSummary({ value: "1234567890123456", label: "x".repeat(50) })).toEqual({ value: "123456789012", label: "x".repeat(40) })
  expect(parseWidgetSummary({ lines: ["a", { b: 1 }, "c"] })).toEqual({ lines: ["a", "c"] })
})

posix("#165: a widget button runs the declared action on the applet's server, signed and named, and the widget follows", async () => {
  const { svc, make } = setup()
  const moved: { name: string; except?: string }[] = []
  ;(svc as any).deps.widgetChanged = (a: { name: string }, except?: string) => moved.push({ name: a.name, except })
  const SERVER = `let done = []
Bun.serve({ port: Number(process.env.PORT), hostname: "127.0.0.1", async fetch(r) {
  const u = new URL(r.url)
  if (u.pathname === "/widget.json") return Response.json({ v: 2, layouts: { small: [
    { type: "stat", value: String(2 - done.length), label: "to test" },
    { type: "list", rows: [{ title: "#164", button: { title: "Tested", action: "tested", args: { id: 164 } } }] },
  ] } })
  if (u.pathname.startsWith("/api/issues/") && r.method === "POST") {
    const b = await r.json()
    done.push({ path: u.pathname, via: r.headers.get("x-mux-via"), sig: r.headers.get("x-mux-via-sig"), actor: r.headers.get("x-mux-actor"), body: b })
    return new Response(null, { status: 204 })
  }
  if (u.pathname === "/done") return Response.json(done)
  if (u.pathname === "/api/fail") return new Response("nope", { status: 500 })
  return new Response("no", { status: 404 })
} })`
  make("issues", { title: "Issues", run: `${process.execPath} server.ts`, widget: { data: "/widget.json", actions: {
    tested: { title: "Tested", path: "/api/issues/{id}/tested" }, fail: { path: "/api/fail" },
  } } }, { "server.ts": SERVER })
  await svc.scan()
  const { waitForPort } = await import("../../channels/web/applet-route")
  const id = svc.get("issues")!.row.id
  expect(await waitForPort(svc.supervisor.port(id)!, 5_000)).toBe(true)
  for (let i = 0; i < 50 && svc.get("issues")!.process?.state !== "running"; i++) await Bun.sleep(100)
  await svc.refreshWidget("issues")
  expect((svc.widget(id)!.doc.layouts.small[0] as any).value).toBe("2")
  moved.length = 0

  const r = await svc.runWidgetAction("issues", "tested", { id: 164 }, "device:phone1", "phone1")
  expect(r.ok).toBe(true)
  expect(((r as any).widget.layouts.small[0]).value).toBe("1")
  expect(moved).toEqual([{ name: "issues", except: "phone1" }])
  const port = svc.supervisor.port(id)!
  const done = await (await fetch(`http://127.0.0.1:${port}/done`)).json() as any[]
  expect(done).toEqual([{ path: "/api/issues/164/tested", via: "supermux:widget-action", sig: createHmac("sha256", svc.viaKey(id)).update("supermux:widget-action").digest("hex"), actor: "device:phone1", body: { action: "tested", args: { id: 164 } } }])

  expect(await svc.runWidgetAction("issues", "nope", {}, "device:phone1")).toEqual({ ok: false, error: `Issues has no widget action "nope"` })
  expect(await svc.runWidgetAction("issues", "tested", {}, "device:phone1")).toEqual({ ok: false, error: `"tested" needs {id}` })
  expect(await svc.runWidgetAction("issues", "fail", {}, "device:phone1")).toEqual({ ok: false, error: "Issues answered 500: nope" })

  // The device API: GET the document, POST an action.
  const { handleAppletApi } = await import("../../channels/web/applet-api")
  const json = (b: unknown, status = 200) => Response.json(b, { status })
  const g = await handleAppletApi(new Request("http://broker/applets/issues/widget"), { applets: svc, device: "phone1", json })
  expect(((await g.json()) as any).widget.layouts.small[0].value).toBe("1")
  const p = await handleAppletApi(new Request("http://broker/applets/issues/widget/actions/tested", { method: "POST", body: JSON.stringify({ args: { id: 165 } }) }), { applets: svc, device: "phone1", json })
  expect(p.status).toBe(200)
  expect(((await p.json()) as any).widget.layouts.small[0].value).toBe("0")
  const bad = await handleAppletApi(new Request("http://broker/applets/issues/widget/actions/nope", { method: "POST", body: "{}" }), { applets: svc, device: "phone1", json })
  expect(bad.status).toBe(409)
  const list = await handleAppletApi(new Request("http://broker/applets"), { applets: svc, device: "phone1", json })
  const row = ((await list.json()) as any[])[0]
  expect(row.widget).toBe("/widget.json")
  expect(row.widgetActions).toEqual({ tested: { title: "Tested", confirm: false }, fail: { title: "fail", confirm: false } })
  expect(row.widgetDoc.v).toBe(2)
})
