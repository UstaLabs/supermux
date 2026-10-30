import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { mkdir, mkdtemp, readFile, rm, stat, symlink, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { accountHttpStatus } from "../src/accounts/policy.js"
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

type Fake = { driver: AgentDriver; contexts: DriverContext[]; prompts: Array<{ account?: string; text: string }> }
/** A driver whose prompt emits `usage` (the text of the prompt names the rate-limit payload). */
function fake(id: string): Fake {
  const contexts: DriverContext[] = []
  const prompts: Fake["prompts"] = []
  let seq = 0
  return {
    contexts, prompts,
    driver: {
      id,
      async open(context) {
        contexts.push(context)
        const runtime: AgentRuntime = {
          agentSessionId: context.resumeId ?? `native-${++seq}`,
          capabilities: { resume: true, steer: false, fork: true, detach: false },
          async prompt(content) {
            const text = content[0]?.type === "text" ? content[0].text : ""
            prompts.push({ ...(context.account !== undefined ? { account: context.account } : {}), text })
            if (text.startsWith("usage:")) context.onUpdate({ protocol: "native", value: { rateLimits: JSON.parse(text.slice(6)) } })
            if (text.startsWith("refuse:")) {
              context.onUpdate({ protocol: "native", value: { refused: text.slice(7) } })
              throw new Error(text.slice(7))
            }
            if (text.startsWith("http:")) {
              // How the ACP SDK rejects an agent's JSON-RPC error: a generic message, the HTTP status in data.
              const status = Number(text.slice(5))
              throw Object.assign(new Error("Internal error"), { code: -32603, data: { message: `API error (status ${status})`, http_status: status } })
            }
            if (text.startsWith("refuse402:")) {
              // The account is refused during the turn, and the turn then fails out of credits.
              context.onUpdate({ protocol: "native", value: { refused: text.slice(10) } })
              throw Object.assign(new Error("Internal error"), { code: -32603, data: { message: "API error (status 402)", http_status: 402 } })
            }
            return { stopReason: "end_turn" }
          },
          interrupt: async () => {},
          close: async () => {},
          normalize: update => {
            const value = update.value as { rateLimits?: unknown; refused?: string }
            if (value && typeof value === "object" && typeof value.refused === "string") return [{ kind: "error", message: value.refused, errorType: "account_unavailable" }]
            return value && typeof value === "object" && "rateLimits" in value ? [{ kind: "usage", rateLimits: value.rateLimits }] : []
          },
        }
        return runtime
      },
    },
  }
}

async function setup(options: { agents?: Fake[]; autoSwitch?: boolean; continueAfterSwitch?: boolean; continuePrompt?: string; profiles?: Record<string, { agent: string; env?: Record<string, string> }> } = {}) {
  const base = await scratch()
  const stateDirectory = join(base, "state")
  const claudeRoot = join(base, "claude-root")
  const codexRoot = join(base, "codex-root")
  const agents = options.agents ?? [fake("claude"), fake("codex")]
  const vault = memoryVault()
  const core = createCore({
    stateDirectory, agents: agents.map(a => a.driver), limits: TEST_LIMITS,
    accounts: {
      vault, homes: { claudeRoot, codexRoot, grokRoot: join(base, "grok-root") },
      ...(options.autoSwitch !== undefined ? { autoSwitch: options.autoSwitch } : {}),
      ...(options.continueAfterSwitch !== undefined ? { continueAfterSwitch: options.continueAfterSwitch } : {}),
      ...(options.continuePrompt !== undefined ? { continuePrompt: options.continuePrompt } : {}),
    },
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
  expect(await waitFor(events, e => e.type === "account.exhausted" && e.sessionId === sid ? e : undefined)).toEqual({ type: "account.exhausted", sessionId: sid, agent: "claude", account: "b", reason: "limit" })
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

const DEFAULT_CONTINUE = "Continue from where you stopped. Your previous turn hit a usage limit and you are now on another account."
const settle = (ms = 60) => new Promise(resolve => setTimeout(resolve, ms))

test("limit continuation: one continuation prompt on the new account after a limit switch", async () => {
  const { core, agents, events } = await setup({ autoSwitch: true })
  const cwd = await scratch()
  await core.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "claude", cwd, account: "a" })
  const full = JSON.stringify({ status: "rejected", rateLimitType: "five_hour", resetsAt: future() })
  await (await session.send({ content: text(`usage:${full}`), whenBusy: "queue" })).completed
  await waitFor(events, e => e.type === "account.switched" ? e : undefined)
  const deadline = Date.now() + 3000
  while (agents[0]!.prompts.length < 2 && Date.now() < deadline) await settle(10)
  await settle()
  expect(agents[0]!.prompts).toEqual([
    { account: "a", text: `usage:${full}` },
    { account: "claude:system", text: DEFAULT_CONTINUE },
  ])
})

test("limit continuation: custom prompt; off with continueAfterSwitch false; skipped when input is queued", async () => {
  const full = JSON.stringify({ status: "rejected", rateLimitType: "five_hour", resetsAt: future() })
  {
    const { core, agents, events } = await setup({ autoSwitch: true, continuePrompt: "go on" })
    await core.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
    const s = await core.sessions.create({ id: nextId(), agent: "claude", cwd: await scratch(), account: "a" })
    await (await s.send({ content: text(`usage:${full}`), whenBusy: "queue" })).completed
    await waitFor(events, e => e.type === "account.switched" ? e : undefined)
    const deadline = Date.now() + 3000
    while (agents[0]!.prompts.length < 2 && Date.now() < deadline) await settle(10)
    expect(agents[0]!.prompts.at(-1)).toEqual({ account: "claude:system", text: "go on" })
  }
  {
    const { core, agents, events } = await setup({ autoSwitch: true, continueAfterSwitch: false })
    await core.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
    const s = await core.sessions.create({ id: nextId(), agent: "claude", cwd: await scratch(), account: "a" })
    await (await s.send({ content: text(`usage:${full}`), whenBusy: "queue" })).completed
    await waitFor(events, e => e.type === "account.switched" ? e : undefined)
    await settle(150)
    expect(agents[0]!.prompts.length).toBe(1)
  }
  {
    const { core, agents, events } = await setup({ autoSwitch: true })
    await core.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
    const s = await core.sessions.create({ id: nextId(), agent: "claude", cwd: await scratch(), account: "a" })
    await s.send({ content: text(`usage:${full}`), whenBusy: "queue" })
    await s.send({ content: text("queued by the host"), whenBusy: "queue" })
    await waitFor(events, e => e.type === "account.switched" ? e : undefined)
    await settle(150)
    expect(agents[0]!.prompts.some(p => p.text === DEFAULT_CONTINUE)).toBe(false)
  }
  {
    // A switch that was not caused by the turn hitting a limit (manual) sends nothing.
    const { core, agents } = await setup({ autoSwitch: true })
    await core.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
    const id = nextId()
    const s = await core.sessions.create({ id, agent: "claude", cwd: await scratch() })
    await (await s.send({ content: text("hello"), whenBusy: "queue" })).completed
    await core.sessions.resume(id, { account: "a" })
    await settle(100)
    expect(agents[0]!.prompts.map(p => p.text)).toEqual(["hello"])
  }
  const dir = await scratch()
  expect(() => createCore({ stateDirectory: dir, agents: [], limits: TEST_LIMITS, accounts: { continuePrompt: " " } })).toThrow("continuePrompt")
  expect(() => createCore({ stateDirectory: dir, agents: [], limits: TEST_LIMITS, accounts: { continueAfterSwitch: "no" as never } })).toThrow("continueAfterSwitch")
})

const BACKGROUND_CONTINUE = `${DEFAULT_CONTINUE} Background subagents may have stopped on the limit or the switch: check them and resume or restart any unfinished work.`
const rejected = () => ({ status: "rejected", rateLimitType: "five_hour", resetsAt: future() })

test("limit continuation: a turn the agent started on its own (background work finished) continues after the switch", async () => {
  const { core, agents, events } = await setup({ autoSwitch: true })
  await core.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  const s = await core.sessions.create({ id: nextId(), agent: "claude", cwd: await scratch(), account: "a" })
  await (await s.send({ content: text("spawn a background subagent"), whenBusy: "queue" })).completed
  // The subagent's notification starts a native turn, which hits the limit.
  const context = agents[0]!.contexts[0]!
  context.onActivity?.({ id: "unsolicited-1", phase: "started" })
  context.onUpdate({ protocol: "native", value: { rateLimits: rejected() } })
  context.onActivity?.({ id: "unsolicited-1", phase: "completed" })
  await waitFor(events, e => e.type === "account.switched" ? e : undefined)
  const deadline = Date.now() + 3000
  while (agents[0]!.prompts.length < 2 && Date.now() < deadline) await settle(10)
  expect(agents[0]!.prompts.at(-1)).toEqual({ account: "claude:system", text: BACKGROUND_CONTINUE })
})

test("limit continuation: a limit hit while the main turn is idle (a background subagent) continues after the switch", async () => {
  const { core, agents, events } = await setup({ autoSwitch: true })
  await core.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  const s = await core.sessions.create({ id: nextId(), agent: "claude", cwd: await scratch(), account: "a" })
  await (await s.send({ content: text("spawn a background subagent"), whenBusy: "queue" })).completed
  agents[0]!.contexts[0]!.onUpdate({ protocol: "native", value: { rateLimits: rejected() } })
  await waitFor(events, e => e.type === "account.switched" ? e : undefined)
  const deadline = Date.now() + 3000
  while (agents[0]!.prompts.length < 2 && Date.now() < deadline) await settle(10)
  expect(agents[0]!.prompts.at(-1)).toEqual({ account: "claude:system", text: BACKGROUND_CONTINUE })
})

test("usage persists across restarts (windows with a future reset only); remove drops it", async () => {
  const base = await scratch()
  const stateDirectory = join(base, "state")
  const open = () => createCore({ stateDirectory, agents: [fake("claude").driver], limits: TEST_LIMITS, accounts: { vault: memoryVault({ a: "t-a" }), homes: { claudeRoot: join(base, "root") } } })
  const first = open()
  await first.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  const s = await first.sessions.create({ id: nextId(), agent: "claude", cwd: await scratch(), account: "a" })
  const rateLimits = { status: "allowed", unifiedWindows: { five_hour: { utilization: 0.5, resetsAt: future() }, seven_day: { utilization: 0.2 } } }
  await (await s.send({ content: text(`usage:${JSON.stringify(rateLimits)}`), whenBusy: "queue" })).completed
  await settle(20)
  await first.close({ agents: "shutdown" })
  const file = join(stateDirectory, "accounts", "usage.json")
  const saved = JSON.parse(await readFile(file, "utf8"))
  expect(Object.keys(saved.accounts)).toEqual(["a"])
  expect(saved.accounts.a).toEqual([{ name: "five_hour", usedPercent: 50, resetsAt: expect.any(String) }])
  expect((await stat(file)).mode & 0o777).toBe(0o600)

  // Expired windows are dropped on load.
  saved.accounts.old = [{ name: "five_hour", usedPercent: 100, resetsAt: new Date(Date.now() - 1000).toISOString() }]
  await writeFile(file, JSON.stringify(saved))
  const second = open()
  cores.push(second)
  expect((await second.accounts.list()).length).toBe(2)
  expect(second.accounts.usage("a")).toEqual([{ name: "five_hour", usedPercent: 50, resetsAt: expect.any(Date) }])
  expect(second.accounts.usage("old")).toBeUndefined()
  expect((await second.accounts.pick("claude"))?.id).toBe("a")
  await second.accounts.remove("a")
  expect(second.accounts.usage("a")).toBeUndefined()
  await second.close({ agents: "shutdown" })
  expect(JSON.parse(await readFile(file, "utf8")).accounts).toEqual({})
})

test("a corrupt usage.json is a cache miss", async () => {
  const base = await scratch()
  const stateDirectory = join(base, "state")
  await mkdir(join(stateDirectory, "accounts"), { recursive: true })
  await writeFile(join(stateDirectory, "accounts", "usage.json"), "{nope")
  const core = createCore({ stateDirectory, agents: [fake("claude").driver], limits: TEST_LIMITS, accounts: { vault: memoryVault() } })
  cores.push(core)
  expect((await core.accounts.pick("claude"))?.id).toBe("claude:system")
})

test("remove({ deleteHome }) unlinks shared entries and removes the private files; the history root is untouched", async () => {
  const { core, claudeRoot } = await setup()
  await core.accounts.add({ id: "s", agent: "claude", method: "subscription" })
  const home = await core.accounts.home("s")
  await writeFile(join(claudeRoot, "projects", "keep.jsonl"), "history")
  await writeFile(join(home, ".credentials.json"), "{}")
  await core.accounts.add({ id: "s2", agent: "claude", method: "subscription" })
  const home2 = await core.accounts.home("s2")
  await core.accounts.remove("s2")
  expect((await stat(home2)).isDirectory()).toBe(true)
  await core.accounts.remove("s", { deleteHome: true })
  await expect(stat(home)).rejects.toMatchObject({ code: "ENOENT" })
  expect(await readFile(join(claudeRoot, "projects", "keep.jsonl"), "utf8")).toBe("history")
  expect((await stat(join(claudeRoot, "settings.json"))).isFile()).toBe(true)
  await expect(core.accounts.remove("s2", { deleteHome: true })).rejects.toMatchObject({ code: "unknown_account" })
})

test("remove({ deleteHome }) also removes a non-subscription account's home (antigravity api_key cli dir), links unlinked first", async () => {
  const { core, base, stateDirectory } = await setup({ agents: [fake("antigravity")] })
  await core.accounts.add({ id: "k", agent: "antigravity", method: "api_key", secret: "K" })
  // What materialize's context.home() creates for it; a shared link inside must not be followed.
  const home = join(stateDirectory, "accounts", "homes", "antigravity", "k")
  const outside = join(base, "user-conversations")
  await mkdir(outside)
  await writeFile(join(outside, "keep.pb"), "history")
  await mkdir(home, { recursive: true })
  await symlink(outside, join(home, "conversations"))
  await core.accounts.remove("k", { deleteHome: true })
  await expect(stat(home)).rejects.toMatchObject({ code: "ENOENT" })
  expect(await readFile(join(outside, "keep.pb"), "utf8")).toBe("history")
  // No home at all is fine.
  await core.accounts.add({ id: "k2", agent: "antigravity", method: "api_key", secret: "K" })
  await core.accounts.remove("k2", { deleteHome: true })
})

const REFUSED = "Your organization has disabled Claude subscription access for Claude Code"
const UNAVAILABLE_CONTINUE = "Continue from where you stopped. Your previous turn failed because its account was refused (login or subscription problem) and you are now on another account."

test("unavailable: a refused account is marked, the session moves to a healthy account and the turn continues once", async () => {
  const { core, agents, events } = await setup({ autoSwitch: true })
  const cwd = await scratch()
  await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "claude", cwd })
  expect((await (await session.send({ content: text(`refuse:${REFUSED}`), whenBusy: "queue" })).completed).status).toBe("failed")
  expect(await waitFor(events, e => e.type === "account.unavailable" ? e : undefined))
    .toEqual({ type: "account.unavailable", sessionId: id, agent: "claude", account: "claude:system", reason: REFUSED })
  expect(await waitFor(events, e => e.type === "account.switched" ? e : undefined))
    .toEqual({ type: "account.switched", sessionId: id, from: "claude:system", to: "b", reason: "unavailable" })
  expect(core.accounts.unavailable("claude:system")).toEqual({ reason: REFUSED, since: expect.any(Date) })
  const deadline = Date.now() + 3000
  while (agents[0]!.prompts.length < 2 && Date.now() < deadline) await settle(10)
  await settle()
  expect(agents[0]!.prompts).toEqual([{ text: `refuse:${REFUSED}` }, { account: "b", text: UNAVAILABLE_CONTINUE }])
  // A turn completing on b leaves the system mark alone.
  expect(core.accounts.unavailable("claude:system")).toBeDefined()
})

