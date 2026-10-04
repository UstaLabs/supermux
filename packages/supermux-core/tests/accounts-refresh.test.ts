import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { mkdir, mkdtemp, readdir, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, memoryVault } from "../src/index.js"
import { AccountRegistry, CODEX_OAUTH_CLIENT_ID, CODEX_OAUTH_TOKEN_URL, CODEX_TOKEN_ENV } from "../src/accounts/index.js"
import type { FetchLike } from "../src/accounts/index.js"
import type { AgentDriver, AgentRuntime, CoreEvent, DriverContext } from "../src/types.js"
import { TEST_LIMITS, nextId } from "./helpers.js"

setDefaultTimeout(20_000)
const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  await Promise.all(cores.splice(0).map(core => core.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})
async function scratch() { const dir = await mkdtemp(join(tmpdir(), "accounts-refresh-")); dirs.push(dir); return dir }

const b64 = (value: unknown) => Buffer.from(JSON.stringify(value)).toString("base64url")
const jwt = (payload: Record<string, unknown>) => `${b64({ alg: "none" })}.${b64(payload)}.sig`
const inSeconds = (s: number) => Math.floor(Date.now() / 1000) + s
const token = (access: string, refresh: string | undefined, exp: number) => JSON.stringify({ access_token: jwt({ exp, n: access }), ...(refresh ? { refresh_token: refresh } : {}), account_id: "acct-1" })
const accessName = (secret: string) => JSON.parse(Buffer.from(JSON.parse(secret).access_token.split(".")[1], "base64url").toString()).n

/** Fake OAuth endpoint: replies in order; records form bodies. */
function endpoint(replies: Array<{ status: number; body: unknown; delayMs?: number }>) {
  const calls: Array<{ url: string; form: Record<string, string> }> = []
  const fetch: FetchLike = async (url, init) => {
    calls.push({ url, form: Object.fromEntries(new URLSearchParams(init.body)) })
    const reply = replies[Math.min(calls.length - 1, replies.length - 1)]!
    if (reply.delayMs) await new Promise(resolve => setTimeout(resolve, reply.delayMs))
    return { ok: reply.status < 400, status: reply.status, text: async () => JSON.stringify(reply.body) }
  }
  return { fetch, calls }
}

async function registry(fetch: FetchLike, vault = memoryVault()) {
  const base = await scratch()
  return { registry: new AccountRegistry(join(base, "state"), ["codex"], { vault, fetch, homes: { codexRoot: join(base, "codex-root") } }), vault, base }
}

test("an access token expiring within 5 min is refreshed; the rotated refresh token is stored before use", async () => {
  const { fetch, calls } = endpoint([{ status: 200, body: { access_token: jwt({ exp: inSeconds(3600), n: "new" }), refresh_token: "r2", id_token: jwt({ email: "a@x" }) } }])
  const { registry: r, vault } = await registry(fetch)
  await r.add({ id: "t", agent: "codex", method: "token", secret: token("old", "r1", inSeconds(120)) })
  const materialized = await r.materialize((await r.get("t"))!)
  expect(calls).toEqual([{ url: CODEX_OAUTH_TOKEN_URL, form: { grant_type: "refresh_token", client_id: CODEX_OAUTH_CLIENT_ID, refresh_token: "r1" } }])
  const stored = JSON.parse((await vault.get("t"))!)
  expect(stored).toMatchObject({ refresh_token: "r2", account_id: "acct-1", expires_at: expect.any(Number) })
  expect(stored.id_token).toBeDefined()
  expect(materialized.env[CODEX_TOKEN_ENV]).toBe(stored.access_token)
  expect(accessName(await vault.get("t") as string)).toBe("new")
})

