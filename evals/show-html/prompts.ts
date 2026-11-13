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
  /** A mermaid diagram (supermux draws those natively) is as good an answer as a card. */
  orDiagram?: boolean
  /** Written after the wordings, with phrasing neither wording echoes: reported apart. */
  heldOut?: boolean
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

const DARK_CARD = `<style>body{background:#111;color:#eee;font:14px sans-serif} .bar{height:14px;background:#4cc9f0;margin:4px 0}</style>
<h3>Signups</h3><div class="bar" style="width:50%"></div><div class="bar" style="width:56%"></div><div class="bar" style="width:100%"></div>`

// 120 days of daily active users: a slow climb, a dip around day 70, then a faster climb. Deterministic.
const DAU = ["day,dau", ...Array.from({ length: 120 }, (_, d) =>
  `${d + 1},${Math.round(1000 + d * 6 + (d > 65 && d < 80 ? -180 : 0) + (d >= 80 ? (d - 80) * 9 : 0) + ((d * 37) % 23))}`)].join("\n")

const Q3 = `Quarter | Revenue (k$)
Q1 | 410
Q2 | 455
Q3 | 512
Q4 | 498`

const COUNTER = { "counter.js": "let cnt = 0\nexport function tick() {\n  cnt += 1\n  return cnt\n}\n" }

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
  { id: "09-timeline", prompt: "Make a timeline of these events: 2019 founded, 2020 seed round, 2021 first 1,000 customers, 2023 Series A, 2024 expanded to the EU, 2026 IPO filing.", expect: "card", orDiagram: true },
  { id: "10-regex", prompt: "Explain this regex visually: ^(?<user>[\\w.+-]+)@(?<domain>[\\w-]+\\.[\\w.]+)$", expect: "card", orDiagram: true },
  { id: "11-fact", prompt: "What's the capital of Australia?", expect: "none" },
  { id: "12-just-tell", prompt: "Just tell me in one line: is Postgres or SQLite better for a single-user desktop app?", expect: "none" },
  { id: "13-refactor-chart", prompt: "Refactor src/components/UsageChart.tsx so the tooltip hover logic lives in its own hook. No behavior change.", expect: "none", files: { "src/components/UsageChart.tsx": USAGE_CHART } },
  { id: "14-image-to-post", prompt: "Make me an image I can post on X announcing that our app now supports dark mode.", expect: "none", instead: "attach" },
  { id: "15-fix-test", prompt: "The test in sum.test.js fails. Fix it.", expect: "none", files: SUM },
  { id: "16-repair-blank", prompt: CHART, expect: "card", force: "blank" },
  { id: "17-repair-overflow", prompt: COMPARE, expect: "card", force: "overflow" },
  { id: "18-repair-script", prompt: CALC, expect: "card", force: "script-error" },
  { id: "19-light-mode", prompt: `Earlier in this chat you showed me this card:\n\n\`\`\`html\n${DARK_CARD}\n\`\`\`\n\nMake it light mode.`, expect: "card" },
  { id: "20-big-data", prompt: `Daily active users for the last 120 days:\n\n${DAU}\n\nWhat does the trend look like?`, expect: "card" },
  { id: "21-just-the-number", prompt: `${Q3}\n\nJust give me the number: what was revenue in Q3?`, expect: "none" },
  { id: "22-sales-regions", prompt: "Our Q3 sales by region: North 120k, South 95k, East 143k, West 88k. Anything stand out?", expect: "card", heldOut: true },
  { id: "23-kanban", prompt: "Sketch the layout of a kanban board with three columns for me.", expect: "card", heldOut: true },
  { id: "24-rename", prompt: "Rename the variable cnt to count in counter.js.", expect: "none", files: COUNTER, heldOut: true },
]
