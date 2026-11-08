// OpenCode Go plan limits: GET https://opencode.ai/zen/go/v1/usage (the source upstream opencode 2.x reads in
// packages/core/src/usage-limits.ts). Fixtures follow that schema: `{ usage: { rolling|weekly|monthly:
// { status: "ok" | "rate-limited", percent, resetsAt } } }`; 403 = the workspace has no Go plan.
import { afterEach, expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { mkdtempSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fetchOpenCodePlan, type FetchLike } from "../src/core/usage/opencode-go"
import { fetchOpenCodeUsage } from "../src/core/usage"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })
function dataDir() { const d = mkdtempSync(join(tmpdir(), "oc-go-")); dirs.push(d); return d }
const NOW = Date.parse("2026-11-08T10:00:00Z")

const GO_BODY = {
  usage: {
    rolling: { status: "ok", percent: 3, resetsAt: "2026-11-08T12:44:55.176Z" },
    weekly: { status: "rate-limited", percent: 100, resetsAt: "2026-11-09T00:00:00.000Z" },
    monthly: { status: "ok", percent: 41.5, resetsAt: "2026-12-01T02:13:38.176Z" },
  },
}

type Call = { url: string; headers: Record<string, string> }
function fakeFetch(status: number, body: unknown, calls: Call[] = []): FetchLike {
  return async (url, init) => {
    calls.push({ url: String(url), headers: Object.fromEntries(new Headers(init?.headers).entries()) })
    return new Response(typeof body === "string" ? body : JSON.stringify(body), { status, headers: { "content-type": "application/json" } })
  }
}

function withAuth(dir: string, auth: Record<string, unknown>) { writeFileSync(join(dir, "auth.json"), JSON.stringify(auth)) }
function withConsole(dir: string, expiry: number, orgId: string | null = "wrk_1") {
  const db = new Database(join(dir, "opencode.db"))
  db.run(`CREATE TABLE account (id text PRIMARY KEY, email text NOT NULL, url text NOT NULL, access_token text NOT NULL,
    refresh_token text NOT NULL, token_expiry integer, time_created integer NOT NULL, time_updated integer NOT NULL)`)
  db.run(`CREATE TABLE account_state (id integer PRIMARY KEY NOT NULL, active_account_id text, active_org_id text)`)
  db.run("INSERT INTO account VALUES ('acc_1','me@x.io','https://opencode.ai/console','console-token','rt',?,1,2)", [expiry])
  db.run("INSERT INTO account_state VALUES (1,'acc_1',?)", [orgId])
  db.close()
}
const paths = (dir: string) => ({ dbPath: join(dir, "opencode.db"), authPath: join(dir, "auth.json") })

test("an OpenCode Go key: the three meters, with percent, limited and reset", async () => {
  const dir = dataDir()
  withAuth(dir, { "opencode-go": { type: "api", key: "oc_sk_go" }, opencode: { type: "api", key: "oc_sk_zen" } })
  const calls: Call[] = []
  const plan = await fetchOpenCodePlan({ ...paths(dir), fetch: fakeFetch(200, GO_BODY, calls), now: () => NOW })
  expect(plan).toEqual({
    status: "active",
    via: "api_key",
    go: {
      rolling: { percent: 3, limited: false, resetsAtIso: "2026-11-08T12:44:55.176Z" },
      weekly: { percent: 100, limited: true, resetsAtIso: "2026-11-09T00:00:00.000Z" },
      monthly: { percent: 41.5, limited: false, resetsAtIso: "2026-12-01T02:13:38.176Z" },
    },
  })
  expect(calls).toHaveLength(1)
  expect(calls[0].url).toBe("https://opencode.ai/zen/go/v1/usage")
  expect(calls[0].headers.authorization).toBe("Bearer oc_sk_go")
})

