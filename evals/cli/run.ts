// The #156 eval runner: tasks × arms × samples on one agent and model. Each run gets its own throwaway broker (a Docker
// container, no network), its own issues server on a temp data dir, a seeded world, and a fresh agent process.
// Nothing here reaches the live broker: every MUX_* variable of this shell is stripped from the agent's env.
//
//   bun evals/cli/run.ts --agent claude --model haiku --out /tmp/ce/out/haiku [--home <claude account>] \
//     [--arms mcp,cli] [--tasks 01-tell-git,07-rides-chart] [--samples 3] [--concurrency 3] [--baseline]
import { appendFileSync, existsSync, lstatSync, mkdirSync, readFileSync, rmSync, writeFileSync, chmodSync } from "node:fs"
import { join } from "node:path"
import { spawnSync, spawn } from "node:child_process"
import { createServer } from "node:net"
import { buildAgentHeader } from "../../src/core/agents/agent-header"
import { interactiveHtmlInstructions } from "../../src/core/show-html/description"
import { seed, SELF_ID } from "./seed"
import { TASKS } from "./tasks"
import { invocation, type AgentKind } from "./agents"

const REPO = join(import.meta.dir, "..", "..")
const arg = (k: string, d?: string) => { const i = process.argv.indexOf(`--${k}`); return i > 0 ? process.argv[i + 1] : d }
const AGENT = arg("agent", "claude") as AgentKind
const MODEL = arg("model", "haiku")!
const OUT = arg("out", `/tmp/ce/out/${AGENT}-${MODEL}`)!
const HOME = arg("home")
const ARMS = (arg("arms", "mcp,cli")!).split(",") as Array<"mcp" | "cli">
const SAMPLES = Number(arg("samples", "3"))
const CONC = Number(arg("concurrency", "3"))
const BASELINE = process.argv.includes("--baseline")
const PICK = arg("tasks")?.split(",")
const TIMEOUT_MS = 6 * 60_000
mkdirSync(OUT, { recursive: true })
const FAKE_HOME = "/tmp/ce/fakehome"
mkdirSync(join(FAKE_HOME, ".config", "issues"), { recursive: true })
writeFileSync(join(FAKE_HOME, ".config", "issues", "token"), "eval-fake-token-156\n")
const built = spawnSync(process.execPath, ["build", "--target=bun", join(import.meta.dir, "cli.ts"), "--outfile", "/tmp/ce/cli-bundle.js"], { encoding: "utf8" })
if (built.status !== 0) throw new Error(`cli bundle: ${built.stderr}`)
const CLI_BUNDLE = readFileSync("/tmp/ce/cli-bundle.js")

/** The CLI hint (§2), measured at 230 tokens; replaces the tools paragraph in the CLI arm. */
const CLI_HINT = `## Your supermux tools (the supermux CLI)

You run inside supermux. Use the \`supermux\` CLI (already authenticated for this session) (commands: session, msg, delegate, council, card, attach, file, port, memory, comments, display, device, docs, api). \`supermux help\` prints the whole reference on one page; add \`--json\` for machine output. Exit codes: 0 ok, 1 error, 2 usage, 3 not found, 4 denied, 5 broker down.
Common: \`supermux msg <session> "text"\` · \`supermux session spawn <dir>\` · \`supermux session ls\` · \`supermux card show page.html --title T\` · \`supermux attach FILE…\` · \`supermux memory search Q\`
The \`mux-shim\` MCP server keeps three tools for sessions without a shell: \`message_session\`, \`show_html\`, \`attach\`.`

