import { afterEach, expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { existsSync, mkdtempSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { openCodeConsoleFingerprint, readOpenCodeConsole } from "./console"
import { loginSpawnCommand } from "./auth"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

/** opencode 1.18's own tables for a Console sign-in (`opencode console login`). */
function consoleDb(rows: { account?: Record<string, unknown>; state?: { active_account_id: string | null; active_org_id: string | null } } = {}) {
  const dir = mkdtempSync(join(tmpdir(), "oc-console-"))
  dirs.push(dir)
  const path = join(dir, "opencode.db")
  const db = new Database(path)
  db.run(`CREATE TABLE account (id text PRIMARY KEY, email text NOT NULL, url text NOT NULL, access_token text NOT NULL,
    refresh_token text NOT NULL, token_expiry integer, time_created integer NOT NULL, time_updated integer NOT NULL)`)
  db.run(`CREATE TABLE account_state (id integer PRIMARY KEY NOT NULL, active_account_id text, active_org_id text)`)
  if (rows.account) {
    const a = { id: "acc_1", email: "me@x.io", url: "https://opencode.ai/console", access_token: "at-1", refresh_token: "rt-1",
      token_expiry: 2_000_000_000_000, time_created: 1, time_updated: 5, ...rows.account }
    db.run("INSERT INTO account VALUES (?,?,?,?,?,?,?,?)", [a.id, a.email, a.url, a.access_token, a.refresh_token, a.token_expiry, a.time_created, a.time_updated] as any)
  }
  if (rows.state) db.run("INSERT INTO account_state VALUES (1, ?, ?)", [rows.state.active_account_id, rows.state.active_org_id])
  db.close()
  return path
}

test("reads the active Console sign-in: email, server, token and workspace", () => {
  const path = consoleDb({ account: {}, state: { active_account_id: "acc_1", active_org_id: "wrk_9" } })
  expect(readOpenCodeConsole(path)).toEqual({
    accountId: "acc_1", email: "me@x.io", url: "https://opencode.ai/console", accessToken: "at-1",
    tokenExpiry: 2_000_000_000_000, orgId: "wrk_9",
  })
})

test("no Console sign-in: a missing store, an older store without the tables, or no active account", () => {
  expect(readOpenCodeConsole("/nonexistent/opencode.db")).toBeNull()
  const dir = mkdtempSync(join(tmpdir(), "oc-console-"))
  dirs.push(dir)
  const old = join(dir, "opencode.db")
  new Database(old).close()
  expect(readOpenCodeConsole(old)).toBeNull()
  expect(readOpenCodeConsole(consoleDb({ account: {} }))).toBeNull()
  expect(openCodeConsoleFingerprint(consoleDb())).toBeNull()
})

test("the fingerprint changes with a new sign-in and never shows the token", () => {
  const a = openCodeConsoleFingerprint(consoleDb({ account: {}, state: { active_account_id: "acc_1", active_org_id: null } }))
  const b = openCodeConsoleFingerprint(consoleDb({ account: { access_token: "at-2", time_updated: 9 }, state: { active_account_id: "acc_1", active_org_id: null } }))
  expect(a).not.toBeNull()
  expect(b).not.toBeNull()
  expect(a).not.toBe(b)
  expect(a!).not.toContain("at-1")
})

test("loginSpawnCommand runs `opencode console login` with no host browser, and cleans up", () => {
  const prior = process.env.DISPLAY
  process.env.DISPLAY = ":0"
  try {
    const spec = loginSpawnCommand("linux")!
    expect(spec.args).toEqual(["console", "login"])
    expect(spec.cmd.endsWith("opencode")).toBe(true)
    expect(spec.env?.DISPLAY).toBeUndefined()
    expect(spec.env?.BROWSER).toBe("true")
    expect(spec.cwd && existsSync(spec.cwd)).toBe(true)
    spec.cleanup!()
    expect(existsSync(spec.cwd!)).toBe(false)
  } finally {
    if (prior === undefined) delete process.env.DISPLAY
    else process.env.DISPLAY = prior
  }
})