test("unavailable: the picker skips the account, a manual switch is still allowed and a completed turn clears the mark", async () => {
  const { core, events } = await setup({ autoSwitch: true })
  const cwd = await scratch()
  await core.accounts.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "claude", cwd, account: "a" })
  await (await session.send({ content: text(`refuse:${REFUSED}`), whenBusy: "queue" })).completed
  await waitFor(events, e => e.type === "account.switched" && e.sessionId === id ? e : undefined)
  expect((await core.accounts.pick("claude", ["claude:system"]))).toBeUndefined()
  expect((await core.accounts.pick("claude"))?.id).toBe("claude:system")
  // Back on `a` by hand: not blocked; the first completed turn proves it works again.
  const back = await core.sessions.resume(id, { account: "a" })
  await waitFor(events, e => e.type === "account.switched" && e.reason === "manual" ? e : undefined)
  expect((await (await back.send({ content: text("hello"), whenBusy: "queue" })).completed).status).toBe("completed")
  expect(await waitFor(events, e => e.type === "account.available" ? e : undefined)).toEqual({ type: "account.available", sessionId: id, agent: "claude", account: "a" })
  expect(core.accounts.unavailable("a")).toBeUndefined()
  expect((await core.accounts.pick("claude", ["claude:system"]))?.id).toBe("a")
})

