import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { reconcileOnStartup, Supervisor } from "../src/core/session-manager/supervisor"

function fakeSupervisor(): Supervisor & { superviseCalls: number } {
  const calls = { superviseCalls: 0 }
  return {
    superviseAgents: async () => { calls.superviseCalls++ },
    reconcile: async () => {},
    stop: () => {},
    get superviseCalls() { return calls.superviseCalls },
  }
}

let tmpDir: string
let db: ReturnType<typeof openDb>

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-reconcile-"))
  db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
})

afterEach(() => {
  try { db.close() } catch {}
  rmSync(tmpDir, { recursive: true, force: true })
})

test("reconcileOnStartup suspends sessions whose pids are dead (not drops)", async () => {
  const registry = new Registry(db)
  registry.register({ name: "alpha", workdir: "/x", tmux_target: "t1", pid: 100 })
  registry.register({ name: "beta",  workdir: "/y", tmux_target: "t2", pid: 200 })
  // alpha alive, beta dead
  const alive = (pid: number) => pid === 100
  const sup = fakeSupervisor()

  await reconcileOnStartup({
    registry, bindSocket: async () => {}, supervisor: sup, isAlive: alive,
  })

  expect(registry.resolveName("alpha")).toBeDefined()
  // beta is suspended (not dropped): still accessible, but status = "suspended"
  const beta = registry.resolveName("beta")
  expect(beta).toBeDefined()
  expect(beta?.status).toBe("suspended")
})

test("reconcileOnStartup binds sockets for every survivor, Agents included", async () => {
  const registry = new Registry(db)
  const alpha = registry.register({ name: "alpha",  workdir: "/x", tmux_target: "t1", pid: 100 })
  const assistant = registry.register({ name: "assistant", workdir: "/d", tmux_target: "td", pid: 101, role: "personal_assistant", is_default: true })
  const bound: string[] = []
  const sup = fakeSupervisor()

  await reconcileOnStartup({
    registry,
    bindSocket: async (id) => { bound.push(id) },
    supervisor: sup,
    isAlive: () => true,
  })

  // Agents bind like every session (keyed by UUID); restarting them is resumeAtBoot's job.
  expect(bound).toEqual([alpha.id, assistant.id])
  expect(sup.superviseCalls).toBe(0)
})

test("reconcileOnStartup does not unregister or suspend a dead Agent", async () => {
  // resumeAtBoot (then supervisor.superviseAgents) owns the Agent lifecycle.
  const registry = new Registry(db)
  registry.register({ name: "assistant", workdir: "/d", tmux_target: "td", pid: 999, role: "personal_assistant", is_default: true })
  const sup = fakeSupervisor()

  await reconcileOnStartup({
    registry,
    bindSocket: async () => {},
    supervisor: sup,
    isAlive: () => false,  // PA pid is dead
  })

  expect(registry.resolveName("assistant")?.status).toBe("active")
  expect(sup.superviseCalls).toBe(0)
})

test("reconcileOnStartup never supervises Agents itself (that runs after resumeAtBoot)", async () => {
  const registry = new Registry(db)
  const sup = fakeSupervisor()
  await reconcileOnStartup({
    registry, bindSocket: async () => {}, supervisor: sup, isAlive: () => true,
  })
  expect(sup.superviseCalls).toBe(0)
})

// A Core Claude row has no tmux pane and pid 0 (its agent lives under a keeper), so the
// tmux-era "dead pid → suspended" rule must not touch it: it stays active and resumeAtBoot
// re-attaches it. Only a tmux-era (core=0) Claude row with a dead pid and no pane is suspended.
test("reconcileOnStartup leaves Core Claude sessions active", async () => {
  const registry = new Registry(db)
  registry.register({ id: "core-1", name: "corey", workdir: "/x", pid: 0, agent: "claude", core: true } as never)
  registry.register({ id: "tmux-1", name: "tmuxy", workdir: "/y", pid: 200, agent: "claude", core: false } as never)
  await reconcileOnStartup({ registry, bindSocket: async () => {}, supervisor: fakeSupervisor(), isAlive: () => false, livePanePid: async () => null })
  expect(registry.resolveName("corey")?.status).toBe("active")
  expect(registry.resolveName("tmuxy")?.status).toBe("suspended")
})

// A suspended row stays suspended at startup even if its stored window id has a live pane: tmux
// reuses ids after its server restarts, so that pane can be another session's (dry run 2026-10-06).
test("reconcileOnStartup never wakes a suspended row through a live pane", async () => {
  const registry = new Registry(db)
  const s = registry.register({ id: "sus-1", name: "personal-7", workdir: "/x", pid: 0, agent: "claude", core: false, tmux_window_id: "@0" } as never)
  registry.sessions.suspend(s.id)
  await reconcileOnStartup({ registry, bindSocket: async () => {}, supervisor: fakeSupervisor(), isAlive: () => false, livePanePid: async () => 777 })
  expect(registry.resolveName("personal-7")?.status).toBe("suspended")
})
