import { afterEach, expect, test } from "bun:test"
import { mkdtemp, readdir, readFile, rm, stat, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { SessionStore } from "../src/store.js"
const stores: SessionStore[] = []
const dirs: string[] = []
async function setup() {
  const dir = await mkdtemp(join(tmpdir(), "core-store-"))
  dirs.push(dir)
  const store = new SessionStore(dir)
  stores.push(store)
  await store.open()
  return { dir, store }
}
afterEach(async () => {
  for (const s of stores.splice(0)) await s.close()
  for (const d of dirs.splice(0)) await rm(d, { recursive: true, force: true })
})
test("state directory has exactly one owner and is reusable after close", async () => {
  const { dir, store } = await setup()
  const other = new SessionStore(dir)
  await expect(other.open()).rejects.toMatchObject({ code: "state_locked" })
  await other.close()
  const third = new SessionStore(dir)
  await expect(third.open()).rejects.toMatchObject({ code: "state_locked" })
  await store.close()
  await third.open()
  await third.close()
})
test("a lock left by an owner that no longer exists is reclaimed", async () => {
  const dir = await mkdtemp(join(tmpdir(), "core-store-"))
  dirs.push(dir)
  const gone = Bun.spawn(["true"])
  await gone.exited
  await writeFile(join(dir, ".core.lock"), JSON.stringify({ pid: gone.pid, startedAt: new Date().toISOString() }))
  const store = new SessionStore(dir)
  stores.push(store)
  await store.open()
  expect(JSON.parse(await readFile(join(dir, ".core.lock"), "utf8")).pid).toBe(process.pid)
})
test("a lock written before the last boot is reclaimed even if its pid is alive again", async () => {
  const dir = await mkdtemp(join(tmpdir(), "core-store-"))
  dirs.push(dir)
  // After a reboot the old owner's pid can belong to an unrelated process — here, us.
  await writeFile(join(dir, ".core.lock"), JSON.stringify({ pid: process.pid, startedAt: "2000-01-01T00:00:00.000Z" }))
  const store = new SessionStore(dir)
  stores.push(store)
  await store.open()
  expect(JSON.parse(await readFile(join(dir, ".core.lock"), "utf8")).startedAt).not.toBe("2000-01-01T00:00:00.000Z")
})
test("an unreadable lock is never reclaimed", async () => {
  const dir = await mkdtemp(join(tmpdir(), "core-store-"))
  dirs.push(dir)
  await writeFile(join(dir, ".core.lock"), "")
  const store = new SessionStore(dir)
  stores.push(store)
  await expect(store.open()).rejects.toMatchObject({ code: "state_locked" })
})
test("atomic JSON persistence round-trips records with owner-only access", async () => {
  const { store, dir } = await setup()
  const record = { version: 1 as const, id: "123", agent: "test", agentSessionId: "native", cwd: tmpdir(), createdAt: new Date().toISOString() }
  await store.put(record)
  expect(await store.get("123")).toEqual(record)
  expect(await store.list()).toEqual([record])
  const file = join(dir, "sessions", "123.json")
  if (process.platform !== "win32") expect((await stat(file)).mode & 0o777).toBe(0o600)
  expect(await readdir(join(dir, "sessions"))).toEqual(["123.json"])
})
test("corrupt or foreign-schema history never silently becomes an empty registry", async () => {
  const { store, dir } = await setup()
  await writeFile(join(dir, "sessions", "123.json"), "{bad")
  await expect(store.list()).rejects.toMatchObject({ code: "invalid_session_record" })
  await writeFile(join(dir, "sessions", "123.json"), JSON.stringify({ version: 99 }))
  await expect(store.get("123")).rejects.toMatchObject({ code: "invalid_session_record" })
})
test("IDs cannot escape the state directory", async () => {
  const { store } = await setup()
  await expect(store.get("../../credentials")).rejects.toMatchObject({ code: "invalid_session_id" })
})
