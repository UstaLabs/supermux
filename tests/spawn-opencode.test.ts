import { afterEach, beforeEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { STATE_DIR } from "../src/shared/paths"
import { fakeOpenCodeHost } from "./helpers/fake-opencode-host"

let tmpDir: string
const createdNames: string[] = []
let fakeOc = fakeOpenCodeHost()

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "mux-spawn-oc-"))
  createdNames.length = 0
  fakeOc = fakeOpenCodeHost()
})
afterEach(async () => {
  await fakeOc.close()
  rmSync(tmpDir, { recursive: true, force: true })
  for (const n of createdNames) rmSync(join(STATE_DIR, "agents", "opencode", n), { recursive: true, force: true })
})

function deps(registry: unknown) {
  return {
    registry,
    bind: async () => {},
    tmuxSession: "mux",
    registerAdapter: () => {},
    onOpenCodeSessionId: () => {},
    opencodeHost: fakeOc.host,
  }
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
  const boundIds: string[] = []

  const result = await spawnSession({
    ...deps(registry),
    bind: async (id: string) => { boundIds.push(id) },
  } as never, { workdir: tmpDir, requestedName: "oc-id", agent: "opencode" })
  createdNames.push(result.name)

  expect(registry.resolveName(result.name)?.id).toBe(result.session_id)
  expect(registry.resolveName(result.name)?.agent).toBe("opencode")
  expect(boundIds).toEqual([result.session_id])

  const cfgPath = join(STATE_DIR, "agents", "opencode", result.name, "config", "opencode", "opencode.json")
  const cfg = JSON.parse(readFileSync(cfgPath, "utf8"))
  expect(cfg.mcp["mux-shim"].environment.MUX_SESSION_ID).toBe(result.session_id)
})

test("opencode spawn succeeds without auth — free tier, not fail-closed", async () => {
  const { spawnSession } = await import("../src/core/session-manager/spawn-helper")
  const registry = await freshRegistry()

  const result = await spawnSession(deps(registry) as never, { workdir: tmpDir, requestedName: "oc-noauth", agent: "opencode" })
  createdNames.push(result.name)
  expect(registry.resolveName(result.name)?.agent).toBe("opencode")
})
