import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, memoryVault } from "../src/index.js"
import type { AgentDriver, AgentRuntime, CoreEvent, DriverContext } from "../src/types.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

setDefaultTimeout(15_000)
const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
async function scratch() { const dir = await mkdtemp(join(tmpdir(), "accounts-core-")); dirs.push(dir); return dir }

type Fake = { driver: AgentDriver; contexts: DriverContext[] }
/** A driver whose prompt emits `usage` (the text of the prompt names the rate-limit payload). */
function fake(id: string): Fake {
  const contexts: DriverContext[] = []
  let seq = 0
  return {
    contexts,
    driver: {
      id,
      async open(context) {
        contexts.push(context)
        const runtime: AgentRuntime = {
          agentSessionId: context.resumeId ?? `native-${++seq}`,
          capabilities: { resume: true, steer: false, fork: true, detach: false },
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
    },
  }
}

async function setup(options: { agents?: Fake[]; autoSwitch?: boolean; profiles?: Record<string, { agent: string; env?: Record<string, string> }> } = {}) {
  const base = await scratch()
  const stateDirectory = join(base, "state")
  const claudeRoot = join(base, "claude-root")
  const codexRoot = join(base, "codex-root")
  const agents = options.agents ?? [fake("claude"), fake("codex")]
  const vault = memoryVault()
  const core = createCore({
    stateDirectory, agents: agents.map(a => a.driver), limits: TEST_LIMITS,
    accounts: { vault, homes: { claudeRoot, codexRoot }, ...(options.autoSwitch !== undefined ? { autoSwitch: options.autoSwitch } : {}) },
    ...(options.profiles ? { profiles: options.profiles } : {}),
  })
  cores.push(core)
  const events: CoreEvent[] = []
  core.subscribe(event => { events.push(event) })
  return { core, base, stateDirectory, claudeRoot, codexRoot, agents, vault, events }
}

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
const text = (t: string) => [{ type: "text" as const, text: t }]
const future = () => Math.floor(Date.now() / 1000) + 3600

test("system accounts per agent with lazily read identity; registry persists metadata, never secrets", async () => {
  const { core, claudeRoot, stateDirectory, vault } = await setup()
  await mkdir(claudeRoot, { recursive: true })
  await writeFile(join(claudeRoot, ".claude.json"), JSON.stringify({ oauthAccount: { emailAddress: "me@x.io", organizationUuid: "org-1", accountUuid: "u-1" } }))
  expect((await core.accounts.list()).map(a => a.id)).toEqual(["claude:system", "codex:system"])
  expect(await core.accounts.system("claude")).toMatchObject({ id: "claude:system", method: "system", identity: { email: "me@x.io", org: "org-1", accountId: "u-1" } })
  await expect(core.accounts.system("nope")).rejects.toMatchObject({ code: "unknown_agent" })
  const added = await core.accounts.add({ id: "work", agent: "claude", method: "token", secret: "sk-ant-oat-secret", label: "Work" })
  expect(added).toMatchObject({ id: "work", agent: "claude", method: "token", label: "Work" })
  expect(await vault.get("work")).toBe("sk-ant-oat-secret")
  const file = await readFile(join(stateDirectory, "accounts", "accounts.json"), "utf8")
  expect(file).not.toContain("sk-ant-oat-secret")
  expect((await stat(join(stateDirectory, "accounts", "accounts.json"))).mode & 0o777).toBe(0o600)
  expect((await core.accounts.list("claude")).map(a => a.id)).toEqual(["claude:system", "work"])
  await expect(core.accounts.add({ id: "work", agent: "claude", method: "token", secret: "x" })).rejects.toMatchObject({ code: "account_exists" })
  await core.accounts.remove("work")
  expect(await vault.get("work")).toBeUndefined()
  expect(await core.accounts.get("work")).toBeUndefined()
  await expect(core.accounts.remove("claude:system")).rejects.toMatchObject({ code: "invalid_input" })
})

test("add validation: methods per agent, secrets, unknown agent", async () => {
  const { core } = await setup({ agents: [fake("claude"), fake("cursor"), fake("opencode"), fake("custom")] })
  await expect(core.accounts.add({ agent: "nope", method: "api_key", secret: "k" })).rejects.toMatchObject({ code: "unknown_agent" })
  await expect(core.accounts.add({ agent: "claude", method: "system" as never })).rejects.toMatchObject({ code: "invalid_input" })
  await expect(core.accounts.add({ agent: "claude", method: "api_key" })).rejects.toMatchObject({ code: "invalid_input" })
  await expect(core.accounts.add({ agent: "claude", method: "subscription", secret: "k" })).rejects.toMatchObject({ code: "invalid_input" })
  await expect(core.accounts.add({ agent: "cursor", method: "token", secret: "k" })).rejects.toMatchObject({ code: "unsupported_operation" })
  await expect(core.accounts.add({ agent: "custom", method: "api_key", secret: "k" })).rejects.toMatchObject({ code: "unsupported_operation" })
  await expect(core.accounts.add({ agent: "opencode", method: "api_key", secret: "k" })).rejects.toMatchObject({ code: "invalid_input" })
  await expect(core.accounts.add({ id: "../x", agent: "claude", method: "api_key", secret: "k" })).rejects.toMatchObject({ code: "invalid_account_id" })
  const generated = await core.accounts.add({ agent: "claude", method: "api_key", secret: "k" })
  expect(generated.id).toMatch(/^claude-[0-9a-f]{8}$/)
})

test("duplicate identity of a rotating login is rejected; non-rotating tokens may share it", async () => {
  const { core, codexRoot, base } = await setup()
  await mkdir(codexRoot, { recursive: true })
  await writeFile(join(codexRoot, "auth.json"), JSON.stringify({ tokens: { account_id: "chatgpt-1" } }))
  expect((await core.accounts.system("codex")).identity).toEqual({ accountId: "chatgpt-1" })
  // A refresh token would be a second copy of the system login → rejected.
  await expect(core.accounts.add({ agent: "codex", method: "token", secret: JSON.stringify({ access_token: "a", refresh_token: "r", account_id: "chatgpt-1" }) })).rejects.toMatchObject({ code: "account_exists" })
  await expect(core.accounts.add({ agent: "codex", method: "subscription", identity: { accountId: "chatgpt-1" } })).rejects.toMatchObject({ code: "account_exists" })
  // Access token only: nothing rotates, allowed.
  const access = await core.accounts.add({ id: "t1", agent: "codex", method: "token", secret: JSON.stringify({ access_token: "a", account_id: "chatgpt-1" }) })
  expect(access.identity).toEqual({ accountId: "chatgpt-1" })
  // Two subscription logins with one identity (email + org).
  await core.accounts.add({ id: "s1", agent: "claude", method: "subscription", identity: { email: "Me@x.io", org: "o" } })
  await expect(core.accounts.add({ id: "s2", agent: "claude", method: "subscription", identity: { email: "me@x.io", org: "o" } })).rejects.toMatchObject({ code: "account_exists" })
  await core.accounts.add({ id: "s3", agent: "claude", method: "subscription", identity: { email: "me@x.io", org: "other" } })
  expect(await core.accounts.home("s1")).toBe(join(base, "state", "accounts", "homes", "claude", "s1"))
})

test("create/resume with an account: env, unset and args reach the driver; record keeps the account id only", async () => {
  const { core, agents, stateDirectory, base } = await setup()
  const claude = agents[0]!
  const cwd = await scratch()
  await core.accounts.add({ id: "tok", agent: "claude", method: "token", secret: "sk-ant-oat-1" })
  await core.accounts.add({ id: "sub", agent: "claude", method: "subscription" })
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "claude", cwd, account: "tok" })
  expect(claude.contexts[0]!.account).toBe("tok")
  expect(claude.contexts[0]!.profile).toEqual({ agent: "claude", env: { CLAUDE_CODE_OAUTH_TOKEN: "sk-ant-oat-1" }, unsetEnv: ["ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"] })
  expect(session.snapshot().account).toBe("tok")
  const saved = await readFile(join(stateDirectory, "sessions", `${id}.json`), "utf8")
  expect(JSON.parse(saved).account).toBe("tok")
  expect(saved).not.toContain("sk-ant-oat-1")

  // Manual switch of a live session: shut down, reopen the same native id under the new account.
  const switched = await core.sessions.resume(id, { account: "sub" })
  expect(session.snapshot().state).toBe("closed")
  expect(switched.snapshot()).toMatchObject({ account: "sub", agentSessionId: "native-1" })
  const reopened = claude.contexts[1]!
  expect(reopened.resumeId).toBe("native-1")
  expect(reopened.profile).toEqual({ agent: "claude", env: { CLAUDE_CONFIG_DIR: join(base, "state", "accounts", "homes", "claude", "sub") }, unsetEnv: ["CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"] })
  expect((await core.sessions.get(id))!.account).toBe("sub")

  // Same account again: no reopen.
  expect(await core.sessions.resume(id, { account: "sub" })).toBe(switched)
  expect(claude.contexts.length).toBe(2)

  // Back to the system account (closed session → plain reopen).
  await switched.close({ mode: "shutdown" })
  const system = await core.sessions.resume(id, { account: "claude:system" })
  expect(claude.contexts[2]!.profile).toEqual({ agent: "claude", env: {} })
  expect(system.snapshot().account).toBe("claude:system")
})

