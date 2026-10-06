import { expect, test } from "bun:test"
import { limited, pickAccount, score } from "../src/accounts/index.js"
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
