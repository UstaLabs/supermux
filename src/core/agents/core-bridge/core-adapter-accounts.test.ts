import { afterEach, expect, test } from "bun:test"
import { existsSync, readFileSync } from "node:fs"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import type { AgentDriver, AgentRuntime, DriverContext, Host } from "../../../../packages/supermux-core/src/index.js"
import { AccountRegistry, UsageStore, memoryVault } from "../../../../packages/supermux-core/src/accounts/index.js"
import { CoreAdapter, CORE_ADAPTER_PROFILES } from "./core-adapter"
import { createClaudeCoreHost } from "../claude/core-host"
import { createGrokCoreHost } from "../grok/core-host"
import { createCodexCoreHost } from "../codex/core-host"
import { resetSharedAccountsForTests } from "../../accounts/broker-accounts"
import type { AgentKind } from "../types"

const dirs: string[] = []
const hosts: Host[] = []
const adapters: CoreAdapter[] = []

afterEach(async () => {
  await Promise.all(adapters.splice(0).map((a) => a.stop().catch(() => {})))
  await Promise.all(hosts.splice(0).map((h) => h.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map((d) => rm(d, { recursive: true, force: true })))
  resetSharedAccountsForTests()
})

async function tmp(prefix: string) {
  const dir = await mkdtemp(join(tmpdir(), prefix))
  dirs.push(dir)
  return dir
}

function fakeDriver(kind: AgentKind) {
  const opens: DriverContext[] = []
  let hold: (() => void) | undefined
  let holdNext = false
  const driver: AgentDriver = {
    id: kind,
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? `native-${opens.length}`,
        capabilities: { resume: true, steer: kind === "codex", fork: false, detach: false, configure: true, history: false, permissions: true },
        async prompt(_content, signal) {
          if (holdNext) {
            holdNext = false
            await new Promise<void>((resolve) => { hold = resolve; signal.addEventListener("abort", () => resolve(), { once: true }) })
          }
          return { stopReason: "end_turn" }
        },
        async interrupt() { hold?.() },
        async close() { hold?.() },
        async configure() {},
        configuration: () => ({}),
        async setPermissions() { return { applied: "now" as const } },
      }
      return runtime
    },
  }
  return { driver, opens, holdNextPrompt() { holdNext = true }, release() { hold?.() } }
}

async function setup(kind: AgentKind) {
  const base = await tmp(`acct-${kind}-`)
  const registry = new AccountRegistry(join(base, "broker"), ["claude", "codex", "cursor", "grok", "opencode"], {
    vault: memoryVault(), homes: { claudeRoot: join(base, "claude-root"), codexRoot: join(base, "codex-root"), grokRoot: join(base, "grok-root"), cursorRoot: join(base, "cursor-root") },
  })
  const usage = new UsageStore(join(registry.directory, "usage.json"))
  resetSharedAccountsForTests({ registry, usage })
  const fake = fakeDriver(kind)
  const workdir = await tmp(`acct-${kind}-wd-`)
  const opts = {
    stateDirectory: join(base, "core"),
    driverFactory: () => fake.driver,
    limits: { interruptTimeoutMs: 40, maxPending: 128, outstandingActivity: 256 },
    accounts: { registry, usage },
  }
  const host = kind === "claude" ? createClaudeCoreHost(opts) : kind === "codex" ? createCodexCoreHost(opts) : createGrokCoreHost(opts)
  hosts.push(host)
  return { host, fake, workdir, registry, base }
}

function makeAdapter(kind: AgentKind, host: Host, workdir: string, sessionHome: string, account?: string): CoreAdapter {
  const extra: Record<string, unknown> = { cwd: workdir, workdir, sessionHome, sessionName: "s1", sessionId: "sess-1" }
  const handle = host.register({ id: "sess-1", env: {}, extra, ...(account ? { account } : {}) })
  const adapter = new CoreAdapter(CORE_ADAPTER_PROFILES[kind], {
    handle,
    reregister: (fields) => host.register({ id: "sess-1", env: {}, extra: { ...extra, permissionMode: fields.permissionMode } }),
    core: host.core,
    id: "sess-1",
    sessionName: "s1",
    workdir,
    persistSessionId: async () => {},
  })
  adapters.push(adapter)
  return adapter
}

