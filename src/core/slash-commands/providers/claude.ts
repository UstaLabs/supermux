import { spawn as defaultSpawn } from "child_process"
import { makeLogger } from "../../../shared/log"
import { AgentKind } from "../../../shared/agents"
import { agentCommand, type AgentCommandProvider, type ProviderCtx, type SlashCommand } from "../types"

const log = makeLogger("slash/claude")

/** Returns the slash_commands array if `line` is the stream-json system/init frame, else null. */
export function parseClaudeInitLine(line: string): string[] | null {
  let msg: any
  try { msg = JSON.parse(line) } catch { return null }
  if (msg?.type === "system" && msg?.subtype === "init" && Array.isArray(msg.slash_commands)) {
    return msg.slash_commands.filter((s: unknown): s is string => typeof s === "string")
  }
  return null
}

export function claudeNamesToCommands(names: string[]): SlashCommand[] {
  return names.map((name) => agentCommand({ name, sigil: "/" }))
}

type SpawnFn = (cmd: string, args: string[], opts: any) => any

// Limits how many `claude --print` probes run at once, so a broker restart with
// many Claude sessions doesn't fork N ~300MB processes simultaneously.
class Semaphore {
  private active = 0
  private queue: (() => void)[] = []
  constructor(private max: number) {}
  async run<T>(fn: () => Promise<T>): Promise<T> {
    if (this.active >= this.max) await new Promise<void>((r) => this.queue.push(r))
    this.active++
    try { return await fn() }
    finally { this.active--; this.queue.shift()?.() }
  }
}

// Discovers a Claude session's slash commands by running a one-shot headless
// probe (`claude --print --output-format stream-json`). The `system/init` frame
// carries the full authoritative `slash_commands` list (bundled binary skills +
// ~/.claude/skills + plugin-namespaced) and arrives BEFORE the model turn — so
// we read only that line, then kill the child (no assistant turn billed).
// Verified 2026-05-30: 45 commands, ~8s, no billed turn. See the design doc.
export class ClaudeCommandProvider implements AgentCommandProvider {
  readonly kind = AgentKind.Claude
  private readonly spawn: SpawnFn
  private readonly timeoutMs: number
  private readonly sem: Semaphore
  constructor(opts: { spawn?: SpawnFn; timeoutMs?: number; maxConcurrent?: number } = {}) {
    this.spawn = opts.spawn ?? (defaultSpawn as unknown as SpawnFn)
    this.timeoutMs = opts.timeoutMs ?? 25_000
    this.sem = new Semaphore(opts.maxConcurrent ?? 2)
  }

  list(ctx: ProviderCtx): Promise<SlashCommand[]> {
    return this.sem.run(() => this.probe(ctx))
  }

  private probe(ctx: ProviderCtx): Promise<SlashCommand[]> {
    return new Promise((resolve) => {
      const args = [
        "--print", "--output-format", "stream-json", "--verbose",
        ...ctx.pluginSpawnArgs,
        "hi", // any prompt; we kill before the model turn runs
      ]
      let child: any
      try {
        child = this.spawn("claude", args, { cwd: ctx.workdir, env: process.env, stdio: ["pipe", "pipe", "ignore"] })
      } catch (err: any) {
        log.debug("claude_probe_spawn_failed", { err: err?.message })
        return resolve([])
      }
      let done = false
      let buf = ""
      const finish = (cmds: SlashCommand[]) => {
        if (done) return
        done = true
        clearTimeout(timer)
        try { child.stdin?.end() } catch {}
        try { if (!child.killed) child.kill("SIGTERM") } catch {}
        resolve(cmds)
      }
      const timer = setTimeout(() => {
        log.debug("claude_probe_timeout", { session: ctx.sessionName })
        finish([])
      }, this.timeoutMs)
      child.on("error", () => finish([]))
      child.on("exit", () => finish([])) // exited before init → empty
      child.stdout.on("data", (d: Buffer) => {
        buf += d.toString("utf8")
        let i: number
        while ((i = buf.indexOf("\n")) >= 0) {
          const line = buf.slice(0, i); buf = buf.slice(i + 1)
          const names = parseClaudeInitLine(line.trim())
          if (names) return finish(claudeNamesToCommands(names))
        }
      })
    })
  }
}
