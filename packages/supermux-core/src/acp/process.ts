import { TextDecoder, TextEncoder } from 'node:util'
import { CoreError } from '../errors.js'
import { connectKeeper, type KeeperConnection, type KeeperFrameEvent } from '../keeper/client.js'
import type { KeeperWelcome } from '../keeper/protocol.js'

export type AcpKeeperLimits = {
  parkedDeadlineMs: number
  journalMaxBytes: number
  connectTimeoutMs: number
}

export type ConnectAcpProcessOptions = {
  command: string
  args: string[]
  env: NodeJS.ProcessEnv
  cwd: string
  sessionId: string
  shutdownTimeoutMs: number
  maxFrameBytes: number
  keeper: { stateDirectory: string; limits: AcpKeeperLimits }
  onStale?: (event: KeeperFrameEvent) => void
  /** Forward the agent's stderr lines (keeper captureStderr). Required decision, no default. */
  captureStderr: boolean
  onStderr?: (line: string) => void
  onOutgoingLine?: (line: string) => void
}

export type AcpProcess = {
  input: ReadableStream<Uint8Array>
  output: WritableStream<Uint8Array>
  welcome: KeeperWelcome
  failure: Promise<never>
  onExit(listener: (error: Error) => void): void
  close(opts: { mode: 'shutdown' | 'detach' }): Promise<void>
  setMeta(value: Record<string, unknown>): void
  ackConsumed(): void
  begin(): void
}

export async function connectAcpProcess(options: ConnectAcpProcessOptions): Promise<AcpProcess> {
  const encoder = new TextEncoder()
  const decoder = new TextDecoder()
  let closed = false
  let closing = false
  let closePromise: Promise<void> | undefined
  let rejectFailure!: (error: Error) => void
  let exitListener: ((error: Error) => void) | undefined
  const failure = new Promise<never>((_, reject) => { rejectFailure = reject })
  void failure.catch(() => {})
  const delivered: number[] = []
  const waiters: Array<(ev: IteratorResult<KeeperFrameEvent>) => void> = []
  const pending: KeeperFrameEvent[] = []
  let framesDone = false

  const conn: KeeperConnection = await connectKeeper({
    stateDirectory: options.keeper.stateDirectory,
    sessionId: options.sessionId,
    spec: { command: options.command, args: options.args, cwd: options.cwd, env: options.env, frameShape: 'jsonrpc', captureStderr: options.captureStderr },
    limits: {
      maxFrameBytes: options.maxFrameBytes,
      shutdownTimeoutMs: options.shutdownTimeoutMs,
      parkedDeadlineMs: options.keeper.limits.parkedDeadlineMs,
      journalMaxBytes: options.keeper.limits.journalMaxBytes,
      connectTimeoutMs: options.keeper.limits.connectTimeoutMs,
    },
    cursor: 'acked',
  })

  function fail(error: Error, keepAgent = false) {
    rejectFailure(error)
    if (!closing) exitListener?.(error)
    if (!closed) void close({ mode: keepAgent ? 'detach' : 'shutdown' }).catch(() => { /* failure already reported */ })
  }

  function pushFrame(item: IteratorResult<KeeperFrameEvent>) {
    const w = waiters.shift()
    if (w) w(item)
    else if (!item.done) pending.push(item.value)
    else framesDone = true
  }

  let begun = false
  function begin() {
    if (begun) return
    begun = true
    void (async () => {
      try {
        for await (const ev of conn.frames) {
          if (ev.type === 'stderr') {
            options.onStderr?.(ev.line)
            conn.ack(ev.seq)
            continue
          }
          if (ev.stale === true) {
            options.onStale?.(ev)
            conn.ack(ev.seq)
            continue
          }
          pushFrame({ done: false, value: ev })
        }
        pushFrame({ done: true, value: undefined })
        if (!closed && !closing) fail(new CoreError('connection_closed', 'ACP connection closed'), true)
      } catch (error) {
        const msg = error instanceof Error ? error.message : String(error)
        pushFrame({ done: true, value: undefined })
        if (closed || closing) return
        if (msg === 'replaced') fail(new Error('replaced'), true)
        else fail(error instanceof Error ? error : new Error(String(error)), true)
      }
    })()
  }

  function nextFrame(): Promise<IteratorResult<KeeperFrameEvent>> {
    if (pending.length) return Promise.resolve({ done: false, value: pending.shift()! })
    if (framesDone) return Promise.resolve({ done: true, value: undefined })
    return new Promise(r => waiters.push(r))
  }

  const input = new ReadableStream<Uint8Array>({
    async pull(controller) {
      const step = await nextFrame()
      if (step.done) {
        controller.close()
        return
      }
      delivered.push(step.value.seq)
      controller.enqueue(encoder.encode(step.value.line + '\n'))
    },
    cancel() {
      if (!closed && !closing) fail(new CoreError('connection_closed', 'ACP connection closed'), true)
    },
  })

  const writer = {
    write(line: string) {
      options.onOutgoingLine?.(line)
      conn.write(line)
    },
  }

  let outBuf = ''
  const output = new WritableStream<Uint8Array | string>({
    write(chunk) {
      const text = typeof chunk === 'string' ? chunk : decoder.decode(chunk, { stream: true })
      outBuf += text
      let idx
      while ((idx = outBuf.indexOf('\n')) >= 0) {
        const line = outBuf.slice(0, idx)
        outBuf = outBuf.slice(idx + 1)
        writer.write(line)
      }
    },
    close() {
      if (outBuf.trim()) writer.write(outBuf)
      outBuf = ''
    },
    abort() {},
  })

  function close(opts: { mode: 'shutdown' | 'detach' }): Promise<void> {
    if (!opts || (opts.mode !== 'shutdown' && opts.mode !== 'detach')) throw new TypeError('close mode is required')
    if (closePromise) return closePromise
    closed = true
    closing = true
    rejectFailure(new CoreError('runtime_closed', 'ACP runtime closed'))
    closePromise = (async () => {
      if (opts.mode === 'shutdown') await conn.shutdown()
      else await conn.detach()
    })()
    return closePromise
  }

  return {
    input,
    output,
    welcome: conn.welcome,
    failure,
    onExit(listener) { exitListener = listener },
    close,
    setMeta(value) { conn.setMeta(value) },
    ackConsumed() {
      const seq = delivered.shift()
      if (seq !== undefined) conn.ack(seq)
    },
    begin,
  }
}
