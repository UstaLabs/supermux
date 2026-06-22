import { spawn } from "child_process"

type TmuxResult = { code: number; stdout: string; stderr: string }
type TmuxRunner = (args: string[]) => Promise<TmuxResult>
// Runs an arbitrary command (used to wrap tmux in `systemd-run`); injectable for tests.
type CmdRunner = (cmd: string, args: string[]) => Promise<TmuxResult>

async function streamToString(stream: any): Promise<string> {
  const chunks: string[] = []
  // Bun exposes ReadableStream on stdout/stderr; Node exposes Readable with .on("data")
  if (stream && "getReader" in stream) {
    const reader = stream.getReader()
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      chunks.push(typeof value === "string" ? value : Buffer.from(value).toString("utf8"))
    }
  } else if (stream && "on" in stream) {
    return new Promise((resolve) => {
      let out = ""
      stream.on("data", (d: any) => { out += d })
      stream.on("end", () => resolve(out))
    })
  }
  return chunks.join("")
}

function runCommand(cmd: string, args: string[]): Promise<TmuxResult> {
  return new Promise((resolve, reject) => {
    const proc = spawn(cmd, args, { stdio: ["ignore", "pipe", "pipe"] })
    let stdoutP = streamToString(proc.stdout)
    let stderrP = streamToString(proc.stderr)
    proc.on("close", async (code) => {
      const stdout = await stdoutP
      const stderr = await stderrP
      resolve({ code: code ?? 0, stdout, stderr })
    })
    proc.on("error", reject)
  })
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
  return run(tmuxArgs)
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
    return { windowId: r.stdout.trim() }
  }

  async function killSessionWindow(opts: { session: string; window: string }): Promise<void> {
    const r = await run(["kill-window", "-t", `${opts.session}:${opts.window}`])
    if (r.code !== 0 && !/can't find (window|session)/.test(r.stderr)) throw new Error(`tmux kill-window failed: ${r.stderr}`)
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

  async function sendKeys(target: string, keys: string[]): Promise<void> {
    const r = await run(["send-keys", "-t", target, ...keys])
    if (r.code !== 0) throw new Error(`tmux send-keys failed: ${r.stderr}`)
  }

  async function sendKeysToWindowId(windowId: string, keys: string[]): Promise<void> {
    const r = await run(["send-keys", "-t", windowId, ...keys])
    if (r.code !== 0) throw new Error(`tmux send-keys failed: ${r.stderr}`)
  }

  return { spawnSessionWindow, killSessionWindow, killWindowById, listSessionWindows, livePanePid, sendKeys, sendKeysToWindowId }
}

export const {
  spawnSessionWindow,
  killSessionWindow,
  killWindowById,
  listSessionWindows,
  livePanePid,
  sendKeys,
  sendKeysToWindowId,
} = createTmuxClient()
