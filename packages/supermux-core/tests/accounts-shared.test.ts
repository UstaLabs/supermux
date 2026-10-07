import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { existsSync } from "node:fs"
import { mkdir, mkdtemp, readFile, rm, utimes, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, createHost, memoryVault } from "../src/index.js"
import type { Host, HostRegistration } from "../src/index.js"
import { AccountRegistry, UsageStore } from "../src/accounts/index.js"
import { prepareCodexEnvironment, prepareCursorEnvironment } from "../src/environment/index.js"
import type { AgentDriver, AgentRuntime, CoreEvent, DriverContext } from "../src/types.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

setDefaultTimeout(15_000)
const dirs: string[] = []
const closers: Array<() => Promise<void>> = []
afterEach(async () => {
  await Promise.all(closers.splice(0).map(close => close().catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
async function scratch() { const dir = await mkdtemp(join(tmpdir(), "accounts-shared-")); dirs.push(dir); return dir }

function fakeDriver(id: string) {
  const contexts: DriverContext[] = []
  let seq = 0
  const driver: AgentDriver = {
    id,
    async open(context) {
      contexts.push(context)
      const runtime: AgentRuntime = {
        agentSessionId: context.resumeId ?? `native-${++seq}`,
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt(content) {
          const text = content[0]?.type === "text" ? content[0].text : ""
          if (text.startsWith("usage:")) context.onUpdate({ protocol: "native", value: { rateLimits: JSON.parse(text.slice(6)) } })
          return { stopReason: "end_turn" }
        },
        interrupt: async () => {},
        close: async () => {},
        normalize: update => {
          const value = update.value as { rateLimits?: unknown }
          return value && typeof value === "object" && "rateLimits" in value ? [{ kind: "usage", rateLimits: value.rateLimits }] : []
        },
      }
      return runtime
    },
  }
  return { driver, contexts }
}

async function sharedAccounts(base: string) {
  const registry = new AccountRegistry(join(base, "broker"), ["claude", "codex"], {
    vault: memoryVault(), homes: { claudeRoot: join(base, "claude-root"), codexRoot: join(base, "codex-root") },
  })
  const usage = new UsageStore(join(registry.directory, "usage.json"))
  return { registry, usage }
}

const text = (t: string) => [{ type: "text" as const, text: t }]
const full = () => JSON.stringify({ status: "rejected", rateLimitType: "five_hour", resetsAt: Math.floor(Date.now() / 1000) + 3600 })

function waitFor<T>(events: CoreEvent[], pick: (e: CoreEvent) => T | undefined, ms = 5000): Promise<T> {
  return new Promise((resolve, reject) => {
    const started = Date.now()
    const tick = () => {
      for (const e of events) { const hit = pick(e); if (hit !== undefined) return resolve(hit) }
      if (Date.now() - started > ms) return reject(new Error("timed out waiting for event"))
      setTimeout(tick, 5)
    }
    tick()
  })
}

test("one registry shared by two Cores: one source of truth, accounts stay typed per agent", async () => {
  const base = await scratch()
  const shared = await sharedAccounts(base)
  const claude = createCore({ stateDirectory: join(base, "core-claude"), agents: [fakeDriver("claude").driver], limits: TEST_LIMITS, accounts: shared })
  const codexFake = fakeDriver("codex")
  const codex = createCore({ stateDirectory: join(base, "core-codex"), agents: [codexFake.driver], limits: TEST_LIMITS, accounts: shared })
  closers.push(() => claude.close({ agents: "shutdown" }), () => codex.close({ agents: "shutdown" }))
  await claude.accounts.add({ id: "c-key", agent: "claude", method: "api_key", secret: "sk-a" })
  await codex.accounts.add({ id: "x-key", agent: "codex", method: "api_key", secret: "sk-x" })
  expect((await claude.accounts.list("claude")).map(a => a.id)).toEqual(["claude:system", "c-key"])
  await expect(codex.accounts.list("claude")).rejects.toMatchObject({ code: "unknown_agent" })
  expect((await claude.accounts.list()).map(a => a.id)).toEqual(["claude:system", "codex:system", "c-key", "x-key"])
  // Metadata lives under the shared directory, not under either Core's state directory.
  expect(existsSync(join(base, "broker", "accounts", "accounts.json"))).toBe(true)
  expect(existsSync(join(base, "core-claude", "accounts", "accounts.json"))).toBe(false)
  const cwd = await scratch()
  await expect(codex.sessions.create({ id: nextId(), agent: "codex", cwd, account: "c-key" })).rejects.toMatchObject({ code: "invalid_input" })
  await codex.sessions.create({ id: nextId(), agent: "codex", cwd, account: "x-key" })
  expect(codexFake.contexts[0]!.profile?.env).toEqual({ CODEX_API_KEY: "sk-x", OPENAI_API_KEY: "sk-x" })
  // A removal through one Core is seen by the other.
  await claude.accounts.remove("x-key")
  expect(await codex.accounts.get("x-key")).toBeUndefined()
})

test("a shared registry must know every agent of the Core", async () => {
  const base = await scratch()
  const shared = await sharedAccounts(base)
  expect(() => createCore({ stateDirectory: join(base, "core"), agents: [fakeDriver("grok").driver], limits: TEST_LIMITS, accounts: shared }))
    .toThrow(/does not know agent/)
})

test("autoSwitch as a function is read when a limit is seen", async () => {
  const base = await scratch()
  let enabled = false
  const core = createCore({
    stateDirectory: join(base, "core"), agents: [fakeDriver("claude").driver], limits: TEST_LIMITS,
    accounts: { ...(await sharedAccounts(base)), autoSwitch: () => enabled },
  })
  closers.push(() => core.close({ agents: "shutdown" }))
  const events: CoreEvent[] = []
  core.subscribe(e => { events.push(e) })
  await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
  const cwd = await scratch()
  const off = await core.sessions.create({ id: nextId(), agent: "claude", cwd })
  await (await off.send({ content: text(`usage:${full()}`), whenBusy: "queue" })).completed
  await new Promise(resolve => setTimeout(resolve, 30))
  expect(events.some(e => e.type === "account.switched")).toBe(false)
  enabled = true
  const id = nextId()
  const on = await core.sessions.create({ id, agent: "claude", cwd, account: "claude:system" })
  await (await on.send({ content: text(`usage:${full()}`), whenBusy: "queue" })).completed
  expect(await waitFor(events, e => e.type === "account.switched" && e.sessionId === id ? e : undefined)).toMatchObject({ to: "b", reason: "limit" })
})

async function makeHost(base: string, extra: { prepare?: (r: HostRegistration) => Promise<void> } = {}) {
  const fake = fakeDriver("claude")
  const registrations: HostRegistration[] = []
  const host: Host = createHost({
    stateDirectory: join(base, "host"),
    limits: TEST_LIMITS,
    agent: "claude",
    driver: (registered) => { registrations.push(registered); return fake.driver },
    prepare: extra.prepare,
    accounts: await sharedAccounts(base),
  })
  closers.push(() => host.close({ agents: "shutdown" }))
  return { host, fake, registrations }
}

test("host: a registration's account creates the session on it; the system account keeps today's record", async () => {
  const base = await scratch()
  const { host, fake } = await makeHost(base)
  await host.core.accounts.add({ id: "tok", agent: "claude", method: "token", secret: "setup-token" })
  const cwd = await scratch()
  const plain = host.register({ id: "plain", env: {}, account: "claude:system" })
  await plain.start({ cwd })
  expect(fake.contexts[0]!.account).toBeUndefined()
  expect(fake.contexts[0]!.profile).toBeUndefined()
  expect((await host.core.sessions.get("plain"))!.account).toBeUndefined()
  const withAccount = host.register({ id: "acct", env: {}, account: "tok" })
  await withAccount.start({ cwd })
  expect(fake.contexts[1]!.account).toBe("tok")
  expect(fake.contexts[1]!.profile?.env).toEqual({ CLAUDE_CODE_OAUTH_TOKEN: "setup-token" })
  expect((await host.core.sessions.get("acct"))!.account).toBe("tok")
})

test("host: prepare sees the effective account; a re-registration with another account switches on a real reopen", async () => {
  const base = await scratch()
  const prepared: Array<string | undefined> = []
  const { host, fake } = await makeHost(base, { prepare: async (r) => { prepared.push(r.account) } })
  await host.core.accounts.add({ id: "tok", agent: "claude", method: "token", secret: "setup-token" })
  await host.core.accounts.add({ id: "key", agent: "claude", method: "api_key", secret: "sk-key" })
  const events: CoreEvent[] = []
  host.core.subscribe(e => { events.push(e) })
  const cwd = await scratch()
  const first = host.register({ id: "s", env: {}, account: "tok" })
  await first.start({ cwd })
  await first.stop({ mode: "shutdown" })
  // Resume without an account: the record's account is used (and prepare is told so).
  const again = host.register({ id: "s", env: {} })
  await again.start({ cwd })
  expect(fake.contexts.at(-1)!.account).toBe("tok")
  await again.stop({ mode: "shutdown" })
  const switched = host.register({ id: "s", env: {}, account: "key" })
  await switched.start({ cwd })
  expect(prepared).toEqual(["tok", "tok", "key"])
  expect(fake.contexts.at(-1)!.account).toBe("key")
  expect(fake.contexts.at(-1)!.resumeId).toBe("native-1")
  expect(events.filter(e => e.type === "account.switched")).toEqual([{ type: "account.switched", sessionId: "s", from: "tok", to: "key", reason: "manual" }])
})

test("host: a Core-internal limit switch keeps the ready handle on the new Session", async () => {
  const base = await scratch()
  const fake = fakeDriver("claude")
  const host = createHost({
    stateDirectory: join(base, "host"), limits: TEST_LIMITS, agent: "claude",
    driver: () => fake.driver,
    accounts: { ...(await sharedAccounts(base)), autoSwitch: true },
  })
  closers.push(() => host.close({ agents: "shutdown" }))
  await host.core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
  const events: CoreEvent[] = []
  host.core.subscribe(e => { events.push(e) })
  const handle = host.register({ id: "lim", env: {} })
  const before = await handle.start({ cwd: await scratch() })
  await (await before.send({ content: text(`usage:${full()}`), whenBusy: "queue" })).completed
  await waitFor(events, e => e.type === "account.switched" ? e : undefined)
  expect(handle.session).not.toBe(before)
  expect(handle.session!.snapshot().account).toBe("b")
  expect(handle.session!.snapshot().state).not.toBe("closed")
})

test("environment: account mode gives codex and cursor sessions no credential copy and heals a leftover back", async () => {
  const base = await scratch()
  const canonicalHome = join(base, "codex-canonical")
  await mkdir(canonicalHome, { recursive: true })
  await writeFile(join(canonicalHome, "auth.json"), JSON.stringify({ tokens: { access_token: "old" } }))
  const home = join(base, "codex-session")
  await mkdir(home, { recursive: true })
  // A leftover copy that the CLI refreshed (newer): it goes back to the canonical file, then away.
  await writeFile(join(home, "auth.json"), JSON.stringify({ tokens: { access_token: "refreshed" } }))
  const past = new Date(Date.now() - 60_000)
  await utimes(join(canonicalHome, "auth.json"), past, past)
  const codex = await prepareCodexEnvironment({
    home, workdir: base, mcpServers: [], skillsPaths: [], instructions: null,
    credentials: { apiKey: null, canonicalHome, account: true }, nativeMemory: false,
  })
  expect(codex.credentials).toBe("account")
  expect(codex.env).toEqual({ CODEX_HOME: home })
  expect(existsSync(join(home, "auth.json"))).toBe(false)
  expect(JSON.parse(await readFile(join(canonicalHome, "auth.json"), "utf8")).tokens.access_token).toBe("refreshed")

  const userConfigDir = join(base, "xdg")
  const userCursorDir = join(base, "dot-cursor")
  await mkdir(join(userConfigDir, "cursor"), { recursive: true })
  await mkdir(userCursorDir, { recursive: true })
  await writeFile(join(userConfigDir, "cursor", "auth.json"), JSON.stringify({ accessToken: "a" }))
  await writeFile(join(userCursorDir, "cli-config.json"), "{}")
  const cursorHome = join(base, "cursor-session")
  const cursor = await prepareCursorEnvironment({
    home: cursorHome, workdir: base, mcpServers: [], skillsPaths: [], instructions: null,
    credentials: { apiKey: null, userCursorDir, userConfigDir, account: true }, sharedRuntime: null, platform: "linux",
  })
  expect(cursor.credentials).toBe("account")
  expect(existsSync(join(cursorHome, ".config", "cursor", "auth.json"))).toBe(false)
  expect(existsSync(join(cursorHome, ".cursor", "cli-config.json"))).toBe(true)
  expect(cursor.env.CURSOR_API_KEY).toBeUndefined()
})
