import { CLIENT_METHODS, ClientSideConnection, ndJsonStream, PROTOCOL_METHODS, PROTOCOL_VERSION } from '@agentclientprotocol/sdk'
import type { AnyMessage, AuthMethod, McpServer, RequestPermissionResponse } from '@agentclientprotocol/sdk'
import type { AgentDriver, AgentUpdate, AuthContext, DriverContext } from '../types.js'
import { ACTIVITY_OVERFLOW, MAX_OUTSTANDING_ACTIVITY } from '../activity.js'
import { CoreError, UnsupportedOperation } from '../errors.js'
import { launch } from './process.js'

export type AcpActivityHint = { id?: string; phase: 'started' | 'completed' }
export type AcpActivityClassifier = (update: AgentUpdate) => AcpActivityHint | undefined

export type AcpOptions = {
  id: string
  command: string
  args?: string[]
  env?: Record<string, string>
  inheritEnv?: boolean
  mcpServers?: McpServer[]
  setupTimeoutMs?: number
  shutdownTimeoutMs?: number
  /** Delay between same-prompt cancel retries. Default 250ms. */
  cancelRetryIntervalMs?: number
  /**
   * How long to keep retrying cancel for the live prompt. Default 10s.
   * This is not a universal cancellation guarantee: the core session
   * interruptTimeoutMs is independent and may return `{status:'unconfirmed'}`
   * while these retries continue. ACP never fabricates a cancelled prompt result.
   */
  cancelRetryTimeoutMs?: number
  /**
   * Optional vendor-specific mapping from a forwarded update to a native activity
   * notice. Generic ACP does not parse Grok `_x.ai` payloads itself.
   */
  classifyActivity?: AcpActivityClassifier
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

export function acp(options: AcpOptions): AgentDriver {
  const setupTimeout = options.setupTimeoutMs ?? 30_000
  const shutdownTimeout = options.shutdownTimeoutMs ?? 2_000
  const cancelRetryInterval = options.cancelRetryIntervalMs ?? 250
  const cancelRetryTimeout = options.cancelRetryTimeoutMs ?? 10_000
  if (!options.id || !options.command) throw new TypeError('ACP id and command are required')
  if (!Number.isFinite(setupTimeout) || setupTimeout <= 0 || !Number.isFinite(shutdownTimeout) || shutdownTimeout <= 0) throw new TypeError('ACP timeouts must be positive finite numbers')
  if (!Number.isFinite(cancelRetryInterval) || cancelRetryInterval <= 0 || !Number.isFinite(cancelRetryTimeout) || cancelRetryTimeout <= 0) throw new TypeError('ACP cancel retry window must be positive finite numbers')

  async function connect(context: AuthContext, session?: DriverContext) {
    context.signal.throwIfAborted()
    const process = launch(options.command, options.args ?? [], {
      ...(options.inheritEnv === false ? {} : globalThis.process.env), ...options.env, ...context.profile?.env,
    }, session?.cwd, shutdownTimeout)
    const lifetime = new AbortController()
    let turn: AbortController | undefined
    let cancelRetry: ReturnType<typeof setTimeout> | undefined
    let replay = false
    let closed = false
    let runtimeReady = false
    let agentSessionId = ''
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
    const failOverflow = () => {
      if (closed) return
      closed = true
      stopCancelRetry()
      lifetime.abort()
      abortPermission()
      resetNativeTracking()
      session?.onExit(new CoreError(ACTIVITY_OVERFLOW.code, ACTIVITY_OVERFLOW.message))
      void process.close().catch(() => {})
    }
    const close = async () => { closed = true; stopCancelRetry(); lifetime.abort(); abortPermission(); resetNativeTracking(); await process.close() }

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
      if (!closed) session?.onActivity?.({ id, phase: 'completed' })
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
        if (!unmatched.has(id) && unmatched.size >= MAX_OUTSTANDING_ACTIVITY) {
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
        session?.onActivity?.({ id, phase: 'started' })
        return
      }
      const id = typeof hint.id === 'string' && hint.id ? hint.id : unmatchedMinted
      if (!id) return
      completeActivity(id)
    }

    const connection = new ClientSideConnection(() => ({
      async requestPermission(request) {
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
          const result = await Promise.race([Promise.resolve().then(() => session.requestPermission({ ...request, coreSessionId: session.sessionId }, signal)), cancellation])
          if (signal.aborted) return cancelled
          return result
        } finally { signal.removeEventListener('abort', cancel) }
      },
      async sessionUpdate(notification) {
        emitUpdate({ protocol: 'acp', value: notification.update, ...(replay ? { replay: true } : {}) })
      },
      async extNotification() {},
    }), (() => {
      const framed = ndJsonStream(process.output, process.input)
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
          transform(message, controller) { forwardOpaque(message); controller.enqueue(message) },
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
    const io = <T>(promise: Promise<T>) => Promise.race([promise, process.failure, disconnected])
    process.onExit(error => { stopCancelRetry(); lifetime.abort(); turn?.abort(); abortPermission(); resetNativeTracking(); if (runtimeReady && !closed) session?.onExit(error) })
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
      void io(connection.cancel({ sessionId: agentSessionId })).catch(() => {})
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
    const setup = <T>(promise: Promise<T>) => Promise.race([io(promise), aborted, timeout])
    try {
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
      const canResume = capabilities?.sessionCapabilities?.resume != null
      const canLoad = capabilities?.loadSession === true
      const params = { cwd: session.cwd, mcpServers: options.mcpServers ?? [] }
      if (session.resumeId) {
        agentSessionId = session.resumeId
        if (canResume) await setup(connection.resumeSession({ ...params, sessionId: agentSessionId }))
        else if (canLoad) {
          replay = true
          try { await setup(connection.loadSession({ ...params, sessionId: agentSessionId })) } finally { replay = false }
        } else throw new UnsupportedOperation('resume', options.id)
      } else agentSessionId = (await setup(connection.newSession(params))).sessionId
      finishSetup()
      runtimeReady = true
      return { runtime: {
        agentSessionId,
        capabilities: { resume: canResume || canLoad, steer: false, fork: false, detach: false },
        async prompt(content: Parameters<import('../types.js').AgentRuntime['prompt']>[0], signal: AbortSignal) {
          if (closed) throw new CoreError('runtime_closed', 'ACP runtime closed')
          signal.throwIfAborted()
          if (turn) throw new CoreError('busy', 'ACP prompt is already running')
          ownedUsedNative = unmatched.size > 0
          const active = new AbortController()
          turn = active
          const onAbort = () => { if (turn === active) beginCancel() }
          signal.addEventListener('abort', onAbort, { once: true })
          try { return await io(connection.prompt({ sessionId: agentSessionId, prompt: content })) }
          finally {
            signal.removeEventListener('abort', onAbort)
            promptActivityIds.clear()
            active.abort()
            if (turn === active) {
              turn = undefined
              ownedUsedNative = unmatched.size > 0
            }
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
    } catch (error) { finishSetup(); await close(); throw error }
    function finishSetup() { clearTimeout(timer!); context.signal.removeEventListener('abort', abortSetup) }
  }
  return {
    id: options.id,
    async open(context) { const connected = await connect(context, context); return connected.runtime! },
    auth: {
      async methods(context): Promise<AuthMethod[]> {
        const connected = await connect(context)
        try { return connected.methods! } finally { connected.finishSetup(); await connected.close() }
      },
      async login(context, methodId) {
        const connected = await connect(context)
        try { await connected.authenticate!(methodId) } finally { connected.finishSetup(); await connected.close() }
      },
    },
  }
}
