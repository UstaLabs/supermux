import { test, expect } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations"
import { sqliteCommandStore } from "./store"
import { agentCommand } from "./types"

const list = (names: string[], project?: string[]) => ({
  commands: names.map((name) => agentCommand({ name, sigil: "/" })),
  ...(project ? { projectIds: project.map((n) => `agent:${n}`) } : {}),
})

function fresh(now: () => number) {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return { db, store: sqliteCommandStore(db, now) }
}

test("a list saved for a folder is loaded back for that folder only", () => {
  const { store } = fresh(() => 1)
  store.save("claude", ["/w/a"], list(["verify", "proj"], ["proj"]))
  expect(store.load("claude", "/w/a")).toEqual(list(["verify", "proj"], ["proj"]))
  expect(store.load("claude", "/w/b")).toBeUndefined()
  expect(store.load("codex", "/w/a")).toBeUndefined()
})

test("save writes every folder it is given (a worktree and its repository)", () => {
  const { store } = fresh(() => 1)
  store.save("grok", ["/w/tree", "/w/repo"], list(["soul"]))
  expect(store.load("grok", "/w/tree")?.commands.map((c) => c.name)).toEqual(["soul"])
  expect(store.load("grok", "/w/repo")?.commands.map((c) => c.name)).toEqual(["soul"])
})

test("latest is the most recently changed folder of that agent", () => {
  let t = 1
  const { store } = fresh(() => t)
  store.save("claude", ["/w/a"], list(["a"]))
  t = 2
  store.save("claude", ["/w/b"], list(["b"]))
  t = 3
  store.save("codex", ["/w/c"], list(["c"]))
  expect(store.latest("claude")?.commands.map((c) => c.name)).toEqual(["b"])
  expect(store.latest("cursor")).toBeUndefined()
})

test("an identical list is not rewritten, so it does not become the latest", () => {
  let t = 1
  const { db, store } = fresh(() => t)
  store.save("claude", ["/w/a"], list(["a"]))
  t = 2
  store.save("claude", ["/w/b"], list(["b"]))
  t = 3
  store.save("claude", ["/w/a"], list(["a"]))
  const row = db.query("SELECT updated_at FROM agent_commands WHERE agent = 'claude' AND workdir = '/w/a'").get() as { updated_at: number }
  expect(row.updated_at).toBe(1)
  expect(store.latest("claude")?.commands.map((c) => c.name)).toEqual(["b"])
})

test("keeps the newest 50 folders per agent", () => {
  let t = 0
  const { db, store } = fresh(() => t)
  for (let i = 0; i < 55; i++) { t = i; store.save("claude", [`/w/${i}`], list([`c${i}`])) }
  store.save("codex", ["/w/x"], list(["x"]))
  const count = (agent: string) => (db.query("SELECT COUNT(*) AS n FROM agent_commands WHERE agent = ?").get(agent) as { n: number }).n
  expect(count("claude")).toBe(50)
  expect(count("codex")).toBe(1)
  expect(store.load("claude", "/w/4")).toBeUndefined()
  expect(store.load("claude", "/w/5")?.commands.map((c) => c.name)).toEqual(["c5"])
})

test("a row that does not parse reads as nothing", () => {
  const { db, store } = fresh(() => 1)
  db.query("INSERT INTO agent_commands (agent, workdir, commands, updated_at) VALUES ('claude', '/w/a', '{bad', 1)").run()
  expect(store.load("claude", "/w/a")).toBeUndefined()
  expect(store.latest("claude")).toBeUndefined()
})

test("reads stay current after a save and after old folders are pruned", () => {
  let t = 0
  const { store } = fresh(() => t)
  store.save("claude", ["/w/0"], list(["first"]))
  expect(store.load("claude", "/w/0")?.commands.map((c) => c.name)).toEqual(["first"])
  expect(store.latest("claude")?.commands.map((c) => c.name)).toEqual(["first"])
  t = 1
  store.save("claude", ["/w/0"], list(["second"]))
  expect(store.load("claude", "/w/0")?.commands.map((c) => c.name)).toEqual(["second"])
  expect(store.latest("claude")?.commands.map((c) => c.name)).toEqual(["second"])
  for (let i = 1; i <= 55; i++) { t = 1 + i; store.save("claude", [`/w/${i}`], list([`c${i}`])) }
  expect(store.load("claude", "/w/0")).toBeUndefined() // pruned
  expect(store.latest("claude")?.commands.map((c) => c.name)).toEqual(["c55"])
})
