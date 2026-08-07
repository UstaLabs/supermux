import { afterEach, beforeEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { spawnSession, type SpawnDeps } from "../src/core/session-manager/spawn-helper"

const codexAdapterOpts: any[] = []

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
    codexResolveAuth: async () => ({ mode: "oauth_copy", env: {} }),
    codexPrepareSessionHome: async () => {},
    codexSpawnArgs: () => ({ args: [], env: {} }),
    codexSpawnAppServer: () => ({
      pid: 123,
      client: { request: async () => ({}), onNotification: () => {} },
      onExit: () => {},
    }) as any,
    codexAdapterFactory: (opts) => {
      codexAdapterOpts.push(opts)
      return {
        kind: "codex",
        sessionName: opts.sessionName,
        workdir: opts.workdir,
        start: async () => {},
        resume: async () => {},
        stop: async () => {},
        send: async () => {},
        interrupt: async () => {},
        on: () => {},
        emit: () => {},
      } as any
    },
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