test("unavailable: without autoSwitch the account is only marked; no other account → account.exhausted", async () => {
  {
    const { core, events } = await setup()
    const cwd = await scratch()
    await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
    const s = await core.sessions.create({ id: nextId(), agent: "claude", cwd })
    await (await s.send({ content: text(`refuse:${REFUSED}`), whenBusy: "queue" })).completed
    await waitFor(events, e => e.type === "account.unavailable" ? e : undefined)
    await settle()
    expect(events.some(e => e.type === "account.switched" || e.type === "account.exhausted")).toBe(false)
  }
  {
    const { core, events } = await setup({ autoSwitch: true })
    const cwd = await scratch()
    const sid = nextId()
    const s = await core.sessions.create({ id: sid, agent: "claude", cwd })
    await (await s.send({ content: text(`refuse:${REFUSED}`), whenBusy: "queue" })).completed
    expect(await waitFor(events, e => e.type === "account.exhausted" ? e : undefined)).toEqual({ type: "account.exhausted", sessionId: sid, agent: "claude", account: "claude:system", reason: "unavailable" })
  }
})

test("unavailable marks persist across restarts", async () => {
  const base = await scratch()
  const stateDirectory = join(base, "state")
  const open = () => createCore({ stateDirectory, agents: [fake("claude").driver], limits: TEST_LIMITS, accounts: { vault: memoryVault(), homes: { claudeRoot: join(base, "root") } } })
  const first = open()
  const cwd = await scratch()
  const s = await first.sessions.create({ id: nextId(), agent: "claude", cwd })
  await (await s.send({ content: text(`refuse:${REFUSED}`), whenBusy: "queue" })).completed
  await settle()
  await first.close({ agents: "shutdown" })
  const saved = JSON.parse(await readFile(join(stateDirectory, "accounts", "usage.json"), "utf8"))
  expect(saved.unavailable["claude:system"].reason).toBe(REFUSED)
  const second = open()
  cores.push(second)
  await second.accounts.list()
  expect(second.accounts.unavailable("claude:system")?.reason).toBe(REFUSED)
})

