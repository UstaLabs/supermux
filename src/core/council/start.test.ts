import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../storage/db"
import { MIGRATIONS } from "../storage/migrations/index"
import { Registry } from "../session-manager/registry"
import type { RepoInfo } from "../git/repo-info"
import type { WorktreeHandle } from "../worktree/manager"
import { CouncilStore } from "./store"
import { createCouncil, type StartCouncilArgs, type StartCouncilDeps } from "./start"

const NOTHING = { councils: 0, council_members: 0, council_rounds: 0, council_reports: 0, council_member_status: 0 }

function setup(opts: {
  repo?: RepoInfo
  /** The nth worktree (from 1) can't be made. */
  worktreeFails?: number
  /** This member's spawn throws; `live`: after its session row was made. */
  spawnFails?: { id: string; live?: boolean }
  notInstalled?: string
} = {}) {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const registry = new Registry(db)
  const store = new CouncilStore(db)
  const caller = registry.register({ name: "Planner", workdir: "/w", pid: 0, agent: "claude" })
  const events: string[] = []
  const made: Array<Parameters<StartCouncilDeps["createWorktree"]>[0]> = []
  const handles: WorktreeHandle[] = []
  const removed: Array<Parameters<StartCouncilDeps["removeWorktree"]>> = []
  const spawns: Array<Parameters<StartCouncilDeps["spawn"]>[0]> = []
  const atSpawn: unknown[] = []
  const atKill: Array<{ id: string; council: unknown; membership: unknown }> = []
  const spawnError = new Error("the agent exited at once")
  let councilId: string | undefined
  let ids = 0
  const deps: StartCouncilDeps = {
    store,
    session: (id) => registry.sessions.getById(id),
    isLive: (id) => !!registry.get(id),
    assertInstalled: (agent) => { if (agent === opts.notInstalled) throw new Error(`${agent} is not installed`) },
    workspaceOf: (id) => (id === caller.id ? "ws-1" : undefined),
    repoInfo: async (workdir) => {
      events.push(`repoInfo ${workdir}`)
      return opts.repo ?? { isGitRepo: true, eligible: true, repoRoot: "/repo", currentBranch: "feature" }
    },
    createWorktree: async (o) => {
      events.push(`worktree ${o.sessionName}`)
      made.push(o)
      const n = made.length
      if (n === opts.worktreeFails) throw new Error("branch already exists")
      const wt = { worktreeDir: `/wt/${n}`, sessionBranch: `branch-${n}`, baseBranch: o.baseBranch, repoRoot: o.repoRoot }
      handles.push(wt)
      return wt
    },
    removeWorktree: async (...a) => {
      events.push(`remove ${a[1]}`)
      removed.push(a)
    },
    newId: () => `m${++ids}`,
    spawn: async (a) => {
      events.push(`spawn ${a.id}`)
      spawns.push(a)
      atSpawn.push(store.membership(a.id))
      councilId ??= store.membership(a.id)?.councilId
      const fails = opts.spawnFails?.id === a.id
      if (!fails || opts.spawnFails?.live) {
        registry.register({ id: a.id, name: a.requestedName, workdir: a.workdir, pid: 0, agent: a.agent, model: a.model, parent_session_id: a.parentSessionId })
      }
      if (fails) throw spawnError
    },
    kill: async (id) => {
      events.push(`kill ${id}`)
      atKill.push({ id, council: store.get(councilId!), membership: store.membership(id) })
      registry.unregister(id)
    },
    opened: (id) => { events.push(`opened ${id}`) },
    memberStarted: (id) => { events.push(`started ${id}`) },
    memberStartFailed: (id, error) => { events.push(`failed ${id}: ${error}`) },
  }
  /** Rows in each council table. */
  const left = () => Object.fromEntries(Object.keys(NOTHING).map((t) => [t, (db.query(`SELECT COUNT(*) AS n FROM ${t}`).get() as { n: number }).n]))
  return { registry, store, caller, deps, events, made, handles, removed, spawns, atSpawn, atKill, spawnError, left, councilId: () => councilId }
}

const MEMBERS = [
  { agent: "codex" as const, model: "gpt-6", effort: "high", access: "auto" },
  { agent: "grok" as const, access: "ask" },
]

const failure = (p: Promise<unknown>): Promise<Error> => p.then(() => { throw new Error("it started") }, (err: Error) => err)

/** createCouncil, then its background start, to the end. */
async function startAll(deps: StartCouncilDeps, args: StartCouncilArgs) {
  const created = await createCouncil(deps, args)
  await created.run()
  return created.started
}

test("the council is saved and returned before any member starts", async () => {
  const t = setup()
  const created = await createCouncil(t.deps, { callerId: t.caller.id, question: "Q", members: MEMBERS, checkout: "shared" })
  expect(t.spawns).toEqual([])
  expect(t.store.members(created.started.council).map((m) => [m.letter, m.sessionId])).toEqual([["A", "m1"], ["B", "m2"]])
  expect(t.events).toEqual([`opened ${created.started.council}`])
  expect(created.started).toEqual({
    council: created.started.council,
    name: "council-1",
    members: [
      { letter: "A", session: "m1", agent: "codex", model: "gpt-6" },
      { letter: "B", session: "m2", agent: "grok", model: null },
    ],
  })
})

