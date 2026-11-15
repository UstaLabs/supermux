import { expect, test } from "bun:test"
import { accountOrg, earliestReset, limited, pickAccount, pickSwitchTarget, score } from "../src/accounts/index.js"
import type { Account, UsageWindow } from "../src/accounts/index.js"

const now = Date.parse("2026-10-04T12:00:00Z")
const inHours = (h: number) => new Date(now + h * 3_600_000)
const acct = (id: string, extra: Partial<Account> = {}): Account => ({ id, agent: "claude", method: "token", createdAt: "2026-10-01T00:00:00Z", ...extra })

test("score = min over windows of remaining% / hours-to-reset (≥ 1 minute)", () => {
  expect(score([], now)).toBeUndefined()
  expect(score([{ name: "5h", usedPercent: 50, resetsAt: inHours(2) }], now)).toBe(25)
  expect(score([{ name: "5h", usedPercent: 50, resetsAt: inHours(2) }, { name: "7d", usedPercent: 20, resetsAt: inHours(80) }], now)).toBe(1)
  expect(score([{ name: "5h", usedPercent: 90, resetsAt: inHours(0.001) }], now)).toBeCloseTo(600)
  expect(score([{ name: "w", usedPercent: 40 }], now)).toBe(60)
  // A window that already reset counts as unused.
  expect(score([{ name: "5h", usedPercent: 100, resetsAt: inHours(-1) }], now)).toBe(6000)
})

test("limited: ≥100% and not yet reset", () => {
  expect(limited([{ name: "a", usedPercent: 100, resetsAt: inHours(1) }], now)).toBe(true)
  expect(limited([{ name: "a", usedPercent: 100, resetsAt: inHours(-1) }], now)).toBe(false)
  expect(limited([{ name: "a", usedPercent: 99.9 }], now)).toBe(false)
})

test("pick: highest score wins, no-data after data, limited/isolated/cursor skipped", () => {
  const usage: Record<string, UsageWindow[]> = {
    low: [{ name: "5h", usedPercent: 80, resetsAt: inHours(4) }],
    high: [{ name: "5h", usedPercent: 10, resetsAt: inHours(4) }],
    full: [{ name: "5h", usedPercent: 100, resetsAt: inHours(1) }],
  }
  const get = (id: string) => usage[id]
  expect(pickAccount([acct("nodata"), acct("low"), acct("high"), acct("full")], get, now)?.id).toBe("high")
  expect(pickAccount([acct("nodata"), acct("full")], get, now)?.id).toBe("nodata")
  expect(pickAccount([acct("full"), acct("iso", { isolated: true }), acct("cur", { agent: "cursor" })], get, now)).toBeUndefined()
  expect(pickAccount([acct("first"), acct("second")], get, now)?.id).toBe("first")
})

test("accountOrg: the organization from the account's identity (Claude's oauthAccount.organizationUuid), else undefined", () => {
  expect(accountOrg(acct("a", { identity: { email: "a@x.io", org: "org-1" } }))).toBe("org-1")
  expect(accountOrg(acct("b", { identity: { email: "b@x.io" } }))).toBeUndefined()
  expect(accountOrg(acct("c"))).toBeUndefined()
})

test("switch target (#159): a seat in the current org with room wins over more room elsewhere; else the most room", () => {
  const usage: Record<string, UsageWindow[]> = {
    sameLow: [{ name: "5h", usedPercent: 80, resetsAt: inHours(4) }],
    sameMid: [{ name: "5h", usedPercent: 50, resetsAt: inHours(4) }],
    sameFull: [{ name: "5h", usedPercent: 100, resetsAt: inHours(1) }],
    otherHigh: [{ name: "5h", usedPercent: 10, resetsAt: inHours(4) }],
  }
  const get = (id: string) => usage[id]
  const org = (id: string, o: string, extra: Partial<Account> = {}) => acct(id, { identity: { org: o }, ...extra })
  const pick = (candidates: Account[], current?: string, unavailable: (id: string) => boolean = () => false) =>
    pickSwitchTarget(candidates, get, current, now, unavailable)
  const r = (p: ReturnType<typeof pick>) => p && { id: p.account.id, why: p.why }
  // Among the org's seats the policy's own order applies: the best score first.
  expect(r(pick([org("otherHigh", "o2"), org("sameLow", "o1"), org("sameMid", "o1")], "o1"))).toEqual({ id: "sameMid", why: "same_org" })
  // A same-org seat with no usage data is not known to be full: it still keeps the cache warm.
  expect(r(pick([org("otherHigh", "o2"), org("nodata", "o1")], "o1"))).toEqual({ id: "nodata", why: "same_org" })
  // No same-org seat with room (full, refused, isolated): the most room anywhere, as before.
  const noRoom = [org("sameFull", "o1"), org("refused", "o1"), org("iso", "o1", { isolated: true }), org("otherHigh", "o2"), org("sameLow", "o3")]
  expect(r(pick(noRoom, "o1", id => id === "refused"))).toEqual({ id: "otherHigh", why: "most_room" })
  // Unknown current org (API key, token without identity): the most room.
  expect(r(pick([org("sameLow", "o1"), org("otherHigh", "o2")], undefined))).toEqual({ id: "otherHigh", why: "most_room" })
  // Candidates without an org never count as the same org.
  expect(r(pick([acct("sameMid"), org("otherHigh", "o2")], "o1"))).toEqual({ id: "otherHigh", why: "most_room" })
  expect(pick([org("sameFull", "o1")], "o1")).toBeUndefined()
})

test("earliestReset: the account that frees up first, once ALL of its full windows reset", () => {
  const usage: Record<string, UsageWindow[]> = {
    a: [{ name: "5h", usedPercent: 100, resetsAt: inHours(2) }, { name: "7d", usedPercent: 100, resetsAt: inHours(30) }],
    b: [{ name: "5h", usedPercent: 100, resetsAt: inHours(3) }, { name: "7d", usedPercent: 40, resetsAt: inHours(50) }],
    c: [{ name: "5h", usedPercent: 100, resetsAt: inHours(1) }],
  }
  const get = (id: string) => usage[id]
  expect(earliestReset([acct("a"), acct("b")], get, now)).toEqual({ account: "b", at: inHours(3) })
  expect(earliestReset([acct("a"), acct("b"), acct("c")], get, now)).toEqual({ account: "c", at: inHours(1) })
})

test("earliestReset: unavailable, isolated, Cursor and reset-unknown accounts are no candidates", () => {
  const usage: Record<string, UsageWindow[]> = {
    dead: [{ name: "5h", usedPercent: 100, resetsAt: inHours(1) }],
    iso: [{ name: "5h", usedPercent: 100, resetsAt: inHours(1) }],
    unknown: [{ name: "w", usedPercent: 100 }],
    later: [{ name: "5h", usedPercent: 100, resetsAt: inHours(4) }],
  }
  const get = (id: string) => usage[id]
  const accounts = [acct("dead"), acct("iso", { isolated: true }), acct("cur", { agent: "cursor" }), acct("unknown"), acct("later")]
  expect(earliestReset(accounts, get, now, id => id === "dead")).toEqual({ account: "later", at: inHours(4) })
  expect(earliestReset([acct("dead"), acct("unknown")], get, now, id => id === "dead")).toBeUndefined()
  expect(earliestReset([acct("nodata")], get, now)).toBeUndefined()
})
