// Ahmet, 2026-11-05: "on boot we should only resume the agents, that's it." After that day's reboot
// the broker brought back all 132 running sessions (about 230 MB each) and the box sat at 23 of 27 GB.
// Boot now resumes Agents; a worker is left asleep (suspended) and wakes on its next message, the way a
// suspended session always has, unless leaving it would lose something that runs without a message.
import { afterAll, afterEach, beforeEach, expect, mock, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { fakeClaudeHost } from "./helpers/fake-claude-host"

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
let workdir: string
beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "boot-agents-"))
  workdir = mkdtempSync(join(tmpDir, "wd-"))
  fake = fakeClaudeHost("claude-native-1")
})
afterEach(async () => { await fake.close(); rmSync(tmpDir, { recursive: true, force: true }) })

/** `running`: sessions whose agent still runs under its keeper. `scheduled`: sessions whose CLI keeps scheduled tasks.
 *  `probeThrows`: the keeper probe fails (an unreadable keeper directory). */
function build(opts: { running?: string[]; scheduled?: string[]; probeThrows?: boolean } = {}) {
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
    register: { interruptClaudePane: async () => {}, notifyAgentError: async () => {}, ensureClaudeTailer: () => {} },
    outbound: { onAssistantMessage: async () => ({ ok: true as const, delivered: 1 }), getChannel: () => undefined, telegramApi: undefined },
    orchestration: { spawnSession: async () => { throw new Error("unused") }, refreshTelegramMenu: async () => {}, wsDto: () => undefined, exposedProxyLinksBaseUrl: () => undefined, proxyWsPayload: () => ({}), proxyLiveness: { getStatus: () => "unknown", refresh: async () => {} }, postBrokerInbound: () => {} },
    stores: { fileStore: {}, messageLog: { get: () => [], update: () => false, addReaction: () => false, findByChannelMessageId: () => undefined }, searchStore: { searchKnowledge: () => [], searchSessions: () => [] }, db, reviewStore: new ReviewStore(db), walkthroughStore: new WalkthroughStore(db) },
    resume: {
      bind: async () => {}, ensureSessionWorktree: async () => {}, sessionEffort: () => undefined, sessionFast: () => false,
      resolveAttachment: async () => { throw new Error("unused") }, wireAdapterEvents: () => {},
      sessionBackend: { list: async () => [], create: async () => { throw new Error("unused") } }, tmuxSession: "mux-test",
      agentRunning: async (s: { id: string }) => {
        if (opts.probeThrows) throw new Error("keepers dir is not a real directory")
        return (opts.running ?? []).includes(s.id)
      },
      hasScheduledTasks: (s: { id: string }) => (opts.scheduled ?? []).includes(s.id),
    },
  }
  return { registry, manager: new SessionManager(registry, ports as never) }
}

function agent(registry: InstanceType<typeof Registry>, id: string, nativeId: string) {
  registry.register({ id, name: id, workdir, pid: 0, agent: "claude", core: true, agent_session_id: nativeId, role: "personal_assistant" } as never)
}
function worker(registry: InstanceType<typeof Registry>, id: string, nativeId: string) {
  registry.register({ id, name: id, workdir, pid: 0, agent: "claude", core: true, agent_session_id: nativeId } as never)
}

test("boot resumes the Agents and leaves a worker asleep", async () => {
  const { registry, manager } = build()
  agent(registry, "agent-1", "native-agent")
  worker(registry, "worker-1", "native-worker")
  await manager.resumeAtBoot()
  expect(fake.opens.map((ctx) => ctx.resumeId)).toEqual(["native-agent"])
  expect(manager.adapterFor("agent-1")).toBeDefined()
  expect(registry.get("agent-1")?.status).toBe("active")
  expect(manager.adapterFor("worker-1")).toBeUndefined()
  // Asleep, not crashed: a suspended row is what every door (web, Telegram, inbox) wakes before it delivers.
  expect(registry.get("worker-1")?.status).toBe("suspended")
})

test("a worker left asleep at boot wakes on its next message", async () => {
  const { registry, manager } = build()
  worker(registry, "worker-1", "native-worker")
  await manager.resumeAtBoot()
  expect(fake.opens).toHaveLength(0)
  // The lazy door every inbound path takes for a suspended row (main.ts web/Telegram, inbox ensureLive).
  expect(await manager.resumeSuspended(registry.get("worker-1")!)).toBe(true)
  expect(fake.opens.map((ctx) => ctx.resumeId)).toEqual(["native-worker"])
  expect(manager.adapterFor("worker-1")).toBeDefined()
  expect(registry.get("worker-1")?.status).toBe("active")
})

// A crash or an OOM kill: the worker's keeper outlived the broker and kept its agent running, with its crons,
// /loop and background tasks. Re-attaching starts no process; leaving it would strand a running agent nobody
// listens to. (A graceful restart stops every agent, so this is the only way one is still running at boot.)
test("a worker whose agent still runs is re-attached at boot", async () => {
  const { registry, manager } = build({ running: ["worker-live"] })
  worker(registry, "worker-live", "native-live")
  worker(registry, "worker-dead", "native-dead")
  await manager.resumeAtBoot()
  expect(fake.opens.map((ctx) => ctx.resumeId)).toEqual(["native-live"])
  expect(registry.get("worker-live")?.status).toBe("active")
  expect(registry.get("worker-dead")?.status).toBe("suspended")
})

// Grok keeps durable scheduled tasks on disk and runs them only while its session is open: left asleep,
// they would stop until someone happened to write to the worker.
test("a worker whose CLI keeps scheduled tasks is resumed at boot", async () => {
  const { registry, manager } = build({ scheduled: ["worker-cron"] })
  worker(registry, "worker-cron", "native-cron")
  await manager.resumeAtBoot()
  expect(fake.opens.map((ctx) => ctx.resumeId)).toEqual(["native-cron"])
  expect(registry.get("worker-cron")?.status).toBe("active")
})

// Unknown is not asleep: a probe that throws (an unreadable keeper directory) resumes the worker, as boot always did.
test("a worker whose probe fails is resumed at boot", async () => {
  const { registry, manager } = build({ probeThrows: true })
  worker(registry, "worker-1", "native-worker")
  await manager.resumeAtBoot()
  expect(fake.opens.map((ctx) => ctx.resumeId)).toEqual(["native-worker"])
  expect(registry.get("worker-1")?.status).toBe("active")
})
