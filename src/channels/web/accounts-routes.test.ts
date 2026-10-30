// Accounts over HTTP (slice A3a): the routes wired to the real BrokerAccounts service over a
// throwaway shared registry, with a fake login CLI (supermux-core's login fixture).
import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebAccountsApi, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { createCore, type Core } from "../../../packages/supermux-core/src/index.js"
import { AccountRegistry, UsageStore, memoryVault } from "../../../packages/supermux-core/src/accounts/index.js"
import { ACCOUNT_AGENTS, AUTO_USAGE_WAIT_MS, BrokerAccounts, migrateSettingsCredentials } from "../../core/accounts/broker-accounts"

setDefaultTimeout(20_000)
const FIXTURE = join(import.meta.dir, "../../../packages/supermux-core/tests/fixtures/login-cli.mjs")

let channel: WebChannel | undefined
const cores: Core[] = []
const dirs: string[] = []
afterEach(async () => {
  if (channel) { await channel.stop(); channel = undefined }
  await Promise.all(cores.splice(0).map((c) => c.close({ agents: "shutdown" }).catch(() => {})))
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function setup(overrides: Partial<WebAccountsApi> = {}) {
  const base = mkdtempSync(join(tmpdir(), "mux-accounts-routes-"))
  dirs.push(base)
  const registry = new AccountRegistry(join(base, "state"), [...ACCOUNT_AGENTS], {
    vault: memoryVault(),
    homes: { claudeRoot: join(base, "claude-root"), codexRoot: join(base, "codex-root"), grokRoot: join(base, "grok-root"), cursorRoot: join(base, "cursor-root") },
  })
  const usage = new UsageStore(join(registry.directory, "usage.json"))
  const commands: Record<string, string> = {}
  for (const kind of ["claude", "codex", "grok", "cursor"]) {
    const path = join(base, `fake-${kind}`)
    writeFileSync(path, `#!/bin/sh\nexec ${JSON.stringify(process.execPath)} ${JSON.stringify(FIXTURE)} ${kind} ok ${JSON.stringify(join(base, "cli.log"))} '${JSON.stringify({ email: "new@x.io", accountId: "acct-new" })}' -- "$@"\n`)
    chmodSync(path, 0o755)
    commands[kind] = path
  }
  const coreByAgent = new Map<string, Core>()
  const frames: Array<Record<string, unknown>> = []
  const accounts = new BrokerAccounts({
    registry, usage,
    coreFor: (agent) => {
      let core = coreByAgent.get(agent)
      if (!core) {
        core = createCore({
          stateDirectory: join(base, "core", agent),
          agents: [{ id: agent, async open() { throw new Error("not used") } }],
          limits: { interruptTimeoutMs: 40, maxPending: 8, outstandingActivity: 8 },
          accounts: { registry, usage, login: { commands } },
        })
        cores.push(core)
        coreByAgent.set(agent, core)
      }
      return core
    },
    broadcast: (frame) => frames.push(frame),
  })
  let autoSwitch = false
  const api: WebAccountsApi = {
    list: (agent) => accounts.list(agent),
    add: (body) => accounts.add(body),
    remove: (id, options) => accounts.remove(id, options),
    startLogin: (body) => accounts.startLogin(body),
    loginState: (id) => accounts.loginState(id),
    submitCode: (id, code) => accounts.submitLoginCode(id, code),
    cancelLogin: (id) => accounts.cancelLogin(id),
    setSessionAccount: async (sessionId, account) => ({ ok: true, session: sessionId, account }),
    getSettings: () => ({ autoSwitch }),
    setSettings: (patch) => { if (typeof patch.autoSwitch === "boolean") autoSwitch = patch.autoSwitch; return { autoSwitch } },
    ...overrides,
  }
  const devicesFile = join(base, "devices.json")
  // With a static dir: /accounts must be an API prefix, or GETs get the SPA shell (and POSTs skip
  // the same-origin guard).
  const staticDir = join(base, "static")
  mkdirSync(staticDir)
  writeFileSync(join(staticDir, "index.html"), "<!doctype html><html><body>SPA</body></html>")
  const full: WebChannelOpts = {
    port: 0, devicesFile, publicUrl: "http://localhost", staticDir,
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    accounts: api,
  }
  channel = new WebChannel(full)
  const token = new DeviceStore(devicesFile).mint("test-device").token
  const call = async (method: string, path: string, body?: unknown) => {
    const res = await fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
      method,
      headers: { authorization: `Bearer ${token}`, ...(body !== undefined ? { "content-type": "application/json" } : {}) },
      ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    })
    return { status: res.status, body: await res.json() as any }
  }
  const cookieLessPost = (path: string, body: unknown) => fetch(`http://127.0.0.1:${channel!.boundPort}${path}`, {
    method: "POST", headers: { "content-type": "application/json", origin: "https://evil.example" }, body: JSON.stringify(body),
  })
  return { registry, usage, accounts, frames, call, cookieLessPost }
}