function instructions(arm: "mcp" | "cli", workdir: string, muxHome: string, rundir: string): string {
  let env = readFileSync(join(REPO, "prompts", "environment.md"), "utf8")
  let header = buildAgentHeader({ name: "Eval session", role: "worker", workdir, agent: AGENT as any, id: SELF_ID, muxHome })
  if (arm === "cli") {
    env = env.replace(/## Your supermux tools \(mux-shim\)[\s\S]*?(?=\n## )/, CLI_HINT + "\n")
      .replace("use `memory_search` to find knowledge across domains, and\n`find_sessions` + `read_session`", "use `supermux memory search` to find knowledge across domains, and\n`supermux session find` + `supermux session read`")
    header = header.replace("by calling the `rename_session` tool", "with `supermux session rename NAME`")
  }
  const index = readFileSync(join(muxHome, "agents.md"), "utf8")
  const inbox = [
    "## Inbox",
    `Your session id: ${SELF_ID}. Your inbox: ${join(rundir, "inbox.jsonl")} (one JSON message per line; read it like any file).`,
    'A message from another session arrives as a turn that starts with "Message from [name:id]".',
    arm === "cli" ? 'Send a message with `supermux msg <session> "text"` (or message_session).' : 'Send a message with message_session({ to: ["<session-id>"], text, interrupt }).',
  ].join("\n")
  return [header, env + "\n" + interactiveHtmlInstructions(), index, inbox].join("\n").replaceAll("~/.mux", muxHome)
}

async function freePort(): Promise<number> {
  return await new Promise((res) => { const s = createServer(); s.listen(0, "127.0.0.1", () => { const p = (s.address() as any).port; s.close(() => res(p)) }) })
}
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

/** The agent's env: this shell's, minus every MUX_* (so nothing can reach the live broker), plus the run's. */
function agentEnv(extra: Record<string, string>): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) if (v !== undefined && !k.startsWith("MUX_") && k !== "ISSUES_AUTHOR") env[k] = v
  return { ...env, ...extra }
}

function quotaLow(): string | undefined {
  try {
    const u = JSON.parse(readFileSync(join(process.env.HOME!, ".mux/state/accounts/usage.json"), "utf8")).accounts as Record<string, Array<{ name: string; usedPercent: number }>>
    const key = AGENT === "claude" ? (HOME ? HOME.split("/").pop()! : "claude:system") : AGENT === "grok" ? "grok:system" : undefined
    const lim = key ? u[key] : undefined
    const cap = Number(arg("max-quota", "85"))
    const high = lim?.find((w) => w.usedPercent >= (w.name === "five_hour" ? 90 : cap))
    return high ? `${key} ${high.name} at ${Math.round(high.usedPercent)}%` : undefined
  } catch { return undefined }
}


/** spawnSync would block the other workers: the agent runs async, killed after TIMEOUT_MS. */
function runProc(cmd: string[], opts: { cwd: string; env: Record<string, string> }): Promise<{ status: number | null; stdout: string; stderr: string; timedOut: boolean }> {
  return new Promise((res) => {
    const p = spawn(cmd[0]!, cmd.slice(1), { cwd: opts.cwd, env: opts.env, stdio: ["ignore", "pipe", "pipe"] })
    let stdout = "", stderr = "", timedOut = false
    p.stdout.on("data", (d) => { stdout += d })
    p.stderr.on("data", (d) => { stderr += d })
    const t = setTimeout(() => { timedOut = true; p.kill("SIGKILL") }, TIMEOUT_MS)
    p.on("close", (status) => { clearTimeout(t); res({ status, stdout, stderr, timedOut }) })
  })
}

/**
 * The agent runs in a bubblewrap sandbox: the filesystem read-only; /tmp empty except this run; ~/.mux (live state,
 * memory, worktrees with this harness) hidden except the agent's own account home; the Docker socket gone; the issues
 * token replaced by a fake one the live board rejects. Its home dirs stay writable (sign-ins, caches). Network stays.
 */
const TUNNELS = ["/usr/bin/cloudflared", "/usr/local/bin/cloudflared", "/usr/bin/ngrok", "/usr/local/bin/ngrok", "/usr/bin/tailscale", "/usr/local/bin/bore"]
function sandboxed(cmd: string[], rundir: string): string[] {
  const H = process.env.HOME!
  const rw = [join(H, ".cache"), join(H, ".local", "share"), join(H, ".local", "state"), join(H, ".grok"), join(H, ".config")].filter(existsSync)
  return ["bwrap", "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp", "--bind", rundir, rundir,
    ...rw.flatMap((d) => ["--bind", d, d]),
    "--tmpfs", join(H, ".mux"), ...(HOME ? ["--bind", HOME, HOME] : []),
    "--ro-bind", join(FAKE_HOME, ".config", "issues", "token"), join(H, ".config", "issues", "token"),
    ...(existsSync("/run/docker.sock") ? ["--ro-bind", "/dev/null", "/run/docker.sock"] : []),
    // the stubs also cover the real binaries' absolute paths: a Grok run once called /usr/bin/cloudflared directly
    ...TUNNELS.flatMap((p) => (existsSync(p) && !lstatSync(p).isSymbolicLink() ? ["--ro-bind", join(rundir, "bin", "cloudflared"), p] : [])),
    "--unshare-pid", "--die-with-parent", "--", ...cmd]
}

