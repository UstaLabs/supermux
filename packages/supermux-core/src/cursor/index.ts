import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { homedir } from 'node:os'
import { join, resolve } from 'node:path'
import { UnsupportedOperation } from '../errors.js'
import type { AgentDriver, ContentBlock, DriverContext } from '../types.js'
import { launchCursor } from './transport.js'

export type CursorOptions = {
  id?: string
  command?: string
  args?: string[]
  env?: Record<string, string>
  inheritEnv?: boolean
  model?: string
  mode?: 'ask' | 'plan'
  sandbox?: 'enabled' | 'disabled'
  trust?: boolean
  force?: boolean
  approveMcps?: boolean
  setupTimeoutMs?: number
  shutdownTimeoutMs?: number
  maxFrameBytes?: number
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  void promise.catch(() => {})
  return { promise, resolve, reject }
}

export function cursorConfigRoot(env: NodeJS.ProcessEnv): string {
  if (env.CURSOR_CONFIG_DIR) return env.CURSOR_CONFIG_DIR
  if (env.XDG_CONFIG_HOME) return join(env.XDG_CONFIG_HOME, 'cursor')
  return join(env.HOME ?? homedir(), '.cursor')
}

export function cursorHistoryStorePath(env: NodeJS.ProcessEnv, cwd: string, chatId: string): string {
  const hash = createHash('md5').update(resolve(cwd)).digest('hex')
  return join(cursorConfigRoot(env), 'chats', hash, chatId, 'store.db')
}

function environment(options: CursorOptions, context: DriverContext): NodeJS.ProcessEnv {
  return { ...(options.inheritEnv === false ? {} : process.env), ...options.env, ...context.profile?.env }
}

function textOnly(content: ContentBlock[]): string {
  const parts: string[] = []
  for (const block of content) {
    if (block.type !== 'text') throw new UnsupportedOperation(`content block ${block.type}`, 'cursor')
    parts.push(block.text)
  }
  return parts.join('\n')
}

function promptArgv(options: CursorOptions, text: string, sessionId: string, cwd: string): string[] {
  const mode = options.mode ?? 'ask'
  if (mode !== 'ask' && mode !== 'plan') throw new TypeError('Cursor mode must be ask or plan')
  const flags = ['-p', text, '--output-format', 'stream-json', '--stream-partial-output', '--workspace', cwd, '--resume', sessionId]
  flags.push('--mode', mode)
  flags.push('--sandbox', options.sandbox ?? 'enabled')
  if (options.trust !== false) flags.push('--trust')
  if (options.force === true) flags.push('--force')
  if (options.approveMcps === true) flags.push('--approve-mcps')
  if (options.model) flags.push('--model', options.model)
  return [...(options.args ?? []), ...flags]
}