test("claude: a session created on an account launches with its credential; setAccount switches on a real reopen", async () => {
  const { host, fake, workdir, registry } = await setup("claude")
  await registry.add({ id: "tok", agent: "claude", method: "token", secret: "setup-token" })
  await registry.add({ id: "key", agent: "claude", method: "api_key", secret: "sk-key" })
  const adapter = makeAdapter("claude", host, workdir, await tmp("claude-home-"), "tok")
  const events: Array<Record<string, unknown>> = []
  adapter.on("account", (e) => events.push(e))
  await adapter.start()
  expect(fake.opens[0]!.account).toBe("tok")
  expect(fake.opens[0]!.profile?.env).toEqual({ CLAUDE_CODE_OAUTH_TOKEN: "setup-token" })
  await adapter.setAccount("key")
  expect(fake.opens).toHaveLength(2)
  expect(fake.opens[1]!.account).toBe("key")
  expect(fake.opens[1]!.resumeId).toBe("native-1")
  expect(fake.opens[1]!.profile?.unsetEnv).toEqual(["CLAUDE_CODE_OAUTH_TOKEN"])
  expect(events).toEqual([{ kind: "account", event: "switched", from: "tok", to: "key", reason: "manual" }])
  expect(adapter.isAlive()).toBe(true)
  // The adapter talks to the reopened session.
  await adapter.send("after switch")
  expect((await host.core.sessions.get("sess-1"))!.account).toBe("key")
})

test("claude: setAccount is refused mid-turn", async () => {
  const { host, fake, workdir, registry } = await setup("claude")
  await registry.add({ id: "key", agent: "claude", method: "api_key", secret: "sk-key" })
  const adapter = makeAdapter("claude", host, workdir, await tmp("claude-home-"))
  await adapter.start()
  fake.holdNextPrompt()
  const turn = adapter.send("long turn")
  for (let i = 0; i < 50 && adapter.sessionSnapshotState() !== "running"; i++) await new Promise((r) => setTimeout(r, 2))
  await expect(adapter.setAccount("key")).rejects.toMatchObject({ code: "session_busy" })
  fake.release()
  await turn
})

test("claude: a subscription account gets the mux-shim server through an extra --mcp-config", async () => {
  const { host, fake, workdir, registry } = await setup("claude")
  await registry.add({ id: "sub", agent: "claude", method: "subscription" })
  const sessionHome = await tmp("claude-home-")
  const adapter = makeAdapter("claude", host, workdir, sessionHome, "sub")
  await adapter.start()
  const ctx = fake.opens[0]!
  expect(ctx.profile?.env?.CLAUDE_CONFIG_DIR).toContain(join("homes", "claude", "sub"))
  const file = join(sessionHome, "mcp-account.json")
  expect(JSON.parse(readFileSync(file, "utf8")).mcpServers["mux-shim"].type).toBe("stdio")
})

test("grok: an api_key account does not read the user's login file", async () => {
  const { host, fake, workdir, registry } = await setup("grok")
  await registry.add({ id: "xai", agent: "grok", method: "api_key", secret: "xai-key" })
  const sessionHome = await tmp("grok-home-")
  const adapter = makeAdapter("grok", host, workdir, sessionHome, "xai")
  await adapter.start()
  expect(fake.opens[0]!.profile?.env).toEqual({ XAI_API_KEY: "xai-key" })
  expect(fake.opens[0]!.account).toBe("xai")
})

test("codex: a session on an api_key account gets no copy of the user's auth.json", async () => {
  const { host, fake, workdir, registry } = await setup("codex")
  await registry.add({ id: "okey", agent: "codex", method: "api_key", secret: "sk-codex" })
  const sessionHome = await tmp("codex-home-")
  const adapter = makeAdapter("codex", host, workdir, sessionHome, "okey")
  await adapter.start()
  expect(fake.opens[0]!.profile?.env).toEqual({ CODEX_API_KEY: "sk-codex", OPENAI_API_KEY: "sk-codex" })
  expect(existsSync(join(sessionHome, "auth.json"))).toBe(false)
  expect(existsSync(join(sessionHome, "config.toml"))).toBe(true)
})
