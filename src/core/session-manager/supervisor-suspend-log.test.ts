import { describe, expect, test, spyOn } from "bun:test"
import { spawn } from "child_process"
import { join, dirname } from "path"
import { fileURLToPath } from "url"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"
import { createSupervisor, reconcileOnStartup } from "./supervisor"
import type { SessionBackend } from "../runtime/session-backend"

// Observability: when the broker detects a worker session whose process has
// died, it flips it to `suspended`. That transition used to be SILENT (no log),
// which is a big reason "claude got killed mid-session" was so hard to diagnose.
// These tests pin that the suspend is now logged on BOTH death-detection paths.

const MIGRATIONS = join(dirname(fileURLToPath(import.meta.url)), "../storage/migrations")

function freshRegistry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return new Registry(db)
}

const supervisorStub = {
  ensurePersonalAssistants: async () => {},
  bootstrapPA: async () => {},
  reconcile: async () => {},
  stop: () => {},
}

// A pid that is guaranteed dead: spawn a trivial process and wait for it to exit.
async function deadPid(): Promise<number> {
  const child = spawn("true")
  const pid = child.pid!
  await new Promise<void>((r) => child.on("exit", () => r()))
  return pid
}

describe("session-death observability (suspend logging)", () => {
  test("the timer reconcile logs session_suspended when a worker's process is gone", async () => {
    const registry = freshRegistry()
    const pid = await deadPid()
    const s = registry.register({ name: "ztest-dead-timer", workdir: "/tmp", tmux_target: "mux:ztest1", pid })
    const sup = createSupervisor({
      registry,
      bindSocket: async () => {},
      sessionBackend: { resolve: async () => null } as unknown as SessionBackend,
    })

    const prev = process.env.MUX_LOG_LEVEL
    process.env.MUX_LOG_LEVEL = "info"
    const spy = spyOn(process.stderr, "write")
    let logged = ""
    try {
      await sup.reconcile()
      logged = spy.mock.calls.map((c) => String(c[0])).join("")
    } finally {
      spy.mockRestore()
      sup.stop()
      process.env.MUX_LOG_LEVEL = prev
    }

    expect(registry.get(s.id)?.status).toBe("suspended")
    expect(logged).toContain("session_suspended")
    expect(logged).toContain(s.id)
  })

  test("reconcileOnStartup logs session_suspended for a dead worker", async () => {
    const registry = freshRegistry()
    const s = registry.register({ name: "ztest-dead-startup", workdir: "/tmp", tmux_target: "mux:ztest2", pid: 1 })

    const prev = process.env.MUX_LOG_LEVEL
    process.env.MUX_LOG_LEVEL = "info"
    const spy = spyOn(process.stderr, "write")
    let logged = ""
    try {
      await reconcileOnStartup({
        registry,
        bindSocket: async () => {},
        supervisor: supervisorStub,
        isAlive: () => false,
      })
      logged = spy.mock.calls.map((c) => String(c[0])).join("")
    } finally {
      spy.mockRestore()
      process.env.MUX_LOG_LEVEL = prev
    }

    expect(registry.get(s.id)?.status).toBe("suspended")
    expect(logged).toContain("session_suspended")
    expect(logged).toContain(s.id)
  })

  test("reconcileOnStartup KEEPS a claude session whose pid is dead but its tmux pane survived, adopting the pane pid", async () => {
    // After a broker restart the stored pid is stale (a dead broker pid from a
    // lazy-resume, or pid=0 from a DB-only load), but the claude pane lives on in
    // its own systemd scope. Trust the pane, not the pid — otherwise the live
    // session is false-suspended and the next message kill-and-respawns it.
    const registry = freshRegistry()
    const s = registry.register({ name: "ztest-pane-survived", workdir: "/tmp", tmux_target: "mux:z3", pid: 1 })
    registry.sessions.setTmuxWindowId(s.id, "@42")

    await reconcileOnStartup({
      registry,
      bindSocket: async () => {},
      supervisor: supervisorStub,
      isAlive: () => false,                                   // stored pid is dead
      livePanePid: async (wid) => (wid === "@42" ? 4242 : null), // but the pane survived
    })

    const after = registry.get(s.id)
    expect(after?.status).toBe("active")   // NOT suspended — the pane is alive
    expect(after?.pid).toBe(4242)          // pid adopted from the surviving pane
  })

  test("reconcileOnStartup still suspends a claude session whose pid AND pane are both dead", async () => {
    const registry = freshRegistry()
    const s = registry.register({ name: "ztest-both-dead", workdir: "/tmp", tmux_target: "mux:z4", pid: 1 })
    registry.sessions.setTmuxWindowId(s.id, "@99")

    await reconcileOnStartup({
      registry,
      bindSocket: async () => {},
      supervisor: supervisorStub,
      isAlive: () => false,
      livePanePid: async () => null,   // pane gone too
    })

    expect(registry.get(s.id)?.status).toBe("suspended")
  })
})
