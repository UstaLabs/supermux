import { spawn, type ChildProcess } from "node:child_process"
import { encodeMessage, MessageReader } from "./framing"

export interface LspServerOpts {
  command: string
  args: string[]
  cwd: string
  env?: Record<string, string>
  /** raw JSON-RPC message bodies coming FROM the server. */
  onMessage: (json: string) => void
  onExit: (code: number | null, signal: NodeJS.Signals | null) => void
  onStderr?: (text: string) => void
}

// A single running language-server child process. Mirrors the codex
// app-server spawn pattern, but with LSP's Content-Length framing instead of
// newline-delimited JSON-RPC.
export class LspServerProcess {
  readonly pid: number
  private readonly child: ChildProcess
  private readonly reader: MessageReader
  private alive = true

  constructor(opts: LspServerOpts) {
    this.child = spawn(opts.command, opts.args, {
      cwd: opts.cwd,
      env: { ...process.env, ...opts.env },
      stdio: ["pipe", "pipe", "pipe"],
    })
    this.pid = this.child.pid ?? -1
    this.reader = new MessageReader(opts.onMessage)
    this.child.stdout?.on("data", (c: Buffer) => this.reader.push(c))
    this.child.stderr?.on("data", (c: Buffer) => opts.onStderr?.(c.toString("utf8")))
    this.child.on("exit", (code, signal) => {
      this.alive = false
      opts.onExit(code, signal)
    })
    this.child.on("error", () => {
      this.alive = false
      opts.onExit(null, null)
    })
  }

  // Forward a raw JSON-RPC message body TO the server (adds framing).
  write(json: string): void {
    if (!this.alive) return
    try {
      this.child.stdin?.write(encodeMessage(json))
    } catch {
      // stdin closed mid-write — the exit handler will clean up.
    }
  }

  kill(): void {
    if (!this.alive) return
    this.alive = false
    try {
      this.child.kill("SIGTERM")
    } catch {
      // already gone
    }
  }
}
