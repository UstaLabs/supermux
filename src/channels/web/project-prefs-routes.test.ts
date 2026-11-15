// #152 B4: the launcher's Local / Worktree pick per project, and the new-branch prefix, kept on the broker per user.
import { afterEach, expect, test } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, realpathSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel } from "./index"
import { DeviceStore } from "./device-store"
import { openDb, runMigrations } from "../../core/storage/db"
import { MIGRATIONS } from "../../core/storage/migrations"
import { UserPrefs } from "../../core/settings/user-prefs"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

function repo(): string {
  const dir = realpathSync(mkdtempSync(join(tmpdir(), "mux-project-prefs-")))
  execFileSync("git", ["init", "-q", "-b", "main", dir])
  execFileSync("git", ["-C", dir, "config", "user.name", "Deniz Ak"])
  return dir
}

async function boot() {
  const db = openDb(":memory:"); runMigrations(db, MIGRATIONS)
  const devicesFile = join(mkdtempSync(join(tmpdir(), "mux-project-prefs-dev-")), "devices.json")
  channel = new WebChannel({
    port: 0, devicesFile, publicUrl: "http://localhost", updateChecker: null,
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    userPrefs: new UserPrefs(db),
  })
  await channel.start()
  const store = new DeviceStore(devicesFile)
  const phone = store.mint("phone").token, laptop = store.mint("laptop").token
  const call = (token: string, method: string, path: string, body?: unknown) => fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
    method, headers: { authorization: `Bearer ${token}`, "content-type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body),
  })
  return { phone, laptop, call }
}

test("a project starts on Worktree; a pick made on one device is the user's on every device", async () => {
  const { phone, laptop, call } = await boot()
  const a = repo(), b = repo()
  expect(await (await call(phone, "GET", `/projects/prefs?dir=${encodeURIComponent(a)}`)).json()).toEqual({ workIn: "worktree", branchPrefix: "deniz/" })
  const put = await call(phone, "PUT", "/projects/prefs", { dir: a, workIn: "local" })
  expect(await put.json()).toEqual({ workIn: "local", branchPrefix: "deniz/" })
  expect(await (await call(laptop, "GET", `/projects/prefs?dir=${encodeURIComponent(a)}`)).json()).toMatchObject({ workIn: "local" })
  expect(await (await call(laptop, "GET", `/projects/prefs?dir=${encodeURIComponent(b)}`)).json()).toMatchObject({ workIn: "worktree" })
  expect(await (await call(laptop, "PUT", "/projects/prefs", { dir: b, branchPrefix: "dak/" })).json()).toEqual({ workIn: "worktree", branchPrefix: "dak/" })
  expect(await (await call(phone, "GET", `/projects/prefs?dir=${encodeURIComponent(a)}`)).json()).toEqual({ workIn: "local", branchPrefix: "dak/" })
})

test("a value it can't keep, or no folder, is a 400 and changes nothing", async () => {
  const { phone, call } = await boot()
  const a = repo()
  expect((await call(phone, "PUT", "/projects/prefs", { dir: a, workIn: "cloud" })).status).toBe(400)
  expect((await call(phone, "PUT", "/projects/prefs", { dir: a, branchPrefix: "a b/" })).status).toBe(400)
  expect((await call(phone, "PUT", "/projects/prefs", { dir: a, branchPrefix: 7 })).status).toBe(400)
  expect((await call(phone, "PUT", "/projects/prefs", { workIn: "local" })).status).toBe(400)
  expect(await (await call(phone, "GET", `/projects/prefs?dir=${encodeURIComponent(a)}`)).json()).toEqual({ workIn: "worktree", branchPrefix: "deniz/" })
})
