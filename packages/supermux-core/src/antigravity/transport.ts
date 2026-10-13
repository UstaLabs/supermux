/** Keeper-backed NDJSON stdio transport for `agy --input-format stream-json --output-format stream-json`. */
import { connectKeeper, type KeeperConnection } from "../keeper/client.js"
import type { KeeperWelcome } from "../keeper/protocol.js"

export type AgyTransportOptions = {
  command: string
  args: string[]
  env: NodeJS.ProcessEnv
  cwd: string
  shutdownTimeoutMs: number
  maxFrameBytes: number
  sessionId: string
  keeper: {
    stateDirectory: string
    limits: { parkedDeadlineMs: number; journalMaxBytes: number; connectTimeoutMs: number }
  }
  /** The launch's session-context fingerprint (see KeeperSpec.fingerprint). */
  fingerprint?: string
}

/** `error.message === "replaced"`: another attacher took the keeper (fatal). No error: agy exited. */
export type AgyExit = { error?: Error }

export type AgyTransport = {
  welcome: KeeperWelcome
  /** Throws when the process is gone or the frame exceeds maxFrameBytes. */
  write(message: unknown): void
  /** SIGINT through the keeper; a no-op once the process is gone. */
  interrupt(): void
  /** Idempotent. */
  close(options: { mode: "shutdown" | "detach" }): Promise<void>
  /** Never throws. */
  setMeta(value: Record<string, unknown>): void
  setFingerprint(fingerprint: string): Promise<void>
  /** The last 4 KiB of agy's stderr. */
  stderrTail(): string
}

const STDERR_TAIL_BYTES = 4096

export async function agyTransport(
  options: AgyTransportOptions,
  onMessage: (message: Record<string, any>) => void,
  onExit: (exit: AgyExit) => void,
): Promise<AgyTransport> {
  let closed = false
  let ended = false
  let closing: Promise<void> | undefined
  let tail = ""

  const conn: KeeperConnection = await connectKeeper({
    stateDirectory: options.keeper.stateDirectory,
    sessionId: options.sessionId,
    spec: {
      command: options.command, args: options.args, cwd: options.cwd, env: options.env,
      ...(options.fingerprint !== undefined ? { fingerprint: options.fingerprint } : {}),
      frameShape: "ndjson", captureStderr: true,
    },
    limits: {
      maxFrameBytes: options.maxFrameBytes,
      shutdownTimeoutMs: options.shutdownTimeoutMs,
      parkedDeadlineMs: options.keeper.limits.parkedDeadlineMs,
      journalMaxBytes: options.keeper.limits.journalMaxBytes,
      connectTimeoutMs: options.keeper.limits.connectTimeoutMs,
    },
    cursor: "acked",
  })

  function ack(seq: number) {
    try { conn.ack(seq) } catch { /* keeper gone: the loop ends on its own */ }
  }

  function dispatch(line: string, seq: number) {
    let message: unknown
    try { message = JSON.parse(line.trim()) } catch { message = undefined }
    if (message && typeof message === "object" && !Array.isArray(message)) {
      if (!closed) {
        try { onMessage(message as Record<string, any>) }
        catch (error) { console.warn("[antigravity] frame handler failed:", error) }
      }
    } else if (line.trim() && process.env.SUPERMUX_DEBUG) {
      console.warn(`[antigravity] skipped a malformed agy frame: ${line.slice(0, 200)}`)
    }
    ack(seq)
  }

  // A macrotask: the caller's `await agyTransport(...)` continuation (which records the transport
  // its callbacks close over) runs before the first frame is dispatched.
  setImmediate(() => {
    void (async () => {
      try {
        for await (const ev of conn.frames) {
          if (ev.type === "stderr") {
            tail = (tail + ev.line + (ev.line.endsWith("\n") ? "" : "\n")).slice(-STDERR_TAIL_BYTES)
            ack(ev.seq)
          } else if (ev.type === "frame" || ev.type === "parked") {
            if (closed) { ack(ev.seq); continue }
            dispatch(ev.line, ev.seq)
          }
        }
        ended = true
        if (!closed) onExit({})
      } catch (error) {
        ended = true
        if (!closed) onExit({ error: error instanceof Error ? error : new Error(String(error)) })
      }
    })()
  })

  return {
    welcome: conn.welcome,
    write(message: unknown) {
      if (closed || ended) throw new Error("Antigravity process is gone")
      const line = JSON.stringify(message) + "\n"
      if (Buffer.byteLength(line) > options.maxFrameBytes) throw new Error("Antigravity outgoing frame exceeds size limit")
      conn.write(line)
    },
    interrupt() {
      if (closed || ended) return
      try { conn.signal("SIGINT") } catch { /* the keeper socket closed: agy already exited */ }
    },
    close(opts: { mode: "shutdown" | "detach" }) {
      if (!opts || (opts.mode !== "shutdown" && opts.mode !== "detach")) throw new TypeError("close mode is required")
      if (closing) return closing
      closed = true
      closing = opts.mode === "shutdown" ? conn.shutdown() : conn.detach()
      return closing
    },
    setMeta(value: Record<string, unknown>) {
      try { conn.setMeta(value) } catch { /* keeper gone */ }
    },
    setFingerprint(fingerprint: string) { return conn.setFingerprint(fingerprint) },
    stderrTail() { return tail },
  }
}
