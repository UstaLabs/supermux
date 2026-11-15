// The #156 eval report: per model × arm, success (all / tuning / held out), baseline tokens, tokens per run and per
// success, turns, calls, failed calls, help look-ups and time; then per task; then the decision rule applied.
//   bun evals/cli/report.ts /tmp/ce/out/<model>/  ...   → JSON on stdout (the page renders it)
import { existsSync, readFileSync } from "node:fs"
import { join } from "node:path"

const rows = (f: string) => (existsSync(f) ? readFileSync(f, "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)) : [])
const mean = (xs: number[]) => (xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0)
const median = (xs: number[]) => { if (!xs.length) return 0; const s = [...xs].sort((a, b) => a - b); return s.length % 2 ? s[(s.length - 1) / 2]! : (s[s.length / 2 - 1]! + s[s.length / 2]!) / 2 }

const out: any = { models: [], tasks: {} }
const fin = (r: any[]) => r.filter((x) => !x.timedOut)
for (const dir of process.argv.slice(2)) {
  // 11-delegate-codex is out: the seed for task 05 told agents not to start Codex sessions (both arms refused; see 11-delegate-claude)
  const res = rows(join(dir, "results.jsonl")).filter((r) => !r.noAnswer && r.task !== "11-delegate-codex")
  const base = rows(join(dir, "baseline.jsonl"))
  if (!res.length) continue
  const model = `${res[0].agent} · ${res[0].model}`
  const m: any = { model, arms: {}, noAnswer: rows(join(dir, "no-answer.jsonl")).length, errors: rows(join(dir, "errors.jsonl")).length }
  for (const arm of ["mcp", "cli"]) {
    const r = res.filter((x) => x.arm === arm)
    const pass = r.filter((x) => x.pass)
    const split = (s: string) => { const t = r.filter((x) => x.split === s); return { pass: t.filter((x) => x.pass).length, n: t.length } }
    m.arms[arm] = {
      n: r.length, pass: pass.length, rate: r.length ? pass.length / r.length : 0, tuning: split("T"), heldOut: split("H"), fresh: split("N"),
      baseline: Math.round(mean(base.filter((b) => b.arm === arm).map((b) => b.firstInput ?? b.inputTokens + b.cachedTokens))),
      // a run killed at the time limit reports no tokens: token figures use the runs that finished
      tokensPerRun: Math.round(mean(fin(r).map((x) => x.totalTokens))), medianTokens: Math.round(median(fin(r).map((x) => x.totalTokens))),
      tokensPerSuccess: fin(r).some((x) => x.pass) ? Math.round(fin(r).reduce((a, x) => a + x.totalTokens, 0) / fin(r).filter((x) => x.pass).length) : null,
      costPerRun: mean(r.map((x) => x.costUsd ?? 0)), turns: +mean(r.map((x) => x.turns ?? 0)).toFixed(1), toolCalls: +mean(r.map((x) => x.toolCalls ?? 0)).toFixed(1),
      failedCalls: +mean(r.map((x) => (x.mcpFailed ?? 0) + (x.cliFailed ?? 0))).toFixed(2), help: +mean(r.map((x) => x.helpLookups ?? 0)).toFixed(2),
      seconds: Math.round(mean(r.map((x) => x.ms / 1000))), leaked: r.filter((x) => x.leaked).length, timedOut: r.filter((x) => x.timedOut).length,
    }
  }
  out.models.push(m)
  for (const r of res) {
    const t = (out.tasks[r.task] ??= { task: r.task, split: r.split, area: r.area, by: {} })
    const k = `${model}|${r.arm}`
    const c = (t.by[k] ??= { pass: 0, n: 0, why: [] as string[], tokens: [] as number[] })
    c.n++; if (r.pass) c.pass++; else c.why.push(r.why); c.tokens.push(r.totalTokens)
  }
}
// The decision rule as approved, with Ahmet's change: no per-agent MCP exception.
const all = (arm: string) => out.models.reduce((a: any, m: any) => ({ pass: a.pass + m.arms[arm].pass, n: a.n + m.arms[arm].n }), { pass: 0, n: 0 })
const mcp = all("mcp"), cli = all("cli")
const pooledGap = (cli.pass / cli.n - mcp.pass / mcp.n) * 100
const perAgent = out.models.map((m: any) => ({ model: m.model, gap: (m.arms.cli.rate - m.arms.mcp.rate) * 100, tokenChange: m.arms.mcp.tokensPerSuccess && m.arms.cli.tokensPerSuccess ? (m.arms.cli.tokensPerSuccess / m.arms.mcp.tokensPerSuccess - 1) * 100 : null, claude: m.model.startsWith("claude") }))
out.decision = {
  pooled: { mcp, cli, gapPts: +pooledGap.toFixed(1) },
  perAgent: perAgent.map((p: any) => ({ ...p, gap: +p.gap.toFixed(1), tokenChange: p.tokenChange === null ? null : +p.tokenChange.toFixed(1) })),
  successOk: pooledGap >= -5 && perAgent.every((p: any) => p.gap >= -10),
  tokensOk: perAgent.every((p: any) => p.tokenChange !== null && (p.claude ? p.tokenChange <= 10 : p.tokenChange <= -15)),
}
console.log(JSON.stringify(out, null, 1))