/**
 * willSwitch read where the broker's adapter reads it: at the session's first idle after the failed turn's
 * message.completed, when a switch that will run has moved from the queue into limitSwitching. Read once:
 * the continuation turn on the new account ends idle again, with nothing pending.
 */
function willSwitchAtIdle(core: ReturnType<typeof createCore>, id: string): () => Promise<boolean> | undefined {
  let completed = false
  let read: Promise<boolean> | undefined
  core.subscribe(event => {
    if (event.type === "message.completed" && event.sessionId === id) completed = true
    else if (completed && event.type === "session.stateChanged" && event.sessionId === id && event.state === "idle") read ??= core.accounts.willSwitch(id)
  })
  return () => read
}

test("willSwitch: true at the turn's end while a switch is pending and another account is available; false otherwise", async () => {
  // A refused account with another one to move to.
  {
    const { core, events } = await setup({ autoSwitch: true })
    const cwd = await scratch()
    await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
    const id = nextId()
    const session = await core.sessions.create({ id, agent: "claude", cwd })
    expect(await core.accounts.willSwitch(id)).toBe(false)
    const atTurnEnd = willSwitchAtIdle(core, id)
    expect((await (await session.send({ content: text(`refuse:${REFUSED}`), whenBusy: "queue" })).completed).status).toBe("failed")
    await waitFor(events, e => e.type === "account.switched" && e.sessionId === id ? e : undefined)
    expect(await atTurnEnd()).toBe(true)
  }
  // No other account: the switch is pending but has nowhere to go.
  {
    const { core, events } = await setup({ autoSwitch: true })
    const cwd = await scratch()
    const id = nextId()
    const session = await core.sessions.create({ id, agent: "claude", cwd })
    const atTurnEnd = willSwitchAtIdle(core, id)
    await (await session.send({ content: text(`refuse:${REFUSED}`), whenBusy: "queue" })).completed
    await waitFor(events, e => e.type === "account.exhausted" && e.sessionId === id ? e : undefined)
    expect(await atTurnEnd()).toBe(false)
  }
  // A session on an isolated account is never switched.
  {
    const { core } = await setup({ autoSwitch: true })
    const cwd = await scratch()
    await core.accounts.add({ id: "iso", agent: "claude", method: "token", secret: "t-i", isolated: true })
    await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
    const id = nextId()
    const session = await core.sessions.create({ id, agent: "claude", cwd, account: "iso" })
    const atTurnEnd = willSwitchAtIdle(core, id)
    await (await session.send({ content: text(`refuse:${REFUSED}`), whenBusy: "queue" })).completed
    await settle()
    expect(await atTurnEnd()).toBe(false)
  }
  // Without autoSwitch nothing is pending.
  {
    const { core } = await setup({ autoSwitch: false })
    const cwd = await scratch()
    await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
    const id = nextId()
    const session = await core.sessions.create({ id, agent: "claude", cwd })
    const atTurnEnd = willSwitchAtIdle(core, id)
    await (await session.send({ content: text(`refuse:${REFUSED}`), whenBusy: "queue" })).completed
    await settle()
    expect(await atTurnEnd()).toBe(false)
  }
})

