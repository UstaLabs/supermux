export type JsonRpcId = number
type Pending = { resolve: (v: unknown) => void; reject: (e: Error) => void }

/** Format a JSON-RPC error object into a user-facing message. Prefer the
 * server's `message`; fold in `data` when present so rate-limit / auth detail
 * isn't discarded (supermux surfaces this via the adapter `error` event). */
export function formatJsonRpcError(error: unknown): string {
  const e = error as { message?: unknown; data?: unknown; code?: unknown } | null
  if (!e || typeof e !== "object") return "jsonrpc error"
  const msg = typeof e.message === "string" && e.message.trim()
    ? e.message.trim()
    : "jsonrpc error"
  if (e.data == null || e.data === "") return msg
  const data = typeof e.data === "string"
    ? e.data.trim()
    : (() => { try { return JSON.stringify(e.data) } catch { return String(e.data) } })()
  if (!data) return msg
  // Avoid "foo: foo" when data repeats the message.
  if (data === msg) return msg
  return `${msg}: ${data}`
}

/** Minimal JSON-RPC 2.0 client over a newline-delimited byte pipe. Transport-agnostic:
 * construct with a `write` callback (we do NOT append the trailing newline — the runner does)
 * and push inbound bytes via feed(). */
export class AcpClient {
  private nextId = 1
  private pending = new Map<JsonRpcId, Pending>()
  private buf = ""
  private write: (line: string) => void

  onNotification: (method: string, params: unknown) => void = () => {}
  onServerRequest: (method: string, params: unknown) => Promise<unknown> = async () => ({})

  constructor(write: (line: string) => void) { this.write = write }

  /** Redirect writes (used by the real runner to target the child's stdin). */
  setWrite(fn: (line: string) => void): void { this.write = fn }

  request<T = unknown>(method: string, params: unknown): Promise<T> {
    const id = this.nextId++
    const line = JSON.stringify({ jsonrpc: "2.0", id, method, params })
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve: resolve as (v: unknown) => void, reject })
      this.write(line)
    })
  }

  notify(method: string, params: unknown): void {
    this.write(JSON.stringify({ jsonrpc: "2.0", method, params }))
  }

  feed(chunk: string): void {
    this.buf += chunk
    let i: number
    while ((i = this.buf.indexOf("\n")) >= 0) {
      const line = this.buf.slice(0, i)
      this.buf = this.buf.slice(i + 1)
      if (line.trim()) this.dispatch(line)
    }
  }

  private dispatch(line: string): void {
    let m: any
    try { m = JSON.parse(line) } catch { return }
    if (m.id != null && (m.result !== undefined || m.error !== undefined)) {
      const p = this.pending.get(m.id)
      if (!p) return
      this.pending.delete(m.id)
      if (m.error) p.reject(new Error(formatJsonRpcError(m.error)))
      else p.resolve(m.result)
      return
    }
    if (m.id != null && typeof m.method === "string") {
      void this.onServerRequest(m.method, m.params)
        .then((result) => this.write(JSON.stringify({ jsonrpc: "2.0", id: m.id, result })))
        .catch((e) => this.write(JSON.stringify({ jsonrpc: "2.0", id: m.id, error: { code: -32000, message: String(e?.message ?? e) } })))
      return
    }
    if (typeof m.method === "string") this.onNotification(m.method, m.params)
  }

  fail(err: Error): void {
    for (const p of this.pending.values()) p.reject(err)
    this.pending.clear()
  }
}
