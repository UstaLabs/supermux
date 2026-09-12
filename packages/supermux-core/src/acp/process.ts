import { spawn } from 'node:child_process'
import { Readable, Writable } from 'node:stream'
import { CoreError } from '../errors.js'

export function launch(command: string, args: string[], env: NodeJS.ProcessEnv, cwd: string | undefined, shutdownTimeoutMs: number) {
  const child = spawn(command, args, { env, cwd, stdio: ['pipe', 'pipe', 'pipe'], shell: false })
  // Drain diagnostics without exposing potentially secret-bearing stderr.
  child.stderr.resume()
  let stopped = false
  let closing = false
  let closePromise: Promise<void> | undefined
  let rejectFailure!: (error: Error) => void
  let exitListener: ((error: Error) => void) | undefined
  const failure = new Promise<never>((_, reject) => { rejectFailure = reject })
  void failure.catch(() => {})
  let resolveExit!: () => void
  const exited = new Promise<void>(resolve => { resolveExit = resolve })
  const fail = (error: Error) => { rejectFailure(error); if (!closing) exitListener?.(error) }
  child.once('error', error => { fail(error); stopped = true; resolveExit() })
  child.once('exit', (code, signal) => { stopped = true; fail(new CoreError('agent_exited', `ACP process exited (${code ?? signal})`)); resolveExit() })
  return {
    input: Readable.toWeb(child.stdout) as ReadableStream<Uint8Array>,
    output: Writable.toWeb(child.stdin) as WritableStream<Uint8Array>,
    failure,
    onExit(listener: (error: Error) => void) { exitListener = listener },
    close(): Promise<void> {
      if (closePromise) return closePromise
      closing = true
      rejectFailure(new CoreError('runtime_closed', 'ACP runtime closed'))
      closePromise = (async () => {
        if (stopped) return
        child.kill('SIGTERM')
        const timer = setTimeout(() => { if (!stopped) child.kill('SIGKILL') }, shutdownTimeoutMs)
        try { await exited } finally { clearTimeout(timer); child.stdin.destroy(); child.stdout.destroy(); child.stderr.destroy() }
      })()
      return closePromise
    },
  }
}
