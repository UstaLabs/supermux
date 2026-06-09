import type { Readable, Writable } from "stream"

type Pending = { resolve: (v: any) => void; reject: (e: Error) => void }
type NotificationHandler = (n: { method: string; params: any }) => void

export class JsonRpcClient {
  private stdin: Writable
  private pending = new Map<string | number, Pending>()
  private notifHandlers: NotificationHandler[] = []
  private nextId = 1
  private buf = ""

  constructor(opts: { stdin: Writable; stdout: Readable }) {
    this.stdin = opts.stdin
    opts.stdout.on("data", (chunk: Buffer) => this.onData(chunk))
  }

  private onData(chunk: Buffer): void {
    this.buf += chunk.toString("utf8")
    const lines = this.buf.split("\n")
    this.buf = lines.pop() ?? ""
    for (const line of lines) {
      const t = line.trim()
      if (!t) continue
      let msg: any
      try { msg = JSON.parse(t) } catch { continue }
      if (msg.id != null && (msg.result !== undefined || msg.error !== undefined)) {
        const p = this.pending.get(msg.id)
        if (!p) continue
        this.pending.delete(msg.id)
        if (msg.error) p.reject(new Error(msg.error.message ?? "rpc error"))
        else p.resolve(msg.result)
      } else if (msg.method) {
        for (const h of this.notifHandlers) h({ method: msg.method, params: msg.params })
      }
    }
  }

  request<T = any>(method: string, params: any): Promise<T> {
    const id = this.nextId++
    const line = JSON.stringify({ jsonrpc: "2.0", id, method, params }) + "\n"
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.stdin.write(line, (err) => {
        if (err) { this.pending.delete(id); reject(err) }
      })
    })
  }

  notify(method: string, params: any): void {
    const line = JSON.stringify({ jsonrpc: "2.0", method, params }) + "\n"
    this.stdin.write(line)
  }

  onNotification(h: NotificationHandler): void {
    this.notifHandlers.push(h)
  }

  /**
   * Reject all pending requests. Call from the owner when the underlying
   * child process exits or errors so callers don't wait forever for a
   * response that will never arrive.
   */
  dispose(err: Error): void {
    for (const [id, p] of this.pending) {
      this.pending.delete(id)
      p.reject(err)
    }
  }
}
