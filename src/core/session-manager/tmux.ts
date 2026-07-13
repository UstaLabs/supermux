type TmuxResult = { code: number; stdout: string; stderr: string }
type TmuxRunner = (args: string[]) => Promise<TmuxResult>
// Runs an arbitrary command (used to wrap tmux in `systemd-run`); injectable for tests.
type CmdRunner = (cmd: string, args: string[]) => Promise<TmuxResult>

export const TMUX_COMMAND_TIMEOUT_MS = 10_000

export async function runCommand(cmd: string, args: string[], timeoutMs = TMUX_COMMAND_TIMEOUT_MS): Promise<TmuxResult> {
  // The broker is a Bun binary; use Bun's native subprocess lifecycle instead of its Node
  // child_process compatibility events, which are unreliable for tmux clients in a compiled
  // macOS executable. The first-server path is separately detached below, so every command that
  // reaches this runner is expected to exit and close its own output streams.
  const proc = Bun.spawn([cmd, ...args], { stdin: "ignore", stdout: "pipe", stderr: "pipe" })
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const code = await Promise.race([
      proc.exited,
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => {
          proc.kill()
          reject(new Error(`${cmd} timed out after ${timeoutMs}ms`))
        }, timeoutMs)
      }),
    ])
    const [stdout, stderr] = await Promise.all([
      new Response(proc.stdout).text(),
      new Response(proc.stderr).text(),
    ])
    return { code, stdout, stderr }
  } finally {
    if (timer) clearTimeout(timer)
  }
}

const runTmux: TmuxRunner = (args) => runCommand("tmux", args)

// Birth the tmux SERVER in its own systemd scope so it OUTLIVES the broker.
// The broker runs under mux.service (default KillMode=control-group); a server
// spawned as a plain broker child lives in that cgroup and is SIGKILLed on every
// broker restart/redeploy — taking every agent pane (= every session) with it.
// `systemd-run --user --scope` puts the server in a sibling scope of mux.service,
// outside the kill set, so sessions survive a broker restart (tmux already moves
// each PANE into its own scope, so only the server needed decoupling). Falls back
// to a plain spawn where systemd-run is unavailable (Docker, non-systemd) or the
// scope can't be created — no worse than the previous behavior.
async function newSessionScoped(runRaw: CmdRunner, run: TmuxRunner, tmuxArgs: string[]): Promise<TmuxResult> {
  try {
    const r = await runRaw("systemd-run", ["--user", "--scope", "--quiet", "--collect", "tmux", ...tmuxArgs])
    if (r.code === 0) return r
  } catch {
    // systemd-run not on PATH — fall through to a plain tmux spawn.
  }
  // On macOS the first `tmux new-session` process becomes the long-lived server. Waiting on that
  // process from Bun therefore waits forever even though the session is ready. Birth it behind a
  // short-lived shell with every stdio redirected; the shell returns immediately and the caller
  // resolves the authoritative window id by polling the now-running server.
  return runRaw("/bin/sh", [
    "-c", '"$@" </dev/null >/dev/null 2>&1 &', "supermux-tmux-detached", "tmux", ...tmuxArgs,
  ])
}