test("accountHttpStatus reads an ACP error's data.http_status (also through cause); only 401, 402 and 403 count", () => {
  const rpc = (status: number) => Object.assign(new Error("Internal error"), { data: { http_status: status } })
  expect(accountHttpStatus(rpc(402))).toBe(402)
  expect(accountHttpStatus(rpc(401))).toBe(401)
  expect(accountHttpStatus(rpc(403))).toBe(403)
  expect(accountHttpStatus(rpc(500))).toBeUndefined()
  expect(accountHttpStatus(new Error("wrapped", { cause: rpc(402) }))).toBe(402)
  expect(accountHttpStatus(new Error("plain"))).toBeUndefined()
})

test("HTTP 402 on a failed turn: the account counts as full, the switch moves the session on and the turn continues", async () => {
  const { core, agents, events } = await setup({ autoSwitch: true })
  const cwd = await scratch()
  await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "claude", cwd })
  const atTurnEnd = willSwitchAtIdle(core, id)
  expect((await (await session.send({ content: text("http:402"), whenBusy: "queue" })).completed).status).toBe("failed")
  expect(await waitFor(events, e => e.type === "account.switched" && e.sessionId === id ? e : undefined))
    .toEqual({ type: "account.switched", sessionId: id, from: "claude:system", to: "b", reason: "limit" })
  // The broker's failed-turn notice reads willSwitch when the turn ends: it must see this switch coming.
  expect(await atTurnEnd()).toBe(true)
  expect(core.accounts.usage("claude:system")).toEqual([{ name: "balance", usedPercent: 100, resetsAt: expect.any(Date) }])
  const deadline = Date.now() + 3000
  while (agents[0]!.prompts.length < 2 && Date.now() < deadline) await settle(10)
  expect(agents[0]!.prompts.at(-1)).toEqual({ account: "b", text: expect.stringContaining("usage limit") })
  expect((await core.accounts.pick("claude"))?.id).toBe("b")
})

