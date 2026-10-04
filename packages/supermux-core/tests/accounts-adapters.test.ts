import { afterEach, expect, test } from "bun:test"
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import {
  claudeAdapter, claudeUsage, codexAdapter, codexTokenArgs, codexUsage, cursorAdapter, grokAdapter, opencodeAdapter,
  readClaudeIdentity, readCodexIdentity,
} from "../src/accounts/index.js"
import type { Account, AuthAdapter } from "../src/accounts/index.js"

const dirs: string[] = []
afterEach(async () => { await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true }))) })
async function scratch() { const dir = await mkdtemp(join(tmpdir(), "accounts-adapters-")); dirs.push(dir); return dir }
const acct = (agent: string, method: Account["method"], extra: Partial<Account> = {}): Account => ({ id: `${agent}-x`, agent, method, createdAt: "2026-10-04T00:00:00Z", ...extra })
const HOME = "/accounts/home"
const ctx = (secret?: string) => ({ ...(secret !== undefined ? { secret } : {}), home: async () => HOME })
const run = (adapter: AuthAdapter, account: Account, secret?: string) => adapter.materialize(account, ctx(secret))
const jwt = (payload: object) => `e30.${Buffer.from(JSON.stringify(payload)).toString("base64url")}.sig`

test("claude: system / token / api_key / subscription", async () => {
  expect(await run(claudeAdapter, acct("claude", "system"))).toEqual({ env: {} })
  expect(await run(claudeAdapter, acct("claude", "token"), "sk-ant-oat")).toEqual({ env: { CLAUDE_CODE_OAUTH_TOKEN: "sk-ant-oat" }, unset: ["ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"] })
  expect(await run(claudeAdapter, acct("claude", "api_key"), "sk-ant-api")).toEqual({ env: { ANTHROPIC_API_KEY: "sk-ant-api" }, unset: ["CLAUDE_CODE_OAUTH_TOKEN"] })
  expect(await run(claudeAdapter, acct("claude", "subscription"))).toEqual({ env: { CLAUDE_CONFIG_DIR: HOME }, unset: ["CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN"] })
  await expect(run(claudeAdapter, acct("claude", "token"))).rejects.toMatchObject({ code: "account_secret_missing" })
})

test("codex: system / api_key / token (provider args) / subscription; expired token fails", async () => {
  expect(await run(codexAdapter, acct("codex", "system"))).toEqual({ env: {} })
  expect(await run(codexAdapter, acct("codex", "api_key"), "sk-o")).toEqual({ env: { CODEX_API_KEY: "sk-o", OPENAI_API_KEY: "sk-o" } })
  expect(await run(codexAdapter, acct("codex", "subscription"))).toEqual({ env: { CODEX_HOME: HOME }, unset: ["CODEX_API_KEY", "OPENAI_API_KEY"] })
  const access = jwt({ exp: Math.floor(Date.now() / 1000) + 3600 })
  const token = await run(codexAdapter, acct("codex", "token"), JSON.stringify({ access_token: access, account_id: "acc-1" }))
  expect(token.env).toEqual({ SUPERMUX_CODEX_ACCESS_TOKEN: access })
  expect(token.unset).toEqual(["CODEX_API_KEY", "OPENAI_API_KEY", "OPENAI_BASE_URL"])
  expect(token.args).toEqual(codexTokenArgs("acc-1"))
  expect(token.args).toEqual([
    "-c", "model_provider=supermux_chatgpt",
    "-c", 'model_providers.supermux_chatgpt.name="ChatGPT (supermux)"',
    "-c", 'model_providers.supermux_chatgpt.base_url="https://chatgpt.com/backend-api/codex"',
    "-c", 'model_providers.supermux_chatgpt.env_key="SUPERMUX_CODEX_ACCESS_TOKEN"',
    "-c", 'model_providers.supermux_chatgpt.wire_api="responses"',
    "-c", "model_providers.supermux_chatgpt.requires_openai_auth=false",
    "-c", 'model_providers.supermux_chatgpt.http_headers={ "chatgpt-account-id" = "acc-1" }',
  ])
  const expiredJwt = jwt({ exp: Math.floor(Date.now() / 1000) - 10 })
  await expect(run(codexAdapter, acct("codex", "token"), JSON.stringify({ access_token: expiredJwt, account_id: "acc-1" }))).rejects.toMatchObject({ code: "account_expired" })
  await expect(run(codexAdapter, acct("codex", "token"), JSON.stringify({ access_token: "opaque", account_id: "acc-1", expires_at: "2020-01-01T00:00:00Z" }))).rejects.toMatchObject({ code: "account_expired" })
  await expect(run(codexAdapter, acct("codex", "token"), JSON.stringify({ access_token: "opaque", account_id: 'x" = 1' }))).rejects.toMatchObject({ code: "invalid_input" })
  expect(codexAdapter.secretIdentity!(JSON.stringify({ access_token: "a", account_id: "acc-9" }))).toEqual({ accountId: "acc-9" })
  expect(codexAdapter.secretRotates!(JSON.stringify({ access_token: "a", account_id: "acc-9" }))).toBe(false)
  expect(codexAdapter.secretRotates!(JSON.stringify({ access_token: "a", refresh_token: "r", account_id: "acc-9" }))).toBe(true)
})

