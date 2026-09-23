import { randomBytes } from 'node:crypto'
import { CLIENT_METHODS, ClientSideConnection, ndJsonStream, PROTOCOL_METHODS, PROTOCOL_VERSION } from '@agentclientprotocol/sdk'
import type { AnyMessage, AuthMethod, McpServer, RequestPermissionResponse } from '@agentclientprotocol/sdk'
import type { AgentDriver, AgentUpdate, AuthContext, CloseOptions, DriverContext, PermissionsSpec } from '../types.js'
import { requireCloseMode } from '../types.js'
import { ACTIVITY_OVERFLOW } from '../activity.js'
import { CoreError, UnsupportedOperation } from '../errors.js'
import { acpPermissionDecision, appliedFor, validatePermissionsSpec } from '../permissions.js'
import { connectAcpProcess, type AcpKeeperLimits } from './process.js'
import { createAcpNormalizer } from './normalize.js'
import type { KeeperFrameEvent } from '../keeper/client.js'

export type AcpActivityHint = { id?: string; phase: 'started' | 'completed' }
export type AcpActivityClassifier = (update: AgentUpdate) => AcpActivityHint | undefined

export type AcpKeeperOptions = {
  stateDirectory: string
  limits: AcpKeeperLimits
}

export type AcpOptions = {
  id: string
  command: string
  args: string[]
  env?: Record<string, string>
  inheritEnv: boolean
  mcpServers: McpServer[]
  setupTimeoutMs: number
  shutdownTimeoutMs: number
  maxFrameBytes: number
  maxOutstandingActivity: number
  keeper: AcpKeeperOptions
  /** Applied right after session/new|resume|load via session/set_config_option (select options such as OpenCode's `model`). Absent = nothing sent. */
  sessionConfig?: Record<string, string>
  /** Forward the agent's stderr lines as native updates {method:'stderr', params:{line}} so a normalizer can map logged errors. Required, no default. */
  captureStderr: boolean
  /** Delay between same-prompt cancel retries. */
  cancelRetryIntervalMs: number
  /**
   * How long to keep retrying cancel for the live prompt.
   * This is not a universal cancellation guarantee: the core session
   * interruptTimeoutMs is independent and may return `{status:'unconfirmed'}`
   * while these retries continue. ACP never fabricates a cancelled prompt result.
   */
  cancelRetryTimeoutMs: number
  /**
   * Optional vendor-specific mapping from a forwarded update to a native activity
   * notice. Generic ACP does not parse Grok `_x.ai` payloads itself.
   */
  classifyActivity?: AcpActivityClassifier
  /** When `'grok'`, handle vendor `_x.ai/ask_user_question` agent→client requests. */
  vendor?: "grok"
  permissions: Extract<PermissionsSpec, { kind: "acp" }>
}

function abortError() { return new CoreError('aborted', 'ACP operation aborted') }
const cancelled: RequestPermissionResponse = { outcome: { outcome: 'cancelled' } }
const standardNotifications = new Set<string>([CLIENT_METHODS.session_update, CLIENT_METHODS.elicitation_complete, PROTOCOL_METHODS.cancel_request])
let mintedActivitySeq = 0

function isOpaqueNotification(message: unknown): message is { method: string; params?: unknown } {
  if (!message || typeof message !== 'object' || Array.isArray(message)) return false
  const value = message as Record<string, unknown>
  return value.jsonrpc === '2.0' && typeof value.method === 'string' && !('id' in value)
}

function requireKeeper(keeper: AcpOptions['keeper'] | undefined): AcpKeeperOptions {
  if (keeper === undefined) throw new TypeError('ACP keeper is required')
  if (!keeper || typeof keeper !== 'object' || Array.isArray(keeper)) throw new TypeError('ACP keeper is required')
  if (typeof keeper.stateDirectory !== 'string' || !keeper.stateDirectory) throw new TypeError('ACP keeper.stateDirectory is required')
  const limits = keeper.limits
  if (!limits || typeof limits !== 'object' || Array.isArray(limits)) throw new TypeError('ACP keeper.limits is required')
  for (const key of ['parkedDeadlineMs', 'journalMaxBytes', 'connectTimeoutMs'] as const) {
    if (limits[key] === undefined) throw new TypeError(`ACP keeper.limits.${key} is required`)
    const v = limits[key]
    if (typeof v !== 'number' || !Number.isSafeInteger(v) || v <= 0) throw new TypeError(`ACP keeper.limits.${key} is required`)
  }
  return keeper
}

