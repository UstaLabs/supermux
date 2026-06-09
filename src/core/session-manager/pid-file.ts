import { existsSync, readFileSync, writeFileSync, unlinkSync, mkdirSync } from "fs"
import { dirname } from "path"

export function isProcessAlive(pid: number): boolean {
  if (pid <= 0) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

function isBrokerProcess(pid: number): boolean {
  try {
    const cmdline = readFileSync(`/proc/${pid}/cmdline`, "utf8")
    return cmdline.includes("bun") || cmdline.includes("mux")
  } catch {
    return false
  }
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