test("account.switched (manual) is emitted; wrong agent and unknown accounts are refused before any reopen", async () => {
  const { core, agents, events } = await setup()
  const cwd = await scratch()
  await core.accounts.add({ id: "k", agent: "claude", method: "api_key", secret: "sk-1" })
  await core.accounts.add({ id: "cx", agent: "codex", method: "api_key", secret: "sk-2" })
  const id = nextId()
  await core.sessions.create({ id, agent: "claude", cwd })
  expect(agents[0]!.contexts[0]!.profile).toBeUndefined()
  expect(agents[0]!.contexts[0]!.account).toBeUndefined()
  await expect(core.sessions.resume(id, { account: "cx" })).rejects.toMatchObject({ code: "invalid_input" })
  await expect(core.sessions.resume(id, { account: "ghost" })).rejects.toMatchObject({ code: "unknown_account" })
  expect(agents[0]!.contexts.length).toBe(1)
  await core.sessions.resume(id, { account: "k" })
  expect(await waitFor(events, e => e.type === "account.switched" ? e : undefined)).toEqual({ type: "account.switched", sessionId: id, from: "claude:system", to: "k", reason: "manual" })
  await expect(core.sessions.create({ id: nextId(), agent: "claude", cwd, account: "cx" })).rejects.toMatchObject({ code: "invalid_input" })
  await expect(core.sessions.create({ id: nextId(), agent: "claude", cwd, account: "ghost" })).rejects.toMatchObject({ code: "unknown_account" })
})