test("GET /accounts lists the six system accounts with no accounts added", async () => {
  const { call } = setup()
  await channel!.start()
  const res = await call("GET", "/accounts")
  expect(res.status).toBe(200)
  expect(res.body.accounts.map((a: any) => [a.id, a.method, a.system, a.label])).toEqual([
    ["claude:system", "system", true, "System login"],
    ["codex:system", "system", true, "System login"],
    ["cursor:system", "system", true, "System login"],
    ["grok:system", "system", true, "System login"],
    ["opencode:system", "system", true, "System login"],
    ["antigravity:system", "system", true, "System login"],
  ])
  expect((await call("GET", "/accounts?agent=codex")).body.accounts.map((a: any) => a.id)).toEqual(["codex:system"])
  expect((await call("GET", "/accounts?agent=nope")).status).toBe(400)
})

test("GET /accounts carries an unavailable mark (reason + since) until it is cleared", async () => {
  const { call, usage, accounts, frames } = setup()
  await channel!.start()
  usage.markUnavailable("claude:system", "Your organization has disabled Claude subscription access", new Date("2026-10-11T09:08:27.000Z"))
  accounts.notifyChanged()
  expect(frames.filter((f) => f.type === "accounts_changed")).toHaveLength(1)
  const [claude, codex] = (await call("GET", "/accounts")).body.accounts
  expect(claude.unavailable).toEqual({ reason: "Your organization has disabled Claude subscription access", since: "2026-10-11T09:08:27.000Z" })
  expect(codex).not.toHaveProperty("unavailable")
  usage.clearUnavailable("claude:system")
  expect((await call("GET", "/accounts?agent=claude")).body.accounts[0]).not.toHaveProperty("unavailable")
})

test("POST /accounts adds an api_key account (secret never returned); DELETE removes it; accounts_changed is broadcast", async () => {
  const { call, frames, registry } = setup()
  await channel!.start()
  const added = await call("POST", "/accounts", { agent: "codex", method: "api_key", secret: "sk-test-secret", label: "Work key" })
  expect(added.status).toBe(200)
  expect(added.body).toMatchObject({ agent: "codex", method: "api_key", label: "Work key", system: false, usage: [] })
  expect(JSON.stringify(added.body)).not.toContain("sk-test-secret")
  const listed = await call("GET", "/accounts?agent=codex")
  expect(listed.body.accounts.map((a: any) => a.id)).toEqual(["codex:system", added.body.id])
  expect(JSON.stringify(listed.body)).not.toContain("sk-test-secret")
  expect(await registry.vault.get(added.body.id)).toBe("sk-test-secret")
  expect(frames.filter((f) => f.type === "accounts_changed")).toHaveLength(1)
  expect((await call("DELETE", `/accounts/${encodeURIComponent(added.body.id)}`)).body).toEqual({ ok: true })
  expect((await call("GET", "/accounts?agent=codex")).body.accounts).toHaveLength(1)
  expect(frames.filter((f) => f.type === "accounts_changed")).toHaveLength(2)
})

test("POST /accounts validation: bad method, no secret, unknown agent; removing the system account is refused", async () => {
  const { call } = setup()
  await channel!.start()
  expect((await call("POST", "/accounts", { agent: "codex", method: "subscription", secret: "x" })).status).toBe(400)
  expect((await call("POST", "/accounts", { agent: "codex", method: "api_key" })).status).toBe(400)
  expect((await call("POST", "/accounts", { agent: "vim", method: "api_key", secret: "x" })).status).toBe(400)
  expect((await call("POST", "/accounts", { agent: "cursor", method: "token", secret: "x" })).body.code).toBe("unsupported_operation")
  const sys = await call("DELETE", "/accounts/claude%3Asystem")
  expect(sys.status).toBe(400)
  expect((await call("DELETE", "/accounts/nope")).status).toBe(404)
})

