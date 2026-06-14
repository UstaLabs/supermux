// Helpers for the DEDICATED tmux server that backs supermux web terminals.
//
// Web terminals run on their own tmux server socket (`tmux -L muxterm`), kept
// entirely separate from the agent-session tmux server. Each web terminal is
// one tmux session named `muxterm_<hex(agentSession)>_<terminalId>`:
//   - hex-encoding the agent session name keeps it tmux-safe (no `.`/`:`/space)
//   - the constant prefix + per-session prefix let us list a session's
//     terminals and recover their ids purely from `tmux list-sessions`.
//
// The control ops (list/kill/has) go through an injectable runner so they can
// be unit-tested without spawning tmux. The ATTACH itself is not run here — it
// is exec'd by pty-helper — so `attachArgv` just builds the argv vector.
import { spawn } from "child_process"

export const MUXTERM_SOCKET = process.env.MUX_TERM_TMUX_SOCKET ?? "muxterm"

const PREFIX = "muxterm_"

export type TmuxResult = { code: number; stdout: string; stderr: string }
export type TmuxRunner = (args: string[]) => Promise<TmuxResult>

export interface TerminalSummary {
  id: string
  createdAt: number
}

function hexEncode(s: string): string {
  return Buffer.from(s, "utf8").toString("hex")
}

/** tmux session name backing a given (agentSession, terminalId). */
export function tmuxSessionName(agentSession: string, terminalId: string): string {
  return `${PREFIX}${hexEncode(agentSession)}_${terminalId}`
}

/** Prefix shared by every tmux session belonging to one agent session. */
export function sessionPrefixFor(agentSession: string): string {
  return `${PREFIX}${hexEncode(agentSession)}_`
}

/** Recover the terminalId from a tmux session name, or null if it isn't ours. */
export function parseTerminalId(agentSession: string, tmuxName: string): string | null {
  const p = sessionPrefixFor(agentSession)
  if (!tmuxName.startsWith(p)) return null
  const id = tmuxName.slice(p.length)
  return id.length > 0 ? id : null
}

/** argv for pty-helper to exec: attach-or-create the backing tmux session. */
export function attachArgv(opts: {
  agentSession: string
  terminalId: string
  workdir: string
  cols: number
  rows: number
  socket?: string
  confPath: string
}): string[] {
  const socket = opts.socket ?? MUXTERM_SOCKET
  const name = tmuxSessionName(opts.agentSession, opts.terminalId)
  // `new-session -A` = attach if it exists, else create. -x/-y set the initial
  // size on create; -c the start directory. -f sources our server config (sets
  // history-limit/mouse/status) when this invocation is what starts the server.
  return [
    "tmux", "-L", socket, "-f", opts.confPath,
    "new-session", "-A", "-s", name,
    "-x", String(opts.cols), "-y", String(opts.rows),
    "-c", opts.workdir,
  ]
}

function makeRunner(socket: string, confPath: string): TmuxRunner {
  return (args: string[]) =>
    new Promise<TmuxResult>((resolve, reject) => {
      const proc = spawn("tmux", ["-L", socket, "-f", confPath, ...args], {
        stdio: ["ignore", "pipe", "pipe"],
      })
      let stdout = ""
      let stderr = ""
      proc.stdout.on("data", (d) => { stdout += d })
      proc.stderr.on("data", (d) => { stderr += d })
      proc.on("close", (code) => resolve({ code: code ?? 0, stdout, stderr }))
      proc.on("error", reject)
    })
}

export function createTermTmux(opts: { socket?: string; confPath: string; run?: TmuxRunner }) {
  const socket = opts.socket ?? MUXTERM_SOCKET
  const run = opts.run ?? makeRunner(socket, opts.confPath)

  async function listTerminals(agentSession: string): Promise<TerminalSummary[]> {
    const r = await run(["list-sessions", "-F", "#{session_name}\t#{session_created}"])
    if (r.code !== 0) return [] // no server / no sessions yet
    const out: TerminalSummary[] = []
    for (const line of r.stdout.split("\n")) {
      const tab = line.indexOf("\t")
      const name = (tab === -1 ? line : line.slice(0, tab)).trim()
      if (!name) continue
      const id = parseTerminalId(agentSession, name)
      if (!id) continue
      const created = Number(tab === -1 ? "" : line.slice(tab + 1).trim())
      out.push({ id, createdAt: Number.isFinite(created) ? created * 1000 : 0 })
    }
    out.sort((a, b) => a.createdAt - b.createdAt)
    return out
  }

  async function killTerminal(agentSession: string, terminalId: string): Promise<void> {
    // Ignore "can't find session" — already gone is success.
    await run(["kill-session", "-t", tmuxSessionName(agentSession, terminalId)])
  }

  async function killAllTerminals(agentSession: string): Promise<void> {
    const terms = await listTerminals(agentSession)
    for (const t of terms) await killTerminal(agentSession, t.id)
  }

  async function hasTerminal(agentSession: string, terminalId: string): Promise<boolean> {
    const r = await run(["has-session", "-t", tmuxSessionName(agentSession, terminalId)])
    return r.code === 0
  }

  function buildAttachArgv(o: { agentSession: string; terminalId: string; workdir: string; cols: number; rows: number }): string[] {
    return attachArgv({ ...o, socket, confPath: opts.confPath })
  }

  return { socket, confPath: opts.confPath, listTerminals, killTerminal, killAllTerminals, hasTerminal, attachArgv: buildAttachArgv }
}
