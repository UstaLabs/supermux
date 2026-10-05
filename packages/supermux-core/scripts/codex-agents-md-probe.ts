/**
 * C3: does a resumed Codex thread re-read <CODEX_HOME>/AGENTS.md, or keep what it got at start?
 * (Decides whether the broker may stop writing / remove the per-session AGENTS.md.)
 *
 *   bun scripts/codex-agents-md-probe.ts
 *
 * Token mode, gpt-5.6-luna low, scratch under ~/.cache/context-c3/. Thread started with AGENTS.md
 * holding token A; the app-server is stopped, AGENTS.md is rewritten to token B (case "changed")
 * or removed (case "removed"), a new app-server resumes the thread and is asked which tokens its
 * instructions contain.
 */
import { spawn, type ChildProcess } from "node:child_process"
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"
import { createInterface } from "node:readline"
import { codexTokenArgs, CODEX_TOKEN_ENV } from "../src/accounts/adapters/codex.js"

const HOME = homedir()
const RUN = join(HOME, ".cache", "context-c3", `codex-agents-md-${new Date().toISOString().replace(/[:.]/g, "-")}`)
const tokens = JSON.parse(readFileSync(join(HOME, ".codex", "auth.json"), "utf8")).tokens

function baseEnv(): Record<string, string> {
  const env: Record<string, string> = {}
  for (const [k, v] of Object.entries(process.env)) if (v !== undefined && !/^(MUX_|CLAUDECODE|CLAUDE_CODE_|CODEX_|OPENAI_)/.test(k)) env[k] = v
  return env
}

function appServer(env: Record<string, string>, cwd: string) {
  const child: ChildProcess = spawn("codex", ["app-server", ...codexTokenArgs(tokens.account_id)], { cwd, env, stdio: ["pipe", "pipe", "pipe"] })
  child.stderr!.on("data", () => {})
  let next = 1
  const waiting = new Map<number, (v: any) => void>()
  let text = ""
  let turnDone: (() => void) | undefined
  createInterface({ input: child.stdout! }).on("line", line => {
    let m: any
    try { m = JSON.parse(line) } catch { return }
    if (m.id !== undefined && waiting.has(m.id)) { waiting.get(m.id)!(m); waiting.delete(m.id); return }
    if (m.method === "item/agentMessage/delta") text += m.params?.delta ?? ""
    if (m.method === "turn/completed") turnDone?.()
  })
  const request = (method: string, params: unknown) => new Promise<any>(resolve => {
    const id = next++
    waiting.set(id, resolve)
    child.stdin!.write(JSON.stringify({ id, method, params }) + "\n")
  })
  return {
    async init() {
      await request("initialize", { clientInfo: { name: "c3-probe", version: "0" }, capabilities: { experimentalApi: true } })
      child.stdin!.write(JSON.stringify({ method: "initialized", params: {} }) + "\n")
    },
    request,
    async ask(threadId: string, prompt: string) {
      text = ""
      const done = new Promise<void>(r => { turnDone = r })
      await request("turn/start", { threadId, input: [{ type: "text", text: prompt }], effort: "low" })
      await Promise.race([done, new Promise(r => setTimeout(r, 120_000))])
      return text.trim()
    },
    stop() { child.kill("SIGTERM") },
  }
}

const QUESTION = "List every token of the form AGENTS-TOKEN-<WORD> that appears in your instructions (AGENTS.md / developer or user instructions). Answer on one line: TOKENS=<comma list or NONE>. Do not run tools."

for (const kind of ["changed", "removed"] as const) {
  const dir = join(RUN, kind)
  const codexHome = join(dir, "codex-home"), home = join(dir, "home"), work = join(dir, "work")
  for (const d of [codexHome, home, work]) mkdirSync(d, { recursive: true })
  const env = { ...baseEnv(), HOME: home, CODEX_HOME: codexHome, [CODEX_TOKEN_ENV]: tokens.access_token }
  writeFileSync(join(codexHome, "AGENTS.md"), "AGENTS-TOKEN-ALPHA is your session token.\n")
  const first = appServer(env, work)
  await first.init()
  const started = await first.request("thread/start", { cwd: work, model: "gpt-5.6-luna", approvalPolicy: "never", sandbox: "read-only" })
  const threadId = started.result.thread.id
  const before = await first.ask(threadId, QUESTION)
  first.stop()
  await new Promise(r => setTimeout(r, 1500))
  if (kind === "changed") writeFileSync(join(codexHome, "AGENTS.md"), "AGENTS-TOKEN-BRAVO is your session token.\n")
  else rmSync(join(codexHome, "AGENTS.md"))
  const second = appServer(env, work)
  await second.init()
  await second.request("thread/resume", { threadId, cwd: work, approvalPolicy: "never", sandbox: "read-only" })
  const after = await second.ask(threadId, QUESTION + " Look only at your instructions as they are NOW, not at earlier answers in this conversation.")
  second.stop()
  console.log(JSON.stringify({ case: kind, atStart: before, afterResume: after }))
}
console.log("scratch:", RUN)
process.exit(0)
