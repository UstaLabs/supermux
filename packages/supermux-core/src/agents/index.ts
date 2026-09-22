import { isAbsolute } from 'node:path'
import { acp, type AcpActivityHint, type AcpOptions } from '../acp/index.js'
import { createAcpNormalizer } from '../acp/normalize.js'
import { ACTIVITY_OVERFLOW, applyBufferedActivity, copyActivityNotice } from '../activity.js'
import { CoreError, UnsupportedOperation } from '../errors.js'
import type { ActivityNotice, AgentDriver, AgentRuntime, AgentUpdate, CloseOptions, DriverContext, SessionConfiguration } from '../types.js'
import { requireCloseMode } from '../types.js'

const EFFORTS = new Set(['low', 'medium', 'high'])

export type GrokOptions = Omit<AcpOptions, 'id' | 'command' | 'args'> & {
  id: string
  /** Exact executable. Suffix is not inspected; use commandArgs to prefix a script. */
  command: string
  /** Prefix argv before Grok CLI args (e.g. `[fixture]` with `command: process.execPath`). */
  commandArgs: string[]
  model?: string
  reasoningEffort?: 'low' | 'medium' | 'high'
  authPath?: string
  alwaysApprove: boolean
  noLeader: boolean
}

function requireEffort(value: string): 'low' | 'medium' | 'high' {
  if (!EFFORTS.has(value)) throw new TypeError('Grok reasoningEffort must be low, medium, or high')
  return value as 'low' | 'medium' | 'high'
}

function sessionOverrides(value: SessionConfiguration | undefined): SessionConfiguration {
  if (value == null) return {}
  if (typeof value !== 'object' || Array.isArray(value)) throw new TypeError('Grok configuration must be an object')
  const next: SessionConfiguration = {}
  if (value.model !== undefined) {
    if (typeof value.model !== 'string' || !value.model) throw new TypeError('Grok model must be a nonempty string')
    next.model = value.model
  }
  if (value.reasoningEffort !== undefined) {
    if (typeof value.reasoningEffort !== 'string' || !value.reasoningEffort) throw new TypeError('Grok reasoningEffort must be a nonempty string')
    next.reasoningEffort = requireEffort(value.reasoningEffort)
  }
  return next
}

function grokArgs(options: GrokOptions, overrides: SessionConfiguration): string[] {
  const model = overrides.model ?? options.model
  const effort = overrides.reasoningEffort ?? options.reasoningEffort
  const args = ['agent']
  if (options.noLeader) args.push('--no-leader')
  if (options.alwaysApprove) args.push('--always-approve')
  if (model) args.push('--model', model)
  if (effort) args.push('--reasoning-effort', effort)
  args.push('stdio')
  return args
}

function grokCommand(options: GrokOptions): { command: string; prefix: string[] } {
  return { command: options.command, prefix: [...options.commandArgs] }
}

function grokUpdateKind(value: unknown): { kind: string; id?: string } | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return
  const rec = value as Record<string, unknown>
  const kind = rec.sessionUpdate
  if (kind !== 'user_message_chunk' && kind !== 'turn_completed') return
  const idCandidate = rec.prompt_id ?? rec.promptId
  const id = typeof idCandidate === 'string' && idCandidate ? idCandidate : undefined
  return { kind, id }
}

function parseGrokActivity(update: AgentUpdate): { kind: string; id?: string } | undefined {
  if (update.protocol === 'acp') return grokUpdateKind(update.value)
  if (update.protocol !== 'native' || !update.value || typeof update.value !== 'object' || Array.isArray(update.value)) return
  const frame = update.value as { method?: unknown; params?: unknown }
  if (frame.method !== '_x.ai/session_notification' && frame.method !== '_x.ai/session/update') return
  const params = frame.params
  const nested = params && typeof params === 'object' && !Array.isArray(params) && 'update' in params
    ? (params as { update: unknown }).update
    : params
  return grokUpdateKind(nested)
}

/** Live Grok start chunks often have no prompt_id; vendor turn_completed carries a UUID.
 * Pair those as one sequential generation per connection. Do not guess generic `rec.id`.
 * Repeated completed prompt_ids are ignored so a stale UUID cannot end the next turn.
 */