function sameRpcId(a: unknown, b: unknown) {
  return a === b || (a != null && b != null && String(a) === String(b))
}

/** A `session/new|load|resume` result may describe select options (OpenCode/Cursor `model`, Cursor `mode`). */
type ConfigOption = { id: string; type?: string; options?: { value: string; name?: string }[] }
function readConfigOptions(result: unknown): ConfigOption[] {
  const raw = (result as { configOptions?: unknown } | null)?.configOptions
  if (!Array.isArray(raw)) return []
  return raw.filter((o): o is ConfigOption => !!o && typeof o === 'object' && typeof (o as ConfigOption).id === 'string')
}
/** Callers name models the way pickers list them (`claude-opus-5-5`, `auto`); a select option's wire VALUE
 * may carry parameters (`claude-opus-5-5[context=300k,…]`, `default[]` for Auto). Match by value first, then
 * by the option's display name (case-insensitive); anything else is sent as given so the agent reports it. */
function resolveConfigValue(configOptions: ConfigOption[], configId: string, value: string): string {
  const option = configOptions.find(o => o.id === configId)
  if (!option?.options?.length) return value
  if (option.options.some(o => o.value === value)) return value
  const byName = option.options.find(o => typeof o.name === 'string' && o.name.toLowerCase() === value.toLowerCase())
  return byName ? byName.value : value
}

