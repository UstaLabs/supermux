import { randomBytes } from 'node:crypto'
import { CLIENT_METHODS, ClientSideConnection, ndJsonStream, PROTOCOL_METHODS, PROTOCOL_VERSION } from '@agentclientprotocol/sdk'
import type { AnyMessage, AuthMethod, McpServer, RequestPermissionResponse, SessionNotification } from '@agentclientprotocol/sdk'
import type { AgentDriver, AgentRuntime, AgentUpdate, AuthContext, Capabilities, CloseOptions, ContentBlock, DriverContext, PermissionsSpec } from '../types.js'
import { requireCloseMode } from '../types.js'
import { ACTIVITY_OVERFLOW } from '../activity.js'
import { CoreError, UnsupportedOperation } from '../errors.js'
import { acpPermissionDecision, appliedFor, validatePermissionsSpec } from '../permissions.js'
import { connectAcpProcess, type AcpKeeperLimits } from './process.js'
import { createAcpNormalizer, DRIVER_WARNING_METHOD, SUBAGENT_SESSION_METHOD, SUBAGENT_TURN_METHOD, type AcpNormalizer, type AcpVendor } from './normalize.js'
import { freeLoopbackPort, newOpenCodeServerInfo, openCodeAskToAcp, openCodeServerClient, readOpenCodeServerInfo, type OpenCodePendingAsk, type OpenCodeServerClient, type OpenCodeServerInfo } from './opencode-server.js'
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
  /**
   * Vendor quirks. `grok`: `_x.ai/ask_user_question` requests and `_x.ai` subagent notifications.
   * `cursor`: advertises the ACP subagents extension (`clientCapabilities._meta.subagents`) and
   * reads `cursor/task`. `opencode`: its `task` tool is the subagent.
   */
  vendor?: AcpVendor
  /** Normalizer to use instead of a private one (the Grok wrapper keeps one across respawns). */
  normalizer?: AcpNormalizer
  permissions: Extract<PermissionsSpec, { kind: "acp" }>
  /** OpenCode only: how often the side channel polls OpenCode's HTTP server for subagent asks while work is live. Default 500 ms. */
  openCodePollIntervalMs?: number
}

/**
 * Cursor offers no client→subagent channel (prompt/load on a child session are refused); the
 * parent model resumes the agent through its Task tool (`resume`), which keeps its context.
 */
