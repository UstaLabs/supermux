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

/** Split an agent target like "mux:sess-1" into { session, window }. The window
 * keeps any further colons (window names can contain them). */
export function splitTarget(agentTarget: string): { session: string; window: string } {
  const i = agentTarget.indexOf(":")
  if (i === -1) return { session: agentTarget, window: "" }
  return { session: agentTarget.slice(0, i), window: agentTarget.slice(i + 1) }
}

/** Single-quote for embedding in the `sh -c` script (handles embedded quotes). */
function sq(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`
}

/**
 * argv for pty-helper to exec. The script (1) creates the grouped viewer
 * idempotently, (2) pins it to the agent's window, (3) exec-attaches.
 * `; ` runs each step regardless of the prior failing (so a lingering viewer is
 * reused). `exec` replaces the shell with tmux so SIGWINCH (resize) reaches it.
 */
export function attachArgv(opts: { device: string; agentTarget: string }): string[] {
  const viewer = viewerSessionName(opts.device, opts.agentTarget)
  const { session, window } = splitTarget(opts.agentTarget)
  const script =
    `tmux new-session -d -s ${sq(viewer)} -t ${sq(session)} 2>/dev/null; ` +
    `tmux select-window -t ${sq(`${viewer}:${window}`)} 2>/dev/null; ` +
    `exec tmux attach -t ${sq(viewer)}`
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
