// Runs the show_html evals: each (arm, prompt, sample) on one agent CLI and model, scored into results.jsonl.
//   bun evals/show-html/run.ts --agent claude --model claude-haiku-5-5 --out evals/show-html/runs/smoke
//     [--arms A1,B1] [--prompts 01-chart,11-fact] [--samples 1] [--concurrency 1] [--home <account home>] [--max-load 24]
//     [--min-free-gb 6]
import { existsSync, mkdirSync, readFileSync, writeFileSync, appendFileSync } from "node:fs"
import { dirname, join, resolve } from "node:path"
import { freemem, loadavg } from "node:os"
import { ARMS, instructionsFor, type Arm } from "./arms"
import { PROMPTS, type EvalPrompt } from "./prompts"
import { invocation, type AgentKind } from "./agents"
import { launchBrowser } from "./check"
import { clickRoundTrip } from "./click"
import { score, type Call, type Result } from "./score"

const RUN_TIMEOUT_MS = 4 * 60_000

function flag(name: string, fallback?: string): string | undefined {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 ? process.argv[i + 1] : fallback
}

/** The live broker's variables stay out of the agent's environment, so a run can never reach his sessions. */
function cleanEnv(extra: Record<string, string>): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) {
    if (v === undefined || k.startsWith("MUX_") || k === "CLAUDECODE" || k.startsWith("CLAUDE_CODE_") || k === "CLAUDE_CONFIG_DIR" || k === "CODEX_HOME") continue
    env[k] = v
  }
  return { ...env, ...extra }
}

async function runOne(o: { runId: string; agent: AgentKind; model: string; arm: Arm; prompt: EvalPrompt; sample: number; out: string; home?: string }): Promise<Result> {
  const dir = resolve(o.out, "work", o.arm, o.model, `${o.prompt.id}-${o.sample}`)
  mkdirSync(dir, { recursive: true })
  for (const [path, body] of Object.entries(o.prompt.files ?? {})) {
    mkdirSync(dirname(join(dir, path)), { recursive: true })
    writeFileSync(join(dir, path), body)
  }
  const serverEnv: Record<string, string> = { EVAL_ARM: o.arm, EVAL_DIR: dir, SHOW_HTML_CHECK_CAP_MS: process.env.SHOW_HTML_CHECK_CAP_MS ?? "15000",
    ...(o.prompt.force ? { EVAL_FORCE_PROBLEM: o.prompt.force } : {}) }
  const inv = invocation(o.agent, { dir, prompt: o.prompt.prompt, model: o.model, instructions: instructionsFor(o.arm), serverEnv, home: o.home })
  const started = Date.now()
  const child = Bun.spawn(inv.cmd, { cwd: dir, env: cleanEnv(inv.env), stdout: "pipe", stderr: "pipe", stdin: "ignore" })
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; child.kill() }, RUN_TIMEOUT_MS)
  const [stdout, stderr] = await Promise.all([new Response(child.stdout).text(), new Response(child.stderr).text()])
  const exitCode = await child.exited
  clearTimeout(timer)
  writeFileSync(join(dir, "agent.stdout"), stdout)
  writeFileSync(join(dir, "agent.stderr"), stderr)

  const calls: Call[] = existsSync(join(dir, "calls.jsonl"))
    ? readFileSync(join(dir, "calls.jsonl"), "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l))
    : []
  const lastShown = calls.filter((c) => c.tool === "show_html" && c.shown && c.page).at(-1)
  const shownPage = lastShown ? readFileSync(join(dir, lastShown.page!), "utf8") : undefined
  let sendWorks: boolean | undefined
  if (o.prompt.send && shownPage) {
    const browser = await launchBrowser()
    if (browser) {
      try { sendWorks = (await clickRoundTrip(browser, shownPage)).sent.length > 0 } finally { await browser.close() }
    }
  }
  const parsed = inv.parse(stdout)
  return {
    runId: o.runId, agent: o.agent, model: o.model, arm: o.arm, prompt: o.prompt.id, sample: o.sample, expect: o.prompt.expect,
    ...(o.prompt.heldOut ? { heldOut: true } : {}),
    score: score(o.prompt, calls, shownPage, sendWorks, parsed.finalText), usage: parsed.usage, finalText: parsed.finalText.slice(0, 2000),
    refusedTools: parsed.refusedTools, exitCode, timedOut, ms: Date.now() - started,
    ...(exitCode !== 0 && !timedOut ? { error: stderr.trim().split("\n").slice(-3).join(" | ").slice(0, 400) } : {}),
  }
}

