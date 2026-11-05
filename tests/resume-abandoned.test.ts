// Card #75: a session's agent may still be starting when it is archived or killed. Its arm used
// to finish anyway and register a live agent on the archived session (a leaked process, an
// invisible session). It must stop what started instead.
import { afterAll, afterEach, beforeEach, expect, mock, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { fakeClaudeHost } from "./helpers/fake-claude-host"

const realProvider = { ...(await import("../src/core/agents/claude/core-host-provider")) }
let release: () => void = () => {}
let fake = fakeClaudeHost("claude-new")
mock.module("../src/core/agents/claude/core-host-provider", () => ({
  ...realProvider,
  getClaudeCoreHost: () => fake.host,
}))
afterAll(() => { mock.module("../src/core/agents/claude/core-host-provider", () => realProvider) })

const { openDb, runMigrations } = await import("../src/core/storage/db")
const { Registry } = await import("../src/core/session-manager/registry")
const { SessionManager } = await import("../src/core/session-manager/manager")
const { fakePorts } = await import("./helpers/session-manager-ports")

let tmpDir: string
beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "resume-abandoned-"))
  const gate = new Promise<void>((resolve) => { release = resolve })
  fake = fakeClaudeHost("claude-new", { openWaits: () => gate })
})
afterEach(async () => { release(); await fake.close(); rmSync(tmpDir, { recursive: true, force: true }) })

function build() {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const registry = new Registry(db)
  return { registry, manager: new SessionManager(registry, fakePorts(db)) }
}

async function opened(n: number): Promise<void> {
  for (let i = 0; i < 200 && fake.opens.length < n; i++) await Bun.sleep(10)
  expect(fake.opens).toHaveLength(n)
}

function asleep(registry: InstanceType<typeof Registry>, id: string) {
  const workdir = mkdtempSync(join(tmpDir, "wd-"))
  registry.register({ id, name: id, workdir, pid: 0, agent: "claude", core: true, agent_home: join(tmpDir, "home-" + id) } as never)
  registry.sessions.suspend(id)
  return registry.sessions.getById(id)!
}

test("archived while its agent is starting: the agent is stopped, never registered", async () => {
  const { registry, manager } = build()
  const row = asleep(registry, "cl-arch")
  const resuming = manager.resumeSuspended(row as never)
  await opened(1)
  registry.unregister("cl-arch")
  release()
  expect(await resuming).toBe(false)
  expect(manager.adapterFor("cl-arch")).toBeUndefined()
  expect(fake.closes).toBe(1)
  expect(registry.sessions.getById("cl-arch")?.status).toBe("archived")
})

test("deleted while its agent is starting: the same", async () => {
  const { registry, manager } = build()
  const row = asleep(registry, "cl-del")
  const resuming = manager.resumeSuspended(row as never)
  await opened(1)
  registry.sessions.deleteById("cl-del")
  release()
  expect(await resuming).toBe(false)
  expect(manager.adapterFor("cl-del")).toBeUndefined()
  expect(fake.closes).toBe(1)
})
