import { readFileSync, writeFileSync, unlinkSync, mkdirSync } from "fs"
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

/**
 * A broker command line: the compiled `supermux-broker` binary, or `bun` running the broker's entry
 * (`src/main.ts`, or `src/cli.ts` which boots it). Deliberately NOT any path containing "supermux":
 * the desktop app (`Supermux.app`, `/opt/supermux/bin/supermux`, `supermux.exe`) is not a broker,
 * and treating it as one would lock the broker out of its own state dir.
 */
export function looksLikeBrokerCommand(s: string): boolean {
  const cmd = s.replace(/\0/g, " ")
  if (/supermux-broker/i.test(cmd)) return true
  return /(^|[\\/\s"])bun(\.exe)?($|[\s"])/i.test(cmd) && /(^|[\\/\s"])(main|cli)\.ts($|[\s"])/i.test(cmd)
}

/**
 * True when the live process `pid` looks like a broker ([looksLikeBrokerCommand]).
 * If the platform check itself fails, assume it IS a broker: never steal a live pid's lock on uncertainty.
 */
export function isBrokerProcessWith(pid: number, deps: BrokerProbeDeps): boolean {
  try {
    if (deps.platform === "linux") return looksLikeBrokerCommand(deps.readProcCmdline(pid))
    if (deps.platform === "win32") {
      const ps = deps.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", `(Get-CimInstance Win32_Process -Filter 'ProcessId=${pid}').CommandLine`])
      if (ps !== null && ps.trim()) return looksLikeBrokerCommand(ps)
      const tl = deps.run(["tasklist", "/FI", `PID eq ${pid}`, "/FO", "CSV", "/NH"])
      // tasklist shows the image name only: bun.exe may be running the broker, so it counts.
      if (tl !== null && tl.trim()) return /supermux-broker|"bun(\.exe)?"/i.test(tl)
      return true
    }
    const out = deps.run(["ps", "-p", String(pid), "-o", "command="])
    if (out === null || !out.trim()) return true
    return looksLikeBrokerCommand(out)
  } catch {
    return true
  }
}

export interface PidFileDeps {
  pid: number
  isAlive: (pid: number) => boolean
  isBroker: (pid: number) => boolean
  /** Short synchronous pause while another broker is half-way through writing the file. */
  sleep: (ms: number) => void
}

const defaultPidFileDeps: PidFileDeps = {
  pid: process.pid,
  isAlive: isProcessAlive,
  isBroker: (pid) => isBrokerProcessWith(pid, defaultProbeDeps),
  sleep: (ms) => Bun.sleepSync(ms),
}

const ATTEMPTS = 20

function readOrNull(path: string): string | null {
  try {
    return readFileSync(path, "utf8")
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null
    throw error
  }
}

/**
 * Claim `path` for this process. The file is created atomically (`wx`), so two brokers starting at
 * once can't both win. An existing file is stale when its pid is dead, is our own pid (a reused pid,
 * or this process starting again), or isn't a broker; a stale file is removed (only if it still says
 * what we read) and the create retried. Throws when a live broker holds it.
 */
export function acquirePidFile(path: string, deps: PidFileDeps = defaultPidFileDeps): void {
  mkdirSync(dirname(path), { recursive: true })
  const ours = String(deps.pid)
  let empties = 0
  for (let attempt = 0; attempt < ATTEMPTS; attempt++) {
    try {
      writeFileSync(path, ours, { flag: "wx", mode: 0o600 })
      return
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error
    }
    const raw = readOrNull(path)
    if (raw === null) continue // removed between our create and our read: try again
    const text = raw.trim()
    if (text === "" && empties++ < 5) {
      // Created but not written yet: another broker is in the middle of claiming it. An empty
      // file that stays empty was left by a crash and is stale like any other.
      deps.sleep(50)
      continue
    }
    const existing = Number(text)
    if (Number.isInteger(existing) && existing > 0 && existing !== deps.pid && deps.isAlive(existing) && deps.isBroker(existing)) {
      throw new Error(`mux-broker already running as pid ${existing} (${path})`)
    }
    // Stale. Remove it only if it is still the file we judged; another broker may have replaced it.
    if (readOrNull(path) === raw) {
      try { unlinkSync(path) } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error
      }
    }
  }
  throw new Error(`could not claim ${path}: it keeps changing`)
}

/** Remove `path` only while it still names this process: never another broker's claim. */
export function releasePidFile(path: string, pid: number = process.pid): void {
  try {
    if (readFileSync(path, "utf8").trim() !== String(pid)) return
    unlinkSync(path)
  } catch {}
}
