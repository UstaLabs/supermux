// Card #75: a new session is born asleep, so its first start is a resume of a row that never ran.
// Codex used to be gated on a thread id it can't have yet (resume_suspended_no_path).
import { afterAll, afterEach, beforeEach, expect, mock, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { fakeCodexHost } from "./helpers/fake-codex-host"

const realProvider = { ...(await import("../src/core/agents/codex/core-host-provider")) }
let fake = fakeCodexHost("codex-thread-new")
mock.module("../src/core/agents/codex/core-host-provider", () => ({
  ...realProvider,
  getCodexCoreHost: () => fake.host,
}))
afterAll(() => { mock.module("../src/core/agents/codex/core-host-provider", () => realProvider) })

const { openDb, runMigrations } = await import("../src/core/storage/db")
const { Registry } = await import("../src/core/session-manager/registry")
const { SessionManager } = await import("../src/core/session-manager/manager")
const { ReviewStore } = await import("../src/core/review/store")
const { WalkthroughStore } = await import("../src/core/walkthrough/store")

let tmpDir: string
beforeEach(() => { tmpDir = mkdtempSync(join(tmpdir(), "resume-never-started-")); fake = fakeCodexHost("codex-thread-new") })
afterEach(async () => { await fake.close(); rmSync(tmpDir, { recursive: true, force: true }) })

function build() {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const registry = new Registry(db)
  const ports = {
    getWebChannel: () => undefined,
    backend: { runtimeTargetIdOf: async () => null, kill: async () => {} },
    cleanup: { terminals: { killAllForSession: async () => {} }, stopClaudeTailer: () => {}, releaseDraftAttachments: () => {}, syncGitStatus: () => {}, forgetReplyTarget: () => {}, retireWorkspaceIfEmpty: () => {} },
    children: { archived: () => {} },
    displays: { killAllForSession: async () => {}, start: async () => { throw new Error("unused") }, get: () => undefined, stop: async () => {} },
    agentState: { applyEvent: () => {}, clear: () => {}, get: () => ({ phase: "idle", since: 0 }) },
    bgTasks: { clear: () => {} },
    commands: { remove: () => {}, refresh: async () => {} },
    config: { lookupModels: () => [] },
    register: { interruptClaudePane: async () => {}, notifyAgentError: async () => {}, ensureClaudeTailer: () => {} },
    outbound: { onAssistantMessage: async () => ({ ok: true as const, delivered: 1 }), getChannel: () => undefined, telegramApi: undefined },
    orchestration: { spawnSession: async () => { throw new Error("unused") }, refreshTelegramMenu: async () => {}, wsDto: () => undefined, exposedProxyLinksBaseUrl: () => undefined, proxyWsPayload: () => ({}), proxyLiveness: { getStatus: () => "unknown", refresh: async () => {} }, postBrokerInbound: () => {} },
    stores: { fileStore: {}, messageLog: { get: () => [], update: () => false, addReaction: () => false, findByChannelMessageId: () => undefined }, searchStore: { searchKnowledge: () => [], searchSessions: () => [] }, db, reviewStore: new ReviewStore(db), walkthroughStore: new WalkthroughStore(db) },
    resume: { bind: async () => {}, ensureSessionWorktree: async () => {}, sessionEffort: () => undefined, sessionFast: () => false, resolveAttachment: async () => { throw new Error("unused") }, wireAdapterEvents: () => {}, sessionBackend: { list: async () => [], create: async () => { throw new Error("unused") } }, tmuxSession: "mux-test" },
  }
  return { registry, manager: new SessionManager(registry, ports as never) }
}

function bornAsleep(registry: InstanceType<typeof Registry>, id: string) {
  const workdir = mkdtempSync(join(tmpDir, "wd-"))
  const row = registry.register({ id, name: id, workdir, pid: 0, agent: "codex", core: true, agent_home: join(tmpDir, "home-" + id) } as never)
  registry.sessions.suspend(row.id)
  return registry.sessions.getById(row.id)!
}

test("a dozing Codex session with no thread yet starts fresh", async () => {
  const { registry, manager } = build()
  const row = bornAsleep(registry, "cx-new")
  expect(await manager.resumeSuspended(row as never)).toBe(true)
  expect(manager.adapterFor("cx-new")).toBeDefined()
  expect(fake.opens.map((o) => o.resumeId)).toEqual([undefined])
  expect(registry.get("cx-new")?.status).toBe("active")
})

test("restoring an archived Codex session that never ran starts it fresh", async () => {
  const { registry, manager } = build()
  bornAsleep(registry, "cx-arch")
  registry.unregister("cx-arch")
  expect((await manager.resumeFromArchive("cx-arch")).ok).toBe(true)
  expect(manager.adapterFor("cx-arch")).toBeDefined()
  expect(fake.opens.map((o) => o.resumeId)).toEqual([undefined])
})

test("boot starts an active Codex session that has no thread yet instead of skipping it", async () => {
  const { registry, manager } = build()
  const workdir = mkdtempSync(join(tmpDir, "wd-"))
  registry.register({ id: "cx-boot", name: "cx-boot", workdir, pid: 0, agent: "codex", core: true, agent_home: join(tmpDir, "home-boot") } as never)
  await manager.resumeAtBoot()
  expect(manager.adapterFor("cx-boot")).toBeDefined()
  expect(fake.opens.map((o) => o.resumeId)).toEqual([undefined])
})

test("the session's account reaches the agent's registration on a resume (it was dropped before)", async () => {
  const { registry, manager } = build()
  const accounts: unknown[] = []
  const register = fake.host.register.bind(fake.host)
  fake.host.register = ((r: Parameters<typeof register>[0]) => {
    accounts.push(r.account)
    const { account: _skip, ...rest } = r
    return register(rest)
  }) as typeof fake.host.register
  const workdir = mkdtempSync(join(tmpDir, "wd-"))
  const row = registry.register({ id: "cx-acc", name: "cx-acc", workdir, pid: 0, agent: "codex", core: true, agent_home: join(tmpDir, "home-acc"), account: "codex-team" } as never)
  registry.sessions.suspend(row.id)
  expect(await manager.resumeSuspended(registry.sessions.getById("cx-acc") as never)).toBe(true)
  expect(accounts[0]).toBe("codex-team")
})
