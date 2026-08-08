import { afterAll, afterEach, beforeEach, expect, mock, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { spawnSession, type SpawnDeps } from "../src/core/session-manager/spawn-helper"

// The codex collaborators are swapped via bun's module mocks (the production
// spawn path has no injection seams). mock.module is process-global, so the
// real modules are captured first and restored in afterAll — otherwise later
// test files would see the fakes (same pattern as tests/spawn-opencode.test.ts).
const realCodexAuth = { ...(await import("../src/core/agents/codex/auth")) }
const realCodexSpawn = { ...(await import("../src/core/agents/codex/spawn")) }
const realCodexAdapter = { ...(await import("../src/core/agents/codex/adapter")) }
const realPlugins = { ...(await import("../src/core/plugins")) }

const codexAdapterOpts: any[] = []

mock.module("../src/core/agents/codex/auth", () => ({
  ...realCodexAuth,
  resolveCodexAuth: async () => ({ mode: "oauth_copy", env: {} }),
}))
mock.module("../src/core/agents/codex/spawn", () => ({
  ...realCodexSpawn,
  spawnCodexAppServer: () => ({
    pid: 123,
    client: { request: async () => ({}), onNotification: () => {} },
    onExit: () => {},
  }),
}))
mock.module("../src/core/agents/codex/adapter", () => ({
  ...realCodexAdapter,
  CodexAdapter: class {
    kind = "codex"
    sessionName: string
    workdir: string
    constructor(opts: any) {
      codexAdapterOpts.push(opts)
      this.sessionName = opts.sessionName
      this.workdir = opts.workdir
    }
    async start() {}
    async resume() {}
    async stop() {}
    async send() {}
    async interrupt() {}
    on() {}
    emit() {}
  },
}))
mock.module("../src/core/plugins", () => ({
  ...realPlugins,
  codexPrepareSessionHome: async () => {},
  codexSpawnArgs: () => ({ args: [], env: {} }),
}))

afterAll(() => {
  mock.module("../src/core/agents/codex/auth", () => realCodexAuth)
  mock.module("../src/core/agents/codex/spawn", () => realCodexSpawn)
  mock.module("../src/core/agents/codex/adapter", () => realCodexAdapter)
  mock.module("../src/core/plugins", () => realPlugins)
})

let tmpDir: string

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "mux-spawn-codex-"))
  codexAdapterOpts.length = 0
})

afterEach(() => {
  rmSync(tmpDir, { recursive: true, force: true })
})

function makeDeps(registry: Registry, extra?: Partial<SpawnDeps>): SpawnDeps {
  return {
    registry,
    bind: async () => {},
    tmuxSession: "mux",
    ...extra,
  }
}

test("fresh codex spawn wires attachment resolver into registered adapter", async () => {
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const registry = new Registry(db)
  const resolveAttachment = async (file_id: string) => `/uploads/${file_id}.png`

  await spawnSession(
    makeDeps(registry, {
      resolveAttachment,
      registerAdapter: () => {},
      onThreadId: () => {},
    } as Partial<SpawnDeps>),
    { workdir: tmpDir, requestedName: "codex-img", agent: "codex" },
  )

  expect(codexAdapterOpts).toHaveLength(1)
  expect(codexAdapterOpts[0]!.resolveAttachment).toBe(resolveAttachment)
})

test("fresh codex spawn registers the same UUID used by the shim socket", async () => {
  const db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const registry = new Registry(db)

  const result = await spawnSession(
    makeDeps(registry, {
      registerAdapter: () => {},
      onThreadId: () => {},
    } as Partial<SpawnDeps>),
    { workdir: tmpDir, requestedName: "codex-id", agent: "codex" },
  )

  expect(registry.get(result.session_id)?.id).toBe(result.session_id)
})