test("HTTP 402 keeps a usage picture that already says full; without autoSwitch it only records", async () => {
  const { core, events } = await setup({ autoSwitch: false })
  const cwd = await scratch()
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "claude", cwd })
  const full = JSON.stringify({ status: "rejected", rateLimitType: "five_hour", resetsAt: future() })
  await (await session.send({ content: text(`usage:${full}`), whenBusy: "queue" })).completed
  const before = core.accounts.usage("claude:system")
  expect(before?.[0]).toMatchObject({ name: "five_hour", usedPercent: 100 })
  await (await session.send({ content: text("http:402"), whenBusy: "queue" })).completed
  await settle()
  expect(core.accounts.usage("claude:system")).toEqual(before)
  expect(events.some(e => e.type === "account.switched" || e.type === "account.exhausted")).toBe(false)
  const other = await core.sessions.create({ id: nextId(), agent: "codex", cwd })
  await (await other.send({ content: text("http:402"), whenBusy: "queue" })).completed
  await settle()
  expect(core.accounts.usage("codex:system")?.[0]).toMatchObject({ name: "balance", usedPercent: 100 })
})

test("HTTP 401/403 on a failed Grok turn marks the account unavailable and the switch moves the session on", async () => {
  const { core, events } = await setup({ agents: [fake("grok")], autoSwitch: true })
  const cwd = await scratch()
  await core.accounts.add({ id: "b", agent: "grok", method: "api_key", secret: "xai-b" })
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "grok", cwd })
  await (await session.send({ content: text("http:403"), whenBusy: "queue" })).completed
  expect(await waitFor(events, e => e.type === "account.unavailable" ? e : undefined))
    .toEqual({ type: "account.unavailable", sessionId: id, agent: "grok", account: "grok:system", reason: "API error (status 403)" })
  expect(await waitFor(events, e => e.type === "account.switched" ? e : undefined))
    .toEqual({ type: "account.switched", sessionId: id, from: "grok:system", to: "b", reason: "unavailable" })
})

