// Helpers to attach a VIEWER to an AGENT's tmux window. Unlike the scratch
// terminals (their own `tmux -L muxterm` server), agents run on the DEFAULT
// tmux server, one window per agent in session `mux` (see session-manager/tmux.ts).
//
// To show ONE agent window interactively without disturbing the broker or other
// viewers, each viewer gets a throwaway GROUPED session (`new-session -t <agent>`):
// grouped sessions share windows but keep independent current-window + size.
// Detaching kills only the grouped session; the agent window survives.
import { spawn } from "child_process"

export type TmuxResult = { code: number; stdout: string; stderr: string }
export type TmuxRunner = (args: string[]) => Promise<TmuxResult>

const VIEW_PREFIX = "muxview_"

function hex(s: string): string {
  return Buffer.from(s, "utf8").toString("hex")
}

/** Grouped viewer session name for a (device, agentTarget) pair. Hex-encoded so
 * arbitrary device/target strings stay tmux-safe (no `.`/`:`/space). */
export function viewerSessionName(device: string, agentTarget: string): string {
  return `${VIEW_PREFIX}${hex(device)}_${hex(agentTarget)}`
}

/** Single-quote for embedding in the `sh -c` script (handles embedded quotes). */
function sq(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`
}

/**
 * argv for pty-helper to exec. The script resolves the target window's (session,
 * index) from its STABLE window-id at attach time — never pinning by window NAME
 * (names can be renamed and are not unique). `agentTarget` is normally a window-id
 * like "@5"; `display-message` also accepts a "session:window" fallback. It then
 * creates a grouped viewer session (shares the agent session's windows) and
 * selects the target window by index. `; ` runs each step regardless of the prior
 * failing (idempotent reuse); `exec` replaces the shell with tmux so SIGWINCH
 * (resize) reaches it. Exits non-zero if the window is gone.
 */
export function attachArgv(opts: { device: string; agentTarget: string }): string[] {
  const viewer = viewerSessionName(opts.device, opts.agentTarget)
  const tgt = sq(opts.agentTarget)
  const v = sq(viewer)
  const script =
    `s=$(tmux display-message -p -t ${tgt} '#{session_name}' 2>/dev/null); ` +
    `w=$(tmux display-message -p -t ${tgt} '#{window_index}' 2>/dev/null); ` +
    `[ -n "$s" ] && [ -n "$w" ] || exit 1; ` +
    `tmux new-session -d -s ${v} -t "$s" 2>/dev/null; ` +
    `tmux select-window -t ${v}:"$w" 2>/dev/null; ` +
    `exec tmux attach -t ${v}`
  return ["sh", "-c", script]
}

function makeRunner(): TmuxRunner {
  return (args: string[]) =>
    new Promise<TmuxResult>((resolve, reject) => {
      const proc = spawn("tmux", args, { stdio: ["ignore", "pipe", "pipe"] })
      let stdout = ""
      let stderr = ""
      proc.stdout.on("data", (d) => { stdout += d })
      proc.stderr.on("data", (d) => { stderr += d })
      proc.on("close", (code) => resolve({ code: code ?? 0, stdout, stderr }))
      proc.on("error", reject)
    })
}

export function createAgentTmux(opts: { run?: TmuxRunner } = {}) {
  const run = opts.run ?? makeRunner()

  /** Detach by destroying ONLY the grouped viewer session. Never the agent. */
  async function killViewer(device: string, agentTarget: string): Promise<void> {
    await run(["kill-session", "-t", viewerSessionName(device, agentTarget)])
  }

  /** Whether the agent's tmux window still exists. */
  async function hasAgentWindow(agentTarget: string): Promise<boolean> {
    const r = await run(["has-session", "-t", agentTarget])
    return r.code === 0
  }

  return { attachArgv, killViewer, hasAgentWindow }
}
