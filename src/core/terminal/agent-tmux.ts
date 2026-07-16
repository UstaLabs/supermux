// Helpers to attach a VIEWER to an AGENT's tmux window. Unlike the scratch
// terminals (their own `tmux -L muxterm` server), agents run on the DEFAULT
// tmux server, one window per agent in session `mux` (see session-manager/tmux.ts).
//
// To show ONE agent window interactively without disturbing the broker or other
// viewers, each viewer gets a throwaway DEDICATED session that contains ONLY the
// target window, LINKED in by its stable window-id (`link-window -s @id`).
// Detaching / killViewer destroys only the viewer session; the agent window
// survives (it still lives in `mux`).
//
// Why NOT a grouped session (`new-session -t mux`, the old approach): a grouped
// session shares the agent session's ENTIRE window list. When the viewed agent's
// process is killed (e.g. OS OOM) its window closes, and tmux slides the still-
// attached grouped viewer to a NEIGHBORING window — i.e. a DIFFERENT agent — so
// the Native tab silently starts streaming someone else's terminal. A dedicated
// single-window session has no neighbor to fall to: when the window dies the
// session empties and is destroyed, so the viewer cleanly exits instead.
import { spawn } from "child_process"

export type TmuxResult = { code: number; stdout: string; stderr: string }
export type TmuxRunner = (args: string[]) => Promise<TmuxResult>

const VIEW_PREFIX = "muxview_"

function hex(s: string): string {
  return Buffer.from(s, "utf8").toString("hex")
}

/** Dedicated viewer session name for a (device, agentTarget) pair. Hex-encoded so
 * arbitrary device/target strings stay tmux-safe (no `.`/`:`/space). */
export function viewerSessionName(device: string, agentTarget: string): string {
  return `${VIEW_PREFIX}${hex(device)}_${hex(agentTarget)}`
}

// Placeholder window every viewer session is born with (new-session must create
// one window). We link the real target in, then kill this by its unique name.
const PLACEHOLDER = "_mux_ph"

/** Single-quote for embedding in the `sh -c` script (handles embedded quotes). */
function sq(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`
}

/**
 * argv for pty-helper to exec. Resolves the target's STABLE window-id (`@N`) —
 * never pinning by window NAME (renamable, non-unique) or window INDEX (positional,
 * reused when a sibling window closes). `agentTarget` is normally a window-id like
 * "@5"; `display-message` also accepts a "session:window" fallback, which we
 * normalize to its id here.
 *
 * It then reuses-or-builds a DEDICATED viewer session holding ONLY that window,
 * linked in by id: born with a placeholder window, link the target, kill the
 * placeholder. On reconnect the session already exists — we re-link only if the
 * window is missing (avoids a duplicate) and skip straight to attach. `exec`
 * replaces the shell with tmux so SIGWINCH (resize) reaches it.
 *
 * Exits non-zero if the window is gone (target unresolvable, or the link left the
 * session empty) — the client then sees a clean exit rather than a neighbor's pane.
 */
export function attachArgv(opts: { device: string; agentTarget: string }): string[] {
  const viewer = viewerSessionName(opts.device, opts.agentTarget)
  const tgt = sq(opts.agentTarget)
  const v = sq(viewer)
  const ph = sq(PLACEHOLDER)
  const script =
    // Normalize the target to its stable window-id; bail if it no longer exists.
    `wid=$(tmux display-message -p -t ${tgt} '#{window_id}' 2>/dev/null); ` +
    `[ -n "$wid" ] || exit 1; ` +
    // Reuse-or-create the dedicated viewer. new-session is a no-op if it exists
    // (2>/dev/null); it must birth one window, so name it the placeholder.
    `tmux new-session -d -s ${v} -n ${ph} 'exec sh' 2>/dev/null; ` +
    // Link the target window in by id — but only if not already present, so a
    // reconnect into an existing viewer doesn't stack a duplicate window.
    `tmux list-windows -t ${v} -F '#{window_id}' 2>/dev/null | grep -qx "$wid" || ` +
    `tmux link-window -s "$wid" -t ${v}: 2>/dev/null; ` +
    // Drop the placeholder (no-op if a prior attach already did). If the link
    // failed (target vanished), this empties the session → it's destroyed →
    // the attach below exits non-zero. No neighbor to fall through to.
    `tmux kill-window -t ${v}:${ph} 2>/dev/null; ` +
    // Per-viewer: hide the default tmux status bar so the agent's TUI gets the
    // full height and no tmux chrome (the green status line) leaks into the
    // Native view. Scoped to the viewer session — `mux` and other viewers are
    // unaffected.
    `tmux set-option -t ${v} status off 2>/dev/null; ` +
    // Enable mouse on the viewer session so the web terminal's wheel-forwarding
    // (touch-drag → SGR wheel) reaches tmux and scrolls the pane history. The
    // DEFAULT tmux server is mouse-off (unlike the scratch `-L muxterm` server),
    // which leaves xterm's mouseTrackingMode 'none' → touch scroll falls back to
    // a no-op in tmux's alt-screen buffer. Scoped to the viewer session, so the
    // base `mux` session (and the agent itself) stay mouse-off.
    `tmux set-option -t ${v} mouse on 2>/dev/null; ` +
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

  return { attachArgv, killViewer }
}