test("account login: a Codex login becomes a token account by default; state frames carry loginId/url/code", async () => {
  const { call, frames } = setup()
  await channel!.start()
  const started = await call("POST", "/accounts/login", { agent: "codex", label: "Codex work" })
  expect(started.status).toBe(200)
  const loginId = started.body.loginId
  expect(typeof loginId).toBe("string")
  let state: any
  for (let i = 0; i < 200; i++) {
    state = (await call("GET", `/accounts/login/${loginId}`)).body
    if (state.phase === "done" || state.phase === "failed") break
    await Bun.sleep(25)
  }
  expect(state.phase).toBe("done")
  expect(state.account).toMatchObject({ agent: "codex", method: "token", label: "Codex work", identity: { email: "new@x.io", accountId: "acct-new" } })
  const awaiting = frames.find((f) => f.type === "account_login_state" && f.phase === "awaiting_user")
  expect(awaiting).toMatchObject({ loginId, agent: "codex", url: "https://auth.openai.com/codex/device", code: "ABCD-EFGH" })
  expect(frames.some((f) => f.type === "account_login_state" && f.phase === "done")).toBe(true)
  expect(frames.some((f) => f.type === "accounts_changed")).toBe(true)
  expect((await call("GET", "/accounts/login/nope")).status).toBe(404)
  expect((await call("POST", "/accounts/login/nope/cancel")).status).toBe(404)
})

test("POST /sessions/:id/account and /settings/accounts", async () => {
  const { call } = setup()
  await channel!.start()
  expect((await call("POST", "/sessions/s1/account", { account: "codex-x" })).body).toEqual({ ok: true, session: "s1", account: "codex-x" })
  expect((await call("POST", "/sessions/s1/account", {})).status).toBe(400)
  expect((await call("GET", "/settings/accounts")).body).toEqual({ autoSwitch: false })
  expect((await call("PUT", "/settings/accounts", { autoSwitch: true })).body).toEqual({ autoSwitch: true })
})

test("session account validation: foreign and unsupported accounts are refused", async () => {
  const { accounts, registry } = setup()
  await registry.add({ id: "c-key", agent: "claude", method: "api_key", secret: "sk" })
  await registry.add({ id: "x-sub", agent: "codex", method: "subscription" })
  expect(await accounts.resolveSessionAccount("claude", "c-key")).toBe("c-key")
  expect(await accounts.resolveSessionAccount("claude", undefined)).toBeUndefined()
  expect(await accounts.resolveSessionAccount("codex", "codex:system")).toBe("codex:system")
  await expect(accounts.resolveSessionAccount("codex", "c-key")).rejects.toMatchObject({ status: 400 })
  await expect(accounts.resolveSessionAccount("codex", "x-sub")).rejects.toMatchObject({ status: 400, code: "account_unsupported" })
  await expect(accounts.resolveSessionAccount("codex", "missing")).rejects.toMatchObject({ status: 404 })
  await accounts.refreshLabels()
  expect(accounts.label("c-key")).toBe("c-key")
  expect(accounts.label(undefined)).toBe("System login")
})

test("settings credentials migrate once into labelled token/api_key accounts", async () => {
  const { registry } = setup()
  const creds = { claudeOauthToken: "tok", anthropicApiKey: "", codexApiKey: "sk-codex", cursorApiKey: "cur" }
  expect(await migrateSettingsCredentials(registry, creds)).toEqual(["claude-settings-token", "codex-settings-api-key", "cursor-settings-api-key"])
  expect(await migrateSettingsCredentials(registry, creds)).toEqual([])
  const claude = await registry.get("claude-settings-token")
  expect(claude).toMatchObject({ agent: "claude", method: "token", label: "Claude token (settings)" })
  expect(await registry.vault.get("codex-settings-api-key")).toBe("sk-codex")
})

test("the settings Gemini API key migrates into an antigravity api_key account", async () => {
  const { registry } = setup()
  expect(await migrateSettingsCredentials(registry, { geminiApiKey: "AIza-g" })).toEqual(["antigravity-settings-api-key"])
  expect(await registry.get("antigravity-settings-api-key")).toMatchObject({ agent: "antigravity", method: "api_key", label: "Gemini API key (settings)" })
  expect(await registry.vault.get("antigravity-settings-api-key")).toBe("AIza-g")
})

test("a cross-origin POST /accounts without a bearer token is refused by the same-origin guard", async () => {
  const { cookieLessPost } = setup()
  await channel!.start()
  const res = await cookieLessPost("/accounts", { agent: "codex", method: "api_key", secret: "x" })
  expect(res.status).toBe(403)
})

