import { test, expect } from "bun:test"
import { formatUsageTelegram } from "../src/core/usage/format"
import type { UsageResponse, ClaudeUsage, CodexUsage, CursorUsage } from "../src/core/usage"

function makeClaude(): ClaudeUsage {
  return {
    fiveHour: { used: 44, resetsAt: new Date(Date.now() + 2.5 * 3_600_000).toISOString() },
    sevenDay: { used: 4, resetsAt: new Date(Date.now() + 7 * 24 * 3_600_000).toISOString() },
    sevenDaySonnet: { used: 1, resetsAt: new Date(Date.now() + 7 * 24 * 3_600_000).toISOString() },
    sevenDayFable: { used: 9, resetsAt: new Date(Date.now() + 7 * 24 * 3_600_000).toISOString() },
    extraUsage: { enabled: true, monthlyLimit: 5000, usedCredits: 27, currency: "usd" },
  }
}

function makeCodex(): CodexUsage {
  return {
    plan: "plus",
    windows: [
      { id: "primary", used: 2, resetsAt: Math.floor((Date.now() + 4 * 3_600_000 + 2 * 60_000) / 1000), label: "5-hour window", windowSeconds: 18000 },
      { id: "secondary", used: 73, resetsAt: Math.floor((Date.now() + 6 * 24 * 3_600_000) / 1000), label: "7-day window", windowSeconds: 604800 },
    ],
    credits: { hasCredits: true, balance: "15.00" },
    limitReached: false,
    resetCredits: 0,
  }
}

const codexFixture = (resetCredits: number): CodexUsage => ({
  plan: "plus",
  windows: [
    { id: "primary", used: 10, resetsAt: null, label: "5-hour window", windowSeconds: 18000 },
    { id: "secondary", used: 5, resetsAt: null, label: "7-day window", windowSeconds: 604800 },
  ],
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
    spendAvailable: true,
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
    grok: null,
    errors: {},
  }
  const out = formatUsageTelegram(data)
  expect(out).toContain("Claude")
  expect(out).toContain("44% used")
  expect(out).toContain("7d Sonnet: 1% used")
  expect(out).toContain("7d Fable: 9% used")
  expect(out).toContain("Codex (plus)")
  expect(out).toContain("2% used")
  expect(out).toContain("Cursor")
  expect(out).toContain("85% used")
  expect(out).toContain("$27")
})

test("formatUsageTelegram omits per-model rows when the caps are null", () => {
  const claude: ClaudeUsage = { ...makeClaude(), sevenDaySonnet: null, sevenDayFable: null }
  const out = formatUsageTelegram({ claude, codex: null, cursor: null, opencode: null,
    grok: null, errors: {} })
  expect(out).toContain("Claude")
  expect(out).not.toContain("Sonnet")
  expect(out).not.toContain("Fable")
})

test("formatUsageTelegram omits null providers", () => {
  const data: UsageResponse = {
    claude: null,
    codex: null,
    cursor: makeCursor(),
    opencode: null,
    grok: null,
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
    grok: null,
    errors: {},
  }
  const out = formatUsageTelegram(data)
  expect(out).toContain("unavailable")
})

test("formatUsageTelegram shows Codex banked resets when > 0", () => {
  const out = formatUsageTelegram({ claude: null, codex: codexFixture(3), cursor: null, opencode: null,
    grok: null, errors: {} } as any)
  expect(out).toContain("Resets banked: 3")
})

test("formatUsageTelegram omits banked resets when 0", () => {
  const out = formatUsageTelegram({ claude: null, codex: codexFixture(0), cursor: null, opencode: null,
    grok: null, errors: {} } as any)
  expect(out).not.toContain("Resets banked")
})

test("formatUsageTelegram renders only the Codex windows returned by the API", () => {
  const codex = { ...codexFixture(0), windows: [
    { id: "primary", used: 25, resetsAt: null, label: "7-day window", windowSeconds: 604800 },
  ] }
  const out = formatUsageTelegram({ claude: null, codex, cursor: null, opencode: null,
    grok: null, errors: {} })
  expect(out).toContain("7-day window: 25% used")
  expect(out).not.toContain("5-hour window")
})

test("formatUsageTelegram renders Grok credits", () => {
  const data: UsageResponse = {
    claude: null,
    codex: null,
    cursor: null,
    opencode: null,
    grok: {
      plan: "SuperGrokPro",
      percentUsed: 12.5,
      used: 18750,
      monthlyLimit: 150000,
      onDemandCap: 0,
      onDemandUsed: 0,
      prepaidBalance: 0,
      billingPeriodStart: new Date(Date.now() - 10 * 24 * 3_600_000).toISOString(),
      billingPeriodEnd: new Date(Date.now() + 20 * 24 * 3_600_000).toISOString(),
    },
    errors: {},
  }
  const out = formatUsageTelegram(data)
  expect(out).toContain("Grok (SuperGrokPro)")
  expect(out).toContain("13% used")
  expect(out).toContain("18750 / 150000")
})
