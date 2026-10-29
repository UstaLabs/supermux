import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { UsageStore } from "../../../packages/supermux-core/src/accounts/index.js"
import type { ClaudeUsage, CodexUsage, GrokUsage } from "../usage/index"
import { policyWindows, recordProviderUsage } from "./provider-usage"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

const claude: ClaudeUsage = {
  fiveHour: { used: 62, resetsAt: "2026-10-30T22:10:00+00:00", resetsAtIso: "2026-10-30T22:10:00.000Z" },
  sevenDay: { used: 30, resetsAt: null, resetsAtIso: null },
  sevenDaySonnet: null, sevenDayFable: null, extraUsage: null,
}
const codex: CodexUsage = {
  plan: "pro",
  windows: [{ id: "primary", label: "30 days", used: 48, windowSeconds: 2_592_000, resetsAt: 1_795_000_000, resetsAtIso: "2026-11-17T09:41:16.000Z" }],
  models: [], credits: null, limitReached: false, resetCredits: 0,
}
const grok: GrokUsage = {
  plan: "GrokPro", percentUsed: 100, used: 0, monthlyLimit: 0, onDemandCap: 0, onDemandUsed: 0, prepaidBalance: 0,
  periodType: "weekly", products: [], billingPeriodStart: "2026-10-24T16:28:51Z", billingPeriodEnd: "2026-10-31T16:28:51Z",
  billingPeriodEndIso: "2026-10-31T16:28:51.524Z",
}

test("policyWindows: Claude and Codex windows named like the agents' own events; Grok's credit period", () => {
  expect(policyWindows("claude", claude)).toEqual([
    { name: "five_hour", usedPercent: 62, resetsAt: new Date("2026-10-30T22:10:00.000Z") },
    { name: "seven_day", usedPercent: 30 },
  ])
  expect(policyWindows("codex", codex)).toEqual([{ name: "43200m", usedPercent: 48, resetsAt: new Date("2026-11-17T09:41:16.000Z") }])
  expect(policyWindows("grok", grok)).toEqual([{ name: "weekly", usedPercent: 100, resetsAt: new Date("2026-10-31T16:28:51.524Z") }])
  expect(policyWindows("grok", { ...grok, periodType: "unknown", billingPeriodEndIso: null })).toEqual([{ name: "period", usedPercent: 100 }])
  expect(policyWindows("grok", { ...grok, percentUsed: Number.NaN })).toBeUndefined()
  expect(policyWindows("cursor", { totalPercentUsed: 10 })).toBeUndefined()
  expect(policyWindows("claude", null)).toBeUndefined()
})

test("recordProviderUsage writes the windows where the account policy reads them", async () => {
  const dir = mkdtempSync(join(tmpdir(), "provider-usage-"))
  dirs.push(dir)
  const usage = new UsageStore(join(dir, "usage.json"))
  recordProviderUsage(usage, "grok", "grok-a", grok)
  expect(usage.get("grok-a")).toEqual([{ name: "weekly", usedPercent: 100, resetsAt: new Date("2026-10-31T16:28:51.524Z") }])
  recordProviderUsage(usage, "cursor", "cursor:system", { totalPercentUsed: 10 })
  expect(usage.get("cursor:system")).toBeUndefined()
  // The debounced save lands now, not in the temp directory after it is removed.
  await usage.flush()
})