test("a reply without refresh_token keeps the old one; a fresh token is not refreshed", async () => {
  const { fetch, calls } = endpoint([{ status: 200, body: { access_token: jwt({ exp: inSeconds(3600), n: "new" }) } }])
  const { registry: r, vault } = await registry(fetch)
  await r.add({ id: "t", agent: "codex", method: "token", secret: token("old", "r1", inSeconds(60)) })
  await r.materialize((await r.get("t"))!)
  expect(JSON.parse((await vault.get("t"))!).refresh_token).toBe("r1")
  await r.materialize((await r.get("t"))!)
  expect(calls.length).toBe(1)
  // minValidityMs widens the window.
  await r.materialize((await r.get("t"))!, { minValidityMs: 2 * 3600_000 })
  expect(calls.length).toBe(2)
})

test("concurrent materialize calls share one refresh", async () => {
  const { fetch, calls } = endpoint([{ status: 200, body: { access_token: jwt({ exp: inSeconds(3600), n: "new" }), refresh_token: "r2" }, delayMs: 100 }])
  const { registry: r } = await registry(fetch)
  await r.add({ id: "t", agent: "codex", method: "token", secret: token("old", "r1", inSeconds(10)) })
  const account = (await r.get("t"))!
  const results = await Promise.all(Array.from({ length: 6 }, () => r.materialize(account)))
  expect(calls.length).toBe(1)
  expect(new Set(results.map(m => m.env[CODEX_TOKEN_ENV])).size).toBe(1)
})

test("server errors: reuse/expired → account_expired with the code; others → account_refresh_failed; no secrets in messages", async () => {
  for (const [reply, code, detail] of [
    [{ status: 401, body: { error: { code: "refresh_token_reused", message: "Your refresh token has already been used" } } }, "account_expired", "refresh_token_reused"],
    [{ status: 400, body: { error: "invalid_grant", error_description: "bad" } }, "account_expired", "invalid_grant"],
    [{ status: 503, body: { error: { code: "server_busy" } } }, "account_refresh_failed", "server_busy"],
    [{ status: 200, body: { nothing: true } }, "account_refresh_failed", "no access_token"],
  ] as const) {
    const { fetch } = endpoint([reply])
    const { registry: r, vault } = await registry(fetch)
    await r.add({ id: "t", agent: "codex", method: "token", secret: token("old", "secret-refresh", inSeconds(30)) })
    const error = await r.materialize((await r.get("t"))!).then(() => undefined, e => e)
    expect(error).toMatchObject({ code })
    expect(error.message).toContain(detail)
    expect(error.message).not.toContain("secret-refresh")
    expect(JSON.parse((await vault.get("t"))!).refresh_token).toBe("secret-refresh")
  }
  const failing: FetchLike = async () => { throw new Error("ECONNRESET") }
  const { registry: r } = await registry(failing)
  await r.add({ id: "t", agent: "codex", method: "token", secret: token("old", "r", inSeconds(30)) })
  await expect(r.materialize((await r.get("t"))!)).rejects.toMatchObject({ code: "account_refresh_failed" })
})

test("an expired token without a refresh token stays account_expired (no fetch)", async () => {
  const { fetch, calls } = endpoint([{ status: 200, body: {} }])
  const { registry: r } = await registry(fetch)
  await r.add({ id: "t", agent: "codex", method: "token", secret: token("old", undefined, inSeconds(-10)) })
  await expect(r.materialize((await r.get("t"))!)).rejects.toMatchObject({ code: "account_expired" })
  expect(calls.length).toBe(0)
})

test("lockfile: a stale lock (dead pid) is taken over; a live holder that refreshed first means no second refresh", async () => {
  const { fetch, calls } = endpoint([{ status: 200, body: { access_token: jwt({ exp: inSeconds(3600), n: "mine" }), refresh_token: "r2" } }])
  const { registry: r, vault, base } = await registry(fetch)
  const locks = join(base, "state", "accounts", "locks")
  await mkdir(locks, { recursive: true })
  await r.add({ id: "t", agent: "codex", method: "token", secret: token("old", "r1", inSeconds(30)) })
  await writeFile(join(locks, "t.lock"), JSON.stringify({ pid: 2 ** 22 + 12345, nonce: "x" }))
  await r.materialize((await r.get("t"))!)
  expect(calls.length).toBe(1)
  expect(await readdir(locks)).toEqual([])

  // Another (live) process holds the lock, refreshes and releases it.
  await vault.put("t", token("old2", "r3", inSeconds(30)))
  await writeFile(join(locks, "t.lock"), JSON.stringify({ pid: process.pid, nonce: "other" }))
  setTimeout(async () => {
    await vault.put("t", token("theirs", "r4", inSeconds(3600)))
    await rm(join(locks, "t.lock"))
  }, 200)
  const materialized = await r.materialize((await r.get("t"))!)
  expect(calls.length).toBe(1)
  expect(accessName(JSON.stringify({ access_token: materialized.env[CODEX_TOKEN_ENV] }))).toBe("theirs")
})

