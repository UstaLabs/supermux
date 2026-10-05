// resumeAtBoot gives each session at most bootResumeTimeoutMs: a resume that never settles must
// not keep the rest of boot (and so the web port) waiting. Live 2026-10-05: a Codex resume whose
// keeper was lost in setup never settled and the broker stopped after 7 of 24 sessions.
import { afterAll, afterEach, beforeEach, expect, mock, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { fakeClaudeHost } from "./helpers/fake-claude-host"

const realProvider = { ...(await import("../src/core/agents/claude/core-host-provider")) }
const hangs = (ctx: { resumeId?: string }) => ctx.resumeId === "native-stuck"
let fake = fakeClaudeHost("claude-native-1", { openHangs: hangs })
mock.module("../src/core/agents/claude/core-host-provider", () => ({
  ...realProvider,
  getClaudeCoreHost: () => fake.host,
}))
afterAll(() => { mock.module("../src/core/agents/claude/core-host-provider", () => realProvider) })

const { openDb, runMigrations } = await import("../src/core/storage/db")
const { Registry } = await import("../src/core/session-manager/registry")
const { SessionManager, bootResumeTimeoutMs, BOOT_RESUME_TIMEOUT_MS } = await import("../src/core/session-manager/manager")
const { ReviewStore } = await import("../src/core/review/store")
const { WalkthroughStore } = await import("../src/core/walkthrough/store")

let tmpDir: string
beforeEach(() => { tmpDir = mkdtempSync(join(tmpdir(), "boot-resume-")); fake = fakeClaudeHost("claude-native-1", { openHangs: hangs }) })
afterEach(async () => { await fake.close(); rmSync(tmpDir, { recursive: true, force: true }) })

function build() {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const registry = new Registry(db)
  const ports = {
    getWebChannel: () => undefined,
    backend: { runtimeTargetIdOf: async () => null, kill: async () => {} },
    teardown: { terminals: { killAllForSession: async () => {} }, fsWatcher: { killSession: () => {} } },
    displays: { killAllForSession: async () => {}, start: async () => { throw new Error("unused") }, get: () => undefined, stop: async () => {} },
    agentState: { applyEvent: () => {}, clear: () => {}, get: () => ({ phase: "idle", since: 0 }) },
    bgTasks: { clear: () => {} },
    commands: { remove: () => {}, refresh: async () => {} },
    config: { lookupModels: () => [] },
    register: { interruptClaudePane: async () => {}, notifyAgentError: async () => {}, ensureClaudeTailer: () => {}, maybeAutoSendSoulSetup: async () => {} },
    outbound: { onAssistantMessage: async () => ({ ok: true as const, delivered: 1 }), getChannel: () => undefined, telegramApi: undefined },
    orchestration: { spawnSession: async () => { throw new Error("unused") }, refreshTelegramMenu: async () => {}, wsDto: () => undefined, exposedProxyLinksBaseUrl: () => undefined, proxyWsPayload: () => ({}), proxyLiveness: { getStatus: () => "unknown", refresh: async () => {} }, postBrokerInbound: () => {} },
    stores: { fileStore: {}, messageLog: { get: () => [], update: () => false, addReaction: () => false, findByChannelMessageId: () => undefined }, searchStore: { searchKnowledge: () => [], searchSessions: () => [] }, db, reviewStore: new ReviewStore(db), walkthroughStore: new WalkthroughStore(db) },
    resume: { bind: async () => {}, ensureSessionWorktree: async () => {}, sessionEffort: () => undefined, resolveAttachment: async () => { throw new Error("unused") }, wireAdapterEvents: () => {}, sessionBackend: { list: async () => [], create: async () => { throw new Error("unused") } }, tmuxSession: "mux-test" },
  }
  return { registry, manager: new SessionManager(registry, ports as never) }
}

test("a session whose resume never settles does not block the sessions after it", async () => {
  const { registry, manager } = build()
  manager.bootResumeTimeoutMs = 300
  const workdir = mkdtempSync(join(tmpDir, "wd-"))
  registry.register({ id: "stuck-1", name: "stuck", workdir, pid: 0, agent: "claude", core: true, agent_session_id: "native-stuck" } as never)
  registry.register({ id: "after-1", name: "after", workdir, pid: 0, agent: "claude", core: true, agent_session_id: "native-after" } as never)
  const boot = manager.resumeAtBoot().then(() => "done")
  expect(await Promise.race([boot, Bun.sleep(5000).then(() => "blocked")])).toBe("done")
  expect(fake.opens.map((ctx) => ctx.resumeId)).toEqual(["native-stuck", "native-after"])
  expect(manager.adapterFor("after-1")).toBeDefined()
  expect(manager.adapterFor("stuck-1")).toBeUndefined()
})

test("the cap: MUX_BOOT_RESUME_TIMEOUT_MS when a positive integer, else the default", () => {
  expect(bootResumeTimeoutMs(undefined)).toBe(BOOT_RESUME_TIMEOUT_MS)
  expect(bootResumeTimeoutMs("abc")).toBe(BOOT_RESUME_TIMEOUT_MS)
  expect(bootResumeTimeoutMs("0")).toBe(BOOT_RESUME_TIMEOUT_MS)
  expect(bootResumeTimeoutMs("1500")).toBe(1500)
})
