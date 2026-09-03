import { afterEach, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, utimesSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { CodexUsage } from "./index"
import { codexUsageFromRateLimits, readClaudeLocalUsage, readCodexLocalUsage } from "./local"

let tmp: string

afterEach(() => {
  if (tmp) rmSync(tmp, { recursive: true, force: true })
})

function tempDir(): string {
  tmp = mkdtempSync(join(tmpdir(), "usage-local-"))
  return tmp
}

test("readClaudeLocalUsage maps cachedUsageUtilization and uses fetchedAtMs", () => {
  const dir = tempDir()
  const path = join(dir, ".claude.json")
  const fetchedAtMs = Date.now() - 30_000
  writeFileSync(
    path,
    JSON.stringify({
      cachedUsageUtilization: {
        fetchedAtMs,
        accountUuid: "acct",
        utilization: {
          five_hour: { utilization: 42, resets_at: "2026-05-25T12:00:00Z" },
          seven_day: { utilization: 15, resets_at: "2026-05-30T00:00:00Z" },
          seven_day_sonnet: { utilization: 8, resets_at: "2026-05-30T00:00:00Z" },
        },
      },
    }),
  )

  const result = readClaudeLocalUsage(path)
  expect(result).not.toBeNull()
  expect(result!.fetchedAt.getTime()).toBe(fetchedAtMs)
  expect(result!.data.fiveHour.used).toBe(42)
  expect(result!.data.fiveHour.resetsAt).toBe("2026-05-25T12:00:00Z")
  expect(result!.data.fiveHour.resetsAtIso).toBe("2026-05-25T12:00:00.000Z")
  expect(result!.data.sevenDay.used).toBe(15)
  expect(result!.data.sevenDaySonnet?.used).toBe(8)
  expect(result!.data.sevenDayFable).toBeNull()
  expect(result!.data.extraUsage).toBeNull()
})

test("readClaudeLocalUsage ignores entries older than 1 hour and missing files", () => {
  const dir = tempDir()
  const path = join(dir, ".claude.json")
  writeFileSync(
    path,
    JSON.stringify({
      cachedUsageUtilization: {
        fetchedAtMs: Date.now() - 2 * 60 * 60_000,
        utilization: {
          five_hour: { utilization: 1, resets_at: "2026-05-25T12:00:00Z" },
          seven_day: { utilization: 1, resets_at: "2026-05-30T00:00:00Z" },
        },
      },
    }),
  )
  expect(readClaudeLocalUsage(path)).toBeNull()
  expect(readClaudeLocalUsage(join(dir, "missing.json"))).toBeNull()
})

test("readCodexLocalUsage walks newest date dirs and reads rate_limits from the tail", () => {
  const dir = tempDir()
  const oldDay = join(dir, "2026", "08", "01")
  const newDay = join(dir, "2026", "09", "02")
  mkdirSync(oldDay, { recursive: true })
  mkdirSync(newDay, { recursive: true })

  writeFileSync(
    join(oldDay, "rollout-old.jsonl"),
    `${JSON.stringify({ rate_limits: { primary: { used_percent: 1, window_minutes: 300, resets_at: 1 }, plan_type: "old" } })}\n`,
  )

  const newest = join(newDay, "rollout-new.jsonl")
  writeFileSync(
    newest,
    [
      JSON.stringify({ type: "session_meta" }),
      JSON.stringify({ rate_limits: { primary: null } }),
      // Real rollout shape: the block is nested under payload (event_msg/token_count).
      JSON.stringify({
        type: "event_msg",
        payload: {
          type: "token_count",
          rate_limits: {
            primary: { used_percent: 40, window_minutes: 300, resets_at: 1_700_000_000 },
            secondary: { used_percent: 10, window_minutes: 10_080, resets_at: 1_700_001_000 },
            credits: { has_credits: true, balance: "12" },
            plan_type: "plus",
            rate_limit_reached_type: null,
          },
        },
      }),
    ].join("\n") + "\n",
  )
  const mtime = new Date("2026-09-02T10:00:00.000Z")
  utimesSync(newest, mtime, mtime)

  const result = readCodexLocalUsage(dir)
  expect(result).not.toBeNull()
  expect(result!.fetchedAt.getTime()).toBe(mtime.getTime())
  expect(result!.data.plan).toBe("plus")
  expect(result!.data.windows).toHaveLength(2)
  expect(result!.data.windows[0]?.id).toBe("primary")
  expect(result!.data.windows[0]?.used).toBe(40)
  expect(result!.data.windows[0]?.windowSeconds).toBe(300 * 60)
  expect(result!.data.windows[0]?.label).toBe("5-hour window")
  expect(result!.data.windows[1]?.label).toBe("7-day window")
  expect(result!.data.credits).toEqual({ hasCredits: true, balance: "12" })
  expect(result!.data.limitReached).toBe(false)
})

test("codexUsageFromRateLimits maps camelCase app-server and snake_case rollout shapes", () => {
  const prev: CodexUsage = {
    plan: "prev",
    windows: [],
    credits: null,
    limitReached: false,
    resetCredits: 4,
  }

  const camel = codexUsageFromRateLimits(
    {
      primary: { usedPercent: 40, windowDurationMins: 300, resetsAt: 1_700_000_000 },
      secondary: { usedPercent: 10, windowDurationMins: 10_080, resetsAt: 1_700_001_000 },
      planType: "plus",
      credits: { hasCredits: true, unlimited: false, balance: "12" },
      rateLimitReachedType: "primary",
    },
    prev,
  )
  expect(camel).not.toBeNull()
  expect(camel!.plan).toBe("plus")
  expect(camel!.resetCredits).toBe(4)
  expect(camel!.limitReached).toBe(true)
  expect(camel!.windows[0]?.used).toBe(40)
  expect(camel!.windows[0]?.windowSeconds).toBe(18_000)
  expect(camel!.windows[0]?.resetsAt).toBe(1_700_000_000)
  expect(camel!.windows[0]?.resetsAtIso).toBe(new Date(1_700_000_000 * 1000).toISOString())
  expect(camel!.windows[0]?.label).toBe("5-hour window")
  expect(camel!.credits).toEqual({ hasCredits: true, balance: "12" })

  const snake = codexUsageFromRateLimits({
    primary: { used_percent: 22, window_minutes: 60, resets_at: 1_700_000_100 },
    secondary: { used_percent: 3, window_minutes: 10_080, resets_at: 1_700_001_100 },
    plan_type: "pro",
    credits: { has_credits: false, balance: "0" },
    rate_limit_reached_type: null,
  })
  expect(snake).not.toBeNull()
  expect(snake!.plan).toBe("pro")
  expect(snake!.resetCredits).toBe(0)
  expect(snake!.limitReached).toBe(false)
  expect(snake!.windows[0]?.used).toBe(22)
  expect(snake!.windows[0]?.windowSeconds).toBe(3600)
  expect(snake!.windows[0]?.label).toBe("1-hour window")
  expect(snake!.credits).toEqual({ hasCredits: false, balance: "0" })
})

test("codexUsageFromRateLimits returns null without a usable primary window", () => {
  expect(codexUsageFromRateLimits(null)).toBeNull()
  expect(codexUsageFromRateLimits({ primary: null, planType: "plus" })).toBeNull()
})
