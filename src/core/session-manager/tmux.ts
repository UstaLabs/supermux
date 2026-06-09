import { spawn } from "child_process"

type TmuxResult = { code: number; stdout: string; stderr: string }
type TmuxRunner = (args: string[]) => Promise<TmuxResult>

function runTmux(args: string[]): Promise<TmuxResult> {
  return new Promise((resolve, reject) => {
    const proc = spawn("tmux", args, { stdio: ["ignore", "pipe", "pipe"] })
    let stdout = "", stderr = ""
    proc.stdout.on("data", d => { stdout += d })
    proc.stderr.on("data", d => { stderr += d })
    proc.on("close", code => resolve({ code: code ?? 0, stdout, stderr }))
    proc.on("error", reject)
  })
}

export function createTmuxClient(run: TmuxRunner = runTmux) {
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
      r = await run(["new-session", "-d", "-P", "-F", "#{window_id}", "-s", opts.session, "-n", opts.window, "-c", opts.workdir, opts.command])
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

  async function sendKeys(target: string, keys: string[]): Promise<void> {
    const r = await run(["send-keys", "-t", target, ...keys])
    if (r.code !== 0) throw new Error(`tmux send-keys failed: ${r.stderr}`)
  }

  async function sendKeysToWindowId(windowId: string, keys: string[]): Promise<void> {
    const r = await run(["send-keys", "-t", windowId, ...keys])
    if (r.code !== 0) throw new Error(`tmux send-keys failed: ${r.stderr}`)
  }

  return { spawnSessionWindow, killSessionWindow, killWindowById, listSessionWindows, sendKeys, sendKeysToWindowId }
}

export const {
  spawnSessionWindow,
  killSessionWindow,
  killWindowById,
  listSessionWindows,
  sendKeys,
  sendKeysToWindowId,
} = createTmuxClient()
