// The fixed prompt set (spec, "Evals"). `card` prompts should end with a shown card; `none` prompts with no card.
// `send`: the page should let the user answer with a click. `force`: the stub answers the first check with this problem.
import type { Problem } from "./check"

export interface EvalPrompt {
  id: string
  prompt: string
  expect: "card" | "none"
  send?: boolean
  force?: Problem["kind"]
  /** Files the run's folder starts with. */
  files?: Record<string, string>
  /** For `none`: the tool a good answer uses instead, if any. */
  instead?: "attach"
}

const LATENCY = `Latency per release for our three main endpoints, in ms (p50 / p95):
release | /search | /upload | /login
1.4 | 80/190 | 120/300 | 40/90
1.5 | 82/200 | 125/310 | 41/95
1.6 | 85/420 | 130/320 | 42/96
1.7 | 84/430 | 128/330 | 40/94
Which release regressed, and on which endpoint?`

const CI = `CI got slow. Job times in minutes for the last 6 runs on main:
run | install | typecheck | unit | e2e | build
101 | 2 | 3 | 6 | 9 | 4
102 | 2 | 3 | 6 | 10 | 4
103 | 2 | 3 | 7 | 14 | 4
104 | 3 | 3 | 7 | 17 | 5
105 | 2 | 4 | 7 | 18 | 4
106 | 2 | 3 | 6 | 19 | 4
What's eating the time?`

const USAGE_CHART = `import { useState } from "react"

export function UsageChart({ points }: { points: { day: string; value: number }[] }) {
  const [hover, setHover] = useState<number | null>(null)
  const max = Math.max(...points.map((p) => p.value))
  return (
    <div className="usage-chart">
      {points.map((p, i) => (
        <div key={p.day} className="bar" style={{ height: \`\${(p.value / max) * 100}%\` }}
          onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)}>
          {hover === i && <span className="tooltip">{p.day}: {p.value}</span>}
        </div>
      ))}
    </div>
  )
}
`

const SUM = { "sum.js": "export function sum(a, b) {\n  return a - b\n}\n",
  "sum.test.js": "import { expect, test } from \"bun:test\"\nimport { sum } from \"./sum\"\n\ntest(\"adds\", () => {\n  expect(sum(2, 3)).toBe(5)\n})\n" }

const CHART = "Chart these numbers for me: monthly signups Jan 120, Feb 135, Mar 128, Apr 160, May 190, Jun 240."
const COMPARE = "Compare these 3 hosting options for me: Fly.io, $5/mo, 3 regions, no free Postgres; Render, $7/mo, 4 regions, " +
  "free Postgres for 90 days; Railway, $5/mo usage-based, 2 regions, Postgres included."
const CALC = "Make me a small calculator that turns a monthly salary into hourly, daily and yearly pay (40-hour weeks)."

export const PROMPTS: EvalPrompt[] = [
  { id: "01-chart", prompt: CHART, expect: "card" },
  { id: "02-compare", prompt: COMPARE, expect: "card" },
  { id: "03-calculator", prompt: CALC, expect: "card" },
  { id: "04-mockup", prompt: "Show me a UI mockup of a mobile settings screen for a podcast app: account, playback speed, downloads over Wi-Fi only, notifications.", expect: "card" },
  { id: "05-palette", prompt: "Let me pick a color palette for my landing page: give me 4 options and let me choose one.", expect: "card", send: true },
  { id: "06-latency", prompt: LATENCY, expect: "card" },
  { id: "07-ci", prompt: CI, expect: "card" },
  { id: "08-backoff", prompt: "Help me tune our retry backoff: base delay, cap and max attempts. Show me how the delays grow, and let me pick the values.", expect: "card", send: true },
  { id: "09-timeline", prompt: "Make a timeline of these events: 2019 founded, 2020 seed round, 2021 first 1,000 customers, 2023 Series A, 2024 expanded to the EU, 2026 IPO filing.", expect: "card" },
  { id: "10-regex", prompt: "Explain this regex visually: ^(?<user>[\\w.+-]+)@(?<domain>[\\w-]+\\.[\\w.]+)$", expect: "card" },
  { id: "11-fact", prompt: "What's the capital of Australia?", expect: "none" },
  { id: "12-just-tell", prompt: "Just tell me in one line: is Postgres or SQLite better for a single-user desktop app?", expect: "none" },
  { id: "13-refactor-chart", prompt: "Refactor src/components/UsageChart.tsx so the tooltip hover logic lives in its own hook. No behavior change.", expect: "none", files: { "src/components/UsageChart.tsx": USAGE_CHART } },
  { id: "14-image-to-post", prompt: "Make me an image I can post on X announcing that our app now supports dark mode.", expect: "none", instead: "attach" },
  { id: "15-fix-test", prompt: "The test in sum.test.js fails. Fix it.", expect: "none", files: SUM },
  { id: "16-repair-blank", prompt: CHART, expect: "card", force: "blank" },
  { id: "17-repair-overflow", prompt: COMPARE, expect: "card", force: "overflow" },
  { id: "18-repair-script", prompt: CALC, expect: "card", force: "script-error" },
]