test("profiles keep working; account and authProfile are mutually exclusive", async () => {
  const { core, agents } = await setup({ profiles: { legacy: { agent: "claude", env: { LEGACY: "1" } } } })
  const cwd = await scratch()
  await core.accounts.add({ id: "k", agent: "claude", method: "api_key", secret: "sk-1" })
  await expect(core.sessions.create({ id: nextId(), agent: "claude", cwd, account: "k", authProfile: "legacy" })).rejects.toMatchObject({ code: "invalid_input" })
  const id = nextId()
  await core.sessions.create({ id, agent: "claude", cwd, authProfile: "legacy" })
  expect(agents[0]!.contexts.at(-1)!.profile).toEqual({ agent: "claude", env: { LEGACY: "1" } })
  await expect(core.sessions.resume(id, { account: "k" })).rejects.toMatchObject({ code: "invalid_input" })
})

test("fork keeps the parent's account", async () => {
  const { core, agents } = await setup()
  const cwd = await scratch()
  await core.accounts.add({ id: "k", agent: "claude", method: "api_key", secret: "sk-1" })
  const parent = await core.sessions.create({ id: nextId(), agent: "claude", cwd, account: "k" })
  const child = await parent.fork({ id: nextId() })
  expect(child.snapshot().account).toBe("k")
  expect(agents[0]!.contexts.at(-1)!.profile?.env).toEqual({ ANTHROPIC_API_KEY: "sk-1" })
})

test("usage events feed core.accounts.usage and pick", async () => {
  const { core } = await setup()
  const cwd = await scratch()
  await core.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
  const session = await core.sessions.create({ id: nextId(), agent: "claude", cwd, account: "a" })
  const receipt = await session.send({ content: text(`usage:${JSON.stringify({ status: "allowed", unifiedWindows: { five_hour: { utilization: 0.9, resetsAt: future() } } })}`), whenBusy: "queue" })
  await receipt.completed
  await new Promise(resolve => setTimeout(resolve, 20))
  expect(core.accounts.usage("a")).toEqual([{ name: "five_hour", usedPercent: 90, resetsAt: expect.any(Date) }])
  // a has data (low score); b and system have none → a still ranks first (data before no-data).
  expect((await core.accounts.pick("claude"))?.id).toBe("a")
  expect((await core.accounts.pick("claude", ["a"]))?.id).toBe("claude:system")
})

