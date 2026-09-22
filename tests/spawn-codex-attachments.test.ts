import { afterAll, afterEach, beforeEach, expect, mock, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import { spawnSession, type SpawnDeps } from "../src/core/session-manager/spawn-helper"
import { fakeCodexHost } from "./helpers/fake-codex-host"

// The codex collaborators are swapped via bun's module mocks (the production
// spawn path has no injection seams). mock.module is process-global, so the
// real modules are captured first and restored in afterAll — otherwise later
// test files would see the fakes (same pattern as tests/spawn-opencode.test.ts).
const realCodexCoreHost = { ...(await import("../src/core/agents/codex/core-host-provider")) }
const realPlugins = { ...(await import("../src/core/plugins")) }

let fake = fakeCodexHost()

mock.module("../src/core/agents/codex/core-host-provider", () => ({
  ...realCodexCoreHost,
  getCodexCoreHost: () => fake.host,
}))
mock.module("../src/core/plugins", () => ({
  ...realPlugins,
  codexPrepareSessionHome: async () => {},
  codexSpawnArgs: () => ({ args: [], env: {} }),
}))

afterAll(() => {
  mock.module("../src/core/agents/codex/core-host-provider", () => realCodexCoreHost)
  mock.module("../src/core/plugins", () => realPlugins)
})

let tmpDir: string

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "mux-spawn-codex-"))
  fake = fakeCodexHost()
})

afterEach(async () => {
  await fake.close()
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
  const resolveAttachment = async (file_id: string) => `/uploads/${file_id}.pdf`
  let adapter: { send(text: string, meta: { attachment_file_id: string; attachment_name: string }): Promise<void> } | undefined

  await spawnSession(
    makeDeps(registry, {
      resolveAttachment,
      registerAdapter: (_name, registered) => { adapter = registered as typeof adapter },
      onThreadId: () => {},
    } as Partial<SpawnDeps>),
    { workdir: tmpDir, requestedName: "codex-img", agent: "codex" },
  )

  // The resolver is wired end to end: the resolved path lands in the prompt.
  await adapter!.send("look", { attachment_file_id: "f1", attachment_name: "doc.pdf" })
  expect(fake.prompts.flat().join("\n")).toContain("doc.pdf (/uploads/f1.pdf)")
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