interface Job { task: (typeof TASKS)[number] | { id: string; split: "B"; area: string; prompt: string; meta?: string; check: () => { pass: boolean; why: string } }; arm: "mcp" | "cli"; sample: number }

async function runOne(job: Job): Promise<Record<string, unknown>> {
  const runId = `${AGENT[0]}${job.arm[0]}${job.task.id.slice(0, 2)}${job.sample}${Math.random().toString(36).slice(2, 6)}`
  const rundir = join("/tmp/ce/r", runId)
  rmSync(rundir, { recursive: true, force: true })
  mkdirSync(rundir, { recursive: true })
  // what only the broker sees lives outside the run dir, under a name the agent is never told
  const priv = join("/tmp/ce/p", `${runId}-${Math.random().toString(36).slice(2, 10)}`)
  rmSync(priv, { recursive: true, force: true }); mkdirSync(priv, { recursive: true })
  await ensureDevServer()
  const relayBase = `http://127.0.0.1:${RELAY_PORT}/${runId}`
  RELAY_RUNS.set(runId, priv)
  const { workdir, muxHome } = seed(rundir, priv, relayBase)
  writeFileSync(join(rundir, "inbox.jsonl"), "")
  // the board: its own issues server on a temp data dir
  const issuesPort = await freePort()
  // it trusts the token in $HOME/.config/issues/token: a fake one, which the live board rejects
  const iss = spawn("issues", ["serve", "-addr", `127.0.0.1:${issuesPort}`, "-data", join(priv, "iss")], { stdio: "ignore", env: { ...process.env, HOME: FAKE_HOME } })
  // the throwaway broker: a container with no network, the repo read-only, the run dir read-write at the same path
  const cname = `ce-${runId}`
  const docker = spawnSync("docker", ["run", "-d", "--rm", "--name", cname, "--network", "none", "--user", `${process.getuid!()}:${process.getgid!()}`,
    "-e", `ARM=${job.arm}`, "-e", `RELAY_BASE=${relayBase}`, "-v", `${REPO}:${REPO}:ro`, "-v", `${rundir}:${rundir}`, "-v", `${priv}:${priv}`, "-v", `${process.execPath}:/usr/local/bin/bun:ro`,
    "-w", join(REPO, "evals", "cli"), "ubuntu:24.04", "bun", "broker.ts", rundir, priv], { encoding: "utf8" })
  if (docker.status !== 0) throw new Error(`docker: ${docker.stderr}`)
  for (let i = 0; i < 100 && !existsSync(join(priv, "broker.ready")); i++) await sleep(100)
  if (!existsSync(join(priv, "broker.ready"))) throw new Error("broker never came up: " + spawnSync("docker", ["logs", cname], { encoding: "utf8" }).stderr)
  // the CLI arm: a `supermux` on PATH
  const bin = join(rundir, "bin")
  mkdirSync(bin)
  // no real public tunnels from an eval: an agent once opened a cloudflared quick tunnel to the dev page (blocked in both arms)
  for (const t of ["cloudflared", "ngrok", "lt", "localtunnel", "tailscale", "bore"]) {
    writeFileSync(join(bin, t), `#!/bin/sh\necho "${t}: not available on this machine" >&2\nexit 127\n`)
    chmodSync(join(bin, t), 0o755)
  }
  if (job.arm === "cli") {
    writeFileSync(join(bin, "supermux.js"), CLI_BUNDLE)
    writeFileSync(join(bin, "supermux"), `#!/bin/sh\nexec ${JSON.stringify(process.execPath)} ${JSON.stringify(join(bin, "supermux.js"))} "$@"\n`)
    chmodSync(join(bin, "supermux"), 0o755)
  }
  const sessionEnv = { MUX_SESSION_ID: SELF_ID, MUX_SOCKETS_DIR: join(rundir, "s"), MUX_AGENT_KIND: AGENT, MUX_DISPLAY_NAME: "Eval session" }
  const shimEnv = { ...sessionEnv, MUX_STATE_DIR: join(rundir, "state"), MUX_HOME: muxHome }
  const prompt = job.task.meta ? `${job.task.meta}\n${job.task.prompt}\n</channel>` : job.task.prompt
  const inv = invocation(AGENT, { dir: workdir, prompt, model: MODEL, instructions: instructions(job.arm, workdir, muxHome, rundir), shimEnv, home: HOME })
  const env = agentEnv({ ...inv.env, ...sessionEnv, PATH: `${bin}:${process.env.PATH}`, ISSUES_URL: `http://127.0.0.1:${issuesPort}`, ISSUES_AUTHOR: "Eval session", EVAL_CLI_LOG: join(rundir, "cli.jsonl") })
  const t0 = Date.now()
  const proc = await runProc(sandboxed(inv.cmd, rundir), { cwd: workdir, env: { ...env, GROK_CLAUDE_MCPS_ENABLED: "false", GROK_CURSOR_MCPS_ENABLED: "false", GROK_FOLDER_TRUST: "0" } })
  const ms = Date.now() - t0
  writeFileSync(join(rundir, "stdout.txt"), proc.stdout ?? ""); writeFileSync(join(rundir, "stderr.txt"), proc.stderr ?? "")
  spawnSync("docker", ["rm", "-f", cname], { stdio: "ignore" })
  const issuesJson = spawnSync("issues", ["ls", "--all", "--json"], { env: { ...env, HOME: FAKE_HOME }, encoding: "utf8" }).stdout
  iss.kill()
  const { usage, finalText } = inv.parse(proc.stdout ?? "")
  const state = JSON.parse(readFileSync(join(priv, "state.json"), "utf8"))
  let issues: Array<{ title: string; priority: string }> = []
  try { const j = JSON.parse(issuesJson || "{}"); issues = ((Array.isArray(j) ? j : j.issues ?? []) as any[]).map((i) => ({ title: i.title, priority: i.priority })) } catch {}
  const calls = existsSync(join(priv, "calls.jsonl")) ? readFileSync(join(priv, "calls.jsonl"), "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)) : []
  const cli = existsSync(join(rundir, "cli.jsonl")) ? readFileSync(join(rundir, "cli.jsonl"), "utf8").trim().split("\n").filter(Boolean).map((l) => JSON.parse(l)) : []
  const verdict = job.task.split === "B" ? { pass: true, why: "baseline" } : (job.task as any).check({ state, answer: finalText, rundir, workdir, issues })
  const noAnswer = usage.inputTokens + usage.cachedTokens === 0 && !calls.length && !cli.length
  return {
    agent: AGENT, model: MODEL, arm: job.arm, task: job.task.id, split: job.task.split, area: job.task.area, sample: job.sample, run: runId,
    pass: verdict.pass, why: verdict.why, leaked: (proc.stdout ?? "").includes("/tmp/ce/p/"), noAnswer, exit: proc.status, timedOut: proc.timedOut,
    ms, firstInput: usage.firstInput, inputTokens: usage.inputTokens, cachedTokens: usage.cachedTokens, outputTokens: usage.outputTokens,
    totalTokens: usage.inputTokens + usage.cachedTokens + usage.outputTokens, costUsd: usage.costUsd, turns: usage.turns, toolCalls: usage.toolCalls,
    mcpCalls: calls.filter((c) => c.via === "mcp").length, mcpFailed: calls.filter((c) => c.via === "mcp" && !c.ok).length,
    cliCalls: cli.filter((c) => !c.help).length, cliFailed: cli.filter((c) => !c.help && c.exit !== 0).length, helpLookups: cli.filter((c) => c.help).length,
    ops: calls.map((c) => `${c.via}:${c.op}${c.ok ? "" : "!"}`), answer: finalText.slice(0, 600),
  }
}

const jobs: Job[] = []
if (BASELINE) for (const arm of ARMS) for (let s = 1; s <= SAMPLES; s++) jobs.push({ task: { id: "00-baseline", split: "B", area: "baseline", prompt: "Reply with just: ok", check: () => ({ pass: true, why: "" }) }, arm, sample: s })
else for (let s = 1; s <= SAMPLES; s++) for (const t of TASKS.filter((t) => !PICK || PICK.includes(t.id))) for (const arm of ARMS) jobs.push({ task: t, arm, sample: s })

// the dev server task 12 links to: one tiny server on :5173 for the whole eval
// The relay the eval's links point at: a public link proxies to its local port, a private one answers 401 like the
// real relay does for an unpaired browser. One server per runner; each run's links live under /<runId>/p/<slug>/.
const RELAY_RUNS = new Map<string, string>()
const relay = Bun.serve({ port: 0, hostname: "127.0.0.1", async fetch(req) {
  const m = new URL(req.url).pathname.match(/^\/([^/]+)\/p\/([^/]+)(\/.*)?$/)
  const priv = m && RELAY_RUNS.get(m[1]!)
  if (!m || !priv) return new Response("not found", { status: 404 })
  const file = existsSync(join(priv, "state.json")) ? join(priv, "state.json") : join(priv, "world.json")
  const p = (JSON.parse(readFileSync(file, "utf8")).proxies as Array<{ domain: string; port: number; public: boolean }>).find((x) => x.domain === m[2])
  if (!p) return new Response("no such link", { status: 404 })
  if (!p.public) return new Response("paired devices only: open it from a paired supermux device", { status: 401 })
  try { const r = await fetch(`http://127.0.0.1:${p.port}${m[3] ?? "/"}`, { signal: AbortSignal.timeout(3000) }); return new Response(r.body, { status: r.status, headers: r.headers }) }
  catch { return new Response(`bad gateway: nothing answers on port ${p.port}`, { status: 502 }) }
} })
const RELAY_PORT = relay.port

let devServer = spawn("python3", ["-m", "http.server", "5173", "--bind", "127.0.0.1", "--directory", join(import.meta.dir, "devsite")], { stdio: "ignore" })
devServer.unref()
/** Stage 1: a dev server left by a killed runner held :5173 without answering, and task 12 ran against it. Check before every run. */
async function ensureDevServer(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    try { const r = await fetch("http://127.0.0.1:5173/", { signal: AbortSignal.timeout(2000) }); if (r.ok) return } catch {}
    devServer.kill(); await sleep(500)
    devServer = spawn("python3", ["-m", "http.server", "5173", "--bind", "127.0.0.1", "--directory", join(import.meta.dir, "devsite")], { stdio: "ignore" }); devServer.unref()
    await sleep(800)
  }
  throw new Error("the dev server on :5173 does not answer (another process holds the port?)")
}