test("auto-switch: a full window switches after the turn ends, reason limit", async () => {
  const { core, agents, events } = await setup({ autoSwitch: true })
  const cwd = await scratch()
  await core.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
  await core.accounts.add({ id: "iso", agent: "claude", method: "token", secret: "t-i", isolated: true })
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "claude", cwd, account: "a" })
  // No usage data for the others: the first candidate (system) wins; iso is never picked.
  const rejected = JSON.stringify({ status: "rejected", rateLimitType: "five_hour", resetsAt: future() })
  const receipt = await session.send({ content: text(`usage:${rejected}`), whenBusy: "queue" })
  expect((await receipt.completed).status).toBe("completed")
  const switched = await waitFor(events, e => e.type === "account.switched" ? e : undefined)
  expect(switched).toEqual({ type: "account.switched", sessionId: id, from: "a", to: "claude:system", reason: "limit" })
  const reopened = agents[0]!.contexts.at(-1)!
  expect(reopened.resumeId).toBe("native-1")
  expect(reopened.account).toBe("claude:system")
  expect((await core.sessions.get(id))!.account).toBe("claude:system")
})

test("auto-switch prefers the account with the best score; none left → account.exhausted", async () => {
  const { core, agents, events } = await setup({ autoSwitch: true })
  const cwd = await scratch()
  for (const id of ["a", "b"]) await core.accounts.add({ id, agent: "claude", method: "token", secret: `t-${id}` })
  const full = JSON.stringify({ status: "rejected", rateLimitType: "five_hour", resetsAt: future() })
  // Mark system as full via its own session first.
  const sys = await core.sessions.create({ id: nextId(), agent: "claude", cwd })
  await (await sys.send({ content: text(`usage:${full}`), whenBusy: "queue" })).completed
  await waitFor(events, e => e.type === "account.switched" && e.from === "claude:system" ? e : undefined)
  const sid = nextId()
  const s = await core.sessions.create({ id: sid, agent: "claude", cwd, account: "a" })
  await (await s.send({ content: text(`usage:${full}`), whenBusy: "queue" })).completed
  const moved = await waitFor(events, e => e.type === "account.switched" && e.sessionId === sid ? e : undefined)
  expect(moved.to).toBe("b")
  const live = await core.sessions.resume(sid)
  await (await live.send({ content: text(`usage:${full}`), whenBusy: "queue" })).completed
  expect(await waitFor(events, e => e.type === "account.exhausted" && e.sessionId === sid ? e : undefined)).toEqual({ type: "account.exhausted", sessionId: sid, agent: "claude", account: "b" })
  expect(agents[0]!.contexts.at(-1)!.account).toBe("b")
})

test("without autoSwitch a full window only records usage", async () => {
  const { core, events } = await setup()
  const cwd = await scratch()
  await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
  const s = await core.sessions.create({ id: nextId(), agent: "claude", cwd })
  await (await s.send({ content: text(`usage:${JSON.stringify({ status: "rejected", rateLimitType: "five_hour", resetsAt: future() })}`), whenBusy: "queue" })).completed
  await new Promise(resolve => setTimeout(resolve, 30))
  expect(core.accounts.usage("claude:system")?.[0]?.usedPercent).toBe(100)
  expect(events.some(e => e.type === "account.switched" || e.type === "account.exhausted")).toBe(false)
})

test("store validates SessionRecord.account", async () => {
  const { core, stateDirectory } = await setup()
  const cwd = await scratch()
  const id = nextId()
  await core.sessions.create({ id, agent: "claude", cwd })
  const path = join(stateDirectory, "sessions", `${id}.json`)
  const record = JSON.parse(await readFile(path, "utf8"))
  for (const bad of [{ account: "" }, { account: 3 }, { account: "x", authProfile: "y" }]) {
    await writeFile(path, JSON.stringify({ ...record, ...bad }))
    await expect(core.sessions.get(id)).rejects.toMatchObject({ code: "invalid_session_record" })
  }
  await writeFile(path, JSON.stringify({ ...record, account: "claude:system" }))
  expect((await core.sessions.get(id))!.account).toBe("claude:system")
})

test("accounts survive a restart; the default vault is a file vault under the state directory", async () => {
  const base = await scratch()
  const stateDirectory = join(base, "state")
  const open = () => createCore({ stateDirectory, agents: [fake("claude").driver], limits: TEST_LIMITS, accounts: { homes: { claudeRoot: join(base, "root") } } })
  const first = open()
  await first.accounts.add({ id: "k", agent: "claude", method: "api_key", secret: "sk-file" })
  expect(await readFile(join(stateDirectory, "accounts", "vault", "k.secret"), "utf8")).toBe("sk-file")
  await first.close({ agents: "shutdown" })
  const second = open()
  cores.push(second)
  expect((await second.accounts.list()).map(a => a.id)).toEqual(["claude:system", "k"])
  const cwd = await scratch()
  await second.sessions.create({ id: nextId(), agent: "claude", cwd, account: "k" })
})
