import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { spawnSession } from "../src/core/session-manager/spawn-helper"
import { setSessionBackendForTests } from "../src/core/runtime"
import type { SessionBackend } from "../src/core/runtime/session-backend"
import { fakeClaudeHost } from "./helpers/fake-claude-host"
import { createClaudeCoreHost } from "../src/core/agents/claude/core-host"
import type { AgentDriver } from "../packages/supermux-core/src/index.js"

// Non-PA Claude sessions are Core-backed now (CL1): the tmux backend only
// hosts the PA. Worker spawns run against a REAL library host over a fake
// driver; the PA argv/env contract still goes through the backend fake.
let tmpDir: string
let fake = fakeClaudeHost()

function makeRegistry(): Registry {
  const db = openDb(join(tmpDir, `test-${Math.random()}.sqlite3`))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  return new Registry(db)
}

beforeEach(() => { tmpDir = mkdtempSync(join(tmpdir(), "agentmux-spawn-")); fake = fakeClaudeHost() })
afterEach(async () => {
  await fake.close()
  setSessionBackendForTests()
  rmSync(tmpDir, { recursive: true, force: true })
})

// All fakes answer capture with the "listening" marker so the post-spawn
// consent poll (sendChannelConsentEnter) returns immediately.
const LISTENING = "Listening for channel messages"

test("spawnSession binds the socket before opening the Core session", async () => {
  const calls: string[] = []
  const registry = makeRegistry()
  const workdir = mkdtempSync(join(tmpDir, "wd-"))
  await spawnSession(
    {
      registry,
      bind: async (_id) => { calls.push("bind") },
      tmuxSession: "agentmux",
      claudeHost: fake.host,
    },
    { workdir },
  )
  // Order matters: bind MUST precede the native open, otherwise the shim hits ENOENT.
  expect(calls).toEqual(["bind"])
  expect(fake.opens).toHaveLength(1)
})

test("Claude PA spawn registers a Core row and does not create a tmux window", async () => {
  const registry = makeRegistry()
  let created = 0
  setSessionBackendForTests({
    list: async () => [],
    create: async () => {
      created++
      return { id: "runtime-target-1", name: "x", pid: 4242, alive: true }
    },
    capture: async () => LISTENING,
  } as unknown as SessionBackend)

  const result = await spawnSession({
    registry,
    bind: async () => {},
    tmuxSession: "mux",
    claudeHost: fake.host,
  }, {
    workdir: mkdtempSync(join(tmpDir, "pa-wd-")),
    requestedName: "windows-worker",
    model: "claude-opus-4-8",
    pa: { skipRegister: false },
  })

  expect(created).toBe(0)
  expect(result.pid).toBe(0)
  const row = registry.get(result.session_id)
  expect(row?.core).toBe(true)
  expect(row?.role).toBe("personal_assistant")
  expect(row?.name).toBe("windows-worker")
})

test("spawnSession resolves a unique name before the Core open (no race)", async () => {
  const registry = makeRegistry()
  // Pretend something else already grabbed "foo".
  registry.register({ name: "foo", workdir: "/x", tmux_target: "mux:foo", pid: 1 })
  const workdir = mkdtempSync(join(tmpDir, "foo-"))
  const result = await spawnSession(
    {
      registry,
      bind: async () => {},
      tmuxSession: "agentmux",
      claudeHost: fake.host,
    },
    { workdir, requestedName: "foo" },
  )
  // ensureUnique made it foo-2; the registry row and the Core session carry that.
  expect(result.name).toBe("foo-2")
  expect(result.session_id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
  expect(registry.get(result.session_id)?.name).toBe("foo-2")
  expect(fake.opens[0]?.sessionId).toBe(result.session_id)
})

test("two concurrent spawnSessions don't collide on the same name", async () => {
  const registry = makeRegistry()
  const workdir = mkdtempSync(join(tmpDir, "same-"))
  const deps = {
    registry,
    bind: async () => {},
    tmuxSession: "agentmux",
    claudeHost: fake.host,
  }
  const [a, b] = await Promise.all([
    spawnSession(deps, { workdir }),
    spawnSession(deps, { workdir }),
  ])
  expect(a.name).not.toBe(b.name)
  // Both Core sessions are the resolved unique names, not duplicates.
  expect(new Set([registry.get(a.session_id)?.name, registry.get(b.session_id)?.name]).size).toBe(2)
  expect(fake.opens).toHaveLength(2)
})

test("spawnSession releases the reservation if the Core open fails", async () => {
  const registry = makeRegistry()
  const stateDirectory = mkdtempSync(join(tmpDir, "state-"))
  const failing: AgentDriver = { id: "claude", async open() { throw new Error("runtime unavailable") } }
  const host = createClaudeCoreHost({ stateDirectory, driverFactory: () => failing })
  const workdir = mkdtempSync(join(tmpDir, "alpha-"))
  try {
    await expect(
      spawnSession(
        {
          registry,
          bind: async () => {},
          tmuxSession: "agentmux",
          claudeHost: host,
        },
        { workdir, requestedName: "alpha" },
      ),
    ).rejects.toThrow(/runtime/)
  } finally { await host.close({ agents: "shutdown" }).catch(() => {}) }
  // Reservation must be released so a retry can claim the name.
  expect(registry.takenNames().has("alpha")).toBe(false)
})