test("shared: each member starts in the caller's folder, hidden in its workspace, and gets the question once it started", async () => {
  const t = setup()
  const r = await startAll(t.deps, { callerId: t.caller.id, question: "Refresh tokens?", members: MEMBERS, checkout: "shared", callId: "toolu_1" })
  expect(t.store.get(r.council)).toMatchObject({ name: "council-1", callerSessionId: t.caller.id, question: "Refresh tokens?", callId: "toolu_1" })
  expect(t.spawns).toEqual([
    { id: "m1", workdir: "/w", requestedName: "council-1 A", agent: "codex", model: "gpt-6", reasoningLevel: "high", permissionMode: "auto", worktree: false, parentSessionId: t.caller.id, workspaceId: "ws-1" },
    { id: "m2", workdir: "/w", requestedName: "council-1 B", agent: "grok", permissionMode: "ask", worktree: false, parentSessionId: t.caller.id, workspaceId: "ws-1" },
  ])
  // Each member's membership exists when it starts: its preamble and its tool list read it.
  expect(t.atSpawn).toEqual([
    { councilId: r.council, sessionId: "m1", letter: "A" },
    { councilId: r.council, sessionId: "m2", letter: "B" },
  ])
  // Both start at once: neither waits for the other.
  expect(t.events).toEqual([`opened ${r.council}`, "spawn m1", "spawn m2", "started m1", "started m2"])
})

test("worktree: each member starts in its own worktree, from the caller's current branch", async () => {
  const t = setup()
  await startAll(t.deps, { callerId: t.caller.id, question: "Q", members: MEMBERS, checkout: "worktree" })
  expect(t.made).toEqual([
    { repoRoot: "/repo", baseBranch: "feature", sessionName: "council-1 A" },
    { repoRoot: "/repo", baseBranch: "feature", sessionName: "council-1 B" },
  ])
  expect(t.spawns.map((a) => [a.id, a.worktreeHandle?.worktreeDir])).toEqual([["m1", "/wt/1"], ["m2", "/wt/2"]])
  expect(t.removed).toEqual([])
})

test("worktree in a folder that is not a single git repository: the error, and no council", async () => {
  const t = setup({ repo: { isGitRepo: false, eligible: false } })
  const err = await failure(createCouncil(t.deps, { callerId: t.caller.id, question: "Q", members: MEMBERS, checkout: "worktree" }))
  expect(err.message).toBe("/w is not a single git repository, so no worktree can be made")
  expect(t.events).toEqual(["repoInfo /w"])
  expect(t.left()).toEqual(NOTHING)
})

test("a worktree that can't be made: that member doesn't start and is reported; the other one starts", async () => {
  const t = setup({ worktreeFails: 2 })
  await startAll(t.deps, { callerId: t.caller.id, question: "Q", members: MEMBERS, checkout: "worktree" })
  expect(t.spawns.map((a) => a.id)).toEqual(["m1"])
  expect(t.events).toContain("failed m2: could not create a worktree: branch already exists")
  expect(t.events).toContain("started m1")
  expect(t.removed).toEqual([])
})

test("a member that fails to start: its worktree is removed and it is reported; the others carry on", async () => {
  const t = setup({ spawnFails: { id: "m2" } })
  await startAll(t.deps, { callerId: t.caller.id, question: "Q", members: MEMBERS, checkout: "worktree" })
  expect(t.atKill).toEqual([])
  expect(t.removed).toEqual([["/repo", "/wt/2", "branch-2", { force: true }]])
  expect(t.events.filter((e) => e.startsWith("started") || e.startsWith("failed"))).toEqual(["started m1", "failed m2: the agent exited at once"])
  expect(t.registry.get("m1")).toBeDefined()
})

test("a member whose spawn threw after its session row was made is stopped, then reported", async () => {
  const t = setup({ spawnFails: { id: "m2", live: true } })
  await startAll(t.deps, { callerId: t.caller.id, question: "Q", members: MEMBERS, checkout: "shared" })
  expect(t.atKill.map((k) => k.id)).toEqual(["m2"])
  expect(t.registry.get("m2")).toBeUndefined()
  expect(t.events.indexOf("kill m2")).toBeLessThan(t.events.indexOf("failed m2: the agent exited at once"))
})

test("an unknown caller, or a member whose agent is not installed, is refused before the council exists", async () => {
  const t = setup({ notInstalled: "grok" })
  expect((await failure(createCouncil(t.deps, { callerId: "gone", question: "Q", members: MEMBERS, checkout: "shared" }))).message).toBe("unknown session")
  expect((await failure(createCouncil(t.deps, { callerId: t.caller.id, question: "Q", members: MEMBERS, checkout: "shared" }))).message).toBe("grok is not installed")
  expect(t.events).toEqual([])
  expect(t.left()).toEqual(NOTHING)
})