export function acp(options: AcpOptions): AgentDriver {
  if (!options || typeof options !== 'object') throw new TypeError('ACP options are required')
  for (const field of ['id', 'command', 'args', 'inheritEnv', 'mcpServers', 'setupTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'maxOutstandingActivity', 'keeper', 'cancelRetryIntervalMs', 'cancelRetryTimeoutMs', 'captureStderr', 'permissions'] as const) {
    if (options[field] === undefined) throw new TypeError(`ACP ${field} is required`)
  }
  const factoryPermissions = validatePermissionsSpec(options.permissions)
  if (factoryPermissions.kind !== 'acp') throw new TypeError('ACP permissions kind must be acp')
  if (typeof options.id !== 'string' || !options.id) throw new TypeError('ACP id is required')
  if (typeof options.command !== 'string' || !options.command) throw new TypeError('ACP command is required')
  if (!Array.isArray(options.args) || options.args.some(value => typeof value !== 'string')) throw new TypeError('ACP args is required')
  if (typeof options.inheritEnv !== 'boolean') throw new TypeError('ACP inheritEnv is required')
  if (!Array.isArray(options.mcpServers)) throw new TypeError('ACP mcpServers is required')
  const keeper = requireKeeper(options.keeper)
  const setupTimeout = options.setupTimeoutMs
  const shutdownTimeout = options.shutdownTimeoutMs
  const maxFrameBytes = options.maxFrameBytes
  const cancelRetryInterval = options.cancelRetryIntervalMs
  const cancelRetryTimeout = options.cancelRetryTimeoutMs
  const maxOutstandingActivity = options.maxOutstandingActivity
  if (!Number.isFinite(setupTimeout) || setupTimeout <= 0 || !Number.isFinite(shutdownTimeout) || shutdownTimeout <= 0) throw new TypeError('ACP timeouts must be positive finite numbers')
  if (!Number.isFinite(maxFrameBytes) || maxFrameBytes <= 0) throw new TypeError('ACP maxFrameBytes must be a positive finite number')
  if (!Number.isSafeInteger(maxOutstandingActivity) || maxOutstandingActivity <= 0) throw new TypeError('ACP maxOutstandingActivity is required')
  if (!Number.isFinite(cancelRetryInterval) || cancelRetryInterval <= 0 || !Number.isFinite(cancelRetryTimeout) || cancelRetryTimeout <= 0) throw new TypeError('ACP cancel retry window must be positive finite numbers')

  async function connect(context: AuthContext, session?: DriverContext) {
    context.signal.throwIfAborted()
    const sessionId = session?.sessionId ?? `acp-auth-${randomBytes(8).toString('hex')}`
    let ownedPrompt: { requestId: string | number; activityId?: string } | undefined
    const handleStale = (ev: KeeperFrameEvent) => {
      try {
        const parsed = JSON.parse(ev.line)
        if (ownedPrompt && parsed && typeof parsed === 'object' && sameRpcId(parsed.id, ownedPrompt.requestId) && ('result' in parsed || 'error' in parsed) && !('method' in parsed)) {
          const id = ownedPrompt.activityId
          ownedPrompt = undefined
          try { io.setMeta({ ownedPrompt: null }) } catch { /* */ }
          if (id) completeActivity(id)
          else if (unmatchedMinted) completeActivity(unmatchedMinted)
          snapshotLiveActivity()
        }
      } catch { /* ignore other stale ids */ }
    }
    const io = await connectAcpProcess({
      command: options.command,
      args: options.args,
      env: { ...(options.inheritEnv ? globalThis.process.env : {}), ...options.env, ...context.profile?.env },
      cwd: session?.cwd ?? process.cwd(),
      sessionId,
      shutdownTimeoutMs: shutdownTimeout,
      maxFrameBytes,
      keeper,
      onStale: ev => handleStale(ev),
      captureStderr: options.captureStderr,
      onStderr(line) { if (session && !closed) session.onUpdate({ protocol: 'native', value: { method: 'stderr', params: { line } } }) },
      onOutgoingLine(line) {
        try {
          const parsed = JSON.parse(line)
          if (parsed && typeof parsed.method === 'string' && parsed.method === 'session/prompt' && parsed.id !== null && parsed.id !== undefined) {
            ownedPrompt = { requestId: parsed.id, ...(latestNativeId ? { activityId: latestNativeId } : {}) }
            try { io.setMeta({ ownedPrompt }) } catch { /* */ }
          }
        } catch { /* */ }
      },
    })
    const lifetime = new AbortController()
    let turn: AbortController | undefined
    let cancelRetry: ReturnType<typeof setTimeout> | undefined
    let replay = false
    let closed = false
    let runtimeReady = false
    let agentSessionId = ''
    let configOptions: ConfigOption[] = []
    let livePermissions: Extract<PermissionsSpec, { kind: 'acp' }> = factoryPermissions.kind === 'acp'
      ? factoryPermissions
      : { kind: 'acp', policy: 'ask', nativeMode: null }
    let advertisedModes = false
    let hasModeConfig = false
    const nativePermission = new Map<string, AbortController>()
    let latestNativeId: string | undefined
    let ownedUsedNative = false
    let cancelEpoch = 0
    let pendingCapture = false
    const unmatched = new Set<string>()
    const capturedIds = new Set<string>()
    const promptActivityIds = new Set<string>()
    let unmatchedMinted: string | undefined
    const stopCancelRetry = () => { if (cancelRetry !== undefined) { clearTimeout(cancelRetry); cancelRetry = undefined } }
    const abortNativePermission = (id: string) => { nativePermission.get(id)?.abort() }
    const abortAllNativePermission = () => { for (const controller of nativePermission.values()) controller.abort() }
    const abortPermission = () => { abortAllNativePermission(); turn?.abort() }
    const resetNativeTracking = () => {
      unmatched.clear()
      capturedIds.clear()
      promptActivityIds.clear()
      nativePermission.clear()
      unmatchedMinted = undefined
      latestNativeId = undefined
      pendingCapture = false
    }
    const snapshotLiveActivity = () => {
      try { io.setMeta({ liveActivity: [...unmatched] }) } catch { /* transport closing */ }
    }
    const failOverflow = () => {
      if (closed) return
      closed = true
      stopCancelRetry()
      lifetime.abort()
      abortPermission()
      resetNativeTracking()
      session?.onExit(new CoreError(ACTIVITY_OVERFLOW.code, ACTIVITY_OVERFLOW.message))
      void io.close({ mode: 'shutdown' }).catch(() => {})
    }
    const close = async (closeOptions: CloseOptions) => {
      const mode = requireCloseMode(closeOptions)
      closed = true; stopCancelRetry(); lifetime.abort(); abortPermission(); resetNativeTracking(); await io.close({ mode })
    }

    const emitUpdate = (update: AgentUpdate) => {
      if (closed) return
      session?.onUpdate(update)
      applyActivity(update)
    }

    const completeActivity = (id: string) => {
      if (!unmatched.has(id)) return
      unmatched.delete(id)
      promptActivityIds.delete(id)
      if (unmatchedMinted === id) unmatchedMinted = undefined
      capturedIds.delete(id)
      abortNativePermission(id)
      nativePermission.delete(id)
      if (latestNativeId === id) {
        latestNativeId = undefined
        for (const open of unmatched) latestNativeId = open
      }
      if (capturedIds.size === 0) {
        pendingCapture = false
        cancelEpoch++
        stopCancelRetry()
      }
      snapshotLiveActivity()
      if (!closed) session?.onActivity?.({ id, phase: 'completed' })
    }

    const startActivity = (id: string) => {
      if (unmatched.has(id)) return
      if (unmatched.size >= maxOutstandingActivity) {
        failOverflow()
        return
      }
      const newGeneration = capturedIds.size > 0 && !capturedIds.has(id) && !pendingCapture
      if (newGeneration) {
        cancelEpoch++
        stopCancelRetry()
        for (const old of capturedIds) abortNativePermission(old)
        capturedIds.clear()
      }
      unmatched.add(id)
      latestNativeId = id
      if (!unmatchedMinted) unmatchedMinted = id
      if (turn) {
        promptActivityIds.add(id)
        ownedUsedNative = true
        if (ownedPrompt) {
          ownedPrompt = { ...ownedPrompt, activityId: id }
          try { io.setMeta({ ownedPrompt }) } catch { /* */ }
        }
      }
      if (pendingCapture) {
        capturedIds.add(id)
        pendingCapture = false
        const aborted = new AbortController()
        aborted.abort()
        nativePermission.set(id, aborted)
      } else {
        nativePermission.set(id, new AbortController())
      }
      snapshotLiveActivity()
      session?.onActivity?.({ id, phase: 'started' })
    }

    const applyActivity = (update: AgentUpdate) => {
      if (closed || update.replay || !options.classifyActivity) return
      const hint = options.classifyActivity(update)
      if (!hint || (hint.phase !== 'started' && hint.phase !== 'completed')) return
      if (hint.phase === 'started') {
        let id = typeof hint.id === 'string' && hint.id ? hint.id : undefined
        if (id) {
          if (unmatched.has(id)) return
        } else {
          if (unmatchedMinted && unmatched.has(unmatchedMinted)) return
          id = `acp-activity:${++mintedActivitySeq}`
          unmatchedMinted = id
        }
        startActivity(id)
        return
      }
      const id = typeof hint.id === 'string' && hint.id ? hint.id : unmatchedMinted
      if (!id) return
      completeActivity(id)
    }

    const seedLiveActivity = (ids: unknown) => {
      if (!Array.isArray(ids)) return
      for (const id of ids) {
        if (typeof id !== 'string' || !id) continue
        startActivity(id)
      }
    }

    const connection = new ClientSideConnection(() => ({
      async requestPermission(request) {
        io.ackConsumed()
        const rawOptions = Array.isArray(request.options) ? request.options : []
        const optionList = rawOptions.map(o => ({ optionId: o.optionId, kind: String(o.kind) }))
        const toolKind = request.toolCall && typeof request.toolCall === 'object' && 'kind' in request.toolCall
          ? String((request.toolCall as { kind?: unknown }).kind)
          : undefined
        const auto = acpPermissionDecision(livePermissions, toolKind, optionList)
        if (auto.auto) {
          const toolCall = request.toolCall && typeof request.toolCall === 'object'
            ? request.toolCall as { toolCallId?: string; title?: string; kind?: string; rawInput?: unknown }
            : {}
          if (session && !closed) {
            session.onUpdate({
              protocol: 'native',
              value: {
                method: 'permission-auto',
                params: {
                  toolCall: {
                    callId: typeof toolCall.toolCallId === 'string' ? toolCall.toolCallId : '',
                    tool: typeof toolCall.kind === 'string' ? toolCall.kind : '',
                    title: typeof toolCall.title === 'string' ? toolCall.title : '',
                    input: toolCall.rawInput,
                  },
                  optionId: auto.optionId,
                },
              },
            })
          }
          return { outcome: { outcome: 'selected', optionId: auto.optionId } }
        }
        const signals: AbortSignal[] = [lifetime.signal]
        // SDK permission has no native generation id. Any two outstanding
        // native activities (including cancelled-but-not-ended) are ambiguous.
        if (unmatched.size > 1) return cancelled
        const active = [...unmatched].filter(id => {
          const controller = nativePermission.get(id)
          return controller != null && !controller.signal.aborted
        })
        if (active.length === 1) {
          const preferred = latestNativeId && active.includes(latestNativeId) ? latestNativeId : active[0]!
          const controller = nativePermission.get(preferred)
          if (!controller || controller.signal.aborted) return cancelled
          signals.push(controller.signal)
        } else if (turn && !ownedUsedNative && !pendingCapture) {
          signals.push(turn.signal)
        } else {
          return cancelled
        }
        const signal = AbortSignal.any(signals)
        if (!session || signal.aborted) return cancelled
        let cancel!: () => void
        const cancellation = new Promise<RequestPermissionResponse>(resolve => { cancel = () => resolve(cancelled); signal.addEventListener('abort', cancel, { once: true }) })
        try {
          if (session && !closed) session.onUpdate({ protocol: 'native', value: { method: 'session/request_permission', params: request } })
          // ACP option kinds map 1:1 (allow_once / allow_always / reject_once / reject_always).
          // RequestAnswer.message is ignored: the SDK permission result has no message field.
          const result = await Promise.race([Promise.resolve().then(() => session.requestPermission({ ...request, coreSessionId: session.sessionId }, signal)), cancellation])
          if (signal.aborted) return cancelled
          if (result?.outcome?.outcome === 'selected') return { outcome: { outcome: 'selected', optionId: result.outcome.optionId } }
          return result
        } finally { signal.removeEventListener('abort', cancel) }
      },
      async sessionUpdate(notification) {
        emitUpdate({ protocol: 'acp', value: notification.update, ...(replay ? { replay: true } : {}) })
        io.ackConsumed()
      },
      async extNotification() {},
      async extMethod(method, params) {
        io.ackConsumed()
        if (options.vendor === "grok" && method === "_x.ai/ask_user_question") {
          const rec = params && typeof params === "object" && !Array.isArray(params) ? params as Record<string, unknown> : {}
          const list = Array.isArray(rec.questions) ? rec.questions : []
          const specs = list.map((q, i) => {
            const row = q && typeof q === "object" && !Array.isArray(q) ? q as Record<string, unknown> : {}
            return {
              id: `q${i + 1}`,
              question: typeof row.question === "string" ? row.question : "",
              multiSelect: row.multiSelect === true,
              options: Array.isArray(row.options) ? row.options.map(opt => {
                const o = opt && typeof opt === "object" && !Array.isArray(opt) ? opt as Record<string, unknown> : {}
                return {
                  label: typeof o.label === "string" ? o.label : String(opt),
                  ...(typeof o.description === "string" && o.description ? { description: o.description } : {}),
                }
              }) : [],
            }
          })
          const toolCallId = typeof rec.toolCallId === "string" && rec.toolCallId ? rec.toolCallId : undefined
          if (!session) return { outcome: "cancelled" }
          const signals: AbortSignal[] = [lifetime.signal]
          if (turn) signals.push(turn.signal)
          const signal = AbortSignal.any(signals)
          const result = await Promise.resolve().then(() => session.requestAnswers({
            ...(toolCallId ? { toolCallId } : {}),
            questions: specs,
          }, signal)).catch(() => ({ outcome: "cancelled" as const }))
          if (result.outcome !== "answered") return { outcome: "cancelled" }
          const answers: Record<string, string> = {}
          for (const spec of specs) {
            const value = result.answers[spec.id]
            if (value === undefined) continue
            answers[spec.question] = Array.isArray(value) ? value.join(", ") : value
          }
          return { outcome: "accepted", answers }
        }
        throw new Error("Method not found")
      },
    }), (() => {
      const framed = ndJsonStream(io.output, io.input)
      const forwardOpaque = (message: unknown) => {
        if (Array.isArray(message)) { for (const item of message) forwardOpaque(item); return }
        if (!isOpaqueNotification(message) || standardNotifications.has(message.method)) return
        const params = message.params
        if (agentSessionId && params && typeof params === 'object' && !Array.isArray(params) && 'sessionId' in params && (params as { sessionId: unknown }).sessionId !== agentSessionId) return
        emitUpdate({ protocol: 'native', value: { method: message.method, params: message.params }, ...(replay ? { replay: true } : {}) })
      }
      return {
        writable: framed.writable,
        readable: framed.readable.pipeThrough(new TransformStream<AnyMessage, AnyMessage>({
          transform(message, controller) {
            forwardOpaque(message)
            controller.enqueue(message)
            const rec = message && typeof message === 'object' ? message as { method?: string } : {}
            if (rec.method !== CLIENT_METHODS.session_update && rec.method !== CLIENT_METHODS.session_request_permission) {
              io.ackConsumed()
            }
          },
        })),
      }
    })())
    const disconnected = connection.closed.then(() => {
      const error = new CoreError('connection_closed', 'ACP connection closed')
      stopCancelRetry(); lifetime.abort(); turn?.abort(); abortPermission(); resetNativeTracking()
      if (runtimeReady && !closed) session?.onExit(error)
      throw error
    })
    void disconnected.catch(() => {})
    const ioWait = <T>(promise: Promise<T>) => Promise.race([promise, io.failure, disconnected])
    io.onExit(error => { stopCancelRetry(); lifetime.abort(); turn?.abort(); abortPermission(); resetNativeTracking(); if (runtimeReady && !closed) session?.onExit(error) })
    io.begin()
    const workKnown = () => Boolean(turn) || unmatched.size > 0 || pendingCapture
    const shouldRetry = (epoch: number, deadline: number) => {
      if (closed || lifetime.signal.aborted || epoch !== cancelEpoch || Date.now() >= deadline) return false
      if (pendingCapture) return true
      if (capturedIds.size) {
        for (const id of capturedIds) if (unmatched.has(id)) return true
        return false
      }
      return Boolean(turn)
    }
    const cancelNow = () => {
      if (closed || lifetime.signal.aborted || !agentSessionId || !workKnown()) return
      void ioWait(connection.cancel({ sessionId: agentSessionId })).catch(() => {})
    }
    const scheduleCancelRetry = (epoch: number, deadline: number) => {
      stopCancelRetry()
      if (!shouldRetry(epoch, deadline)) return
      cancelRetry = setTimeout(() => {
        cancelRetry = undefined
        if (!shouldRetry(epoch, deadline)) return
        cancelNow()
        scheduleCancelRetry(epoch, deadline)
      }, cancelRetryInterval)
    }
    const beginCancel = () => {
      const epoch = ++cancelEpoch
      if (unmatched.size) {
        capturedIds.clear()
        for (const id of unmatched) capturedIds.add(id)
        pendingCapture = false
      } else {
        capturedIds.clear()
        pendingCapture = Boolean(turn)
      }
      abortPermission()
      turn?.abort()
      cancelNow()
      scheduleCancelRetry(epoch, Date.now() + cancelRetryTimeout)
    }
    let abortSetup!: () => void
    const aborted = new Promise<never>((_, reject) => { abortSetup = () => reject(abortError()); context.signal.addEventListener('abort', abortSetup, { once: true }) })
    let timer: ReturnType<typeof setTimeout>
    const timeout = new Promise<never>((_, reject) => { timer = setTimeout(() => reject(new CoreError('setup_timeout', 'ACP setup timed out')), setupTimeout) })
    const setup = <T>(promise: Promise<T>) => Promise.race([ioWait(promise), aborted, timeout])
    async function applyNativeMode(spec: Extract<PermissionsSpec, { kind: 'acp' }>, required: boolean) {
      if (spec.nativeMode == null) return
      if (!required && typeof options.sessionConfig?.mode === 'string') return
      if (advertisedModes) {
        await connection.setSessionMode({ sessionId: agentSessionId, modeId: spec.nativeMode })
        return
      }
      if (hasModeConfig || configOptions.some(o => o.id === 'mode')) {
        hasModeConfig = true
        await connection.setSessionConfigOption({ sessionId: agentSessionId, configId: 'mode', value: spec.nativeMode })
        return
      }
      if (required) throw new UnsupportedOperation('permissions', options.id)
    }
    async function setPermissions(spec: PermissionsSpec) {
      if (closed) throw new CoreError('runtime_closed', 'ACP runtime closed')
      const next = validatePermissionsSpec(spec)
      if (next.kind !== 'acp') throw new TypeError('ACP permissions kind must be acp')
      if (JSON.stringify(livePermissions) !== JSON.stringify(next)) {
        await applyNativeMode(next, true)
      }
      livePermissions = next
      try { io.setMeta({ permissions: next }) } catch { /* */ }
      return { applied: appliedFor(next) }
    }
    try {
      const reattach = io.welcome.agentRunning === true && typeof io.welcome.meta.agentSessionId === 'string'
      let canResume = false
      let canLoad = false
      if (reattach) {
        const sid = io.welcome.meta.agentSessionId as string
        if (session?.resumeId && session.resumeId !== sid) throw new CoreError('session_identity', 'ACP session identity mismatch')
        agentSessionId = sid
        const live = io.welcome.meta.liveActivity
        seedLiveActivity(live)
        const owned = io.welcome.meta.ownedPrompt
        if (owned && typeof owned === 'object' && !Array.isArray(owned) && (owned as { requestId?: unknown }).requestId != null) {
          const rec = owned as { requestId: string | number; activityId?: string }
          ownedPrompt = rec
          const seedId = typeof rec.activityId === 'string' && rec.activityId ? rec.activityId : `acp-owned:${String(rec.requestId)}`
          startActivity(seedId)
          ownedPrompt = { requestId: rec.requestId, activityId: seedId }
        }
        // A re-attach reuses the live agent, so a config option the caller passes now (e.g. a
        // different OpenCode model) must still reach it; applying it idempotently keeps the
        // "what you asked for is what runs" rule. Skipped while a turn is live: the agent would
        // switch mid-turn or reject, so the caller sees the same session_busy they would get
        // from configure.
        const metaCfg = io.welcome.meta.sessionConfig
        const applied: Record<string, unknown> = metaCfg && typeof metaCfg === 'object' && !Array.isArray(metaCfg) ? metaCfg as Record<string, unknown> : {}
        const rememberedOptions = Array.isArray(io.welcome.meta.configOptions) ? io.welcome.meta.configOptions as ConfigOption[] : []
        const wanted = Object.fromEntries(Object.entries(options.sessionConfig ?? {}).map(([k, v]) => [k, resolveConfigValue(rememberedOptions, k, v)]))
        const changed = Object.entries(wanted).filter(([k, v]) => applied[k] !== v)
        if (changed.length && (io.welcome.meta.liveActivity as unknown[] | undefined)?.length) {
          throw new CoreError('session_busy', 'Cannot change session config while the re-attached agent has live work')
        }
        for (const [configId, value] of changed) {
          await setup(connection.setSessionConfigOption({ sessionId: agentSessionId, configId, value }))
        }
        if (changed.length) io.setMeta({ sessionConfig: { ...applied, ...Object.fromEntries(changed) } })
        const seeded = io.welcome.meta.permissions
        if (seeded && typeof seeded === 'object') {
          try { livePermissions = validatePermissionsSpec(seeded) as Extract<PermissionsSpec, { kind: 'acp' }> } catch { /* keep factory spec */ }
        }
        advertisedModes = io.welcome.meta.advertisedModes === true
        hasModeConfig = io.welcome.meta.hasModeConfig === true
        runtimeReady = true
      } else {
      const initialized = await setup(connection.initialize({ protocolVersion: PROTOCOL_VERSION, clientCapabilities: {}, clientInfo: { name: 'supermux-core', version: '0.0.0' } }))
      if (initialized.protocolVersion !== PROTOCOL_VERSION) throw new CoreError('protocol_version', `Unsupported ACP protocol version ${initialized.protocolVersion}`)
      if (session && !closed) session.onUpdate({ protocol: 'native', value: { method: 'initialize', params: initialized } })
      const methods = initialized.authMethods ?? []
      async function authenticate(methodId: string) {
        const method = methods.find(method => method.id === methodId)
        if (!method) throw new CoreError('auth_method', `Unknown ACP authentication method: ${methodId}`)
        if ('type' in method && method.type === 'terminal') throw new UnsupportedOperation('terminal authentication', options.id)
        await setup(connection.authenticate({ methodId }))
      }
      if (!session) return { methods, authenticate, close, finishSetup }
      if (context.profile?.methodId) await authenticate(context.profile.methodId)
      const capabilities = initialized.agentCapabilities
      canResume = capabilities?.sessionCapabilities?.resume != null
      canLoad = capabilities?.loadSession === true
      const modes = (capabilities?.sessionCapabilities as { modes?: unknown } | undefined)?.modes
      advertisedModes = modes != null
      const params = { cwd: session.cwd, mcpServers: options.mcpServers }
      if (session.resumeId) {
        agentSessionId = session.resumeId
        if (canResume) configOptions = readConfigOptions(await setup(connection.resumeSession({ ...params, sessionId: agentSessionId })))
        else if (canLoad) {
          replay = true
          try { configOptions = readConfigOptions(await setup(connection.loadSession({ ...params, sessionId: agentSessionId }))) } finally { replay = false }
        } else throw new UnsupportedOperation('resume', options.id)
      } else {
        const created = await setup(connection.newSession(params))
        agentSessionId = created.sessionId
        configOptions = readConfigOptions(created)
      }
      // load/resume results carry no option lists (Cursor), so a caller's picker-style
      // value could not be resolved on a resumed session. A turn-less session/new is
      // cheap and never persisted by these agents: use it only to read the lists.
      // A fresh session/new that advertised nothing means the agent has no lists.
      if (session.resumeId && configOptions.length === 0 && Object.keys(options.sessionConfig ?? {}).length > 0) {
        configOptions = readConfigOptions(await setup(connection.newSession(params)))
      }
      const appliedConfig: Record<string, string> = {}
      for (const [configId, value] of Object.entries(options.sessionConfig ?? {})) {
        const resolved = resolveConfigValue(configOptions, configId, value)
        await setup(connection.setSessionConfigOption({ sessionId: agentSessionId, configId, value: resolved }))
        appliedConfig[configId] = resolved
      }
      hasModeConfig = configOptions.some(o => o.id === 'mode')
      if (livePermissions.nativeMode != null) await setup(applyNativeMode(livePermissions, false))
      io.setMeta({ agentSessionId, permissions: livePermissions, advertisedModes, hasModeConfig, ...(options.sessionConfig ? { sessionConfig: appliedConfig } : {}), ...(configOptions.length ? { configOptions } : {}) })
      finishSetup()
      runtimeReady = true
      const normalizer = createAcpNormalizer()
      return { runtime: {
        agentSessionId,
        capabilities: { resume: canResume || canLoad, steer: false, fork: false, detach: true, permissions: true },
        normalize: normalizer,
        flush: () => normalizer.flush(),
        setPermissions,
        async prompt(content: Parameters<import('../types.js').AgentRuntime['prompt']>[0], signal: AbortSignal) {
          if (closed) throw new CoreError('runtime_closed', 'ACP runtime closed')
          signal.throwIfAborted()
          if (turn) throw new CoreError('busy', 'ACP prompt is already running')
          ownedUsedNative = unmatched.size > 0
          const active = new AbortController()
          turn = active
          const onAbort = () => { if (turn === active) beginCancel() }
          signal.addEventListener('abort', onAbort, { once: true })
          try { return await ioWait(connection.prompt({ sessionId: agentSessionId, prompt: content })) }
          finally {
            signal.removeEventListener('abort', onAbort)
            promptActivityIds.clear()
            active.abort()
            if (turn === active) {
              turn = undefined
              ownedUsedNative = unmatched.size > 0
            }
            ownedPrompt = undefined
            try { io.setMeta({ ownedPrompt: null }) } catch { /* */ }
            pendingCapture = false
            let capturedOpen = false
            for (const id of capturedIds) if (unmatched.has(id)) capturedOpen = true
            if (!capturedOpen) {
              cancelEpoch++
              stopCancelRetry()
            }
          }
        },
        async interrupt() {
          if (!turn && unmatched.size === 0 && !pendingCapture) return
          beginCancel()
        },
        close,
      }, close, finishSetup }
      }
      finishSetup()
      if (!session) {
        await close({ mode: 'shutdown' })
        return { methods: [], authenticate: async () => {}, close, finishSetup }
      }
      const fallbackNormalizer = createAcpNormalizer()
      return { runtime: {
        agentSessionId,
        capabilities: { resume: true, steer: false, fork: false, detach: true, permissions: true },
        normalize: fallbackNormalizer,
        flush: () => fallbackNormalizer.flush(),
        setPermissions,
        async prompt(content: Parameters<import('../types.js').AgentRuntime['prompt']>[0], signal: AbortSignal) {
          if (closed) throw new CoreError('runtime_closed', 'ACP runtime closed')
          signal.throwIfAborted()
          if (turn) throw new CoreError('busy', 'ACP prompt is already running')
          ownedUsedNative = unmatched.size > 0
          const active = new AbortController()
          turn = active
          const onAbort = () => { if (turn === active) beginCancel() }
          signal.addEventListener('abort', onAbort, { once: true })
          try { return await ioWait(connection.prompt({ sessionId: agentSessionId, prompt: content })) }
          finally {
            signal.removeEventListener('abort', onAbort)
            promptActivityIds.clear()
            active.abort()
            if (turn === active) {
              turn = undefined
              ownedUsedNative = unmatched.size > 0
            }
            ownedPrompt = undefined
            try { io.setMeta({ ownedPrompt: null }) } catch { /* */ }
            pendingCapture = false
            let capturedOpen = false
            for (const id of capturedIds) if (unmatched.has(id)) capturedOpen = true
            if (!capturedOpen) {
              cancelEpoch++
              stopCancelRetry()
            }
          }
        },
        async interrupt() {
          if (!turn && unmatched.size === 0 && !pendingCapture) return
          beginCancel()
        },
        close,
      }, close, finishSetup }
    } catch (error) { finishSetup(); await close({ mode: 'shutdown' }); throw error }
    function finishSetup() { clearTimeout(timer!); context.signal.removeEventListener('abort', abortSetup) }
  }
  return {
    id: options.id,
    async open(context) { const connected = await connect(context, context); return connected.runtime! },
    auth: {
      async methods(context): Promise<AuthMethod[]> {
        const connected = await connect(context)
        try { return connected.methods! } finally { connected.finishSetup(); await connected.close({ mode: 'shutdown' }) }
      },
      async login(context, methodId) {
        const connected = await connect(context)
        try { await connected.authenticate!(methodId) } finally { connected.finishSetup(); await connected.close({ mode: 'shutdown' }) }
      },
    },
  }
}