/** Codex-like fake driver: prompts named "wait:<ms>" take that long. */
function fakeCodex() {
  const contexts: DriverContext[] = []
  const driver: AgentDriver = {
    id: "codex",
    async open(context) {
      contexts.push(context)
      const runtime: AgentRuntime = {
        agentSessionId: context.resumeId ?? "thread-1",
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt(content) {
          const text = content[0]?.type === "text" ? content[0].text : ""
          if (text.startsWith("wait:")) await new Promise(resolve => setTimeout(resolve, Number(text.slice(5))))
          return { stopReason: "end_turn" }
        },
        interrupt: async () => {},
        close: async () => {},
        normalize: () => [],
      }
      return runtime
    },
  }
  return { driver, contexts }
}

test("a token session is reopened (idle, same account and native id) shortly before expiry: account.refreshed, never mid-turn", async () => {
  // Opening needs 30 min of validity: the first refresh yields a token that is due again ~1.5 s later.
  const { fetch, calls } = endpoint([
    { status: 200, body: { access_token: jwt({ exp: inSeconds(30 * 60 + 2), n: "short" }), refresh_token: "r2" } },
    { status: 200, body: { access_token: jwt({ exp: inSeconds(4 * 3600), n: "long" }), refresh_token: "r3" } },
  ])
  const base = await scratch()
  const vault = memoryVault()
  const codex = fakeCodex()
  const core = createCore({ stateDirectory: join(base, "state"), agents: [codex.driver], limits: TEST_LIMITS, accounts: { vault, fetch, homes: { codexRoot: join(base, "root") } } })
  cores.push(core)
  const events: CoreEvent[] = []
  core.subscribe(event => { events.push(event) })
  await core.accounts.add({ id: "t", agent: "codex", method: "token", secret: token("old", "r1", inSeconds(600)) })
  const id = nextId()
  const session = await core.sessions.create({ id, agent: "codex", cwd: base, account: "t" })
  expect(calls.length).toBe(1)
  expect(codex.contexts[0]!.profile!.env![CODEX_TOKEN_ENV]).toBe(JSON.parse((await vault.get("t"))!).access_token)
  // A turn spans the due time: no reopen until it ends.
  const receipt = await session.send({ content: [{ type: "text", text: "wait:3000" }], whenBusy: "queue" })
  await new Promise(resolve => setTimeout(resolve, 2500))
  expect(events.some(e => e.type === "account.refreshed")).toBe(false)
  expect(codex.contexts.length).toBe(1)
  expect((await receipt.completed).status).toBe("completed")
  const deadline = Date.now() + 5000
  while (!events.some(e => e.type === "account.refreshed") && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 20))
  expect(events.find(e => e.type === "account.refreshed")).toEqual({ type: "account.refreshed", sessionId: id, account: "t" })
  expect(events.some(e => e.type === "account.switched")).toBe(false)
  expect(calls.length).toBe(2)
  expect(codex.contexts.length).toBe(2)
  expect(codex.contexts[1]).toMatchObject({ resumeId: "thread-1", account: "t" })
  expect(accessName(JSON.stringify({ access_token: codex.contexts[1]!.profile!.env![CODEX_TOKEN_ENV] }))).toBe("long")
  expect(session.snapshot().state).toBe("closed")
})