test("HTTP 401/403 counts only where it refuses the whole login: on Claude it marks nothing and switches nothing", async () => {
  const { core, events } = await setup({ autoSwitch: true })
  const cwd = await scratch()
  await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
  const session = await core.sessions.create({ id: nextId(), agent: "claude", cwd })
  for (const status of [401, 403]) {
    expect((await (await session.send({ content: text(`http:${status}`), whenBusy: "queue" })).completed).status).toBe("failed")
  }
  await settle()
  expect(core.accounts.unavailable("claude:system")).toBeUndefined()
  expect(core.accounts.usage("claude:system")).toBeUndefined()
  expect(events.some(e => e.type === "account.unavailable" || e.type === "account.switched" || e.type === "account.exhausted")).toBe(false)
})

test("HTTP 401 is a refused login, not an empty balance: no balance window, an unavailable switch", async () => {
  const { core, events } = await setup({ agents: [fake("grok")], autoSwitch: true })
  const cwd = await scratch()
  await core.accounts.add({ id: "b", agent: "grok", method: "api_key", secret: "xai-b" })
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "grok", cwd })
  await (await session.send({ content: text("http:401"), whenBusy: "queue" })).completed
  expect(await waitFor(events, e => e.type === "account.switched" ? e : undefined))
    .toEqual({ type: "account.switched", sessionId: id, from: "grok:system", to: "b", reason: "unavailable" })
  expect(core.accounts.unavailable("grok:system")).toEqual({ reason: "API error (status 401)", since: expect.any(Date) })
  expect(core.accounts.usage("grok:system")).toBeUndefined()
})

test("a session with an authProfile is left alone on HTTP 402/401/403: it runs on the host's credentials, not an account", async () => {
  const { core, events } = await setup({ agents: [fake("grok")], autoSwitch: true, profiles: { own: { agent: "grok", env: { XAI_API_KEY: "host-key" } } } })
  const cwd = await scratch()
  await core.accounts.add({ id: "b", agent: "grok", method: "api_key", secret: "xai-b" })
  const session = await core.sessions.create({ id: nextId(), agent: "grok", cwd, authProfile: "own" })
  for (const status of [402, 401, 403]) {
    expect((await (await session.send({ content: text(`http:${status}`), whenBusy: "queue" })).completed).status).toBe("failed")
  }
  await settle()
  expect(core.accounts.usage("grok:system")).toBeUndefined()
  expect(core.accounts.unavailable("grok:system")).toBeUndefined()
  expect(events.some(e => e.type.startsWith("account."))).toBe(false)
})

test("an HTTP 402 at the end of a turn whose account was refused keeps the unavailable switch", async () => {
  const { core, events } = await setup({ autoSwitch: true })
  const cwd = await scratch()
  await core.accounts.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "claude", cwd })
  expect((await (await session.send({ content: text(`refuse402:${REFUSED}`), whenBusy: "queue" })).completed).status).toBe("failed")
  expect(await waitFor(events, e => e.type === "account.switched" ? e : undefined))
    .toEqual({ type: "account.switched", sessionId: id, from: "claude:system", to: "b", reason: "unavailable" })
})
