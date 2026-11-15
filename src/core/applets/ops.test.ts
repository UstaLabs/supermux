import { afterEach, expect, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { AppletService } from "./service"
import { handleAppletOp } from "./ops"
import { spawnProcessSync } from "../process/launcher"

// These run POSIX shell command lines (sh -c, $VARS, printf), signal process groups and check file modes; the
// Windows supervisor (cmd.exe /c, taskkill, the PowerShell start fingerprint) is covered by the platform layer's tests.
const posix = test.skipIf(process.platform === "win32")

const cleanups: (() => void)[] = []
afterEach(() => { while (cleanups.length) cleanups.pop()!() })

function setup() {
  const root = mkdtempSync(join(tmpdir(), "applets-ops-"))
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const svc = new AppletService({ db, appletsDir: join(root, "applets"), stateDir: join(root, "state"), backupsDir: join(root, "backups") })
  cleanups.push(() => { svc.shutdown(); for (const v of svc.list()) svc.supervisor.stop(v.row.id); try { require("@supermux/platform").files.makeWritableTree(root) } catch { /* */ } rmSync(root, { recursive: true, force: true }) })
  const op = (name: string, args: Record<string, unknown> = {}) => handleAppletOp(svc, name, args, { sessionId: "sess-1", publicUrl: "https://relay.example" })
  return { root, svc, op }
}
const git = (cwd: string, ...a: string[]) => spawnProcessSync(["git", ...a], { cwd, stdout: "pipe", stderr: "pipe" }).stdout.toString().trim()

posix("new: a static applet with data/ ignored and its own git identity, listed at once, made by the session", async () => {
  const { svc, op, root } = setup()
  const r = await op("applet_new", { applet: "job-tracker", title: "Job tracker" }) as any
  expect(r.ok).toBe(true)
  const folder = join(root, "applets", "job-tracker")
  expect(r.value).toMatchObject({ name: "job-tracker", title: "Job tracker", folder, url: "https://relay.example/a/job-tracker/", state: "static" })
  expect(readFileSync(join(folder, ".gitignore"), "utf8")).toContain("data/")
  expect(git(folder, "config", "user.name")).toBe("supermux applet")
  expect(git(folder, "log", "--format=%an|%s")).toBe("supermux applet|applet created")
  writeFileSync(join(folder, "data", "app.db"), "x")
  expect(git(folder, "status", "--porcelain")).toBe("")
  expect(svc.get("job-tracker")!.row.updatedBy).toBe("agent:sess-1")
  expect(((await op("applet_new", { applet: "job-tracker" })) as any).error).toContain("exists already")
  expect(((await op("applet_new", { applet: "Job_Tracker" })) as any).error).toContain("can't be an applet name")
})

posix("new --from adopts a folder, keeps its history and adds the ignore + identity", async () => {
  const { op, root } = setup()
  const src = join(root, "src")
  mkdirSync(join(src, "public"), { recursive: true })
  writeFileSync(join(src, "public", "index.html"), "mine")
  writeFileSync(join(src, ".gitignore"), "node_modules\n")
  git(src, "init", "-q"); git(src, "-c", "user.name=x", "-c", "user.email=x@x", "add", "-A")
  spawnProcessSync(["git", "-c", "user.name=x", "-c", "user.email=x@x", "commit", "-qm", "first"], { cwd: src })
  const r = await op("applet_new", { applet: "mine", from: src }) as any
  expect(r.ok).toBe(true)
  const folder = join(root, "applets", "mine")
  expect(readFileSync(join(folder, "public", "index.html"), "utf8")).toBe("mine")
  expect(readFileSync(join(folder, ".gitignore"), "utf8")).toBe("node_modules\n# the applet's live data: backed up by supermux, never committed\ndata/\n")
  expect(git(folder, "log", "--format=%s")).toBe("applet created from " + src + "\nfirst")
  expect(((await op("applet_new", { applet: "rel", from: "relative/dir" })) as any).error).toContain("absolute")
})

posix("run and logs print the real output and the exit code; restart reports a server that dies", async () => {
  const { op, root } = setup()
  await op("applet_new", { applet: "svc" })
  const folder = join(root, "applets", "svc")
  writeFileSync(join(folder, "applet.json"), JSON.stringify({ title: "Svc", run: "echo starting; echo 'boom: bad config' >&2; exit 4", jobs: [{ name: "sync", every: "1h", run: "echo synced 3 rows; exit 2" }] }))
  const run = await op("applet_run", { applet: "svc", job: "sync" }) as any
  expect(run.value).toMatchObject({ exit_code: 2, output: "synced 3 rows\n" })
  const logs = await op("applet_logs", { applet: "svc", job: "sync" }) as any
  expect(logs.value.runs[0]).toMatchObject({ exit_code: 2, output: "synced 3 rows\n" })
  const rs = await op("applet_restart", { applet: "svc" }) as any
  expect(rs.value.state).toBe("crashed")
  expect(rs.value.logs).toContain("boom: bad config")
  expect(((await op("applet_run", { applet: "svc", job: "nope" })) as any).error).toContain("its jobs: sync")
})

posix("backup copies data/ read-only and says where; info lists it; delete and secrets are refused", async () => {
  const { op, root } = setup()
  await op("applet_new", { applet: "notes" })
  writeFileSync(join(root, "applets", "notes", "data", "n.json"), "[1]")
  const b = await op("applet_backup", { applet: "notes", why: "before migration" }) as any
  expect(b.ok).toBe(true)
  expect(readFileSync(join(b.value.path, "n.json"), "utf8")).toBe("[1]")
  const info = await op("applet_info", { applet: "notes" }) as any
  expect(info.value.backups).toMatchObject([{ path: b.value.path, kind: "manual", why: "before migration" }])
  expect(info.value.folder).toBe(join(root, "applets", "notes"))
  for (const name of ["applet_delete", "applet_secret"]) {
    expect(await op(name, { applet: "notes" })).toMatchObject({ ok: false, code: "denied" })
  }
  expect(existsSync(join(root, "applets", "notes"))).toBe(true)
  expect(await op("applet_info", { applet: "nope" })).toMatchObject({ ok: false, code: "not_found" })
  const list = await op("applet_list") as any
  expect(list.value.map((a: any) => [a.name, a.state])).toEqual([["notes", "static"]])
})

posix("an applet can't take the name of one of the CLI's verbs", async () => {
  const { op } = setup()
  expect(((await op("applet_new", { applet: "logs" })) as any).error).toContain("own commands")
})

posix("restart shows only what the server printed since this start", async () => {
  const { op, root } = setup()
  await op("applet_new", { applet: "svc" })
  writeFileSync(join(root, "applets", "svc", "applet.json"), JSON.stringify({ title: "Svc", run: "echo --- not a header; echo run-$$; exec sleep 30" }))
  await op("applet_restart", { applet: "svc" })
  const second = await op("applet_restart", { applet: "svc" }) as any
  expect(second.value.state).toBe("running")
  expect(second.value.logs.match(/run-\d+/g)?.length).toBe(1)
  expect(second.value.logs).toContain("--- not a header")
})

posix("new: the template carries the page API; --from warns about a reserved _supermux/", async () => {
  const { op, root } = setup()
  await op("applet_new", { applet: "plain" })
  expect(readFileSync(join(root, "applets", "plain", "public", "index.html"), "utf8")).toContain('<script src="_supermux/api.js"></script>')
  const src = join(root, "src2")
  mkdirSync(join(src, "public", "_supermux"), { recursive: true })
  const r = await op("applet_new", { applet: "clash", from: src }) as any
  expect(r.value.warnings[0]).toContain("reserved")
})

posix("ask: one chat per applet, made once; an Agent by name", async () => {
  const root = mkdtempSync(join(tmpdir(), "applets-ask-"))
  const db = openDb(":memory:"); runMigrations(db, MIGRATIONS)
  const calls: any[] = []
  const svc = new AppletService({ db, appletsDir: join(root, "applets"), stateDir: join(root, "state"), backupsDir: join(root, "backups"),
    ask: async (a, text, o) => { calls.push({ text, ...o }); return { sessionId: o.agent ? "pa" : o.chatId ?? "new-chat", name: o.agent ?? `${a.title} applet` } } })
  cleanups.push(() => { svc.shutdown(); rmSync(root, { recursive: true, force: true }) })
  mkdirSync(join(root, "applets", "mail"), { recursive: true })
  writeFileSync(join(root, "applets", "mail", "applet.json"), JSON.stringify({ title: "Mail" }))
  await svc.scan()
  expect(await svc.ask("mail", "fix this card")).toEqual({ ok: true, sessionId: "new-chat", session: "Mail applet" })
  await svc.ask("mail", "again")
  await svc.ask("mail", "to tally", "Tally")
  expect(calls).toEqual([
    { text: "From the applet Mail: fix this card", chatId: undefined },
    { text: "From the applet Mail: again", chatId: "new-chat" },
    { text: "From the applet Mail: to tally", agent: "Tally", chatId: "new-chat" },
  ])
  expect(await svc.ask("mail", " ")).toMatchObject({ ok: false })
})
