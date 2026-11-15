import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { AccountRegistry, UsageStore, memoryVault } from "../../../packages/supermux-core/src/accounts/index.js"
import { ACCOUNT_AGENTS, AccountsApiError, BrokerAccounts } from "./broker-accounts"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

function setup(sessions: Array<{ id: string; agent: string; account: string }>, rehome?: (id: string, account: string) => Promise<void>, removed: string[] = []) {
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
    onRemoved: (id) => { removed.push(id) },
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

test("a removal moves sessions to a seat in the removed account's org first, like the auto-switch (#159)", async () => {
  const sessions = [{ id: "s1", agent: "claude", account: "a" }]
  const { registry, usage, accounts, moves } = setup(sessions)
  await registry.add({ id: "a", agent: "claude", method: "token", secret: "t-a", identity: { org: "org-1" } })
  await registry.add({ id: "other", agent: "claude", method: "token", secret: "t-o", identity: { org: "org-2" } })
  await registry.add({ id: "seat", agent: "claude", method: "token", secret: "t-s", identity: { org: "org-1" }, label: "Seat" })
  usage.set("other", [{ name: "five_hour", usedPercent: 0, resetsAt: soon() }])
  usage.set("seat", [{ name: "five_hour", usedPercent: 70, resetsAt: soon() }])
  expect(await accounts.removalPlan("a")).toEqual({ account: "a", sessions: 1, to: "seat", toLabel: "Seat", why: "same_org" })
  // The org's seat full: the most room anywhere.
  usage.set("seat", [{ name: "five_hour", usedPercent: 100, resetsAt: soon() }])
  expect((await accounts.removalPlan("a")).to).toBe("other")
  usage.set("seat", [{ name: "five_hour", usedPercent: 70, resetsAt: soon() }])
  await accounts.remove("a")
  expect(moves).toEqual([["s1", "seat"]])
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

test("removalPlan says how many sessions a removal moves and where, without moving them (#106)", async () => {
  const sessions = [{ id: "s1", agent: "claude", account: "a" }, { id: "s2", agent: "claude", account: "a" }]
  const { registry, usage, accounts, moves } = setup(sessions)
  await registry.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  await registry.add({ id: "b", agent: "claude", method: "token", secret: "t-b", label: "Work" })
  usage.set("claude:system", [{ name: "five_hour", usedPercent: 100, resetsAt: soon() }])
  usage.set("b", [{ name: "five_hour", usedPercent: 10, resetsAt: soon() }])
  expect(await accounts.removalPlan("a")).toEqual({ account: "a", sessions: 2, to: "b", toLabel: "Work", why: "most_room" })
  expect(moves).toEqual([])
  await expect(accounts.removalPlan("nope")).rejects.toMatchObject({ status: 404 })
})

test("a removal tells onRemoved once the account is gone, not when it is refused (#106 + #92's waits)", async () => {
  const removed: string[] = []
  const { registry, accounts } = setup([], undefined, removed)
  await registry.add({ id: "a", agent: "claude", method: "token", secret: "t-a" })
  await accounts.remove("a")
  await expect(accounts.remove("a")).rejects.toMatchObject({ status: 404 })
  expect(removed).toEqual(["a"])
})

// #146 team seats: a chat near its limit needn't compact when auto-switch would move it to another seat of the SAME
// Anthropic org with room (the prompt cache is per org, so it stays warm there). The org is the account's identity
// (accountOrg, #159), which the registry reads from a login's .claude.json.
test("#146: a same-org seat below the threshold can take over; another org, a full seat or unknown usage cannot", async () => {
  const { registry, usage, accounts } = setup([])
  const orgs: Record<string, string> = { a: "org-team", b: "org-team", c: "org-other", d: "org-team" }
  for (const [id, org] of Object.entries(orgs)) await registry.add({ id, agent: "claude", method: "token", secret: `t-${id}`, identity: { org } })
  usage.set("a", [{ name: "five_hour", usedPercent: 90, resetsAt: soon() }])
  usage.set("c", [{ name: "five_hour", usedPercent: 5, resetsAt: soon() }])
  // b (same org) has no usage known yet, d (same org) is full: nobody can take over.
  usage.set("d", [{ name: "five_hour", usedPercent: 100, resetsAt: soon() }])
  expect(await accounts.sameOrgSeatHasRoom("a", 85)).toBe(false)
  // b now has room below a's threshold.
  usage.set("b", [{ name: "five_hour", usedPercent: 40, resetsAt: soon() }, { name: "seven_day", usedPercent: 50, resetsAt: soon() }])
  expect(await accounts.sameOrgSeatHasRoom("a", 85)).toBe(true)
  // A seat whose WEEK is nearly gone has no room either.
  usage.set("b", [{ name: "five_hour", usedPercent: 40, resetsAt: soon() }, { name: "seven_day", usedPercent: 92, resetsAt: soon() }])
  expect(await accounts.sameOrgSeatHasRoom("a", 85)).toBe(false)
  // An account with no known org (the system login here has no .claude.json) never counts as a team.
  expect(await accounts.sameOrgSeatHasRoom("claude:system", 85)).toBe(false)
})