function createGrokClassifyActivity(): (update: AgentUpdate) => AcpActivityHint | undefined {
  let seq = 0
  let openId: string | undefined
  let openExplicit = false
  const completedPromptIds = new Set<string>()
  const rememberCompleted = (id: string) => {
    completedPromptIds.add(id)
    if (completedPromptIds.size <= 64) return
    const oldest = completedPromptIds.values().next().value
    if (oldest !== undefined) completedPromptIds.delete(oldest)
  }
  return (update: AgentUpdate): AcpActivityHint | undefined => {
    const parsed = parseGrokActivity(update)
    if (!parsed) return
    if (parsed.kind === 'user_message_chunk') {
      if (parsed.id) {
        if (openId === parsed.id) return { phase: 'started', id: parsed.id }
        openId = parsed.id
        openExplicit = true
        return { phase: 'started', id: parsed.id }
      }
      if (openId && !openExplicit) return { phase: 'started', id: openId }
      openId = `grok-activity:${++seq}`
      openExplicit = false
      return { phase: 'started', id: openId }
    }
    if (parsed.id && completedPromptIds.has(parsed.id)) return
    if (parsed.id) rememberCompleted(parsed.id)
    // Explicit current generation: only a matching id completes it. Mismatched
    // or id-less turn_completed must not drop openExplicit (that would let a
    // later stale/no-id event pair to the explicit openId). Sequential no-id
    // start → unseen UUID pairing below is one-generation-at-a-time, not
    // out-of-order or unbounded duplicate protection.
    if (openExplicit) {
      if (!parsed.id || parsed.id !== openId) return
      const id = parsed.id
      openId = undefined
      openExplicit = false
      return { phase: 'completed', id }
    }
    const id = openId
    openId = undefined
    openExplicit = false
    return id ? { phase: 'completed', id } : { phase: 'completed' }
  }
}

function grokAcp(options: GrokOptions, overrides: SessionConfiguration) {
  const launched = grokCommand(options)
  return acp({
    id: options.id,
    command: launched.command,
    args: [...launched.prefix, ...grokArgs(options, overrides)],
    env: { ...options.env, ...(options.authPath ? { GROK_AUTH_PATH: options.authPath } : {}) },
    inheritEnv: options.inheritEnv,
    mcpServers: options.mcpServers,
    setupTimeoutMs: options.setupTimeoutMs,
    shutdownTimeoutMs: options.shutdownTimeoutMs,
    maxFrameBytes: options.maxFrameBytes,
    maxOutstandingActivity: options.maxOutstandingActivity,
    keeper: options.keeper,
    cancelRetryIntervalMs: options.cancelRetryIntervalMs,
    cancelRetryTimeoutMs: options.cancelRetryTimeoutMs,
    classifyActivity: createGrokClassifyActivity(),
    vendor: "grok",
  })
}

type GrokChildFactory = (options: GrokOptions, overrides: SessionConfiguration) => AgentDriver

/** Grok's native automation transport is ACP; credential refresh stays at the
 * explicit canonical auth path, rather than following a replaceable symlink.
 */
