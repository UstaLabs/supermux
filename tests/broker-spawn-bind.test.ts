import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { spawnSession } from "../src/core/session-manager/spawn-helper"
import type { SessionBackend } from "../src/core/runtime/session-backend"

let tmpDir: string

function makeRegistry(): Registry {
  const db = openDb(join(tmpDir, `test-${Math.random()}.sqlite3`))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  return new Registry(db)
}

beforeEach(() => { tmpDir = mkdtempSync(join(tmpdir(), "agentmux-spawn-")) })
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

test("spawnSession binds the socket before spawning tmux", async () => {
  const calls: string[] = []
  const registry = makeRegistry()
  await spawnSession(
    {
      registry,
      bind: async (_id) => { calls.push("bind") },
      sessionBackend: {
        list: async () => [],
        create: async (opts: Parameters<SessionBackend["create"]>[0]) => { calls.push("runtime"); return { id: "target", name: opts.name, pid: 1, alive: true } },
      } as unknown as SessionBackend,
      tmuxSession: "agentmux",
      postSpawnReady: async () => {},
    },
    { workdir: "/tmp/foo" },
  )
  // Order matters: bind MUST precede tmux, otherwise the shim hits ENOENT.
  expect(calls).toEqual(["bind", "runtime"])
})

test("Claude spawn sends structured argv and env to the session backend", async () => {
  const registry = makeRegistry()
  let created: Parameters<SessionBackend["create"]>[0] | undefined
  const sessionBackend = {
    list: async () => [],
    create: async (opts: Parameters<SessionBackend["create"]>[0]) => {
      created = opts
      return { id: "runtime-target-1", name: opts.name, pid: 4242, alive: true }
    },
    capture: async () => "Listening for channel messages",
  } as unknown as SessionBackend

  const result = await spawnSession({
    registry,
    bind: async () => {},
    spawnTmux: async () => { throw new Error("Claude must not use the POSIX spawn adapter") },
    sessionBackend,
    tmuxSession: "mux",
  }, {
    workdir: String.raw`C:\Users\Ahmet Test\project`,
    requestedName: "windows-worker",
    model: "claude-opus-4-8",
  })

  expect(created?.group).toBe("mux")
  expect(created?.cwd).toBe(String.raw`C:\Users\Ahmet Test\project`)
  expect(created?.argv[0]).toBe("claude")
  expect(created?.argv).not.toContain("bash")
  expect(created?.env.MUX_SESSION_ID).toBe(result.session_id)
  expect(created?.env.MUX_DISPLAY_NAME).toBe("windows-worker")
})

test("spawnSession resolves a unique name before tmux spawn (no race)", async () => {
  const registry = makeRegistry()
  // Pretend something else already grabbed "foo".
  registry.register({ name: "foo", workdir: "/x", tmux_target: "mux:foo", pid: 1 })

  let created: Parameters<SessionBackend["create"]>[0] | undefined
  const result = await spawnSession(
    {
      registry,
      bind: async () => {},
      sessionBackend: {
        list: async () => [],
        create: async (opts: Parameters<SessionBackend["create"]>[0]) => { created = opts; return { id: "target", name: opts.name, pid: 1, alive: true } },
      } as unknown as SessionBackend,
      tmuxSession: "agentmux",
      postSpawnReady: async () => {},
    },
    { workdir: "/tmp/foo" },
  )
  // ensureUnique made it foo-2; tmux must see that, not "foo".
  expect(result.name).toBe("foo-2")
  expect(created?.name).toBe("foo-2")
  // session_id is now a UUID; the tmux command carries it as MUX_SESSION_ID.
  // The human-readable name is carried separately as MUX_DISPLAY_NAME.
  expect(result.session_id).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i)
  expect(created?.env.MUX_SESSION_ID).toBe(result.session_id)
  expect(created?.env.MUX_DISPLAY_NAME).toBe("foo-2")
})

test("two concurrent spawnSessions don't collide on the same name", async () => {
  const registry = makeRegistry()
  const seenWindows: string[] = []
  const deps = {
    registry,
    bind: async () => {},
    sessionBackend: {
      list: async () => [],
      create: async (opts: Parameters<SessionBackend["create"]>[0]) => {
        seenWindows.push(opts.name)
        return { id: `target-${seenWindows.length}`, name: opts.name, pid: 1, alive: true }
      },
    } as unknown as SessionBackend,
    tmuxSession: "agentmux",
    postSpawnReady: async () => {},
  }
  const [a, b] = await Promise.all([
    spawnSession(deps, { workdir: "/tmp/foo" }),
    spawnSession(deps, { workdir: "/tmp/foo" }),
  ])
  expect(a.name).not.toBe(b.name)
  // Both tmux windows should be the resolved unique names, not duplicates.
  expect(new Set(seenWindows).size).toBe(2)
})

test("spawnSession releases the reservation if tmux spawn fails", async () => {
  const registry = makeRegistry()
  await expect(
    spawnSession(
      {
        registry,
        bind: async () => {},
        sessionBackend: {
          list: async () => [],
          create: async () => { throw new Error("runtime unavailable") },
        } as unknown as SessionBackend,
        tmuxSession: "agentmux",
        postSpawnReady: async () => {},
      },
      { workdir: "/tmp/foo", requestedName: "alpha" },
    ),
  ).rejects.toThrow(/runtime/)
  // Reservation must be released so a retry can claim the name.
  expect(registry.takenNames().has("alpha")).toBe(false)
})
