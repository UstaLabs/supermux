import { randomUUID } from 'node:crypto'
import { TextDecoder } from 'node:util'
import { connectKeeper, type KeeperConnection } from '../keeper/client.js'
import type { KeeperWelcome } from '../keeper/protocol.js'

type Pending = { resolve(value: any): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout> }

export type ClaudeTransportOptions = {
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
  options: ClaudeTransportOptions,
  onMessage: (message: any) => void,
  onFailure: (error: Error) => void,
) {
  const pending = new Map<string, Pending>()
  let closed = false
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

  function dispatchLine(line: string, seq: number, stale?: true) {
    if (closed || failure) return
    try {
      const decoded = decoder.decode(Buffer.from(line, 'utf8')).trim()
      if (!decoded) { conn.ack(seq); return }
      const message = JSON.parse(decoded)
      if (!message || typeof message !== 'object' || Array.isArray(message)) throw new Error('Invalid Claude message')
      if (message.type === 'control_response' && message.response && typeof message.response.request_id === 'string') {
        const p = pending.get(message.response.request_id)
        if (p) {
          pending.delete(message.response.request_id); clearTimeout(p.timer)
          if (message.response.subtype === 'error') p.reject(new Error(message.response.error ?? 'Claude control request failed'))
          else p.resolve(message.response)
        }
        conn.ack(seq)
        return
      }
      if (stale === true) { conn.ack(seq); return }
      onMessage(message)
      conn.ack(seq)
    } catch (error) { fail(error instanceof Error ? error : new Error(String(error))) }
  }

  conn = await connectKeeper({
    stateDirectory: options.keeper.stateDirectory,
    sessionId: options.sessionId,
    spec: { command: options.command, args: options.args, cwd: options.cwd, env: options.env, frameShape: 'claude-control', captureStderr: false },
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

  queueMicrotask(() => {
    void (async () => {
      try {
        for await (const ev of conn.frames) {
          if (ev.type === 'frame' || ev.type === 'parked') dispatchLine(ev.line, ev.seq, ev.stale)
          else if (ev.type === 'stderr') conn.ack(ev.seq)
        }
        if (!closed) fail(new Error('Claude process exited'), true)
      } catch (error) {
        const msg = error instanceof Error ? error.message : String(error)
        if (closed) return
        if (msg === 'replaced') fail(new Error('replaced'), true)
        else fail(error instanceof Error ? error : new Error(String(error)), true)
      }
    })()
  })

  function write(message: unknown) {
    if (failure || closed) throw failure ?? new Error('Claude runtime closed')
    const line = JSON.stringify(message) + '\n'
    if (Buffer.byteLength(line) > options.maxFrameBytes) throw new Error('Claude outgoing frame exceeds size limit')
    conn.write(line)
  }
  function request(body: Record<string, unknown>): Promise<any> {
    if (pending.size >= 128) return Promise.reject(new Error('Too many pending Claude requests'))
    const request_id = randomUUID()
    const method = typeof body.subtype === 'string' ? body.subtype : 'control'
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { fail(new Error(`Claude request timed out: ${method}`)) }, options.requestTimeoutMs)
      pending.set(request_id, { resolve, reject, timer })
      try { write({ type: 'control_request', request_id, request: body }) } catch (error) { clearTimeout(timer); pending.delete(request_id); reject(error) }
    })
  }
  function close(opts: { mode: TransportCloseMode }): Promise<void> {
    if (!opts || (opts.mode !== 'shutdown' && opts.mode !== 'detach')) throw new TypeError('close mode is required')
    if (closing) return closing
    closed = true
    for (const p of pending.values()) { clearTimeout(p.timer); p.reject(new Error('Claude runtime closed')) }
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
