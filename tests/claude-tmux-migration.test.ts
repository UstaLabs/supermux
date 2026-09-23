import { afterAll, afterEach, beforeEach, expect, mock, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { fakeClaudeHost } from "./helpers/fake-claude-host"

// The manager resolves the Claude host through the process provider; swap it
// for a real library host over a fake driver (process-global, restored below).
const realProvider = { ...(await import("../src/core/agents/claude/core-host-provider")) }
let fake = fakeClaudeHost("claude-native-1")
mock.module("../src/core/agents/claude/core-host-provider", () => ({
  ...realProvider,
  getClaudeCoreHost: () => fake.host,
}))
afterAll(() => { mock.module("../src/core/agents/claude/core-host-provider", () => realProvider) })

const { openDb, runMigrations } = await import("../src/core/storage/db")
const { Registry } = await import("../src/core/session-manager/registry")
const { SessionManager } = await import("../src/core/session-manager/manager")
const { ReviewStore } = await import("../src/core/review/store")
const { WalkthroughStore } = await import("../src/core/walkthrough/store")

let tmpDir: string
beforeEach(() => { tmpDir = mkdtempSync(join(tmpdir(), "claude-migration-")); fake = fakeClaudeHost("claude-native-1") })
afterEach(async () => { await fake.close(); rmSync(tmpDir, { recursive: true, force: true }) })

function build(killed: string[]) {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  const registry = new Registry(db)
  const ports = {
    getWebChannel: () => undefined,
    backend: {
      runtimeTargetIdOf: async (s: { tmux_window_id?: string }) => s.tmux_window_id ?? null,
      kill: async (id: string) => { killed.push(id) },
    },
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

test("a tmux-era Claude row is resumed through Core with its old window killed first", async () => {
  const killed: string[] = []
  const { registry, manager } = build(killed)
  const workdir = mkdtempSync(join(tmpDir, "wd-"))
  registry.register({ id: "old-1", name: "old-claude", workdir, pid: 4242, agent: "claude", tmux_target: "mux:old-claude", tmux_window_id: "@7", agent_session_id: "claude-native-1" } as never)
  await manager.resumeAtBoot()
  expect(killed).toEqual(["@7"])
  expect(registry.get("old-1")?.core).toBe(true)
  expect(fake.opens[0]?.resumeId).toBe("claude-native-1")
})

test("a Core Claude row has no window to retire", async () => {
  const killed: string[] = []
  const { registry, manager } = build(killed)
  const workdir = mkdtempSync(join(tmpDir, "wd-"))
  registry.register({ id: "core-1", name: "core-claude", workdir, pid: 0, agent: "claude", core: true, agent_session_id: "claude-native-1" } as never)
  await manager.resumeAtBoot()
  expect(killed).toEqual([])
  expect(fake.opens).toHaveLength(1)
})
