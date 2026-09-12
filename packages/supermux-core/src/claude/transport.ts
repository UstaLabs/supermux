import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { TextDecoder } from 'node:util'

type Pending = { resolve(value: any): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout> }

export function transport(options: {
  command: string; args: string[]; env: NodeJS.ProcessEnv; cwd: string
  requestTimeoutMs: number; shutdownTimeoutMs: number; maxFrameBytes: number
}, onMessage: (message: any) => void, onFailure: (error: Error) => void) {
  const child = spawn(options.command, options.args, { cwd: options.cwd, env: options.env, stdio: ['pipe', 'pipe', 'pipe'], shell: false })
  const pending = new Map<string, Pending>()
  let closed = false, exited = false
  let failure: Error | undefined, closing: Promise<void> | undefined
  let buffer = Buffer.alloc(0)
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let resolveExit!: () => void
  const exit = new Promise<void>(resolve => { resolveExit = resolve })
  function fail(error: Error) {
    if (failure) return
    failure = error
    for (const p of pending.values()) { clearTimeout(p.timer); p.reject(error) }
    pending.clear()
    onFailure(error)
    void close()
  }
  child.on('error', fail)
  child.on('close', () => { exited = true; resolveExit(); if (!closed) fail(new Error('Claude process exited')) })
  child.stdout.on('error', fail)
  child.stderr.on('error', fail)
  child.stdin.on('error', fail)
  child.stderr.resume()
  child.stdout.on('end', () => { if (!closed) fail(new Error('Claude output closed')) })
  child.stdout.on('data', (chunk: Buffer) => {
    if (closed || failure) return
    let offset = 0
    while (offset < chunk.length) {
      const newline = chunk.indexOf(10, offset)
      const end = newline < 0 ? chunk.length : newline
      if (buffer.length + end - offset > options.maxFrameBytes) { fail(new Error('Claude frame exceeds size limit')); return }
      buffer = Buffer.concat([buffer, chunk.subarray(offset, end)])
      offset = end + 1
      if (newline < 0) break
      try {
        const line = decoder.decode(buffer).trim(); buffer = Buffer.alloc(0)
        if (!line) continue
        const message = JSON.parse(line)
        if (!message || typeof message !== 'object' || Array.isArray(message)) throw new Error('Invalid Claude message')
        if (message.type === 'control_response' && message.response && typeof message.response.request_id === 'string') {
          const p = pending.get(message.response.request_id)
          if (p) {
            pending.delete(message.response.request_id); clearTimeout(p.timer)
            if (message.response.subtype === 'error') p.reject(new Error(message.response.error ?? 'Claude control request failed'))
            else p.resolve(message.response)
            continue
          }
        }
        onMessage(message)
      } catch (error) { fail(error instanceof Error ? error : new Error(String(error))); return }
    }
  })
  function write(message: unknown) {
    if (failure || closed) throw failure ?? new Error('Claude runtime closed')
    const line = JSON.stringify(message) + '\n'
    if (Buffer.byteLength(line) > options.maxFrameBytes || child.stdin.writableLength + Buffer.byteLength(line) > options.maxFrameBytes * 2) throw new Error('Claude outgoing frame exceeds size limit')
    child.stdin.write(line, error => { if (error) fail(error) })
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
  function close(): Promise<void> {
    if (closing) return closing
    closed = true
    for (const p of pending.values()) { clearTimeout(p.timer); p.reject(new Error('Claude runtime closed')) }
    pending.clear()
    closing = (async () => {
      if (exited) return
      child.stdin.end()
      child.kill('SIGTERM')
      const timer = setTimeout(() => { child.kill('SIGKILL') }, options.shutdownTimeoutMs)
      try { await exit } finally { clearTimeout(timer); child.stdout.destroy(); child.stderr.destroy(); child.stdin.destroy() }
    })()
    return closing
  }
  return { request, write, close }
}