/** The box is shared with his sessions: a run starts only below the load cap and with enough memory free. */
async function waitForRoom(maxLoad: number, minFreeGb: number): Promise<void> {
  const available = () => {
    try { return Number(readFileSync("/proc/meminfo", "utf8").match(/MemAvailable:\s+(\d+)/)![1]) * 1024 } catch { return freemem() }
  }
  while (loadavg()[0]! > maxLoad || available() < minFreeGb * 2 ** 30) await Bun.sleep(15_000)
}

if (import.meta.main) {
  const agent = (flag("agent", "claude") as AgentKind)
  const model = flag("model")
  const out = resolve(flag("out", `evals/show-html/runs/${new Date().toISOString().slice(0, 16).replace(/[:T]/g, "-")}`)!)
  if (!model) throw new Error("--model is required")
  const arms = (flag("arms")?.split(",") ?? ARMS) as Arm[]
  const ids = flag("prompts")?.split(",")
  const prompts = PROMPTS.filter((p) => !ids || ids.includes(p.id))
  const samples = Number(flag("samples", "1"))
  const concurrency = Number(flag("concurrency", "1"))
  const maxLoad = Number(flag("max-load", "24"))
  const minFreeGb = Number(flag("min-free-gb", "6"))
  const home = flag("home")
  const runId = `${agent}-${model}-${Date.now().toString(36)}`
  mkdirSync(out, { recursive: true })

  // A rerun into the same --out skips what already finished.
  const resultsFile = join(out, "results.jsonl")
  const finished = new Set(existsSync(resultsFile)
    ? readFileSync(resultsFile, "utf8").split("\n").filter(Boolean).map((l) => { const r = JSON.parse(l) as Result; return `${r.model}/${r.arm}/${r.prompt}/${r.sample}` })
    : [])
  const queue = arms.flatMap((arm) => prompts.flatMap((prompt) => Array.from({ length: samples }, (_, i) => ({ arm, prompt, sample: i + 1 }))))
    .filter((j) => !finished.has(`${model}/${j.arm}/${j.prompt.id}/${j.sample}`))
  console.log(`${queue.length} runs → ${out}/results.jsonl`)
  let done = 0
  const worker = async () => {
    for (let job = queue.shift(); job; job = queue.shift()) {
      await waitForRoom(maxLoad, minFreeGb)
      const r = await runOne({ runId, agent, model, out, home, ...job })
      appendFileSync(join(out, "results.jsonl"), JSON.stringify(r) + "\n")
      done++
      console.log(`[${done}] ${r.arm} ${r.prompt}#${r.sample}: toolUse=${r.score.toolUse} shows=${r.score.shows} ` +
        `problems=${r.score.realProblems.join("+") || "-"} repaired=${r.score.repaired ?? "-"} send=${r.score.sendWorks ?? "-"} ` +
        `tokens=${r.usage.inputTokens + r.usage.cachedTokens}/${r.usage.outputTokens} $${r.usage.costUsd?.toFixed(4) ?? "?"} ` +
        `${Math.round(r.ms / 1000)}s${r.timedOut ? " TIMEOUT" : ""}${r.error ? ` ERROR ${r.error}` : ""}`)
    }
  }
  await Promise.all(Array.from({ length: concurrency }, worker))
}