export function cursorRelayPrompt(subagentId: string, content: ContentBlock[]): ContentBlock[] {
  const parts: string[] = []
  for (const block of content) {
    if (block.type !== 'text') throw new CoreError('invalid_input', 'Cursor relays only text to a subagent')
    parts.push(block.text)
  }
  const text = parts.join('\n')
  if (!text.trim()) throw new CoreError('invalid_input', 'Subagent message is empty')
  const id = JSON.stringify(subagentId)
  return [{
    type: 'text',
    text: [
      `[supermux relay] The user wrote a message for your earlier subagent ${id}. Forward it; do not act on it yourself.`,
      `Call your Task tool exactly once with resume=${id} (agent id ${subagentId}) and prompt set to the exact text between <relay> and </relay> below, unchanged.`,
      'Do not read files or run tools yourself. When the subagent returns, reply with its answer verbatim and nothing else.',
      '<relay>',
      text,
      '</relay>',
    ].join('\n'),
  }]
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
    // OpenCode: pin its HTTP server's port and password so the side channel can reach it (see opencode-server.ts).
    const plannedServer: OpenCodeServerInfo | undefined = options.vendor === 'opencode' ? newOpenCodeServerInfo(await freeLoopbackPort()) : undefined
    const io = await connectAcpProcess({
      command: options.command,
      args: plannedServer ? [...options.args, '--port', String(plannedServer.port)] : options.args,
      env: { ...(options.inheritEnv ? globalThis.process.env : {}), ...options.env, ...context.profile?.env, ...(plannedServer ? { OPENCODE_SERVER_PASSWORD: plannedServer.password } : {}) },
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
          // Only the main session's prompt is the owned turn; a direct prompt to a child session is not.
          if (parsed && typeof parsed.method === 'string' && parsed.method === 'session/prompt' && parsed.id !== null && parsed.id !== undefined && (!agentSessionId || parsed.params?.sessionId === agentSessionId)) {
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
    /** Cursor negotiated the ACP subagents extension. */
    let cursorSubagents = false
    const normalizer: AcpNormalizer = options.normalizer ?? createAcpNormalizer({
      ...(options.vendor ? { vendor: options.vendor } : {}),
      mainSessionId: () => agentSessionId || undefined,
      cursorSubagents: () => cursorSubagents,
    })
    /** Child sessions loaded into this process (a direct prompt needs session/load once). */
    const loadedChildren = new Set<string>()
    /** Child sessions whose session/load replay is in flight: history, not live activity. */
    const loadingChildren = new Set<string>()
    /** Direct prompts in flight, keyed by child session id. */
    const childPrompts = new Map<string, AbortController>()
    /** Child tool call id → child session id, from the child's own session/update frames. */
    const childToolSessions = new Map<string, string>()
    const subagentOfSession = (sessionId: string) => normalizer.subagentForSession(sessionId) ?? sessionId
    function subagentOfPermission(request: { sessionId?: unknown; toolCall?: unknown }): string | undefined {
      const sessionId = typeof request.sessionId === 'string' ? request.sessionId : undefined
      if (sessionId && agentSessionId && sessionId !== agentSessionId) return subagentOfSession(sessionId)
      const callId = request.toolCall && typeof request.toolCall === 'object' ? (request.toolCall as { toolCallId?: unknown }).toolCallId : undefined
      if (typeof callId !== 'string') return
      const childSession = childToolSessions.get(callId)
      if (childSession) return subagentOfSession(childSession)
      return normalizer.subagentForTool(callId)
    }
    type PermissionAsk = { sessionId?: unknown; toolCall?: unknown; options?: unknown }
    /** The live policy's automatic answer, or undefined when the user must be asked. */
    function autoPermission(request: PermissionAsk, subagentId?: string): RequestPermissionResponse | undefined {
      const rawOptions = Array.isArray(request.options) ? request.options as { optionId: string; kind: unknown }[] : []
      const optionList = rawOptions.map(o => ({ optionId: o.optionId, kind: String(o.kind) }))
      const toolKind = request.toolCall && typeof request.toolCall === 'object' && 'kind' in request.toolCall
        ? String((request.toolCall as { kind?: unknown }).kind)
        : undefined
      const auto = acpPermissionDecision(livePermissions, toolKind, optionList)
      if (!auto.auto) return
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
              ...(subagentId ? { subagentId } : {}),
            },
          },
        })
      }
      return { outcome: { outcome: 'selected', optionId: auto.optionId } }
    }
    /** Ask the user through the session's request path; `cancelled` when `signal` ends first. */
    async function askPermission(request: PermissionAsk, signal: AbortSignal, subagentId: string | undefined): Promise<RequestPermissionResponse> {
      if (!session || signal.aborted) return cancelled
      let cancel!: () => void
      const cancellation = new Promise<RequestPermissionResponse>(resolve => { cancel = () => resolve(cancelled); signal.addEventListener('abort', cancel, { once: true }) })
      try {
        if (session && !closed) session.onUpdate({ protocol: 'native', value: { method: 'session/request_permission', params: request } })
        // ACP option kinds map 1:1 (allow_once / allow_always / reject_once / reject_always).
        // RequestAnswer.message is ignored: the SDK permission result has no message field.
        const live = session
        const result = await Promise.race([Promise.resolve().then(() => live.requestPermission({ ...(request as Parameters<typeof live.requestPermission>[0]), coreSessionId: live.sessionId, ...(subagentId ? { subagentId } : {}) }, signal)), cancellation])
        if (signal.aborted) return cancelled
        if (result?.outcome?.outcome === 'selected') return { outcome: { outcome: 'selected', optionId: result.outcome.optionId } }
        return result as RequestPermissionResponse
      } finally { signal.removeEventListener('abort', cancel) }
    }
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
      // A subagent's own session never drives the parent's turn tracking.
      if (update.protocol === 'acp' && update.sessionId && agentSessionId && update.sessionId !== agentSessionId) return
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

    function handleSessionUpdate(notification: { sessionId?: unknown; update?: unknown }) {
      const sessionId = notification.sessionId
      const child = typeof sessionId === 'string' && agentSessionId && sessionId !== agentSessionId
      if (child && loadingChildren.has(sessionId)) { io.ackConsumed(); return }
      if (child) {
        const callId = (notification.update as { toolCallId?: unknown } | undefined)?.toolCallId
        if (typeof callId === 'string' && callId) {
          childToolSessions.set(callId, sessionId)
          if (childToolSessions.size > 512) childToolSessions.delete(childToolSessions.keys().next().value as string)
        }
      }
      emitUpdate({ protocol: 'acp', value: notification.update as SessionNotification['update'], ...(typeof sessionId === 'string' ? { sessionId } : {}), ...(replay ? { replay: true } : {}) })
      io.ackConsumed()
    }
    /** Session updates outside the SDK schema (Cursor's subagents extension) that its validator would drop. */
    const isExtensionUpdate = (message: unknown): message is { params: { sessionId?: unknown; update: unknown } } => {
      if (!isOpaqueNotification(message) || message.method !== CLIENT_METHODS.session_update) return false
      const kind = (message.params as { update?: { sessionUpdate?: unknown } } | undefined)?.update?.sessionUpdate
      return typeof kind === 'string' && kind.startsWith('subagent_')
    }
    const connection = new ClientSideConnection(() => ({
      async requestPermission(request) {
        io.ackConsumed()
        const auto = autoPermission(request)
        if (auto) return auto
        const signals: AbortSignal[] = [lifetime.signal]
        const subagentId = subagentOfPermission(request)
        const childPrompt = typeof request.sessionId === 'string' ? childPrompts.get(request.sessionId) : undefined
        if (childPrompt) {
          // A direct prompt we sent to a child session: its own turn owns the question.
          signals.push(childPrompt.signal)
        } else if (unmatched.size > 1) {
          // SDK permission has no native generation id. Any two outstanding
          // native activities (including cancelled-but-not-ended) are ambiguous.
          return cancelled
        } else {
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
        }
        return askPermission(request, AbortSignal.any(signals), subagentId)
      },
      async sessionUpdate(notification) { handleSessionUpdate(notification) },
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
        if (method === "cursor/task") {
          // Cursor reports a finished Task (description, prompt, agentId, durationMs) and waits for
          // an answer; the payload enriches the subagent.
          if (session && !closed) emitUpdate({ protocol: 'native', value: { method, params } })
          return {}
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
            if (isExtensionUpdate(message)) { handleSessionUpdate(message.params); return }
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

    // ── OpenCode side channel ────────────────────────────────────────────────────────────
    // OpenCode's ACP agent drops permission asks from task subagents' child sessions (it relays
    // only sessions registered over ACP), so a child that asks would wait forever. While work
    // is live, poll OpenCode's own HTTP server for pending asks from sessions ACP does not
    // relay, answer them through the same live policy / request path as parent asks (labelled
    // with the subagent), and learn each running task's child session early.
    let openCodeServer: OpenCodeServerClient | undefined
    let sideChannelTimer: ReturnType<typeof setInterval> | undefined
    let sideChannelBusy = false
    let sideChannelFailures = 0
    let sideChannelWarned = false
    const sideChannelAbort = new AbortController()
    /** Asks being answered (or answered) by the side channel; aborted and pruned once OpenCode drops them. */
    const sideAsks = new Map<string, AbortController>()
    const childParents = new Map<string, string | null>()
    function warnSideChannel(message: string) {
      if (sideChannelWarned || !session || closed) return
      sideChannelWarned = true
      session.onUpdate({ protocol: 'native', value: { method: DRIVER_WARNING_METHOD, params: { message } } })
    }
    async function subagentOfChildSession(sessionId: string, server: OpenCodeServerClient): Promise<string | undefined> {
      let current: string | undefined = sessionId
      for (let depth = 0; current && depth < 6; depth++) {
        if (current === agentSessionId) return
        const known = normalizer.subagentForSession(current)
        if (known) return known
        let parent = childParents.get(current)
        if (parent === undefined) {
          parent = await server.parentOf(current, sideChannelAbort.signal).catch(() => undefined) ?? null
          childParents.set(current, parent)
          if (childParents.size > 256) childParents.delete(childParents.keys().next().value as string)
        }
        current = parent ?? undefined
      }
      return
    }
    async function answerChildAsk(ask: OpenCodePendingAsk, server: OpenCodeServerClient, gone: AbortSignal) {
      const request = openCodeAskToAcp(ask)
      const subagentId = await subagentOfChildSession(ask.sessionID, server)
      // The ask is withdrawn when OpenCode drops it (the child was aborted or answered elsewhere).
      const signals: AbortSignal[] = [lifetime.signal, sideChannelAbort.signal, gone]
      if (turn) signals.push(turn.signal)
      const result = autoPermission(request, subagentId) ?? await askPermission(request, AbortSignal.any(signals), subagentId)
      if (closed || lifetime.signal.aborted || gone.aborted) return
      const chosen = result.outcome.outcome === 'selected' ? result.outcome.optionId : 'reject'
      const reply = chosen === 'once' || chosen === 'always' ? chosen : 'reject'
      await server.reply(ask.id, reply, sideChannelAbort.signal).catch(() => { /* already answered or gone */ })
    }
    async function pollSideChannel() {
      if (sideChannelBusy || closed || !runtimeReady || !session || !agentSessionId) return
      if (!workKnown() && childPrompts.size === 0) return
      const server = openCodeServer
      if (!server) {
        if (normalizer.openSubagents().length) warnSideChannel('This OpenCode session cannot relay its subagents\' permission requests (it was started before supermux could reach OpenCode\'s server). A subagent that needs approval will wait; restart the session to fix this.')
        return
      }
      sideChannelBusy = true
      try {
        const unbound = normalizer.openSubagents().filter(sub => !sub.nativeId)
        if (unbound.length) {
          const children = await server.runningTaskChildren(agentSessionId, sideChannelAbort.signal)
          for (const sub of unbound) {
            const child = children.get(sub.id)
            if (child && session && !closed) session.onUpdate({ protocol: 'native', value: { method: SUBAGENT_SESSION_METHOD, params: { subagentId: sub.id, nativeId: child } } })
          }
        }
        const asks = await server.pendingAsks(sideChannelAbort.signal)
        const present = new Set(asks.map(a => a.id))
        for (const [id, gone] of sideAsks) if (!present.has(id)) { gone.abort(); sideAsks.delete(id) }
        for (const ask of asks) {
          // The parent's own asks, and a child we loaded over ACP, arrive as session/request_permission.
          if (ask.sessionID === agentSessionId || loadedChildren.has(ask.sessionID) || loadingChildren.has(ask.sessionID) || sideAsks.has(ask.id)) continue
          const gone = new AbortController()
          sideAsks.set(ask.id, gone)
          void answerChildAsk(ask, server, gone.signal).catch(() => { if (sideAsks.get(ask.id) === gone) sideAsks.delete(ask.id) })
        }
        sideChannelFailures = 0
      } catch {
        if (closed || sideChannelAbort.signal.aborted) return
        if (++sideChannelFailures >= 10 && normalizer.openSubagents().length) {
          warnSideChannel('supermux cannot reach this OpenCode session\'s server, so subagent permission requests are not relayed. A subagent that needs approval will wait until the session is interrupted.')
        }
      } finally { sideChannelBusy = false }
    }
    function startSideChannel(info: OpenCodeServerInfo | undefined) {
      if (options.vendor !== 'opencode' || !session || sideChannelTimer) return
      openCodeServer = info ? openCodeServerClient(info, session.cwd) : undefined
      sideChannelTimer = setInterval(() => { void pollSideChannel() }, options.openCodePollIntervalMs ?? 500)
      sideChannelTimer.unref?.()
    }
    lifetime.signal.addEventListener('abort', () => {
      if (sideChannelTimer) clearInterval(sideChannelTimer)
      sideChannelTimer = undefined
      sideChannelAbort.abort()
      for (const gone of sideAsks.values()) gone.abort()
      sideAsks.clear()
    }, { once: true })
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
    async function prompt(content: ContentBlock[], signal: AbortSignal) {
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
    }
    /** The child session a subagent is reached through (OpenCode: the task's child session). */
    function childSessionOf(subagentId: string): string {
      const info = normalizer.subagent(subagentId)
      if (!info) throw new CoreError('subagent_not_found', `Unknown subagent ${subagentId}`)
      const target = info.nativeId ?? info.id
      if (options.vendor === 'opencode' && !info.nativeId) throw new CoreError('session_busy', 'The OpenCode subagent has not reported its session yet')
      return target
    }
    function emitSubagentTurn(params: Record<string, unknown>) {
      if (session && !closed) session.onUpdate({ protocol: 'native', value: { method: SUBAGENT_TURN_METHOD, params } })
    }
    async function messageSubagent(subagentId: string, content: ContentBlock[]) {
      if (closed) throw new CoreError('runtime_closed', 'ACP runtime closed')
      if (options.vendor === 'cursor') return { via: 'relay' as const, relay: cursorRelayPrompt(normalizer.subagent(subagentId)?.id ?? subagentId, content) }
      if (options.vendor !== 'grok' && options.vendor !== 'opencode') throw new UnsupportedOperation('subagent messaging', options.id)
      const info = normalizer.subagent(subagentId)
      const canonical = info?.id ?? subagentId
      const child = childSessionOf(subagentId)
      if (childPrompts.has(child)) throw new CoreError('session_busy', 'A message to this subagent is still running')
      if (!loadedChildren.has(child)) {
        // Grok/OpenCode serve a child session only after session/load; its replay is history.
        loadingChildren.add(child)
        try { await ioWait(connection.loadSession({ sessionId: child, cwd: session!.cwd, mcpServers: options.mcpServers })) }
        finally { loadingChildren.delete(child) }
        loadedChildren.add(child)
      }
      const controller = new AbortController()
      childPrompts.set(child, controller)
      emitSubagentTurn({ subagentId: canonical, phase: 'started' })
      void ioWait(connection.prompt({ sessionId: child, prompt: content })).then(result => {
        emitSubagentTurn({ subagentId: canonical, phase: result?.stopReason === 'cancelled' ? 'cancelled' : 'completed', stopReason: result?.stopReason })
      }, error => {
        emitSubagentTurn({ subagentId: canonical, phase: 'failed', error: error instanceof Error ? error.message : String(error) })
      }).finally(() => {
        controller.abort()
        if (childPrompts.get(child) === controller) childPrompts.delete(child)
      })
      return { via: 'direct' as const }
    }
    async function stopSubagent(subagentId: string) {
      if (closed) throw new CoreError('runtime_closed', 'ACP runtime closed')
      if (options.vendor !== 'grok' && options.vendor !== 'opencode') throw new UnsupportedOperation('subagent stop', options.id)
      const child = childSessionOf(subagentId)
      normalizer.markStopRequested(subagentId)
      childPrompts.get(child)?.abort()
      // OpenCode's ACP layer ignores session/cancel for task children it did not create (the same
      // gap as their permission asks), so a stuck child — e.g. a hung webfetch — kept its parent's
      // Task waiting forever. Its own HTTP server aborts any session.
      if (options.vendor === 'opencode' && openCodeServer) {
        await openCodeServer.abort(child, sideChannelAbort.signal)
        return
      }
      await ioWait(connection.cancel({ sessionId: child }))
    }
    function makeRuntime(capabilities: Capabilities): AgentRuntime {
      return {
        agentSessionId,
        capabilities,
        normalize: normalizer,
        flush: () => normalizer.flush(),
        setPermissions,
        prompt,
        async interrupt() {
          if (!turn && unmatched.size === 0 && !pendingCapture) return
          beginCancel()
        },
        close,
        messageSubagent,
        stopSubagent,
      }
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
        cursorSubagents = io.welcome.meta.cursorSubagents === true
        startSideChannel(readOpenCodeServerInfo(io.welcome.meta.opencodeServer))
        runtimeReady = true
      } else {
      const clientCapabilities = options.vendor === 'cursor' ? { _meta: { subagents: true } } : {}
      const initialized = await setup(connection.initialize({ protocolVersion: PROTOCOL_VERSION, clientCapabilities, clientInfo: { name: 'supermux-core', version: '0.0.0' } }))
      cursorSubagents = options.vendor === 'cursor' && (initialized.agentCapabilities?.sessionCapabilities as { subagents?: unknown } | undefined)?.subagents != null
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
      // A process this connect spawned uses the planned server; one we re-attached without an
      // agent session keeps whatever server it recorded (none when an older build spawned it).
      const serverInfo = readOpenCodeServerInfo(io.welcome.meta.opencodeServer) ?? (io.welcome.meta.agentSessionId === undefined ? plannedServer : undefined)
      io.setMeta({ agentSessionId, permissions: livePermissions, advertisedModes, hasModeConfig, cursorSubagents, ...(options.sessionConfig ? { sessionConfig: appliedConfig } : {}), ...(configOptions.length ? { configOptions } : {}), ...(serverInfo ? { opencodeServer: serverInfo } : {}) })
      startSideChannel(serverInfo)
      finishSetup()
      runtimeReady = true
      return { runtime: makeRuntime({ resume: canResume || canLoad, steer: false, fork: false, detach: true, permissions: true }), close, finishSetup }
      }
      finishSetup()
      if (!session) {
        await close({ mode: 'shutdown' })
        return { methods: [], authenticate: async () => {}, close, finishSetup }
      }
      return { runtime: makeRuntime({ resume: true, steer: false, fork: false, detach: true, permissions: true }), close, finishSetup }
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
