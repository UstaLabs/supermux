import { TextDecoder } from 'node:util'
import { connectKeeper, type KeeperConnection } from '../keeper/client.js'
import type { KeeperWelcome } from '../keeper/protocol.js'

type Pending = { resolve(value: any): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout> }

export type CodexTransportOptions = {
  command: string
  args: string[]
  env: NodeJS.ProcessEnv
  cwd: string
  requestTimeoutMs: number
  shutdownTimeoutMs: number
  maxFrameBytes: number
  sessionId: string
  keeper: {
    stateDirectory: string
    limits: { parkedDeadlineMs: number; journalMaxBytes: number; connectTimeoutMs: number }
  }
}

export type TransportCloseMode = 'shutdown' | 'detach'

export async function transport(
  options: CodexTransportOptions,
  onMessage: (message: any) => void,
  onFailure: (error: Error) => void,
) {
  const pending = new Map<number, Pending>()
  let nextId = 0, closed = false
  let failure: Error | undefined, closing: Promise<void> | undefined
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let conn!: KeeperConnection
  let welcome!: KeeperWelcome

  function fail(error: Error, keepAgent = false) {
    if (failure) return
    failure = error
    for (const p of pending.values()) { clearTimeout(p.timer); p.reject(error) }
    pending.clear()
    onFailure(error)
    void close({ mode: keepAgent ? 'detach' : 'shutdown' }).catch(() => { /* failure already reported */ })
  }

  function dispatchLine(line: string, seq: number) {
    if (closed || failure) return
    try {
      const decoded = decoder.decode(Buffer.from(line, 'utf8')).trim()
      if (!decoded) { conn.ack(seq); return }
      const message = JSON.parse(decoded)
      if (!message || typeof message !== 'object' || Array.isArray(message)) throw new Error('Invalid Codex message')
      if (typeof message.method === 'string') onMessage(message)
      else if (typeof message.id === 'number' && ('result' in message || 'error' in message)) {
        const p = pending.get(message.id)
        if (p) { pending.delete(message.id); clearTimeout(p.timer); if (message.error) p.reject(new Error(message.error.message ?? 'Codex RPC failed')); else p.resolve(message.result) }
      } else throw new Error('Invalid Codex message')
      conn.ack(seq)
    } catch (error) { fail(error instanceof Error ? error : new Error(String(error))) }
  }

  conn = await connectKeeper({
    stateDirectory: options.keeper.stateDirectory,
    sessionId: options.sessionId,
    spec: { command: options.command, args: options.args, cwd: options.cwd, env: options.env, frameShape: 'jsonrpc', captureStderr: false },
    limits: {
      maxFrameBytes: options.maxFrameBytes,
      shutdownTimeoutMs: options.shutdownTimeoutMs,
      parkedDeadlineMs: options.keeper.limits.parkedDeadlineMs,
      journalMaxBytes: options.keeper.limits.journalMaxBytes,
      connectTimeoutMs: options.keeper.limits.connectTimeoutMs,
    },
    cursor: 'acked',
  })
  welcome = conn.welcome
  nextId = welcome.maxRequestId

  void (async () => {
    try {
      for await (const ev of conn.frames) {
        if (ev.type === 'frame' || ev.type === 'parked') dispatchLine(ev.line, ev.seq)
      }
      if (!closed) fail(new Error('Codex process exited'), true)
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error)
      if (closed) return
      if (msg === 'replaced') fail(new Error('replaced'), true)
      else fail(error instanceof Error ? error : new Error(String(error)), true)
    }
  })()

  function write(message: unknown) {
    if (failure || closed) throw failure ?? new Error('Codex runtime closed')
    const line = JSON.stringify(message) + '\n'
    if (Buffer.byteLength(line) > options.maxFrameBytes) throw new Error('Codex outgoing frame exceeds size limit')
    conn.write(line)
  }
  function request(method: string, params: unknown): Promise<any> {
    if (pending.size >= 128) return Promise.reject(new Error('Too many pending Codex requests'))
    const id = ++nextId
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { fail(new Error(`Codex request timed out: ${method}`)) }, options.requestTimeoutMs)
      pending.set(id, { resolve, reject, timer })
      try { write({ id, method, params }) } catch (error) { clearTimeout(timer); pending.delete(id); reject(error) }
    })
  }
  function close(opts: { mode: TransportCloseMode }): Promise<void> {
    if (!opts || (opts.mode !== 'shutdown' && opts.mode !== 'detach')) throw new TypeError('close mode is required')
    if (closing) return closing
    closed = true
    for (const p of pending.values()) { clearTimeout(p.timer); p.reject(new Error('Codex runtime closed')) }
    pending.clear()
    closing = (async () => {
      if (opts.mode === 'shutdown') await conn.shutdown()
      else await conn.detach()
    })()
    return closing
  }
  return {
    request,
    write,
    close,
    welcome,
    setMeta(value: Record<string, unknown>) { conn.setMeta(value) },
  }
}
