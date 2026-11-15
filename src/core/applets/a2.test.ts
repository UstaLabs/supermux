import { afterEach, expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { existsSync, mkdirSync, mkdtempSync, readdirSync, rmSync, statSync, writeFileSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { AppletService, DELETED_KEEP_MS } from "./service"
import { AppletBackups, KEEP_DAYS, nightlyDue } from "./backups"
import { handleAppletApi } from "../../channels/web/applet-api"

const cleanups: (() => void)[] = []
afterEach(() => { while (cleanups.length) cleanups.pop()!() })

function setup(now?: () => Date) {
  const root = mkdtempSync(join(tmpdir(), "applets-a2-"))
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const appletsDir = join(root, "applets")
  const svc = new AppletService({ db, appletsDir, stateDir: join(root, "state"), backupsDir: join(root, "backups"), ...(now ? { now } : {}) })
  cleanups.push(() => {
    svc.shutdown()
    for (const v of svc.list()) svc.supervisor.stop(v.row.id)
    try { require("@supermux/platform").files.makeWritableTree(root) } catch { /* */ }
    rmSync(root, { recursive: true, force: true })
  })
  const make = (name: string, manifest: object, files: Record<string, string> = {}) => {
    const dir = join(appletsDir, name)
    mkdirSync(dir, { recursive: true })
    writeFileSync(join(dir, "applet.json"), JSON.stringify(manifest))
    for (const [f, body] of Object.entries(files)) { mkdirSync(join(dir, f, ".."), { recursive: true }); writeFileSync(join(dir, f), body) }
    return dir
  }
  const api = (method: string, path: string, body?: object) =>
    handleAppletApi(new Request(`http://b${path}`, { method, ...(body ? { body: JSON.stringify(body), headers: { "content-type": "application/json" } } : {}) }),
      { applets: svc, device: "laptop", json: (b, st = 200) => Response.json(b, { status: st }) })
  return { root, db, svc, make, api, appletsDir }
}

test("a job prints real output and its exit code, with the applet's secrets, settings and data dir", async () => {
  const { svc, make, api } = setup()
  const dir = make("mail", {
    title: "Mail", secrets: ["TOKEN"], config: [{ key: "every", type: "select", options: ["15m", "30m"], default: "30m" }],
    jobs: [{ name: "poll", every: "30m", run: `echo "token=$TOKEN data=$DATA_DIR settings=$APPLET_SETTINGS telegram=\${MUX_TELEGRAM_BOT_TOKEN:-none}"; echo oops >&2; exit 3` }],
  })
  process.env.MUX_TELEGRAM_BOT_TOKEN = "leak"
  cleanups.push(() => { delete process.env.MUX_TELEGRAM_BOT_TOKEN })
  await svc.scan()
  expect(svc.needsYou(svc.get("mail")!)).toMatchObject([{ name: "TOKEN", state: "missing" }])
  expect((await (await api("PUT", "/applets/mail/secrets/TOKEN", { value: "s3cret" })).json())).toEqual({ ok: true })
  expect((await api("PUT", "/applets/mail/secrets/OTHER", { value: "x" })).status).toBe(400)
  expect((await api("PUT", "/applets/mail/settings/every", { value: "1h" })).status).toBe(400)
  expect((await api("PUT", "/applets/mail/settings/every", { value: "15m" })).status).toBe(200)
  expect(svc.needsYou(svc.get("mail")!)).toEqual([])

  const res = await api("POST", "/applets/mail/jobs/poll/run")
  const run = await res.json() as { exitCode: number; output: string; trigger: string }
  expect(run.exitCode).toBe(3)
  expect(run.trigger).toBe("manual")
  expect(run.output).toContain(`token=s3cret data=${join(dir, "data")} settings={"every":"15m"} telegram=none`)
  expect(run.output).toContain("oops")
  const info = await (await api("GET", "/applets/mail")).json() as any
  expect(info.jobs[0]).toMatchObject({ name: "poll", lastRun: { exitCode: 3, trigger: "manual" } })
  expect(info.secrets).toMatchObject([{ name: "TOKEN", state: "set", wanted: true }])
  expect(JSON.stringify(info)).not.toContain("s3cret")
  expect(info.settings[0]).toMatchObject({ key: "every", value: "15m" })
  expect((await api("POST", "/applets/mail/jobs/nope/run")).status).toBe(404)
})

test("an expired secret is Needs you", async () => {
  let now = new Date("2026-11-15T12:00:00Z")
  const { svc, make } = setup(() => now)
  make("mail", { title: "Mail", secrets: ["TOKEN"] })
  await svc.scan()
  await svc.setSecret("mail", "TOKEN", "v", { expiresAt: "2026-11-16T00:00:00Z", by: "device:laptop" })
  expect(svc.needsYou(svc.get("mail")!)).toEqual([])
  now = new Date("2026-11-16T01:00:00Z")
  expect(svc.needsYou(svc.get("mail")!)).toMatchObject([{ name: "TOKEN", state: "expired" }])
})

test("the scheduler runs a job when it is due, once at a time, and not again until its interval passes", async () => {
  let t = Date.parse("2026-11-15T12:00:00Z")
  const { svc, make, db, root } = setup()
  make("tick", { title: "Tick", jobs: [{ name: "t", every: "1h", run: "echo ran >> \"$DATA_DIR/count\"" }] })
  await svc.scan()
  const { JobRunner } = await import("./jobs")
  const jobs = new JobRunner({ db, now: () => t })
  const target = svc.jobTargets()
  jobs.tick(target)
  jobs.tick(target) // already running: no second run
  for (let i = 0; i < 50 && jobs.isRunning(target[0]!.appletId, "t"); i++) await Bun.sleep(20)
  jobs.tick(target) // just ran
  await Bun.sleep(100)
  expect(readFileSync(join(root, "applets/tick/data/count"), "utf8")).toBe("ran\n")
  t += 3_600_000
  jobs.tick(target)
  for (let i = 0; i < 50 && jobs.isRunning(target[0]!.appletId, "t"); i++) await Bun.sleep(20)
  expect(readFileSync(join(root, "applets/tick/data/count"), "utf8")).toBe("ran\nran\n")
})

test("a backup is a read-only copy, SQLite included, and restoring is left to an agent", async () => {
  const { svc, make, root, api } = setup()
  const dir = make("notes", { title: "Notes" }, { "data/files/a.txt": "hello" })
  const db = new Database(join(dir, "data/app.db"))
  db.run("PRAGMA journal_mode=WAL")
  db.run("CREATE TABLE t (x)")
  db.run("INSERT INTO t VALUES (1), (2)")
  await svc.scan()
  const res = await api("POST", "/applets/notes/backup", { why: "before the import" })
  const { backup } = await res.json() as { backup: { id: string; why: string; kind: string } }
  db.close()
  expect(backup).toMatchObject({ why: "before the import", kind: "manual" })
  const copy = join(root, "backups/notes", backup.id, "data")
  expect(readFileSync(join(copy, "files/a.txt"), "utf8")).toBe("hello")
  const c = new Database(join(copy, "app.db"), { readonly: true })
  expect(c.query("SELECT count(*) AS n FROM t").get()).toEqual({ n: 2 })
  c.close()
  expect(statSync(join(copy, "files/a.txt")).mode & 0o222).toBe(0)
  expect(() => writeFileSync(join(copy, "files/a.txt"), "x")).toThrow()
  expect(((await (await api("GET", "/applets/notes")).json()) as any).backups.map((b: any) => b.id)).toEqual([backup.id])
})

test("nightly backups: one a day after 03:30, two weeks kept", () => {
  let now = new Date(2026, 10, 15, 3, 0)
  expect(nightlyDue(now, undefined)).toBe(false)
  now = new Date(2026, 10, 15, 3, 30)
  expect(nightlyDue(now, undefined)).toBe(true)
  expect(nightlyDue(now, "2026-11-15")).toBe(false)
  expect(nightlyDue(new Date(2026, 10, 16, 9, 0), "2026-11-15")).toBe(true)

  const root = mkdtempSync(join(tmpdir(), "applets-bk-"))
  cleanups.push(() => { require("@supermux/platform").files.makeWritableTree(root); rmSync(root, { recursive: true, force: true }) })
  mkdirSync(join(root, "data"))
  writeFileSync(join(root, "data/x"), "1")
  let t = new Date(2026, 10, 1, 4, 0)
  const b = new AppletBackups(join(root, "bk"), () => t)
  expect(b.backup("n", join(root, "data"), "nightly")!.id).toBe("2026-11-01")
  expect(b.backup("n", join(root, "data"), "nightly")!.id).toBe("2026-11-01") // same day: the same one
  t = new Date(2026, 10, 1 + KEEP_DAYS + 1, 4, 0)
  b.backup("n", join(root, "data"), "nightly")
  b.prune("n")
  expect(readdirSync(join(root, "bk/n"))).toEqual(["2026-11-16"])
  expect(b.backup("n", join(root, "nope"), "manual")).toBeNull()
})

test("delete moves the applet aside for 30 days; undelete brings it back with its id; then it is purged", async () => {
  let now = new Date("2026-11-15T12:00:00Z")
  const { svc, make, appletsDir, api, root } = setup(() => now)
  make("notes", { title: "Notes", secrets: ["K"] }, { "data/x": "1", "public/index.html": "hi" })
  await svc.scan()
  const id = svc.get("notes")!.row.id
  await svc.setSecret("notes", "K", "v", { by: "device:laptop" })
  svc.backup("notes", "keep")
  expect((await api("DELETE", "/applets/notes")).status).toBe(200)
  expect(svc.get("notes")).toBeUndefined()
  expect(existsSync(join(appletsDir, "notes"))).toBe(false)
  await svc.scan()
  expect(svc.list()).toEqual([]) // the scanner doesn't bring it back
  expect(((await (await api("GET", "/applets/.deleted")).json()) as any[]).map((d) => d.id)).toEqual([id])

  expect((await api("POST", `/applets/.deleted/${id}/undelete`)).status).toBe(200)
  expect(svc.get("notes")!.row.id).toBe(id)
  expect(svc.secrets.env(id)).toEqual({ K: "v" })
  expect(svc.backups.list("notes").length).toBe(1)

  await svc.delete("notes", "device:laptop")
  now = new Date(now.getTime() + DELETED_KEEP_MS + 1000)
  svc.purgeDeleted()
  expect(svc.store.byId(id)).toBeUndefined()
  expect(svc.secrets.env(id)).toEqual({})
  expect(readdirSync(join(appletsDir, ".deleted"))).toEqual([])
  expect(readdirSync(join(root, "backups/.deleted"))).toEqual([])
})

test("pins: the 9 most recently updated until he pins; then his list, capped at 9, in order", async () => {
  let t = Date.parse("2026-11-15T10:00:00Z")
  const { svc, make, api } = setup(() => new Date(t))
  for (let i = 1; i <= 11; i++) { make(`a${i}`, { title: `A${i}` }); await svc.scan(); t += 1000 }
  const names = (ids: string[]) => ids.map((id) => svc.store.byId(id)!.name)
  expect(names(svc.pinnedIds())).toEqual(["a11", "a10", "a9", "a8", "a7", "a6", "a5", "a4", "a3"])
  expect((await (await api("POST", "/applets/a1/pin")).json() as any).error).toContain("unpin one first")
  await api("POST", "/applets/a5/unpin")
  expect(names(svc.pinnedIds())).toEqual(["a11", "a10", "a9", "a8", "a7", "a6", "a4", "a3"])
  await api("POST", "/applets/a1/pin")
  expect(names(svc.pinnedIds()).at(-1)).toBe("a1")
  const list = await (await api("GET", "/applets")).json() as any[]
  expect(list.find((a) => a.name === "a1").pin).toBe(8)
  expect(list.find((a) => a.name === "a5").pin).toBeNull()
  await svc.delete("a11", "device:laptop")
  expect(names(svc.pinnedIds())[0]).toBe("a10")
})
