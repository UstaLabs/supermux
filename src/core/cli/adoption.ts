// #156: does every live session know the `supermux` CLI yet? MCP is cut down to its kept tools only once that is true
// and the moved tools have gone unused over MCP for a while (Ahmet's plan: side by side, then the cut).
//
// Per session, the broker records what it can know for certain:
//   launchedWithCliAt  its agent process started with the CLI on PATH and the hint in its instructions;
//   noteAt             a process that started before the CLI got the one-time note (see cliNoteFor);
//   lastCliAt / cliCalls          calls that came from the CLI (call ids "cli-…");
//   lastMcpMovedAt / mcpMovedCalls calls of an MCP tool the CLI also does (CLI_EQUIVALENT).
// It persists in a small JSON file, so the record spans broker restarts (the decision spans days).
import { existsSync, readFileSync, renameSync, writeFileSync } from "fs"
import { host } from "@supermux/platform"
import { spawnCommandSync } from "../process/launcher"
import { readEnvironmentMd } from "../agents/environment"
import { CLI_EQUIVALENT } from "../../shim/cli-equivalents"

export interface AdoptionRow {
  launchedWithCliAt?: string
  noteAt?: string
  lastCliAt?: string
  cliCalls: number
  lastMcpMovedAt?: string
  mcpMovedCalls: number
}

export class CliAdoption {
  private rows: Record<string, AdoptionRow> = {}
  private timer: ReturnType<typeof setTimeout> | undefined

  /** `off`: the unbound default (tests, scripts): everyone counts as knowing, so no note is ever added. */
  constructor(private readonly file?: string, private readonly now: () => Date = () => new Date(), private readonly off = false) {
    if (file && existsSync(file)) {
      try { this.rows = JSON.parse(readFileSync(file, "utf8")).sessions ?? {} } catch { this.rows = {} }
    }
  }

  get(id: string): AdoptionRow | undefined { return this.rows[id] }
  all(): Record<string, AdoptionRow> { return this.rows }
  /** It has the CLI: launched with it, or told about it. */
  isOff(): boolean { return this.off }
  knows(id: string): boolean { if (this.off) return true; const r = this.rows[id]; return !!(r?.launchedWithCliAt || r?.noteAt) }

  markLaunch(id: string): void { this.row(id).launchedWithCliAt = this.stamp() ; this.save() }
  markNote(id: string): void { this.row(id).noteAt = this.stamp(); this.save() }

  /** A broker op from session `id`: from the CLI, or an MCP call of a tool the CLI also does. */
  recordCall(id: string, via: "cli" | "mcp", op: string): void {
    if (via === "cli") { const r = this.row(id); r.cliCalls++; r.lastCliAt = this.stamp(); this.save(); return }
    if (!CLI_EQUIVALENT[op]) return
    const r = this.row(id); r.mcpMovedCalls++; r.lastMcpMovedAt = this.stamp(); this.save()
  }

  private row(id: string): AdoptionRow { return (this.rows[id] ??= { cliCalls: 0, mcpMovedCalls: 0 }) }
  private stamp(): string { return this.now().toISOString() }

  /** Coalesced, atomic writes (a burst of calls costs one write). */
  private save(): void {
    if (!this.file || this.timer) return
    this.timer = setTimeout(() => { this.timer = undefined; this.flush() }, 1000)
    this.timer.unref?.()
  }

  flush(): void {
    if (!this.file) return
    const tmp = `${this.file}.tmp`
    writeFileSync(tmp, JSON.stringify({ version: 1, sessions: this.rows }))
    renameSync(tmp, this.file)
  }
}

let current = new CliAdoption(undefined, undefined, true)
/** main.ts binds the broker's store (with its file); tests and the default get an in-memory one. */
export function bindCliAdoption(a: CliAdoption): () => void { const prev = current; current = a; return () => { current = prev } }
export function cliAdoption(): CliAdoption { return current }

/**
 * The CLI hint every new session gets, as it stands in prompts/environment.md (the paragraph that starts "The same
 * things are a CLI in your shell"): read from there, so the note can never drift from what new sessions are told.
 */
