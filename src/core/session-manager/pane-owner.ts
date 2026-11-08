import { spawnCommandSync } from "../process/launcher"
import { readFileSync } from "fs"

const KEY = "MUX_SESSION_ID="

/** MUX_SESSION_ID from one process's environment (Linux /proc, else `ps -E`), or null. */
function processSessionId(pid: number, platform: NodeJS.Platform): string | null {
  try {
    if (platform === "linux") {
      for (const entry of readFileSync(`/proc/${pid}/environ`, "utf8").split("\0")) {
        if (entry.startsWith(KEY)) return entry.slice(KEY.length) || null
      }
      return null
    }
    const r = spawnCommandSync("ps", ["-E", "-ww", "-p", String(pid), "-o", "command="], { encoding: "utf8", timeout: 2000 })
    if (r.error || r.status !== 0) return null
    const m = r.stdout.toString().match(/(?:^|\s)MUX_SESSION_ID=(\S+)/)
    return m?.[1] ?? null
  } catch {
    return null
  }
}

function childPids(pid: number): number[] {
  try {
    const r = spawnCommandSync("pgrep", ["-P", String(pid)], { encoding: "utf8", timeout: 2000 })
    if (r.error || r.status !== 0) return []
    return r.stdout.toString().split("\n").map((l) => Number(l.trim())).filter((n) => n > 0)
  } catch {
    return []
  }
}

/**
 * The broker session a tmux pane was started for: the MUX_SESSION_ID in the pane process's
 * environment, or in its direct children's (the pane runs `bash -lc 'exec env … claude'`, so
 * either the pane process itself or its child carries it). Null when it can't be read.
 */
export function paneSessionId(panePid: number, platform: NodeJS.Platform = process.platform): string | null {
  return processSessionId(panePid, platform) ?? childPids(panePid).map((pid) => processSessionId(pid, platform)).find((id) => id) ?? null
}
