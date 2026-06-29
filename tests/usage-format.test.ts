import { test, expect } from "bun:test"
import { formatUsageTelegram } from "../src/core/usage/format"
import type { UsageResponse, ClaudeUsage, CodexUsage, CursorUsage } from "../src/core/usage"

function makeClaude(): ClaudeUsage {
  return {
    fiveHour: { used: 44, resetsAt: new Date(Date.now() + 2.5 * 3_600_000).toISOString() },
    sevenDay: { used: 4, resetsAt: new Date(Date.now() + 7 * 24 * 3_600_000).toISOString() },
    sevenDaySonnet: { used: 1, resetsAt: new Date(Date.now() + 7 * 24 * 3_600_000).toISOString() },
    extraUsage: { enabled: true, monthlyLimit: 5000, usedCredits: 27, currency: "usd" },
  }
}

function makeCodex(): CodexUsage {
  return {
    plan: "plus",
    primaryWindow: { used: 2, resetsAt: Math.floor((Date.now() + 4 * 3_600_000 + 2 * 60_000) / 1000) },
    secondaryWindow: { used: 73, resetsAt: Math.floor((Date.now() + 6 * 24 * 3_600_000) / 1000) },
    credits: { hasCredits: true, balance: "15.00" },
    limitReached: false,
    resetCredits: 0,
  }
}

const codexFixture = (resetCredits: number): CodexUsage => ({
  plan: "plus",
  primaryWindow: { used: 10, resetsAt: null },
  secondaryWindow: { used: 5, resetsAt: null },
  credits: null,
  limitReached: false,
  resetCredits,
})

function makeCursor(): CursorUsage {
  return {
    totalPercentUsed: 85,
    totalSpendCents: 1200,
    includedCents: 2000,
    limitCents: 5000,
    billingCycleStart: String(Date.now() - 10 * 24 * 3_600_000),
    billingCycleEnd: String(Date.now() + 33 * 24 * 3_600_000),
  }
}

test("formatUsageTelegram renders all three providers", () => {
  const data: UsageResponse = {
    claude: makeClaude(),
    codex: makeCodex(),
    cursor: makeCursor(),
    opencode: null,
    errors: {},
  }
  const out = formatUsageTelegram(data)
  expect(out).toContain("Claude")
  expect(out).toContain("44% used")
  expect(out).toContain("Codex (plus)")
  expect(out).toContain("2% used")
  expect(out).toContain("Cursor")
  expect(out).toContain("85% used")
  expect(out).toContain("$27")
})

test("formatUsageTelegram omits null providers", () => {
  const data: UsageResponse = {
    claude: null,
    codex: null,
    cursor: makeCursor(),
    opencode: null,
    errors: {},
  }
  const out = formatUsageTelegram(data)
  expect(out).not.toContain("Claude")
  expect(out).not.toContain("Codex")
  expect(out).toContain("Cursor")
})

test("formatUsageTelegram returns fallback when all null", () => {
  const data: UsageResponse = {
    claude: null,
    codex: null,
    cursor: null,
    opencode: null,
    errors: {},
  }
  const out = formatUsageTelegram(data)
  expect(out).toContain("unavailable")
})

test("formatUsageTelegram shows Codex banked resets when > 0", () => {
  const out = formatUsageTelegram({ claude: null, codex: codexFixture(3), cursor: null, opencode: null, errors: {} } as any)
  expect(out).toContain("Resets banked: 3")
})

test("formatUsageTelegram omits banked resets when 0", () => {
  const out = formatUsageTelegram({ claude: null, codex: codexFixture(0), cursor: null, opencode: null, errors: {} } as any)
  expect(out).not.toContain("Resets banked")
})