test("Auto picks the account with the most room left, fetching stale usage first; GET /accounts marks the pick", async () => {
  const refreshed: string[] = []
  const { call, usage, registry, accounts } = setup()
  accounts.setUsageRefresher(async (agent) => { refreshed.push(agent) })
  await registry.add({ id: "grok-a", agent: "grok", method: "api_key", secret: "k-a" })
  await registry.add({ id: "grok-b", agent: "grok", method: "api_key", secret: "k-b" })
  const inAWeek = new Date(Date.now() + 7 * 86_400_000)
  usage.set("grok:system", [{ name: "weekly", usedPercent: 100, resetsAt: inAWeek }])
  usage.set("grok-a", [{ name: "weekly", usedPercent: 80, resetsAt: inAWeek }])
  usage.set("grok-b", [{ name: "weekly", usedPercent: 50, resetsAt: inAWeek }])
  expect(await accounts.autoAccount("grok")).toBe("grok-b")
  expect(refreshed).toEqual(["grok"])
  await channel!.start()
  const grok = (await call("GET", "/accounts?agent=grok")).body.accounts
  expect(grok.filter((a: any) => a.auto).map((a: any) => a.id)).toEqual(["grok-b"])
  // Nothing better than the system login (or no data at all): undefined, the system account.
  usage.set("grok-b", [{ name: "weekly", usedPercent: 100, resetsAt: inAWeek }])
  usage.set("grok-a", [{ name: "weekly", usedPercent: 100, resetsAt: inAWeek }])
  expect(await accounts.autoAccount("grok")).toBeUndefined()
  expect(await accounts.autoAccount("claude")).toBeUndefined()
})

test("Auto never picks an account a broker session cannot run on (a Codex subscription)", async () => {
  const { usage, registry, accounts } = setup()
  // A subscription keeps its credentials in its home: no secret.
  await registry.add({ id: "codex-sub", agent: "codex", method: "subscription" })
  expect((await registry.get("codex-sub"))?.method).toBe("subscription")
  // A second account it can run on, listed after the subscription: Auto has a real choice to make.
  await registry.add({ id: "codex-tok", agent: "codex", method: "api_key", secret: "k" })
  // Whatever the registry accepted, Auto must not return a Codex subscription account.
  usage.set("codex:system", [{ name: "300m", usedPercent: 100, resetsAt: new Date(Date.now() + 3_600_000) }])
  const picked = await accounts.autoAccount("codex")
  const account = picked ? await registry.get(picked) : undefined
  expect(account?.method).not.toBe("subscription")
})

test("Auto never blocks a new session: an unreadable account list starts it on the system login", async () => {
  const { registry, accounts } = setup()
  mkdirSync(registry.directory, { recursive: true })
  writeFileSync(join(registry.directory, "accounts.json"), "{not json")
  await expect(registry.list("claude")).rejects.toMatchObject({ code: "invalid_account_record" })
  expect(await accounts.autoAccount("claude")).toBeUndefined()
})

test("Auto waits for no usage when the system login is the only account it could pick", async () => {
  const refreshed: string[] = []
  const { registry, accounts } = setup()
  accounts.setUsageRefresher(async (agent) => { refreshed.push(agent) })
  // Isolated accounts are picked by hand only, Cursor accounts never by the policy.
  await registry.add({ id: "claude-iso", agent: "claude", method: "api_key", secret: "k", isolated: true })
  await registry.add({ id: "cursor-key", agent: "cursor", method: "api_key", secret: "k" })
  expect(await accounts.autoAccount("claude")).toBeUndefined()
  expect(await accounts.autoAccount("cursor")).toBeUndefined()
  expect(await accounts.autoAccount("grok")).toBeUndefined()
  expect(refreshed).toEqual([])
})

test("Auto waits for usage only so long: a refresh that never finishes still lets it pick", async () => {
  const { usage, registry, accounts } = setup()
  expect(AUTO_USAGE_WAIT_MS).toBe(3000)
  accounts.setUsageRefresher(() => new Promise<void>(() => {}), 50)
  await registry.add({ id: "grok-a", agent: "grok", method: "api_key", secret: "k-a" })
  usage.set("grok:system", [{ name: "weekly", usedPercent: 100, resetsAt: new Date(Date.now() + 86_400_000) }])
  const picked = await Promise.race([accounts.autoAccount("grok"), Bun.sleep(2000).then(() => "still waiting")])
  expect(picked).toBe("grok-a")
})
