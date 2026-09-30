import { existsSync, readFileSync, writeFileSync, unlinkSync, mkdirSync } from "fs"
import { dirname } from "path"

export function isProcessAlive(pid: number): boolean {
  if (pid <= 0) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

export interface BrokerProbeDeps {
  platform: string
  readProcCmdline: (pid: number) => string
  /** Runs a command; returns stdout on success, null when it fails or cannot run. */
  run: (cmd: string[]) => string | null
}

const defaultProbeDeps: BrokerProbeDeps = {
  platform: process.platform,
  readProcCmdline: (pid) => readFileSync(`/proc/${pid}/cmdline`, "utf8"),
  run: (cmd) => {
    try {
      const r = Bun.spawnSync(cmd, { stdout: "pipe", stderr: "pipe" })
      return r.exitCode === 0 ? r.stdout.toString() : null
    } catch {
      return null
    }
  },
}

const looksLikeBroker = (s: string): boolean => /bun|mux/i.test(s)

/**
 * True when the live process `pid` looks like a broker (bun / mux / supermux).
 * If the platform check itself fails, assume it IS a broker: never steal a live pid's lock on uncertainty.
 */
export function isBrokerProcessWith(pid: number, deps: BrokerProbeDeps): boolean {
  try {
    if (deps.platform === "linux") return looksLikeBroker(deps.readProcCmdline(pid))
    if (deps.platform === "win32") {
      const ps = deps.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", `(Get-Process -Id ${pid}).Path`])
      if (ps !== null && ps.trim()) return looksLikeBroker(ps)
      const tl = deps.run(["tasklist", "/FI", `PID eq ${pid}`, "/FO", "CSV", "/NH"])
      if (tl !== null && tl.trim()) return looksLikeBroker(tl)
      return true
    }
    const out = deps.run(["ps", "-p", String(pid), "-o", "command="])
    if (out === null || !out.trim()) return true
    return looksLikeBroker(out)
  } catch {
    return true
  }
}

function isBrokerProcess(pid: number): boolean {
  return isBrokerProcessWith(pid, defaultProbeDeps)
}

export function acquirePidFile(path: string): void {
  mkdirSync(dirname(path), { recursive: true })
  if (existsSync(path)) {
    const raw = readFileSync(path, "utf8").trim()
    const existing = Number(raw)
    if (Number.isFinite(existing) && existing > 0 && isProcessAlive(existing) && isBrokerProcess(existing)) {
      throw new Error(`mux-broker already running as pid ${existing} (${path})`)
    }
    unlinkSync(path)
  }
  writeFileSync(path, String(process.pid), { mode: 0o600 })
}

export function releasePidFile(path: string): void {
  try { unlinkSync(path) } catch {}
}
