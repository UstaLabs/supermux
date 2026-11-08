import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { AccountRegistry, UsageStore, memoryVault } from "../../../packages/supermux-core/src/accounts/index.js"
import { ACCOUNT_AGENTS, AccountsApiError, BrokerAccounts } from "./broker-accounts"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

function setup(sessions: Array<{ id: string; agent: string; account: string }>, rehome?: (id: string, account: string) => Promise<void>) {
  const base = mkdtempSync(join(tmpdir(), "mux-broker-accounts-"))
  dirs.push(base)
  const registry = new AccountRegistry(join(base, "state"), [...ACCOUNT_AGENTS], { vault: memoryVault(), homes: { claudeRoot: join(base, "claude-root") } })
  const usage = new UsageStore(join(registry.directory, "usage.json"))
  const moves: Array<[string, string]> = []
  const accounts = new BrokerAccounts({
    registry, usage,
    coreFor: () => { throw new Error("not used") },
    broadcast: () => {},
    sessionsOn: (account) => sessions.filter((s) => s.account === account),
    rehome: async (id, account) => {
      await rehome?.(id, account)
      moves.push([id, account])
      const s = sessions.find((x) => x.id === id)!
      s.account = account
    },
  })
  return { registry, usage, accounts, moves }
}

const soon = () => new Date(Date.now() + 3_600_000)

test("removing an account moves its sessions to the account Auto would pick, then removes it (#106)", async () => {
  const sessions = [{ id: "s1", agent: "claude", account: "a" }, { id: "s2", agent: "claude", account: "a" }, { id: "s3", agent: "claude", account: "b" }]
  const { registry, usage, accounts, moves } = setup(sessions)
  await registry.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  await registry.add({ id: "b", agent: "claude", method: "token", secret: "t-b" })
  usage.set("claude:system", [{ name: "five_hour", usedPercent: 100, resetsAt: soon() }])
  usage.set("a", [{ name: "five_hour", usedPercent: 0, resetsAt: soon() }])
  usage.set("b", [{ name: "five_hour", usedPercent: 10, resetsAt: soon() }])
  await accounts.remove("a")
  expect(moves).toEqual([["s1", "b"], ["s2", "b"]])
  expect(await registry.get("a")).toBeUndefined()
})

test("with no other account with room, the sessions go back to the system login (#106)", async () => {
  const sessions = [{ id: "s1", agent: "claude", account: "a" }]
  const { registry, usage, accounts, moves } = setup(sessions)
  await registry.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  await registry.add({ id: "dead", agent: "claude", method: "token", secret: "t-d" })
  usage.markUnavailable("dead", "expired", new Date(), "account_expired")
  await accounts.remove("a")
  expect(moves).toEqual([["s1", "claude:system"]])
})

test("a session that cannot move (busy) blocks the removal: the account stays (#106)", async () => {
  const sessions = [{ id: "s1", agent: "claude", account: "a" }]
  const { registry, accounts } = setup(sessions, async () => { throw new AccountsApiError(409, "busy", "session_busy") })
  await registry.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  await expect(accounts.remove("a")).rejects.toMatchObject({ status: 409, code: "session_busy" })
  expect(await registry.get("a")).toBeDefined()
})
