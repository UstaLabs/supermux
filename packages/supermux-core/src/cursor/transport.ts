import { spawn, type ChildProcess } from 'node:child_process'
import { TextDecoder } from 'node:util'

const STDERR_CAP = 512

export type CursorChildOptions = {
  command: string
  args: string[]
  env: NodeJS.ProcessEnv
  cwd: string
  shutdownTimeoutMs: number
  maxFrameBytes: number
  json: boolean
}

export function launchCursor(options: CursorChildOptions, onMessage: (message: any) => void, onFailure: (error: Error) => void) {
  const child: ChildProcess = spawn(options.command, options.args, {
    cwd: options.cwd,
    env: options.env,
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: false,
  })
  let closed = false, exited = false, accepted = false, spawnFailed = false, streamsClosed = false, stdoutEnded = false, waitSettled = false
  let failure: Error | undefined, closing: Promise<void> | undefined
  let buffer = Buffer.alloc(0)
  let stdoutText = ''
  let stderrTail = ''
  let exitCode: number | null = null
  let exitSignal: NodeJS.Signals | null = null
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let resolveProcess!: () => void
  const processGone = new Promise<void>(resolve => { resolveProcess = resolve })
  let resolveWait!: () => void
  const waitDone = new Promise<void>(resolve => { resolveWait = resolve })
  function markProcessGone() {
    if (exited) return
    exited = true
    resolveProcess()
    settleWait()
  }
  function settleWait() {
    if (waitSettled) return
    if (!(spawnFailed || streamsClosed || (exited && stdoutEnded))) return
    waitSettled = true
    resolveWait()
  }
  function fail(error: Error) {
    if (failure) return
    failure = error
    onFailure(error)
    void close()
  }
  child.on('error', error => {
    spawnFailed = true
    fail(error)
    markProcessGone()
  })
  child.on('exit', (code, signal) => {
    exitCode = code
    exitSignal = signal
    markProcessGone()
    if (failure || closed) return
    if (!options.json && (code !== 0 || signal)) fail(new Error('Cursor process failed'))
  })
  child.on('close', () => {
    streamsClosed = true
    if (!exited) markProcessGone()
    settleWait()
  })
  child.stdout?.on('error', fail)
  child.stderr?.on('error', fail)
  child.stderr?.on('data', (chunk: Buffer) => {
    stderrTail = (stderrTail + chunk.toString('utf8')).slice(-STDERR_CAP)
  })
  child.stdout?.on('end', () => {
    stdoutEnded = true
    settleWait()
    if (closed || failure) return
    if (!options.json) return
    if (buffer.length) { fail(new Error('Invalid Cursor message')); return }
    if (!accepted) fail(new Error('Cursor output closed'))
  })
  child.stdout?.on('data', (chunk: Buffer) => {
    if (closed || failure) return
    if (!options.json) {
      if (stdoutText.length + chunk.length > options.maxFrameBytes) { fail(new Error('Cursor frame exceeds size limit')); return }
      stdoutText += chunk.toString('utf8')
      return
    }
    let offset = 0
    while (offset < chunk.length) {
      const newline = chunk.indexOf(10, offset)
      const end = newline < 0 ? chunk.length : newline
      if (buffer.length + end - offset > options.maxFrameBytes) { fail(new Error('Cursor frame exceeds size limit')); return }
      buffer = Buffer.concat([buffer, chunk.subarray(offset, end)])
      offset = end + 1
      if (newline < 0) break
      try {
        const line = decoder.decode(buffer).trim(); buffer = Buffer.alloc(0)
        if (!line) continue
        const message = JSON.parse(line)
        if (!message || typeof message !== 'object' || Array.isArray(message)) throw new Error('Invalid Cursor message')
        onMessage(message)
      } catch (error) { fail(error instanceof Error ? error : new Error(String(error))); return }
    }
  })
  if (!child.stdout) {
    stdoutEnded = true
    settleWait()
  }
  function close(): Promise<void> {
    if (closing) return closing
    closed = true
    closing = (async () => {
      if (spawnFailed || streamsClosed) return
      if (!exited) {
        try { child.kill('SIGTERM') } catch { /* spawn failed or already gone */ }
        const timer = setTimeout(() => { try { child.kill('SIGKILL') } catch { /* ignore */ } }, options.shutdownTimeoutMs)
        try { await processGone } finally { clearTimeout(timer) }
      }
      child.stdout?.destroy()
      child.stderr?.destroy()
    })()
    return closing
  }
  function acceptCompletion() { accepted = true }
  return {
    close,
    acceptCompletion,
    wait: () => waitDone,
    stdout: () => stdoutText,
    stderrTail: () => redact(stderrTail),
    exitCode: () => exitCode,
    exitSignal: () => exitSignal,
    get failure() { return failure },
  }
}

function redact(text: string) {
  return text.replace(/(CURSOR_API_KEY|CURSOR_AUTH_TOKEN|api[_-]?key|auth[_-]?token|bearer)\s*[=:]\s*\S+/gi, '$1=<redacted>')
}
