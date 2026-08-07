import { afterAll, afterEach, beforeEach, expect, mock, test } from "bun:test"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { STATE_DIR } from "../src/shared/paths"

// NOTE on mocking: bun's mock.module is process-global — a mock installed here
// stays installed for every test file loaded afterwards. The original note said
// auth/spawn/preamble were safe to mock because they had "no sibling unit test";
// that stopped being true (src/core/agents/opencode/config-writer.test.ts imports
// resolveOpenCodeAuth, tests/agents/windows-launch.test.ts imports
// spawnOpenCodeServer) and both started failing in a full `bun test` while
// passing standalone. So: capture the real modules first and restore them in
// afterAll — same pattern as src/core/tunnels/cloudflared.test.ts.
const realAuth = await import("../src/core/agents/opencode/auth")
const realPreamble = await import("../src/core/agents/opencode/preamble-writer")
const realSpawn = await import("../src/core/agents/opencode/spawn")

let authed = true
const boundIds: string[] = []

mock.module("../src/core/agents/opencode/auth", () => ({
  resolveOpenCodeAuth: () => ({ env: {}, dataDir: "/d", authPath: "/d/auth.json", authed }),
}))
mock.module("../src/core/agents/opencode/preamble-writer", () => ({
  writeOpenCodePreamble: () => "/tmp/oc-AGENTS.md",
}))
mock.module("../src/core/agents/opencode/spawn", () => ({
  // Fake handle with a minimal working client so the REAL adapter.start()
  // (session.create + event.subscribe) resolves without a live server.
  spawnOpenCodeServer: async () => ({
    pid: 456,
    baseUrl: "http://127.0.0.1:1",
    client: {
      session: { create: async () => ({ data: { id: "oc-sess" } }), update: async () => ({ data: {} }), prompt: async () => ({ data: { parts: [] } }), abort: async () => true },
      event: { subscribe: async () => ({ stream: (async function* () {})() }) },
    },
    kill: () => {},
    onExit: () => {},
  }),
}))

let tmpDir: string
const createdNames: string[] = []
beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "mux-spawn-oc-"))
  authed = true
  boundIds.length = 0
  createdNames.length = 0
})
afterEach(() => {
  rmSync(tmpDir, { recursive: true, force: true })
  for (const n of createdNames) rmSync(join(STATE_DIR, "agents", "opencode", n), { recursive: true, force: true })
})
// Undo the process-global module mocks so later test files see the real modules.
afterAll(() => {
  mock.module("../src/core/agents/opencode/auth", () => realAuth)
  mock.module("../src/core/agents/opencode/preamble-writer", () => realPreamble)
  mock.module("../src/core/agents/opencode/spawn", () => realSpawn)
})

function deps(registry: any) {
  return {
    registry,
    bind: async (id: string) => { boundIds.push(id) },
    tmuxSession: "mux",
    registerAdapter: () => {},
    onOpenCodeSessionId: () => {},
  } as any
}

async function freshRegistry() {
  const { openDb, runMigrations } = await import("../src/core/storage/db")
  const { Registry } = await import("../src/core/session-manager/registry")
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  return new Registry(db)
}

test("opencode spawn threads ONE uuid through registry, socket bind, and the shim config", async () => {
  const { spawnSession } = await import("../src/core/session-manager/spawn-helper")
  const registry = await freshRegistry()

  const result = await spawnSession(deps(registry), { workdir: tmpDir, requestedName: "oc-id", agent: "opencode" })
  createdNames.push(result.name)

  // id consistency: registry row == returned id == bound socket id
  expect(registry.resolveName(result.name)?.id).toBe(result.session_id)
  expect(registry.resolveName(result.name)?.agent).toBe("opencode")
  expect(boundIds).toEqual([result.session_id])

  // ...and the SAME id landed in the shim's MCP config as MUX_SESSION_ID (read
  // back from the file the real config-writer produced).
  const cfgPath = join(STATE_DIR, "agents", "opencode", result.name, "config", "opencode", "opencode.json")
  const cfg = JSON.parse(readFileSync(cfgPath, "utf8"))
  expect(cfg.mcp.mux.environment.MUX_SESSION_ID).toBe(result.session_id)
})

test("opencode spawn succeeds without auth — free tier, not fail-closed", async () => {
  const { spawnSession } = await import("../src/core/session-manager/spawn-helper")
  const registry = await freshRegistry()
  authed = false // no auth.json / no provider key

  const result = await spawnSession(deps(registry), { workdir: tmpDir, requestedName: "oc-noauth", agent: "opencode" })
  createdNames.push(result.name)
  // The free opencode/* tier runs with zero credentials, so the session spawns
  // regardless; provider-gated models just error at prompt time.
  expect(registry.resolveName(result.name)?.agent).toBe("opencode")
})