export function cliHintFromEnvironment(env: string = readEnvironmentMd()): string | undefined {
  // A Windows checkout has CRLF: the note is LF like the rest of the turn.
  return env.replace(/\r\n?/g, "\n").split(/\n\s*\n/).map((p) => p.trim()).find((p) => p.startsWith("The same things are a CLI in your shell"))
}

/**
 * The one-time note for a session whose agent process started before the CLI: prepended to its next turn (never a
 * wake of its own), framed as a system note (Ahmet, 2026-11-16). It carries the same hint new sessions get, plus the
 * one thing that differs for this process: no `supermux` on PATH (and, unless it is Claude, no session identity in its
 * env), so the exact command to run instead is shown once.
 */
export function cliNoteFor(sessionId: string, opts: { binDir: string; socketsDir: string; platform?: NodeJS.Platform; env?: string }): string {
  // PowerShell on Windows (the shell agents get there), sh everywhere else.
  const run = opts.platform === "win32"
    ? `$env:MUX_SESSION_ID="${sessionId}"; $env:MUX_SOCKETS_DIR="${opts.socketsDir}"; & "${opts.binDir}\\supermux.cmd"`
    : `MUX_SESSION_ID=${sessionId} MUX_SOCKETS_DIR=${opts.socketsDir} ${opts.binDir}/supermux`
  const hint = cliHintFromEnvironment(opts.env)
  return [
    "<system-reminder>",
    "This is a one-time note from supermux, not from the user. It needs no reply or acknowledgement: just use the CLI from now on, and answer the user's message below as usual.",
    "supermux now has a CLI that does what the mux-shim MCP tools do. New sessions are told:",
    hint ?? "`supermux help` prints the whole reference on one page; add `--json` for JSON.",
    `Your process started before it, so \`supermux\` is not on your PATH: wherever the above says \`supermux\`, run \`${run}\` instead.`,
    "</system-reminder>",
  ].join("\n")
}

/**
 * Does agent process `pid` have the CLI: the launcher dir on its PATH? Read from the process's own environment (Linux
 * /proc/<pid>/environ, macOS `ps -E`), which a same-user process may read. undefined when this OS can't tell (Windows) or
 * the process is gone. This, not the launch mark, decides the note: a broker restart RE-ATTACHES to running agents (their
 * keepers outlive it), so preparing a launch does not mean a new process with the new env started (seen 2026-11-16: every
 * resumed session was marked, but their processes were days old).
 */
export function processHasCli(pid: number, binDir: string, platform: NodeJS.Platform = host.os()): boolean | undefined {
  try {
    let env: string
    if (platform === "linux") env = readFileSync(`/proc/${pid}/environ`, "utf8").split("\0").join("\n")
    else if (platform === "darwin") {
      const r = spawnCommandSync("ps", ["-E", "-ww", "-p", String(pid), "-o", "command="], { encoding: "utf8", timeout: 2000 })
      if (r.error || r.status !== 0) return undefined
      env = r.stdout.toString().split(" ").join("\n")
    } else return undefined
    const path = env.split("\n").find((l) => l.startsWith("PATH="))
    return path === undefined ? undefined : path.slice(5).split(":").includes(binDir)
  } catch { return undefined }
}

/**
 * Does session `id` know the CLI? Told by the note; else its agent process's own env says (when the OS can tell);
 * else the launch mark.
 */
export function sessionKnowsCli(id: string, hasCli: () => boolean | undefined, a: CliAdoption = current): boolean {
  if (a.isOff()) return true
  if (a.get(id)?.noteAt) return true
  const seen = hasCli()
  return seen ?? a.knows(id)
}

/** `text` for a turn of session `id`, with the one-time note in front when its process predates the CLI (then marked). */
export function withCliNote(id: string, internal: boolean, text: string, opts: { binDir: string; socketsDir: string; platform?: NodeJS.Platform; hasCli?: () => boolean | undefined }, a: CliAdoption = current): string {
  if (internal || sessionKnowsCli(id, opts.hasCli ?? (() => undefined), a)) return text
  a.markNote(id)
  return `${cliNoteFor(id, opts)}\n\n${text}`
}
