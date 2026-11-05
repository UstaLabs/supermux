// A tunnel CLI that serves until stopped (cloudflared's quick tunnel, ngrok): started in the background so it outlives
// `supermux connect`, its pid kept in a file for `connect --off`. Started without a shell (`nohup ... & echo $!` was
// POSIX only), so it works on Windows too.
import { mkdirSync, readFileSync, rmSync, writeFileSync } from "fs"
import type { ConnectCtx } from "./types"

/** Starts [argv] in the background, its output into [log], and records its pid in [pidFile]. */
export function startBackground(ctx: ConnectCtx, argv: string[], log: string, pidFile: string): void {
  mkdirSync(ctx.stateDir, { recursive: true })
  writeFileSync(pidFile, String(ctx.startDetached(argv, log)))
}

/** Stops the tunnel recorded in [pidFile], if any, and forgets it. */
export function stopBackground(ctx: ConnectCtx, pidFile: string): void {
  let pid: number
  try { pid = Number(readFileSync(pidFile, "utf8").trim()) } catch { return }
  if (Number.isInteger(pid) && pid > 0) ctx.stopTree(pid)
  rmSync(pidFile, { force: true })
}

/** What the tunnel has written to [log] so far ("" before it has). */
export function readLog(log: string): string {
  try { return readFileSync(log, "utf8") } catch { return "" }
}