test("a Console sign-in is the Go login: its token and workspace, ahead of any key", async () => {
  const dir = dataDir()
  withAuth(dir, { "opencode-go": { type: "api", key: "oc_sk_go" } })
  withConsole(dir, NOW + 3_600_000)
  const calls: Call[] = []
  const plan = await fetchOpenCodePlan({ ...paths(dir), fetch: fakeFetch(200, GO_BODY, calls), now: () => NOW })
  expect(plan.status).toBe("active")
  expect(plan.via).toBe("console")
  expect(plan.email).toBe("me@x.io")
  expect(calls[0].headers.authorization).toBe("Bearer console-token")
  expect(calls[0].headers["x-opencode-org-id"]).toBe("wrk_1")
})

test("an expired Console token falls back to a key; with no key it says the sign-in expired", async () => {
  const dir = dataDir()
  withConsole(dir, NOW - 1)
  const calls: Call[] = []
  expect(await fetchOpenCodePlan({ ...paths(dir), fetch: fakeFetch(200, GO_BODY, calls), now: () => NOW }))
    .toEqual({ status: "expired", go: null, via: "console", email: "me@x.io" })
  expect(calls).toHaveLength(0)
  withAuth(dir, { opencode: { type: "api", key: "oc_sk_zen" } })
  const plan = await fetchOpenCodePlan({ ...paths(dir), fetch: fakeFetch(200, GO_BODY, calls), now: () => NOW })
  expect(plan.status).toBe("active")
  expect(calls[0].headers.authorization).toBe("Bearer oc_sk_zen")
})

test("403 = no Go plan on this login", async () => {
  const dir = dataDir()
  withAuth(dir, { "opencode-go": { type: "api", key: "oc_sk_go" } })
  const body = { type: "error", error: { type: "EntitlementError", message: "OpenCode Go subscription required." } }
  expect(await fetchOpenCodePlan({ ...paths(dir), fetch: fakeFetch(403, body), now: () => NOW }))
    .toEqual({ status: "none", go: null, via: "api_key" })
})

test("401 = the key or sign-in was refused", async () => {
  const dir = dataDir()
  withAuth(dir, { "opencode-go": { type: "api", key: "oc_sk_go" } })
  const body = { type: "error", error: { type: "AuthError", message: "Unauthorized" } }
  expect(await fetchOpenCodePlan({ ...paths(dir), fetch: fakeFetch(401, body), now: () => NOW }))
    .toEqual({ status: "expired", go: null, via: "api_key" })
})

test("no OpenCode key or sign-in: signed out, no request", async () => {
  const dir = dataDir()
  withAuth(dir, { anthropic: { type: "api", key: "sk-ant" } })
  const calls: Call[] = []
  expect(await fetchOpenCodePlan({ ...paths(dir), fetch: fakeFetch(200, GO_BODY, calls), now: () => NOW }))
    .toEqual({ status: "signed_out", go: null })
  expect(calls).toHaveLength(0)
})

test("a server error or an unexpected body: unavailable, with why", async () => {
  const dir = dataDir()
  withAuth(dir, { "opencode-go": { type: "api", key: "oc_sk_go" } })
  const bad = await fetchOpenCodePlan({ ...paths(dir), fetch: fakeFetch(500, "oops"), now: () => NOW })
  expect(bad).toMatchObject({ status: "unavailable", go: null, via: "api_key" })
  expect(bad.error).toContain("500")
  const odd = await fetchOpenCodePlan({ ...paths(dir), fetch: fakeFetch(200, { usage: { rolling: {} } }), now: () => NOW })
  expect(odd).toMatchObject({ status: "unavailable", go: null })
  const thrown = await fetchOpenCodePlan({ ...paths(dir), fetch: async () => { throw new Error("offline") }, now: () => NOW })
  expect(thrown).toMatchObject({ status: "unavailable", error: "offline" })
})

test("fetchOpenCodeUsage carries the plan beside the local totals, and reports a plan with no local store", async () => {
  const dir = dataDir()
  const plan = { status: "none" as const, go: null, via: "api_key" as const }
  const usage = await fetchOpenCodeUsage(join(dir, "opencode.db"), { plan: async () => plan })
  expect(usage).toMatchObject({ sessions: 0, messages: 0, totalCostUsd: 0, plan })
  expect(await fetchOpenCodeUsage(join(dir, "opencode.db"))).toBeNull()
})