test("cursor, grok, opencode", async () => {
  const base = await scratch()
  const home = async () => base
  expect(await run(cursorAdapter, acct("cursor", "api_key"), "ck")).toEqual({ env: { CURSOR_API_KEY: "ck" } })
  expect(await cursorAdapter.materialize(acct("cursor", "subscription"), { home })).toEqual({ env: { XDG_CONFIG_HOME: join(base, "xdg") }, unset: ["CURSOR_API_KEY", "CURSOR_AUTH_TOKEN"] })
  await expect(run(cursorAdapter, acct("cursor", "token"), "t")).rejects.toMatchObject({ code: "unsupported_operation" })
  expect(await run(grokAdapter, acct("grok", "api_key"), "xk")).toEqual({ env: { XAI_API_KEY: "xk" } })
  expect(await run(grokAdapter, acct("grok", "subscription"))).toEqual({ env: { GROK_AUTH_PATH: join(HOME, "auth.json") }, unset: ["XAI_API_KEY"] })
  const oc = await run(opencodeAdapter, acct("opencode", "api_key", { id: "oc1", provider: "anthropic" }), "sk-a")
  expect(JSON.parse(oc.env.OPENCODE_AUTH_CONTENT!)).toEqual({
    version: 2,
    accounts: { oc1: { id: "oc1", serviceID: "anthropic", description: "supermux", credential: { type: "api", key: "sk-a" } } },
    active: { anthropic: "oc1" },
    anthropic: { type: "api", key: "sk-a" },
  })
  expect(() => opencodeAdapter.validate!({ agent: "opencode", method: "api_key", secret: "k" })).toThrow("provider")
  await expect(run(opencodeAdapter, acct("opencode", "subscription"))).rejects.toMatchObject({ code: "unsupported_operation" })
})

test("identity readers: claude .claude.json oauthAccount, codex auth.json account_id + id_token email", async () => {
  const base = await scratch()
  await writeFile(join(base, ".claude.json"), JSON.stringify({ oauthAccount: { emailAddress: "a@x.io", organizationUuid: "org-1", accountUuid: "acc-1", displayName: "A" } }))
  expect(await readClaudeIdentity(base)).toEqual({ email: "a@x.io", org: "org-1", accountId: "acc-1" })
  const codex = join(base, "codex")
  await mkdir(codex)
  await writeFile(join(codex, "auth.json"), JSON.stringify({ tokens: { account_id: "chatgpt-1", id_token: jwt({ email: "b@x.io" }) } }))
  expect(await readCodexIdentity(codex)).toEqual({ email: "b@x.io", accountId: "chatgpt-1" })
  expect(await readCodexIdentity(join(base, "missing"))).toBeUndefined()
  expect(await readClaudeIdentity(join(base, "missing"))).toBeUndefined()
})

test("usage parsing of real capture shapes; unknown shapes yield no windows", () => {
  const claude = claudeUsage({ status: "allowed", resetsAt: 1791064800, rateLimitType: "five_hour", overageStatus: "rejected", isUsingOverage: false, unifiedWindows: { five_hour: { utilization: 0.06, resetsAt: 1791064800 }, seven_day: { utilization: 0.63, resetsAt: 1791201600 } } })
  expect(claude).toEqual([
    { name: "five_hour", usedPercent: 6, resetsAt: new Date(1791064800_000) },
    { name: "seven_day", usedPercent: 63, resetsAt: new Date(1791201600_000) },
  ])
  expect(claudeUsage({ status: "rejected", resetsAt: 1791064800, rateLimitType: "five_hour" })).toEqual([{ name: "five_hour", usedPercent: 100, resetsAt: new Date(1791064800_000) }])
  expect(claudeUsage({ status: "rejected", rateLimitType: "seven_day", unifiedWindows: { seven_day: { utilization: 0.97, resetsAt: 1791201600 } } })[0]!.usedPercent).toBe(100)
  const codex = codexUsage({ limitId: "codex", primary: { usedPercent: 2, windowDurationMins: 300, resetsAt: 1791069947 }, secondary: { usedPercent: 1, windowDurationMins: 10080, resetsAt: 1791621426 }, planType: "plus", rateLimitReachedType: null })
  expect(codex).toEqual([
    { name: "300m", usedPercent: 2, resetsAt: new Date(1791069947_000) },
    { name: "10080m", usedPercent: 1, resetsAt: new Date(1791621426_000) },
  ])
  expect(codexUsage({ primary: { usedPercent: 40, windowDurationMins: 300, resetsAt: 1791069947 }, rateLimitReachedType: "primary" }).some(w => w.usedPercent === 100)).toBe(true)
  for (const shape of [undefined, null, "x", 3, {}, { foo: 1 }, { unifiedWindows: { a: { utilization: "x" } } }]) {
    expect(claudeUsage(shape)).toEqual([])
    expect(codexUsage(shape)).toEqual([])
  }
})