const resultsFile = join(OUT, BASELINE ? "baseline.jsonl" : "results.jsonl")
const done = new Set(existsSync(resultsFile) ? readFileSync(resultsFile, "utf8").trim().split("\n").filter(Boolean).map((l) => { const r = JSON.parse(l); return `${r.task}|${r.arm}|${r.sample}` }) : [])
const todo = jobs.filter((j) => !done.has(`${j.task.id}|${j.arm}|${j.sample}`))
console.log(`${AGENT} ${MODEL}: ${todo.length} runs to do (${done.size} already done) → ${resultsFile}`)
let noAnswerStreak = 0
let stop = ""
async function worker() {
  while (todo.length && !stop) {
    const low = quotaLow()
    if (low) { stop = `quota low: ${low}`; break }
    const job = todo.shift()!
    try {
      const r = await runOne(job)
      if (r.noAnswer) {
        appendFileSync(join(OUT, "no-answer.jsonl"), JSON.stringify(r) + "\n")
        if (++noAnswerStreak >= 3) stop = "3 runs in a row got no answer (a usage limit?)"
      } else { noAnswerStreak = 0; appendFileSync(resultsFile, JSON.stringify(r) + "\n") }
      console.log(`${r.task} ${r.arm} #${r.sample}: ${r.noAnswer ? "NO ANSWER" : r.pass ? "pass" : "FAIL"} (${r.why}) ${r.totalTokens} tok ${Math.round(Number(r.ms) / 1000)}s`)
    } catch (e) {
      console.error(`${job.task.id} ${job.arm} #${job.sample}: harness error: ${e}`)
      appendFileSync(join(OUT, "errors.jsonl"), JSON.stringify({ task: job.task.id, arm: job.arm, sample: job.sample, error: String(e) }) + "\n")
    }
  }
}
await Promise.all(Array.from({ length: CONC }, worker))
devServer.kill()
relay.stop(true)
if (stop) { console.error(`STOPPED: ${stop}`); process.exit(2) }