export function createTmuxClient(run: TmuxRunner = runTmux, runRaw: CmdRunner = runCommand) {
  async function spawnSessionWindow(opts: {
    session: string
    window: string
    workdir: string
    command: string
    env?: Record<string, string>
  }): Promise<{ windowId: string }> {
    // If session doesn't exist, create it detached with the first window; else new-window in existing.
    const has = await run(["has-session", "-t", opts.session])
    let r: TmuxResult
    if (has.code !== 0) {
      r = await newSessionScoped(runRaw, run, ["new-session", "-d", "-P", "-F", "#{window_id}", "-s", opts.session, "-n", opts.window, "-c", opts.workdir, opts.command])
      if (r.code !== 0) throw new Error(`tmux new-session failed: ${r.stderr}`)
    } else {
      // Trailing colon disambiguates: `-t mux:` means "session named
      // supermux, auto-assign window index". Without it, tmux resolves the
      // bare name against BOTH sessions and windows — if a window happens to
      // be named "supermux" at index 1, tmux tries to create the new window
      // at that same index and fails with "index 1 in use".
      r = await run(["new-window", "-P", "-F", "#{window_id}", "-t", `${opts.session}:`, "-n", opts.window, "-c", opts.workdir, opts.command])
      if (r.code !== 0) throw new Error(`tmux new-window failed: ${r.stderr}`)
    }
    let windowId = r.stdout.trim()
    // Detached first-server fallback has no stdout. Poll the server for the exact, collision-safe
    // window name instead of guessing an index or holding the HTTP spawn request open forever.
    if (!windowId) {
      for (let attempt = 0; attempt < 40 && !windowId; attempt++) {
        const listed = await run(["list-windows", "-t", opts.session, "-F", "#{window_id}\t#{window_name}"])
        if (listed.code === 0) {
          for (const line of listed.stdout.split("\n")) {
            const [id, name] = line.split("\t", 2)
            if (name === opts.window && id) { windowId = id.trim(); break }
          }
        }
        if (!windowId) await new Promise<void>(resolve => setTimeout(resolve, 50))
      }
    }
    if (!windowId) throw new Error(`tmux created window '${opts.window}' but did not report its id`)
    return { windowId }
  }

  async function killWindowById(windowId: string): Promise<void> {
    const r = await run(["kill-window", "-t", windowId])
    if (r.code !== 0 && !/can't find (window|session)/.test(r.stderr)) throw new Error(`tmux kill-window failed: ${r.stderr}`)
  }

  async function listSessionWindows(session: string): Promise<string[]> {
    const r = await run(["list-windows", "-t", session, "-F", "#{window_name}"])
    if (r.code !== 0) return []
    return r.stdout.split("\n").map(s => s.trim()).filter(Boolean)
  }

  // Liveness of a window's pane by tmux window id (@N). Returns the live pane's
  // pid, or null if the window is gone or every pane is dead. reconcileOnStartup
  // uses this to trust a surviving pane over a stale stored pid after a restart.
  async function livePanePid(windowId: string): Promise<number | null> {
    const r = await run(["list-panes", "-t", windowId, "-F", "#{pane_dead} #{pane_pid}"])
    if (r.code !== 0) return null
    for (const line of r.stdout.split("\n")) {
      const [dead, pid] = line.trim().split(/\s+/)
      if (dead === "0" && pid) {
        const n = Number(pid)
        if (Number.isFinite(n) && n > 0) return n
      }
    }
    return null
  }

  async function sendKeysToWindowId(windowId: string, keys: string[]): Promise<void> {
    const r = await run(["send-keys", "-t", windowId, ...keys])
    if (r.code !== 0) throw new Error(`tmux send-keys failed: ${r.stderr}`)
  }

  /** Capture a window's active pane (scrollback included) by tmux window id (@N). Returns null if the window/pane is gone. */
  async function capturePaneById(windowId: string): Promise<string | null> {
    const r = await run(["capture-pane", "-t", windowId, "-p", "-S", "-150"])
    return r.code === 0 ? r.stdout : null
  }

  /** Like capturePaneById but escape-preserving (-e), so styling like the dim
   * ghost autosuggestion in Claude's composer is detectable by callers. */
  async function capturePaneRawById(windowId: string): Promise<string | null> {
    const r = await run(["capture-pane", "-t", windowId, "-p", "-e", "-S", "-150"])
    return r.code === 0 ? r.stdout : null
  }

  /** Resolve a tmux window's id (@N) from its name within a session; null if no window matches. */
  async function resolveWindowIdByName(session: string, name: string): Promise<string | null> {
    const r = await run(["list-windows", "-t", session, "-F", "#{window_id}\t#{window_name}"])
    if (r.code !== 0) return null
    for (const line of r.stdout.split("\n")) {
      const tab = line.indexOf("\t")
      if (tab < 0) continue
      const id = line.slice(0, tab).trim()
      const wname = line.slice(tab + 1)
      if (wname === name && id) return id
    }
    return null
  }

  return { spawnSessionWindow, killWindowById, listSessionWindows, livePanePid, sendKeysToWindowId, capturePaneById, capturePaneRawById, resolveWindowIdByName }
}

export const {
  spawnSessionWindow,
  killWindowById,
  listSessionWindows,
  livePanePid,
  sendKeysToWindowId,
  capturePaneById,
  capturePaneRawById,
  resolveWindowIdByName,
} = createTmuxClient()
