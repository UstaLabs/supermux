import { spawn as defaultSpawn, type ChildProcess } from "child_process"
import type { CursorRunner } from "./adapter"
import { makeLogger } from "../../../shared/log"
import { resolveCommand, spawnCommand, type FileExists } from "../../process/launcher"

const log = makeLogger("agents/cursor/runner")

export function makeRealCursorRunner(opts: {
  home: string
  authEnv: Record<string, string>
  platform?: NodeJS.Platform
  fileExists?: FileExists
  spawn?: (command: string, args: string[], options: Record<string, unknown>) => ChildProcess
}): CursorRunner {
  return async (args, onLine, onExit, signal) => {
    return new Promise((resolve) => {
      const env: Record<string, string> = {
        ...(process.env as Record<string, string>),
        ...opts.authEnv,
        HOME: opts.home,
        ...(opts.platform === "win32" || (!opts.platform && process.platform === "win32") ? { USERPROFILE: opts.home } : {}),
      }
      const platform = opts.platform ?? process.platform
      const shouldResolve = !opts.spawn || opts.platform !== undefined || opts.fileExists !== undefined
      const command = shouldResolve
        ? (resolveCommand(["cursor-agent", "agent"], env, platform, { fileExists: opts.fileExists }) ?? "cursor-agent")
        : "cursor-agent"
      const child = spawnCommand(command, args, {
        platform, fileExists: opts.fileExists, spawn: (opts.spawn ?? defaultSpawn) as never,
        env, stdio: ["ignore", "pipe", "pipe"],
      })
      // User-initiated stop: SIGTERM the child. Its `exit` event then runs the
      // normal settle path (onExit + resolve) — a clean turn-end, not an error.
      const onAbort = () => { try { child.kill("SIGTERM") } catch { /* already gone */ } }
      let settled = false
      let stderrTail = ""
      const settle = (code: number | null) => {
        if (settled) return
        settled = true
        signal?.removeEventListener("abort", onAbort)
        onExit(code, stderrTail.trim() || undefined)
        resolve()
      }
      if (signal) {
        if (signal.aborted) onAbort()
        else signal.addEventListener("abort", onAbort, { once: true })
      }

      let buf = ""
      child.stdout!.on("data", (chunk: Buffer) => {
        buf += chunk.toString("utf8")
        const lines = buf.split("\n")
        buf = lines.pop() ?? ""
        for (const l of lines) if (l.trim()) onLine(l)
      })

      // Consume stderr so the child doesn't deadlock when the pipe buffer
      // fills. Log at debug since cursor-agent uses stderr for routine output.
      child.stderr!.on("data", (chunk: Buffer) => {
        const text = chunk.toString("utf8")
        log.debug("stderr", { text: text.slice(0, 200) })
        // Keep the tail so a crash exit can report cursor-agent's own words.
        stderrTail = (stderrTail + text).slice(-500)
      })

      child.on("exit", (code) => {
        if (buf.trim()) onLine(buf)
        settle(code ?? null)
      })
      // ENOENT / EACCES — child failed to spawn. Without this, onExit never
      // fires and the adapter's `active` promise hangs forever, locking the
      // session.
      child.on("error", (err) => {
        log.warn("spawn_error", { err: String(err) })
        stderrTail = stderrTail || String(err)
        settle(null)
      })
    })
  }
}
