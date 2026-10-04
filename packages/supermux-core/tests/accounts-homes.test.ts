import { afterEach, expect, test } from "bun:test"
import { lstat, mkdir, mkdtemp, readFile, readlink, rm, stat, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { claudeLayout, codexLayout, ensureHome } from "../src/accounts/index.js"

const dirs: string[] = []
afterEach(async () => { await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true }))) })
async function scratch() { const dir = await mkdtemp(join(tmpdir(), "accounts-homes-")); dirs.push(dir); return dir }

test("claude home links shared entries to the root, creating missing root entries; private files stay private", async () => {
  const base = await scratch()
  const root = join(base, "claude-root")
  await mkdir(join(root, "projects"), { recursive: true })
  await writeFile(join(root, "settings.json"), '{"theme":"dark"}')
  const home = await ensureHome(join(base, "homes"), { id: "work", agent: "claude" }, claudeLayout({ claudeRoot: root }))
  expect(home).toBe(join(base, "homes", "claude", "work"))
  expect((await stat(home)).mode & 0o777).toBe(0o700)
  for (const name of ["projects", "skills", "commands", "agents", "plugins", "history.jsonl", "settings.json", "CLAUDE.md"]) {
    expect((await lstat(join(home, name))).isSymbolicLink()).toBe(true)
    expect(await readlink(join(home, name))).toBe(join(root, name))
  }
  expect((await stat(join(root, "skills"))).isDirectory()).toBe(true)
  expect(await readFile(join(root, "history.jsonl"), "utf8")).toBe("")
  expect(await readFile(join(root, "settings.json"), "utf8")).toBe('{"theme":"dark"}')
  await expect(lstat(join(home, ".credentials.json"))).rejects.toThrow()
  await expect(lstat(join(home, "sessions"))).rejects.toThrow()
})

test("a missing settings.json is created as valid JSON", async () => {
  const base = await scratch()
  const root = join(base, "root")
  await ensureHome(join(base, "homes"), { id: "a", agent: "claude" }, claudeLayout({ claudeRoot: root }))
  expect(JSON.parse(await readFile(join(root, "settings.json"), "utf8"))).toEqual({})
})

test("idempotent; empty real entries are replaced, non-empty ones conflict", async () => {
  const base = await scratch()
  const root = join(base, "codex-root")
  const homes = join(base, "homes")
  const layout = codexLayout({ codexRoot: root })
  const home = await ensureHome(homes, { id: "c1", agent: "codex" }, layout)
  expect(await ensureHome(homes, { id: "c1", agent: "codex" }, layout)).toBe(home)
  expect(await readlink(join(home, "sessions"))).toBe(join(root, "sessions"))

  const other = join(homes, "codex", "c2")
  await mkdir(join(other, "sessions"), { recursive: true })
  await writeFile(join(other, "history.jsonl"), "")
  await ensureHome(homes, { id: "c2", agent: "codex" }, layout)
  expect((await lstat(join(other, "sessions"))).isSymbolicLink()).toBe(true)
  expect((await lstat(join(other, "history.jsonl"))).isSymbolicLink()).toBe(true)

  const busy = join(homes, "codex", "c3")
  await mkdir(join(busy, "sessions", "2026"), { recursive: true })
  await expect(ensureHome(homes, { id: "c3", agent: "codex" }, layout)).rejects.toMatchObject({ code: "account_home_conflict" })
  const busyFile = join(homes, "codex", "c4")
  await mkdir(busyFile, { recursive: true })
  await writeFile(join(busyFile, "session_index.jsonl"), "{}\n")
  await expect(ensureHome(homes, { id: "c4", agent: "codex" }, layout)).rejects.toMatchObject({ code: "account_home_conflict" })
  expect(await readFile(join(busyFile, "session_index.jsonl"), "utf8")).toBe("{}\n")
})

test("a stale link is re-pointed at the root", async () => {
  const base = await scratch()
  const root = join(base, "root")
  const homes = join(base, "homes")
  const home = await ensureHome(homes, { id: "x", agent: "codex" }, codexLayout({ codexRoot: join(base, "old-root") }))
  await ensureHome(homes, { id: "x", agent: "codex" }, codexLayout({ codexRoot: root }))
  expect(await readlink(join(home, "sessions"))).toBe(join(root, "sessions"))
})

test("isolated accounts get a private home with no links", async () => {
  const base = await scratch()
  const root = join(base, "root")
  const home = await ensureHome(join(base, "homes"), { id: "iso", agent: "claude", isolated: true }, claudeLayout({ claudeRoot: root }))
  await expect(lstat(join(home, "projects"))).rejects.toThrow()
  await expect(stat(root)).rejects.toThrow()
})

test("ids are validated", async () => {
  const base = await scratch()
  await expect(ensureHome(base, { id: "../escape", agent: "claude" })).rejects.toMatchObject({ code: "invalid_account_id" })
  await expect(ensureHome(base, { id: "ok", agent: "../claude" })).rejects.toMatchObject({ code: "invalid_input" })
})