export function cursor(options: CursorOptions = {}): AgentDriver {
  const setupTimeoutMs = options.setupTimeoutMs ?? 30_000
  const shutdownTimeoutMs = options.shutdownTimeoutMs ?? 2_000
  const maxFrameBytes = options.maxFrameBytes ?? 16 * 1024 * 1024
  for (const value of [setupTimeoutMs, shutdownTimeoutMs, maxFrameBytes]) {
    if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError('Cursor limits must be positive safe integers')
  }
  return { id: options.id ?? 'cursor', async open(context) {
    context.signal.throwIfAborted()
    if (context.forkFrom) throw new UnsupportedOperation('fork', 'cursor')
    const env = environment(options, context)
    const command = options.command ?? 'cursor-agent'
    let agentSessionId = context.resumeId
    let ready = false, closed = false
    let fatal: Error | undefined
    type Child = ReturnType<typeof launchCursor>
    type Active = {
      completion: ReturnType<typeof deferred<{ stopReason: string }>>
      interrupted: boolean
      gotInit: boolean
      gotResult: boolean
      settled: boolean
      child: Child
    }
    let active: Active | undefined
    let setupChild: Child | undefined
    const failure = deferred<never>()
    function fail(error: Error) {
      if (fatal) return
      fatal = error
      failure.reject(error)
      if (active && !active.settled) {
        active.settled = true
        active.completion.reject(error)
      }
      if (ready && !closed) context.onExit(error)
    }
    const close = async () => {
      if (!closed) {
        closed = true
        fail(new Error('Cursor runtime closed'))
      }
      if (setupChild) await setupChild.close()
      setupChild = undefined
      if (active) await active.child.close()
    }
    const setupAbort = () => { fail(new Error('Cursor setup aborted')); void close() }
    context.signal.addEventListener('abort', setupAbort, { once: true })
    const timer = setTimeout(() => { fail(new Error('Cursor setup timed out')); void close() }, setupTimeoutMs)
    try {
      if (context.resumeId) {
        if (!UUID.test(context.resumeId)) throw new Error('Cursor resume id is invalid')
        const store = cursorHistoryStorePath(env, context.cwd, context.resumeId)
        if (!existsSync(store)) throw new Error('Cursor resume store missing')
        agentSessionId = context.resumeId
      } else {
        const child = launchCursor({
          command, args: [...(options.args ?? []), 'create-chat'], env, cwd: context.cwd,
          shutdownTimeoutMs, maxFrameBytes, json: false,
        }, () => {}, error => { fail(error) })
        setupChild = child
        await Promise.race([child.wait(), failure.promise])
        if (fatal) throw fatal
        if (child.exitCode() !== 0 || child.exitSignal()) throw new Error('Cursor process failed')
        const id = child.stdout().trim()
        if (!UUID.test(id)) throw new Error('Cursor create-chat did not return a UUID')
        agentSessionId = id
        setupChild = undefined
      }
      if (fatal) throw fatal
      ready = true
    } catch (error) {
      await close()
      throw error
    } finally {
      clearTimeout(timer)
      context.signal.removeEventListener('abort', setupAbort)
    }
    function finish(a: Active, value: { stopReason: string } | Error) {
      if (a.settled) return
      a.settled = true
      if (value instanceof Error) a.completion.reject(value)
      else a.completion.resolve(value)
    }
    async function interrupt() {
      const a = active
      if (!a) return
      a.interrupted = true
      await a.child.close()
      if (!a.gotResult) finish(a, { stopReason: 'cancelled' })
    }
    return {
      agentSessionId: agentSessionId!,
      capabilities: { resume: true, steer: false, fork: false, detach: false, configure: false, history: false },
      close, interrupt,
      async prompt(content, signal) {
        if (fatal || closed) throw fatal ?? new Error('Cursor runtime closed')
        signal.throwIfAborted()
        if (active) throw new Error('Cursor prompt already running')
        const text = textOnly(content)
        const a: Active = {
          completion: deferred<{ stopReason: string }>(),
          interrupted: false,
          gotInit: false,
          gotResult: false,
          settled: false,
          child: undefined as unknown as Child,
        }
        const child = launchCursor({
          command, args: promptArgv(options, text, agentSessionId!, context.cwd), env, cwd: context.cwd,
          shutdownTimeoutMs, maxFrameBytes, json: true,
        }, message => {
          if (a.settled || fatal || child.failure) return
          const sessionId = message?.session_id
          if (message?.type === 'system' && message.subtype === 'init') {
            if (typeof message.session_id !== 'string' || !message.session_id) { fail(new Error('Cursor session identity missing')); return }
            if (message.session_id !== agentSessionId) { fail(new Error('Cursor session identity mismatch')); return }
            a.gotInit = true
          } else if (typeof sessionId === 'string' && sessionId !== agentSessionId) {
            fail(new Error('Cursor session identity mismatch'))
            return
          }
          if (message?.type === 'result') {
            if (typeof message.session_id !== 'string' || !message.session_id) { fail(new Error('Cursor session identity missing')); return }
            if (message.session_id !== agentSessionId) { fail(new Error('Cursor session identity mismatch')); return }
            if (!a.gotInit) { fail(new Error('Cursor init missing')); return }
          }
          context.onUpdate({ protocol: 'native', value: message })
          if (message?.type !== 'result') return
          a.gotResult = true
          child.acceptCompletion()
          void (async () => {
            await Promise.race([
              child.wait(),
              new Promise<void>(resolve => setTimeout(resolve, shutdownTimeoutMs)),
            ])
            if (a.interrupted) { finish(a, { stopReason: 'cancelled' }); return }
            if (fatal) { finish(a, fatal); return }
            if (child.failure) { finish(a, child.failure); return }
            if (child.exitCode() === null && child.exitSignal() === null) {
              const error = new Error('Cursor process did not exit after result')
              fail(error)
              await child.close()
              finish(a, error)
              return
            }
            if (message.is_error === true || message.subtype !== 'success') {
              finish(a, new Error(typeof message.result === 'string' && message.result ? message.result : 'Cursor turn failed'))
              return
            }
            if (child.exitCode() !== 0 || child.exitSignal()) {
              finish(a, new Error('Cursor process failed'))
              return
            }
            finish(a, { stopReason: 'end_turn' })
          })().catch(error => finish(a, error instanceof Error ? error : new Error(String(error))))
        }, error => {
          if (a.interrupted) finish(a, { stopReason: 'cancelled' })
          else finish(a, error)
        })
        a.child = child
        active = a
        const abort = () => { void interrupt() }
        signal.addEventListener('abort', abort, { once: true })
        try {
          return await a.completion.promise
        } finally {
          signal.removeEventListener('abort', abort)
          if (active === a) active = undefined
          await child.close()
        }
      },
    }
  } }
}
