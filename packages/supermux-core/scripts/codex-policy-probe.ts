/**
 * C3: where does a Codex child thread (spawn_agent) get its sandbox / approval policy from?
 *
 *   bun scripts/codex-policy-probe.ts [case …]
 *
 * Raw `codex app-server` (token mode, gpt-5.6-luna low), scratch CODEX_HOME + HOME under
 * ~/.cache/context-c3/. The parent is asked to spawn ONE subagent that writes a file OUTSIDE the
 * workspace; only danger-full-access allows that. Cases:
 *   write    the old driver: thread/start + turn/start policy, then config/batchWrite into CODEX_HOME
 *   none     per-thread / per-turn policy only (no -c, no file write)
 *   args     `app-server -c sandbox_mode=… -c approval_policy=…` (no file write)
 *   args-ro  launched read-only by -c, parent thread/turn danger-full-access (a live switch)
 *   ro       control: thread/start + turn/start read-only (the child's write must fail)
 * Each case reports whether the child's write landed, which thread ran the command, and the
 * CODEX_HOME config.toml afterwards.
 */
import { spawn } from "node:child_process"
import { existsSync, mkdirSync, readFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"
import { createInterface } from "node:readline"
import { codexTokenArgs, CODEX_TOKEN_ENV } from "../src/accounts/adapters/codex.js"
import { multiAgentV1Launch } from "../src/codex/catalog.js"

const HOME = homedir()
const RUN = join(HOME, ".cache", "context-c3", `codex-policy-${new Date().toISOString().replace(/[:.]/g, "-")}`)
const tokens = JSON.parse(readFileSync(join(HOME, ".codex", "auth.json"), "utf8")).tokens
const CASES = ["write", "none", "args", "args-ro", "ro"] as const
type Case = typeof CASES[number]
const wanted = process.argv.slice(2).filter((a): a is Case => (CASES as readonly string[]).includes(a))

function baseEnv(): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) {
    if (v === undefined || /^(MUX_|CLAUDECODE|CLAUDE_CODE_|CODEX_|OPENAI_)/.test(k)) continue
    env[k] = v
  }
  return env
}

async function probe(kind: Case) {
  const dir = join(RUN, kind)
  const codexHome = join(dir, "codex-home"), home = join(dir, "home"), work = join(dir, "work"), out = join(dir, "outside")
  for (const d of [codexHome, home, work, out]) mkdirSync(d, { recursive: true })
  const env = { ...baseEnv(), HOME: home, CODEX_HOME: codexHome, [CODEX_TOKEN_ENV]: tokens.access_token }
  const policyArgs = kind === "args" ? ["-c", 'sandbox_mode="danger-full-access"', "-c", 'approval_policy="never"']
    : kind === "args-ro" ? ["-c", 'sandbox_mode="read-only"', "-c", 'approval_policy="never"'] : []
  const launch = await multiAgentV1Launch("codex", ["app-server", ...codexTokenArgs(tokens.account_id), ...policyArgs], env, work)
  const child = spawn("codex", launch.args, { cwd: work, env, stdio: ["pipe", "pipe", "pipe"] })
  child.stderr.on("data", () => {})
  let next = 1
  const waiting = new Map<number, (v: any) => void>()
  const commands: Array<{ thread: string; command: string; status?: string }> = []
  let parentThread = ""
  let done!: () => void
  const finished = new Promise<void>(r => { done = r })
  createInterface({ input: child.stdout }).on("line", line => {
    let m: any
    try { m = JSON.parse(line) } catch { return }
    if (m.id !== undefined && waiting.has(m.id)) { waiting.get(m.id)!(m); waiting.delete(m.id); return }
    if (m.id !== undefined && m.method) {
      // Any approval request: refuse (the policy should make approvals unnecessary).
      child.stdin.write(JSON.stringify({ id: m.id, result: { decision: "denied" } }) + "\n")
      commands.push({ thread: m.params?.threadId ?? "?", command: `APPROVAL ${m.method}` })
      return
    }
    if (m.method === "item/completed" && m.params?.item?.type === "commandExecution") {
      commands.push({ thread: m.params.threadId, command: m.params.item.command, status: m.params.item.status })
    }
    if (m.method === "turn/completed" && m.params?.threadId === parentThread) done()
  })
  const request = (method: string, params: unknown) => new Promise<any>((resolve) => {
    const id = next++
    waiting.set(id, resolve)
    child.stdin.write(JSON.stringify({ id, method, params }) + "\n")
  })
  await request("initialize", { clientInfo: { name: "c3-probe", version: "0" }, capabilities: { experimentalApi: true } })
  child.stdin.write(JSON.stringify({ method: "initialized", params: {} }) + "\n")
  const danger = { approvalPolicy: "never", sandbox: kind === "ro" ? "read-only" : "danger-full-access" }
  const started = await request("thread/start", { cwd: work, model: "gpt-5.6-luna", ...danger })
  parentThread = started.result?.thread?.id
  if (kind === "write") {
    const w = await request("config/batchWrite", { edits: [{ keyPath: "sandbox_mode", value: "danger-full-access", mergeStrategy: "replace" }, { keyPath: "approval_policy", value: "never", mergeStrategy: "replace" }], reloadUserConfig: true })
    if (w.error) console.log(kind, "batchWrite error", w.error)
  }
  const target = join(out, `${kind}.txt`)
  const prompt = `Do NOT run any command yourself. Use your spawn_agent tool to start exactly one subagent with this task: ` +
    `"Run the shell command: echo child-wrote > ${target}  and report the exit status." Then wait for that subagent to finish (use your wait tool) and reply with one line: CHILD=<what it reported>.`
  await request("turn/start", { threadId: parentThread, input: [{ type: "text", text: prompt }], effort: "low", approvalPolicy: "never", sandboxPolicy: { type: kind === "ro" ? "readOnly" : "dangerFullAccess" } })
  await Promise.race([finished, new Promise(r => setTimeout(r, 240_000))])
  child.kill("SIGTERM")
  const config = existsSync(join(codexHome, "config.toml")) ? readFileSync(join(codexHome, "config.toml"), "utf8").trim() : "(no config.toml)"
  const landed = existsSync(target)
  console.log(JSON.stringify({ case: kind, childWriteLanded: landed, parentThread, commands, codexHomeConfigToml: config }))
  return landed
}

console.log("scratch:", RUN)
for (const kind of wanted.length ? wanted : [...CASES]) await probe(kind)
process.exit(0)