export function grok(options: GrokOptions, childFactory: GrokChildFactory = grokAcp): AgentDriver {
  if (!options || typeof options !== 'object') throw new TypeError('Grok options are required')
  for (const field of ['id', 'command', 'commandArgs', 'alwaysApprove', 'noLeader', 'inheritEnv', 'mcpServers', 'setupTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'maxOutstandingActivity', 'keeper', 'cancelRetryIntervalMs', 'cancelRetryTimeoutMs'] as const) {
    if (options[field] === undefined) throw new TypeError(`Grok ${field} is required`)
  }
  if (typeof options.id !== 'string' || !options.id) throw new TypeError('Grok id is required')
  if (typeof options.command !== 'string' || !options.command) throw new TypeError('Grok command is required')
  if (!Array.isArray(options.commandArgs) || options.commandArgs.some(value => typeof value !== 'string')) throw new TypeError('Grok commandArgs is required')
  if (typeof options.alwaysApprove !== 'boolean') throw new TypeError('Grok alwaysApprove is required')
  if (typeof options.noLeader !== 'boolean') throw new TypeError('Grok noLeader is required')
  if (typeof options.inheritEnv !== 'boolean') throw new TypeError('Grok inheritEnv is required')
  if (!Array.isArray(options.mcpServers)) throw new TypeError('Grok mcpServers is required')
  if (!Number.isSafeInteger(options.maxOutstandingActivity) || options.maxOutstandingActivity <= 0) throw new TypeError('Grok maxOutstandingActivity is required')
  if (options.authPath && !isAbsolute(options.authPath)) throw new TypeError('authPath must be absolute')
  if (options.model !== undefined && (typeof options.model !== 'string' || !options.model)) throw new TypeError('Grok model must be a nonempty string')
  if (options.reasoningEffort !== undefined) requireEffort(options.reasoningEffort)
  const id = options.id
  const authDriver = childFactory(options, {})
  return {
    id,
    auth: authDriver.auth,
    async open(context) {
      context.signal.throwIfAborted()
      if (context.forkFrom) throw new UnsupportedOperation('fork', id)
      let overrides = sessionOverrides(context.configuration)
      let generation = 0
      let inner: AgentRuntime | undefined
      let pending: AgentRuntime | undefined
      let acceptedSessionId: string | undefined
      let closed = false
      let turn = false
      let configuring: Promise<void> | undefined
      let candidateFailure: Error | undefined
      let liveCapabilities = { resume: false, steer: false, fork: false, detach: false, configure: false, history: false }
      const nativeOutstanding = new Map<string, ActivityNotice>()
      const lifetime = new AbortController()
      const childSignal = () => AbortSignal.any([context.signal, lifetime.signal])
      const nativeBusy = () => nativeOutstanding.size > 0
      const applyNotice = (notice: ActivityNotice) => {
        if (applyBufferedActivity(nativeOutstanding, notice, options.maxOutstandingActivity) === 'overflow') {
          context.onExit(new CoreError(ACTIVITY_OVERFLOW.code, ACTIVITY_OVERFLOW.message))
          return
        }
        context.onActivity?.(notice)
      }

      async function closeOwned(runtime: AgentRuntime, mode: CloseOptions['mode']) {
        await runtime.close({ mode })
        if (pending === runtime) pending = undefined
        if (inner === runtime) inner = undefined
      }

      async function spawn(resumeId?: string) {
        context.signal.throwIfAborted()
        if (lifetime.signal.aborted) throw new CoreError('aborted', 'Grok operation aborted')
        if (pending) await closeOwned(pending, 'shutdown')
        const gen = ++generation
        let published = false
        let setupFailure: Error | undefined
        const buffered = new Map<string, ActivityNotice>()
        const runtime = await childFactory(options, overrides).open({
          ...context,
          signal: childSignal(),
          resumeId,
          configuration: undefined,
          onUpdate(update) { if (gen === generation && !closed) context.onUpdate(update) },
          onActivity(notice) {
            if (gen !== generation || closed) return
            const copied = copyActivityNotice(notice)
            if (!copied) return
            if (!published) {
              if (applyBufferedActivity(buffered, copied, options.maxOutstandingActivity) === 'overflow') {
                setupFailure = new CoreError(ACTIVITY_OVERFLOW.code, ACTIVITY_OVERFLOW.message)
              }
              return
            }
            applyNotice(copied)
          },
          onExit(error) {
            if (gen !== generation) return
            if (!published) {
              setupFailure = error
              return
            }
            if (!closed) context.onExit(error)
          },
        })
        pending = runtime
        const rejectCandidate = async (error: Error) => {
          candidateFailure = error
          generation++
          buffered.clear()
          try {
            await closeOwned(runtime, 'shutdown')
          } catch (cleanup) {
            const cleanupError = cleanup instanceof Error ? cleanup : new Error(String(cleanup))
            const combined = new AggregateError([error, cleanupError], 'Session opening and runtime cleanup failed')
            combined.cause = error
            candidateFailure = combined
            throw combined
          }
          throw error
        }
        if (resumeId && runtime.agentSessionId !== resumeId) {
          await rejectCandidate(new CoreError('session_identity', 'Grok resume did not restore the same agent session id'))
        }
        if (setupFailure) await rejectCandidate(setupFailure)
        inner = runtime
        pending = undefined
        acceptedSessionId = runtime.agentSessionId
        liveCapabilities = { resume: runtime.capabilities.resume, steer: false, fork: false, detach: runtime.capabilities.detach === true, configure: true, history: false }
        published = true
        for (const notice of buffered.values()) {
          if (gen !== generation || closed) break
          applyNotice(notice)
        }
        buffered.clear()
        return runtime
      }

      const closedError = () => new CoreError('runtime_closed', 'Grok child is not running')
      const grokNormalizer = createAcpNormalizer({ vendor: 'grok' })
      const wrapper: AgentRuntime = {
        get agentSessionId() { return inner?.agentSessionId ?? acceptedSessionId ?? pending?.agentSessionId ?? '' },
        get capabilities() { return liveCapabilities },
        normalize: grokNormalizer,
        flush: () => grokNormalizer.flush(),
        async prompt(content, signal) {
          if (closed) throw new CoreError('runtime_closed', 'Grok runtime closed')
          if (turn || configuring || nativeBusy()) throw new CoreError('busy', 'Grok prompt is already running')
          if (!inner) throw closedError()
          signal.throwIfAborted()
          turn = true
          try { return await inner.prompt(content, signal) }
          finally { turn = false }
        },
        async interrupt() {
          if (!inner) throw closedError()
          await inner.interrupt()
        },
        configuration() { return { ...overrides } },
        async configure(configuration) {
          if (closed) throw new CoreError('runtime_closed', 'Grok runtime closed')
          if (!acceptedSessionId) throw closedError()
          if (turn || configuring || nativeBusy()) throw new CoreError('busy', 'Grok prompt is already running')
          const next = sessionOverrides(configuration)
          const sessionId = inner?.agentSessionId ?? acceptedSessionId
          const run = (async () => {
            if (pending) await closeOwned(pending, 'shutdown')
            overrides = next
            const previous = inner
            generation++
            nativeOutstanding.clear()
            if (previous) {
              await closeOwned(previous, 'shutdown')
            }
            await spawn(sessionId)
          })()
          configuring = run
          try { await run }
          finally { if (configuring === run) configuring = undefined }
        },
        async close(closeOptions: CloseOptions) {
          const mode = requireCloseMode(closeOptions)
          closed = true
          lifetime.abort()
          generation++
          const inFlight = configuring
          if (inFlight) await inFlight.catch(() => {})
          const child = pending ?? inner
          if (!child) return
          await closeOwned(child, mode)
        },
      }

      try {
        await spawn(context.resumeId)
      } catch (error) {
        if (pending) {
          context.onExit(candidateFailure ?? (error instanceof Error ? error : new Error(String(error))))
          return wrapper
        }
        throw error
      }

      return wrapper
    },
  }
}

export type OpenCodeOptions = Omit<AcpOptions, 'args' | 'sessionConfig'> & { /** OpenCode model id such as `opencode-go/deepseek-v4-flash`; sent as the `model` config option after the session opens. Absent = OpenCode's own default. */ model?: string }
/** Uses OpenCode's ACP entrypoint. No library HTTP listener or broker globals. */
export function opencode(options: OpenCodeOptions): AgentDriver {
  if (!options || typeof options !== 'object') throw new TypeError('OpenCode options are required')
  for (const field of ['id', 'command', 'inheritEnv', 'mcpServers', 'setupTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'maxOutstandingActivity', 'keeper', 'cancelRetryIntervalMs', 'cancelRetryTimeoutMs'] as const) {
    if (options[field] === undefined) throw new TypeError(`OpenCode ${field} is required`)
  }
  if (typeof options.id !== 'string' || !options.id) throw new TypeError('OpenCode id is required')
  if (typeof options.command !== 'string' || !options.command) throw new TypeError('OpenCode command is required')
  if (options.model !== undefined && (typeof options.model !== 'string' || !options.model)) throw new TypeError('OpenCode model must be a nonempty string')
  const { model, ...rest } = options
  return acp({ ...rest, args: ['acp'], ...(model ? { sessionConfig: { model } } : {}) })
}
