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
 * The one-time note for a session whose agent process started before the CLI: prepended to its next turn (never a
 * wake of its own). That process has no `supermux` on PATH and, unless it is Claude, no session identity in its env,
 * so the note gives the exact command to run.
 */
export function cliNoteFor(sessionId: string, opts: { binDir: string; socketsDir: string; platform?: NodeJS.Platform }): string {
  // PowerShell on Windows (the shell agents get there), sh everywhere else.
  const run = opts.platform === "win32"
    ? `$env:MUX_SESSION_ID="${sessionId}"; $env:MUX_SOCKETS_DIR="${opts.socketsDir}"; & "${opts.binDir}\\supermux.cmd"`
    : `MUX_SESSION_ID=${sessionId} MUX_SOCKETS_DIR=${opts.socketsDir} ${opts.binDir}/supermux`
  return [
    "[supermux note, shown once] supermux now has a CLI that does what the mux-shim MCP tools do, from your shell.",
    `Your process started before it, so run it as: ${run} help   (the whole reference on one page; then the same prefix for every command).`,
    "Sessions started from now on have plain `supermux` on PATH. Background work (delegate, council, replies) arrives in your inbox: never wait or poll for it.",
  ].join("\n")
}

/** `text` for a turn of session `id`, with the one-time note in front when its process predates the CLI (then marked). */
export function withCliNote(id: string, internal: boolean, text: string, opts: { binDir: string; socketsDir: string; platform?: NodeJS.Platform }, a: CliAdoption = current): string {
  if (internal || a.knows(id)) return text
  a.markNote(id)
  return `${cliNoteFor(id, opts)}\n\n${text}`
}
