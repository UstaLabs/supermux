// Turns results.jsonl files into the table per model × arm that goes to Ahmet.
//   bun evals/show-html/report.ts <results.jsonl>...
import { readFileSync } from "node:fs"
import type { Result } from "./score"

const pct = (num: number, den: number) => (den ? `${Math.round((100 * num) / den)}%` : "–")
const frac = (num: number, den: number) => (den ? `${num}/${den}` : "–")

export function table(results: Result[]): string {
  const groups = new Map<string, Result[]>()
  for (const r of results) {
    const key = `${r.agent} ${r.model}\t${r.arm}`
    groups.set(key, [...(groups.get(key) ?? []), r])
  }
  const rows = [...groups.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([key, rs]) => {
    const [model, arm] = key.split("\t")
    const ok = rs.filter((r) => !r.error && !r.timedOut)
    const cards = ok.filter((r) => r.expect === "card")
    const none = ok.filter((r) => r.expect === "none")
    const checked = ok.filter((r) => r.score.firstClean !== undefined)
    const flagged = ok.filter((r) => r.score.repaired !== undefined)
    const shown = ok.filter((r) => r.score.usesTheme !== undefined)
    const sends = ok.filter((r) => r.score.sendWorks !== undefined)
    const previewed = ok.filter((r) => r.score.shows > 0)
    const tokens = ok.reduce((n, r) => n + r.usage.inputTokens + r.usage.cachedTokens + r.usage.outputTokens, 0)
    const cost = ok.reduce((n, r) => n + (r.usage.costUsd ?? 0), 0)
    return `| ${model} | ${arm} | ${frac(cards.filter((r) => r.score.toolUse).length, cards.length)} | ` +
      `${frac(none.filter((r) => r.score.toolUse).length, none.length)} | ${pct(checked.filter((r) => r.score.firstClean).length, checked.length)} | ` +
      `${frac(flagged.filter((r) => r.score.repaired).length, flagged.length)} | ${pct(shown.filter((r) => r.score.usesTheme && r.score.transparent).length, shown.length)} | ` +
      `${frac(sends.filter((r) => r.score.sendWorks).length, sends.length)} | ` +
      `${arm!.startsWith("B") ? frac(previewed.filter((r) => r.score.previewedFirst).length, previewed.length) : "–"} | ` +
      `${ok.length ? Math.round(tokens / ok.length / 1000) : "–"}k | ` +
      `$${cost.toFixed(2)} | ${rs.length - ok.length} |`
  })
  return [
    "| model | arm | card when asked | no card when not | 1st page clean | repaired after a problem | theme + transparent | click sends | previewed first | tokens/run | cost | failed runs |",
    "|---|---|---|---|---|---|---|---|---|---|---|---|",
    ...rows,
  ].join("\n")
}

if (import.meta.main) {
  const results = process.argv.slice(2).flatMap((f) => readFileSync(f, "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l) as Result))
  console.log(table(results))
}
