import type { AgentDriver, ContentBlock, HistoryOptions, PermissionHandler, SessionConfiguration, CloseOptions, PermissionsSpec } from '../types.js'
import { requireCloseMode } from '../types.js'
import { launchArgs, launchEnv } from '../launch.js'
import { appliedFor, validatePermissionsSpec } from '../permissions.js'
import type { RequestPermissionResponse, ToolKind } from '@agentclientprotocol/sdk'
import { transport } from './transport.js'
import { multiAgentV1Launch } from './catalog.js'
import { createCodexNormalizer } from './normalize.js'
import { CoreError, UnsupportedOperation } from '../errors.js'
import { CODEX_CONTEXT, codexContextLaunch, codexExtraRoots, codexPluginIsLive } from '../context/agents.js'
import { EMPTY_CONTEXT_FINGERPRINT } from '../context/index.js'
import type { ContextChange, RuntimeContextControl } from '../context/types.js'
import { REASON, SUBAGENT_STATE_METHOD, shortReason } from '../subagent-actions.js'

export type CodexReasoningEffort = 'none' | 'minimal' | 'low' | 'medium' | 'high' | 'xhigh' | 'max'
export type CodexSandbox = 'read-only' | 'workspace-write' | 'danger-full-access'
export type CodexApprovalPolicy = 'never' | 'on-request' | 'on-failure' | 'untrusted'
export type CodexPermissionPrompts = 'none' | 'host'

const REASONING_EFFORTS = new Set<string>(['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max'])
const SANDBOXES = new Set<string>(['read-only', 'workspace-write', 'danger-full-access'])
const APPROVAL_POLICIES = new Set<string>(['never', 'on-request', 'on-failure', 'untrusted'])
const PERMISSION_PROMPTS = new Set<string>(['none', 'host'])
const MAX_PENDING_PERMISSIONS = 128
const MAX_ANSWERED_PERMISSIONS = 128
const MAX_SETUP_NOTICES = 256
const MAX_EARLY_NOTICES = 256
const MAX_LIVE_TURNS = 256
const MAX_TOMBSTONES = 256
const MAX_CHILDREN = 256
const MAX_FOREIGN_BUFFER = 256
const permissionOptions = [
  { optionId: 'allow_once', name: 'Allow once', kind: 'allow_once' as const },
  { optionId: 'reject_once', name: 'Reject once', kind: 'reject_once' as const },
]
const cancelledPermission: RequestPermissionResponse = { outcome: { outcome: 'cancelled' } }

export type CodexOptions = {
  id: string
  command: string
  args: string[]
  env?: Record<string, string>
  inheritEnv: boolean
  model?: string
  reasoningEffort?: CodexReasoningEffort
  sandbox: CodexSandbox
  approvalPolicy: CodexApprovalPolicy
  permissionPrompts: CodexPermissionPrompts
  permissions: Extract<PermissionsSpec, { kind: 'codex' }>
  setupTimeoutMs: number
  requestTimeoutMs: number
  shutdownTimeoutMs: number
  maxFrameBytes: number
  /** Called once the native thread id is known. `request` is read-only: it
   *  rejects `turn/` and `thread/` methods so turn ownership stays with Core,
   *  and rejects after close. */
  onRuntimeRequest?: (info: { sessionId: string; agentSessionId: string }, request: (method: string, params: unknown) => Promise<unknown>) => void
  keeper: {
    stateDirectory: string
    limits: { parkedDeadlineMs: number; journalMaxBytes: number; connectTimeoutMs: number }
  }
}

function input(content: ContentBlock[]) {
  return content.map(block => {
    if (block.type === 'text') return { type: 'text', text: block.text, text_elements: [] }
    if (block.type === 'image') return { type: 'image', url: `data:${block.mimeType};base64,${block.data}` }
    throw new Error(`Unsupported Codex input block: ${block.type}`)
  })
}
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  void promise.catch(() => {})
  return { promise, resolve, reject }
}
function requireEffort(value: string): CodexReasoningEffort {
  if (!REASONING_EFFORTS.has(value)) throw new Error(`Unsupported Codex reasoning effort: ${value}`)
  return value as CodexReasoningEffort
}
function sessionOverrides(value: SessionConfiguration | undefined): SessionConfiguration {
  if (value == null) return {}
  if (typeof value !== 'object' || Array.isArray(value)) throw new Error('Codex configuration must be an object')
  const next: SessionConfiguration = {}
  if (value.model !== undefined) {
    if (typeof value.model !== 'string' || !value.model) throw new Error('Codex model must be a nonempty string')
    next.model = value.model
  }
  if (value.reasoningEffort !== undefined) {
    if (typeof value.reasoningEffort !== 'string' || !value.reasoningEffort) throw new Error('Codex reasoning effort must be a nonempty string')
    next.reasoningEffort = requireEffort(value.reasoningEffort)
  }
  return next
}

function requireKeeper(keeper: CodexOptions['keeper']): CodexOptions['keeper'] {
  if (!keeper || typeof keeper !== 'object' || Array.isArray(keeper)) throw new TypeError('Codex keeper is required')
  if (typeof keeper.stateDirectory !== 'string' || !keeper.stateDirectory) throw new TypeError('Codex keeper.stateDirectory must be a nonempty string')
  const limits = keeper.limits
  if (!limits || typeof limits !== 'object' || Array.isArray(limits)) throw new TypeError('Codex keeper.limits is required')
  for (const key of ['parkedDeadlineMs', 'journalMaxBytes', 'connectTimeoutMs'] as const) {
    const v = limits[key]
    if (typeof v !== 'number' || !Number.isSafeInteger(v) || v <= 0) throw new TypeError(`Codex keeper.limits.${key} must be a positive safe integer`)
  }
  return keeper
}

/** `app-server -c` overrides carrying a session's sandbox and approval policy (this process only). */
export function codexPolicyArgs(spec: Extract<PermissionsSpec, { kind: 'codex' }>): string[] {
  return ['-c', `sandbox_mode=${JSON.stringify(spec.sandbox)}`, '-c', `approval_policy=${JSON.stringify(spec.approvalPolicy)}`]
}

export function codex(options: CodexOptions): AgentDriver {
  if (!options || typeof options !== 'object') throw new TypeError('Codex options are required')
  for (const field of ['id', 'command', 'args', 'sandbox', 'approvalPolicy', 'setupTimeoutMs', 'requestTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'permissionPrompts', 'keeper', 'inheritEnv', 'permissions'] as const) {
    if (options[field] === undefined) throw new TypeError(`Codex ${field} is required`)
  }
  const initialPermissions = validatePermissionsSpec(options.permissions)
  if (initialPermissions.kind !== 'codex') throw new TypeError('Codex permissions kind must be codex')
  if (typeof options.id !== 'string' || !options.id) throw new TypeError('Codex id is required')
  if (typeof options.command !== 'string' || !options.command) throw new TypeError('Codex command is required')
  if (!Array.isArray(options.args) || options.args.some(value => typeof value !== 'string')) throw new TypeError('Codex args is required')
  if (typeof options.inheritEnv !== 'boolean') throw new TypeError('Codex inheritEnv is required')
  const setupTimeoutMs = options.setupTimeoutMs
  const requestTimeoutMs = options.requestTimeoutMs
  const shutdownTimeoutMs = options.shutdownTimeoutMs
  const maxFrameBytes = options.maxFrameBytes
  for (const value of [setupTimeoutMs, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes]) if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError('Codex limits must be positive safe integers')
  if (options.model !== undefined && (typeof options.model !== 'string' || !options.model)) throw new TypeError('Codex model must be a nonempty string')
  if (options.reasoningEffort !== undefined) requireEffort(options.reasoningEffort)
  if (!SANDBOXES.has(options.sandbox)) throw new TypeError('Codex sandbox must be read-only, workspace-write, or danger-full-access')
  if (!APPROVAL_POLICIES.has(options.approvalPolicy)) throw new TypeError('Codex approvalPolicy must be never, on-request, on-failure, or untrusted')
  if (!PERMISSION_PROMPTS.has(options.permissionPrompts)) throw new TypeError('Codex permissionPrompts must be none or host')
  if (options.onRuntimeRequest !== undefined && typeof options.onRuntimeRequest !== 'function') throw new TypeError('Codex onRuntimeRequest must be a function')
  const keeper = requireKeeper(options.keeper)
  const sandbox = initialPermissions.sandbox
  const approvalPolicy = initialPermissions.approvalPolicy
  const hostPermissions = true
  return { id: options.id, context: CODEX_CONTEXT, async open(context) {
    context.signal.throwIfAborted()
    let agentSessionId = context.resumeId, ready = false, closed = false
    let fatal: Error | undefined
    type TurnSlot = {
      id: string
      generation: number
      origin: 'owned' | 'native'
      started: ReturnType<typeof deferred<string>>
      finished: boolean
      activityStarted: boolean
    }
    type OwnedSlot = {
      id?: string
      generation?: number
      started: ReturnType<typeof deferred<string>>
      completion: ReturnType<typeof deferred<{stopReason: string}>>
      early: any[]
      finished: boolean
    }
    let owned: OwnedSlot | undefined
    const live = new Map<string, TurnSlot>()
    const tombstones = new Map<string, true>()
    let generation = 0
    const pendingSetup: { method?: string; params?: any }[] = []
    // Subagent (collab) child threads share this connection; their frames carry the child
    // threadId. Known children are forwarded (never as the parent's turn lifecycle); frames of
    // not-yet-known threads wait briefly because a child can speak before spawnAgent completes.
    const children = new Set<string>()
    const childTurns = new Map<string, string>()
    /** Children this process has not loaded (learned from thread/read): resume before a turn. */
    const unloadedChildren = new Set<string>()
    const foreign: any[] = []
    const normalizer = createCodexNormalizer()
    const failure = deferred<never>()
    let livePermissions: Extract<PermissionsSpec, { kind: 'codex' }> = initialPermissions
    let overrides = sessionOverrides(context.configuration)
    const requestedBaseline = { model: overrides.model !== undefined, effort: overrides.reasoningEffort !== undefined }
    const nativeInitial: { model?: string; effort?: CodexReasoningEffort } = {}
    type PendingPermission = { controller: AbortController; turnId: string; generation: number; threadId: string }
    const pendingPermissions = new Map<string, PendingPermission>()
    type AnsweredPermission = { threadId: string; turnId: string; fingerprint: string; result: Record<string, unknown> }
    const answeredPermissions = new Map<string, AnsweredPermission>()
    function rememberAnswer(key: string, answer: AnsweredPermission) {
      if (answeredPermissions.has(key)) answeredPermissions.delete(key)
      answeredPermissions.set(key, answer)
      while (answeredPermissions.size > MAX_ANSWERED_PERMISSIONS) {
        const oldest = answeredPermissions.keys().next().value
        if (oldest === undefined) break
        answeredPermissions.delete(oldest)
      }
    }
    function permissionFingerprint(method: string, params: any) {
      return JSON.stringify({
        method,
        threadId: params?.threadId,
        turnId: params?.turnId,
        itemId: params?.itemId,
        approvalId: params?.approvalId,
        command: params?.command,
        grantRoot: params?.grantRoot,
        cwd: params?.cwd,
        reason: params?.reason,
        permissions: params?.permissions,
      })
    }
    function rememberTombstone(id: string) {
      if (tombstones.has(id)) tombstones.delete(id)
      tombstones.set(id, true)
      while (tombstones.size > MAX_TOMBSTONES) {
        const oldest = tombstones.keys().next().value
        if (oldest === undefined) break
        tombstones.delete(oldest)
      }
    }
    function emitActivity(id: string, phase: 'started' | 'completed') {
      try { context.onActivity?.({ id, phase }) } catch { /* driver must not throw from notify */ }
    }
    function cancelPendingPermissions(turnId?: string) {
      // Abort only; settle() still owns the entry and writes the deny result exactly once.
      // Deleting here would leave the native approval request unanswered.
      for (const pending of pendingPermissions.values()) {
        if (turnId != null && pending.turnId !== turnId) continue
        pending.controller.abort()
      }
    }
    function liveUnfinished() {
      const slots: TurnSlot[] = []
      for (const slot of live.values()) if (!slot.finished) slots.push(slot)
      return slots
    }
    function fail(error: Error) {
      if (fatal) return
      fatal = error
      cancelPendingPermissions()
      failure.reject(error)
      owned?.started.reject(error)
      owned?.completion.reject(error)
      for (const slot of live.values()) slot.started.reject(error)
      if (ready && !closed) context.onExit(error)
    }
    // Live-turn snapshot in keeper meta: a re-attaching process has fresh memory, and the
    // frames that opened the running turns may already be acked by the process that died.
    // Record the set synchronously on every bind/complete, before the frame is acked, so a
    // re-attach can seed its slots from meta and match the replayed/live frames that follow.
    function snapshotLiveTurns() {
      try { rpc.setMeta({ liveTurns: [...live.keys()] }) } catch { /* transport closing */ }
    }
    function completeSlot(slot: TurnSlot, params: any) {
      if (slot.finished) return
      slot.finished = true
      live.delete(slot.id)
      snapshotLiveTurns()
      rememberTombstone(slot.id)
      cancelPendingPermissions(slot.id)
      slot.started.resolve(slot.id)
      if (slot.activityStarted) emitActivity(slot.id, 'completed')
      if (owned && owned.id === slot.id && owned.generation === slot.generation) {
        owned.finished = true
        owned.started.resolve(slot.id)
        if (params.turn.status === 'completed') owned.completion.resolve({ stopReason: 'end_turn' })
        else if (params.turn.status === 'interrupted') owned.completion.resolve({ stopReason: 'cancelled' })
        else owned.completion.reject(new Error(params.turn.error?.message ?? `Codex turn ended with status ${params.turn.status}`))
      }
    }
    function bindLive(id: string, origin: 'owned' | 'native'): TurnSlot | undefined {
      const existing = live.get(id)
      if (existing) {
        if (origin === 'owned') existing.origin = 'owned'
        return existing
      }
      if (tombstones.has(id)) return
      if (live.size >= MAX_LIVE_TURNS) {
        fail(new Error('Too many outstanding Codex turns'))
        return
      }
      generation += 1
      const slot: TurnSlot = {
        id,
        generation,
        origin,
        started: deferred<string>(),
        finished: false,
        activityStarted: false,
      }
      live.set(id, slot)
      slot.activityStarted = true
      snapshotLiveTurns()
      emitActivity(id, 'started')
      return slot
    }
    function captureNativeInitial(result: any) {
      const model = result?.model ?? result?.thread?.model
      if (typeof model === 'string' && model && !requestedBaseline.model) nativeInitial.model = model
      const hasEffort = result != null && Object.prototype.hasOwnProperty.call(result, 'reasoningEffort')
      const hasThreadEffort = result?.thread != null && Object.prototype.hasOwnProperty.call(result.thread, 'reasoningEffort')
      const effort = hasEffort ? result.reasoningEffort : hasThreadEffort ? result.thread.reasoningEffort : undefined
      if (typeof effort === 'string' && effort && !requestedBaseline.effort) nativeInitial.effort = requireEffort(effort)
    }
    function canRestoreModel() { return options.model != null || nativeInitial.model != null }
    function canRestoreEffort() { return options.reasoningEffort != null || nativeInitial.effort != null }
    function resolvedModel() { return overrides.model ?? options.model ?? nativeInitial.model }
    function resolvedEffort() { return overrides.reasoningEffort ?? options.reasoningEffort ?? nativeInitial.effort }
    // thread/start takes the sandbox as a string (`sandbox`), turn/start as the
    // internally tagged `sandboxPolicy` object (app-server v2 SandboxPolicy).
    function sandboxPolicyObject(sandbox: CodexSandbox): Record<string, unknown> {
      if (sandbox === 'danger-full-access') return { type: 'dangerFullAccess' }
      if (sandbox === 'read-only') return { type: 'readOnly' }
      return { type: 'workspaceWrite' }
    }
    function turnOverrides() {
      const params: { model?: string; effort?: string; approvalPolicy: string; sandboxPolicy: Record<string, unknown> } = {
        approvalPolicy: livePermissions.approvalPolicy,
        sandboxPolicy: sandboxPolicyObject(livePermissions.sandbox),
      }
      const model = resolvedModel()
      const effort = resolvedEffort()
      if (model) params.model = model
      if (effort) params.effort = effort
      return params
    }
    function writeResult(id: unknown, result: Record<string, unknown>) {
      try { rpc.write({ id, result }) }
      catch (error) { if (!fatal) fail(error instanceof Error ? error : new Error(String(error))) }
    }
    function writeDecision(id: unknown, decision: 'accept' | 'decline') {
      writeResult(id, { decision })
    }
    function hasAlwaysDecision(decisions: unknown) {
      if (!Array.isArray(decisions)) return false
      return decisions.some(entry => entry === 'acceptWithExecpolicyAmendment'
        || (entry && typeof entry === 'object' && 'acceptWithExecpolicyAmendment' in entry))
    }
    function execpolicyAmendment(params: any) {
      const decisions = params?.availableDecisions
      if (Array.isArray(decisions)) {
        for (const entry of decisions) {
          if (entry && typeof entry === 'object' && entry.acceptWithExecpolicyAmendment) return entry.acceptWithExecpolicyAmendment
        }
      }
      if (Array.isArray(params?.proposedExecpolicyAmendment)) {
        return { execpolicy_amendment: params.proposedExecpolicyAmendment }
      }
      return undefined
    }
    // Codex asks for MCP tool approval through `mcpServer/elicitation/request` with
    // `_meta.codex_approval_kind === 'mcp_tool_call'` (observed on the real app-server:
    // `_meta.persist: ['session','always']`, message `Allow the <server> MCP server to
    // run tool "<name>"?`); the answer is an MCP elicitation result
    // `{ action: 'accept' | 'decline' | 'cancel', content?, _meta? }`.
    function isMcpToolApproval(method: string, params: any): boolean {
      return method === 'mcpServer/elicitation/request' && params?._meta?.codex_approval_kind === 'mcp_tool_call'
    }
    function elicitationTool(params: any): { server: string; tool: string } {
      const server = typeof params?.serverName === 'string' ? params.serverName : 'mcp'
      const fromMessage = typeof params?.message === 'string' ? /tool "([^"]+)"/.exec(params.message)?.[1] : undefined
      return { server, tool: fromMessage ?? 'tool' }
    }
    function elicitationPersists(params: any, scope: 'session' | 'always'): boolean {
      const persist = params?._meta?.persist
      return Array.isArray(persist) && persist.includes(scope)
    }
    function hostOptions(params: any, method?: string) {
      const options: { optionId: string; name: string; kind: 'allow_once' | 'allow_always' | 'reject_once' | 'reject_always' }[] = [...permissionOptions]
      if (method && isMcpToolApproval(method, params)) {
        // "Always" here means for this session (Codex's own scope); the global
        // 'always' persistence writes the user's config and is not offered.
        if (elicitationPersists(params, 'session')) options.splice(1, 0, { optionId: 'allow_always', name: 'Allow for this session', kind: 'allow_always' as const })
        return options
      }
      if (hasAlwaysDecision(params?.availableDecisions)) {
        options.splice(1, 0, { optionId: 'allow_always', name: 'Allow always', kind: 'allow_always' as const })
      }
      return options
    }
    function deniedPermissionsResult() {
      return { permissions: {}, scope: 'turn' }
    }
    function selectedOption(response: RequestPermissionResponse | null | undefined) {
      try {
        return response?.outcome?.outcome === 'selected' ? response.outcome.optionId : undefined
      } catch {
        return undefined
      }
    }
    async function askHost(params: any, signal: AbortSignal, method?: string, subagentId?: string) {
      const mcpApproval = method ? isMcpToolApproval(method, params) : false
      const mcp = mcpApproval ? elicitationTool(params) : undefined
      const toolCallId = typeof params?.approvalId === 'string' && params.approvalId
        ? params.approvalId
        : typeof params?.itemId === 'string' && params.itemId ? params.itemId : mcp ? `mcp:${mcp.server}:${mcp.tool}` : 'approval'
      const title = mcp
        ? `${mcp.server}: ${mcp.tool}`
        : typeof params?.command === 'string' && params.command
        ? params.command
        : typeof params?.grantRoot === 'string' && params.grantRoot ? params.grantRoot : 'tool'
      let cancel!: () => void
      const cancellation = new Promise<RequestPermissionResponse>(resolve => {
        cancel = () => resolve(cancelledPermission)
        if (signal.aborted) cancel()
        else signal.addEventListener('abort', cancel, { once: true })
      })
      try {
        const ask: PermissionHandler = context.requestPermission
        const response = await Promise.race([
          Promise.resolve().then(() => ask({
            sessionId: agentSessionId!,
            coreSessionId: context.sessionId,
            toolCall: { toolCallId, title, kind: (mcp ? 'other' : typeof params?.command === 'string' ? 'execute' : 'edit') as ToolKind, rawInput: mcp ? { server: mcp.server, tool: mcp.tool, arguments: params?._meta?.tool_params } : params },
            options: hostOptions(params, method),
            ...(subagentId ? { subagentId } : {}),
            detail: {
              ...(typeof params?.command === 'string' ? { command: params.command } : {}),
              ...(typeof params?.cwd === 'string' ? { cwd: params.cwd } : {}),
            },
          }, signal)).catch(() => cancelledPermission),
          cancellation,
        ])
        if (signal.aborted) return { optionId: undefined as string | undefined }
        return { optionId: selectedOption(response) }
      } finally { signal.removeEventListener('abort', cancel) }
    }
    function handleServerRequest(message: any) {
      const method = message.method
      const isCommand = method === 'item/commandExecution/requestApproval'
      const isFile = method === 'item/fileChange/requestApproval'
      const isPermissions = method === 'item/permissions/requestApproval'
      const isMcpApproval = isMcpToolApproval(method, message.params)
      if (method === 'mcpServer/elicitation/request' && !isMcpApproval) {
        // Free-form MCP elicitations (server-driven forms) are not surfaced yet: decline
        // explicitly so the server's tool call fails fast instead of hanging.
        try { rpc.write({ id: message.id, result: { action: 'decline' } }) }
        catch (error) { if (!fatal) fail(error instanceof Error ? error : new Error(String(error))) }
        return
      }
      if (!isCommand && !isFile && !isPermissions && !isMcpApproval) {
        try { rpc.write({ id: message.id, error: { code: -32601, message: 'Client does not support this server request' } }) }
        catch (error) { if (!fatal) fail(error instanceof Error ? error : new Error(String(error))) }
        return
      }
      const params = message.params
      const key = JSON.stringify(message.id)
      const fingerprint = permissionFingerprint(method, params)
      const denyResult: Record<string, unknown> = isPermissions ? deniedPermissionsResult() : isMcpApproval ? { action: 'decline' } : { decision: 'decline' as const }
      // A subagent's approval arrives with its child threadId: ask the host on its behalf, gated
      // by the child's own running turn instead of the parent's.
      const childThread = typeof params?.threadId === 'string' && params.threadId !== agentSessionId && children.has(params.threadId) ? params.threadId as string : undefined
      const requestThreadId = childThread ?? agentSessionId
      const requestTurnId = typeof params?.turnId === 'string' ? params.turnId : undefined
      const unfinished = liveUnfinished()
      const requestSlot = requestTurnId && !childThread ? live.get(requestTurnId) : undefined
      const childLive = (turnId: string | undefined) => !!childThread && !!turnId && childTurns.get(childThread) === turnId
      const matching = childThread
        ? hostPermissions && params && requestTurnId && childLive(requestTurnId) && !closed && !fatal
        : hostPermissions
        && requestThreadId
        && params
        && params.threadId === requestThreadId
        && requestTurnId
        && requestSlot
        && !requestSlot.finished
        && unfinished.length === 1
        && unfinished[0] === requestSlot
        && !closed && !fatal
      const answered = answeredPermissions.get(key)
      if (answered) {
        const sameTurn = matching && answered.threadId === requestThreadId && answered.turnId === requestTurnId
        writeResult(message.id, sameTurn && answered.fingerprint === fingerprint ? answered.result : denyResult)
        return
      }
      // Under approvalPolicy "never" the mode says "never ask": an MCP tool approval
      // is granted for this call instead of failing the tool behind the user's back.
      if (isMcpApproval && livePermissions.approvalPolicy === 'never') {
        writeResult(message.id, { action: 'accept', content: {} })
        return
      }
      if (!matching) { writeResult(message.id, denyResult); return }
      if (pendingPermissions.has(key)) return
      if (pendingPermissions.size >= MAX_PENDING_PERMISSIONS) { writeResult(message.id, denyResult); return }
      if (isPermissions) {
        rememberAnswer(key, { threadId: requestThreadId!, turnId: requestTurnId!, fingerprint, result: denyResult })
        writeResult(message.id, denyResult)
        return
      }
      const controller = new AbortController()
      const capturedGeneration = requestSlot?.generation ?? -1
      const capturedTurnId = requestSlot?.id ?? requestTurnId!
      pendingPermissions.set(key, { controller, turnId: capturedTurnId, generation: capturedGeneration, threadId: requestThreadId! })
      const settle = (optionId: string | undefined) => {
        try {
          const pending = pendingPermissions.get(key)
          if (pending?.controller !== controller) return
          pendingPermissions.delete(key)
          const current = live.get(capturedTurnId)
          const liveOk = !controller.signal.aborted
            && !closed && !fatal
            && (childThread
              ? childLive(capturedTurnId)
              : current
                && !current.finished
                && current.generation === capturedGeneration
                && current.id === capturedTurnId
                && agentSessionId === requestThreadId)
          let result: Record<string, unknown> = denyResult
          if (isMcpApproval) {
            if (liveOk && optionId === 'allow_once') result = { action: 'accept', content: {} }
            else if (liveOk && optionId === 'allow_always' && elicitationPersists(params, 'session')) result = { action: 'accept', content: {}, _meta: { persist: 'session' } }
          } else if (liveOk && optionId === 'allow_once') result = { decision: 'accept' as const }
          else if (liveOk && optionId === 'allow_always') {
            const amendment = execpolicyAmendment(params)
            result = amendment
              ? { decision: { acceptWithExecpolicyAmendment: amendment } }
              : denyResult
          }
          rememberAnswer(key, { threadId: requestThreadId!, turnId: capturedTurnId, fingerprint, result })
          writeResult(message.id, result)
        } catch {
          try {
            rememberAnswer(key, { threadId: requestThreadId ?? '', turnId: capturedTurnId ?? '', fingerprint, result: denyResult })
            writeResult(message.id, denyResult)
          } catch { /* never reject the host-callback promise */ }
        }
      }
      void askHost(params, controller.signal, method, childThread).then(answer => {
        settle(answer.optionId)
      }, () => {
        settle(undefined)
      })
    }
    /** Remember child threads named by a parent collab item; returns the newly learned ids. */
    function learnChildren(message: any): string[] {
      const { method, params } = message
      // Children may spawn their own (nested) children: learn from any thread we already follow.
      if ((method !== 'item/started' && method !== 'item/completed') || (params?.threadId !== agentSessionId && !children.has(params?.threadId))) return []
      const item = params?.item
      const ids: string[] = []
      if (item?.type === 'collabAgentToolCall' && Array.isArray(item.receiverThreadIds)) {
        for (const id of item.receiverThreadIds) if (typeof id === 'string' && id) ids.push(id)
      } else if (item?.type === 'subAgentActivity' && typeof item.agentThreadId === 'string' && item.agentThreadId) {
        ids.push(item.agentThreadId)
      }
      return ids.filter(id => {
        if (id === agentSessionId || children.has(id)) return false
        rememberChild(id)
        readChild(id)
        return true
      })
    }
    /** Children whose native facts were read (once each). */
    const readChildren = new Set<string>()
    /**
     * Ask Codex about a new child once: its nickname (the name the parent and the user call it)
     * and whether the app server accepts direct input for it. Re-emits the child's state.
     */
    function readChild(id: string) {
      if (readChildren.has(id)) return
      readChildren.add(id)
      void Promise.resolve().then(() => rpc.request('thread/read', { threadId: id, includeTurns: false })).then((read: any) => {
        const thread = read?.thread
        if (!thread || closed) return
        const nickname = typeof thread.agentNickname === 'string' && thread.agentNickname ? thread.agentNickname
          : typeof thread.source?.subAgent?.thread_spawn?.agent_nickname === 'string' ? thread.source.subAgent.thread_spawn.agent_nickname : undefined
        normalizer.setNative(id, { ...(nickname ? { nickname } : {}), canAcceptDirectInput: typeof thread.canAcceptDirectInput === 'boolean' ? thread.canAcceptDirectInput : null })
        refreshChild(id)
      }, () => { readChildren.delete(id) })
    }
    function refreshChild(id: string) {
      if (!closed) context.onUpdate({ protocol: 'native', value: { method: SUBAGENT_STATE_METHOD, params: { subagentId: id } } })
    }
    function rememberChild(id: string) {
      children.add(id)
      while (children.size > MAX_CHILDREN) {
        const oldest = children.values().next().value
        if (oldest === undefined) break
        children.delete(oldest)
      }
      // A re-attaching process must still recognise the children's frames.
      try { rpc.setMeta({ children: [...children] }) } catch { /* transport closing */ }
    }
    function dispatchChild(message: any) {
      const { method, params } = message
      const threadId: string = params.threadId
      if (message.id != null) {
        if (method === 'item/commandExecution/requestApproval' || method === 'item/fileChange/requestApproval' || method === 'item/permissions/requestApproval' || method === 'item/tool/requestUserInput' || method === 'applyPatchApproval' || method === 'execCommandApproval' || method === 'mcpServer/elicitation/request') {
          context.onUpdate({ protocol: 'native', value: { method, params, id: message.id } })
        }
        handleServerRequest(message)
        return
      }
      if (method === 'turn/started' && typeof params.turn?.id === 'string') {
        childTurns.set(threadId, params.turn.id)
        unloadedChildren.delete(threadId)
      }
      // close_agent unloads the child thread: the next message must thread/resume it first.
      if (method === 'thread/status/changed' && params.status?.type === 'notLoaded') unloadedChildren.add(threadId)
      if (method === 'turn/completed') {
        const turnId = childTurns.get(threadId)
        if (turnId && (!params.turn?.id || params.turn.id === turnId)) {
          childTurns.delete(threadId)
          cancelPendingPermissions(turnId)
        }
      }
      // A re-attached process knows the child from keeper meta, its normalizer does not yet.
      if (!normalizer.isChild(threadId)) normalizer.adopt(threadId)
      context.onUpdate({ protocol: 'native', value: { method, params } })
    }
    function dispatchNotify(message: any) {
      const params = message?.params
      const threadId = params?.threadId
      if (agentSessionId && typeof threadId === 'string' && threadId !== agentSessionId) {
        if (children.has(threadId)) {
          const nested = learnChildren(message)
          dispatchChild(message)
          releaseForeign(nested)
          return
        }
        if (message.id != null) { dispatchMain(message); return } // unknown thread: denied as before
        foreign.push(message)
        if (foreign.length > MAX_FOREIGN_BUFFER) foreign.shift()
        return
      }
      const learned = agentSessionId ? learnChildren(message) : []
      dispatchMain(message)
      releaseForeign(learned)
    }
    /** Replay held frames of threads that just became known children, in arrival order. */
    function releaseForeign(learned: string[]) {
      if (!learned.length) return
      const waiting = foreign.splice(0)
      for (const held of waiting) {
        if (learned.includes(held.params.threadId)) dispatchChild(held)
        else foreign.push(held)
      }
    }
    function dispatchMain(message: any) {
      const { method, params } = message
      if (!agentSessionId) {
        // Before thread identity nothing can be matched: buffer notices AND server requests in
        // arrival order so a turn/started followed by its approval request replays as one sequence
        // instead of the request being denied while its turn is still waiting in the buffer.
        if (pendingSetup.length >= MAX_SETUP_NOTICES) {
          fail(new Error('Too many Codex notifications before turn acknowledgement'))
          return
        }
        pendingSetup.push(message)
        return
      }
      if (message.id != null) {
        const requestTurnId = typeof params?.turnId === 'string' ? params.turnId : undefined
        if (requestTurnId && !live.has(requestTurnId) && !tombstones.has(requestTurnId)) bindLive(requestTurnId, 'native')
        if (method === 'item/commandExecution/requestApproval' || method === 'item/fileChange/requestApproval' || method === 'item/permissions/requestApproval' || method === 'item/tool/requestUserInput' || method === 'applyPatchApproval' || method === 'execCommandApproval' || method === 'mcpServer/elicitation/request') {
          context.onUpdate({ protocol: 'native', value: { method, params, id: message.id } })
        }
        handleServerRequest(message)
        return
      }
      if (method === 'account/rateLimits/updated') {
        if (params?.threadId != null && params.threadId !== agentSessionId) return
        context.onUpdate({ protocol: 'native', value: { method, params } })
        return
      }
      if (!params) return
      if (params.threadId != null && params.threadId !== agentSessionId) return
      if (params.threadId == null) return
      const turnId = params.turnId ?? params.turn?.id
      if (owned && !owned.id) {
        if (owned.early.length >= MAX_EARLY_NOTICES) {
          fail(new Error('Too many Codex notifications before turn acknowledgement'))
          return
        }
        owned.early.push({ method, params })
        return
      }
      if (turnId == null) {
        context.onUpdate({ protocol: 'native', value: { method, params } })
        return
      }
      if (method === 'turn/started') {
        if (typeof turnId !== 'string' || !turnId) return
        const slot = bindLive(turnId, 'native')
        if (!slot) return
        slot.started.resolve(turnId)
        if (owned && owned.id === turnId) owned.started.resolve(turnId)
        context.onUpdate({ protocol: 'native', value: { method, params } })
        return
      }
      const slot = live.get(turnId)
      if (!slot || slot.finished) return
      context.onUpdate({ protocol: 'native', value: { method, params } })
      if (method === 'item/completed') askInlineQuestions(slot, params?.item)
      if (method === 'turn/completed' && params.turn?.id === slot.id) completeSlot(slot, params)
    }
    // Real Codex (0.153) asks the user by putting `questions:[{title, options:[string]}]` on an
    // agentMessage item and KEEPING the turn running; the answer is steered into that turn
    // (verified live: steering "Blue" yields the continuation). Surface it as a normal
    // answerable question; if the turn ends first the request resolves as cancelled.
    const askedQuestionItems = new Set<string>()
    function askInlineQuestions(slot: TurnSlot, item: any) {
      if (!item || item.type !== 'agentMessage' || !Array.isArray(item.questions) || !item.questions.length) return
      const itemId = typeof item.id === 'string' && item.id ? item.id : undefined
      if (!itemId || askedQuestionItems.has(itemId)) return
      if (askedQuestionItems.size >= 256) askedQuestionItems.clear()
      askedQuestionItems.add(itemId)
      const questions = item.questions.map((q: any, index: number) => ({
        id: `q${index + 1}`,
        prompt: typeof q?.title === 'string' && q.title ? q.title : typeof q?.question === 'string' ? q.question : `Question ${index + 1}`,
        multiSelect: false,
        allowFreeText: true,
        options: (Array.isArray(q?.options) ? q.options : []).map((o: any) => ({ label: typeof o === 'string' ? o : String(o?.label ?? o?.title ?? '') })).filter((o: { label: string }) => o.label),
      }))
      const controller = new AbortController()
      const stop = () => controller.abort()
      void slot.started.promise.catch(() => {})
      const watch = setInterval(() => { if (slot.finished || closed || fatal) stop() }, 200)
      void Promise.resolve().then(() => context.requestAnswers({ toolCallId: itemId, questions }, controller.signal)).then(async result => {
        if (result.outcome === 'cancelled' || slot.finished || closed || fatal) return
        const text = result.outcome === 'declined'
          ? 'I decline to answer; continue using your best judgment.'
          : questions.map((q: { id: string; prompt: string }) => {
              const value = result.answers[q.id]
              const answer = Array.isArray(value) ? value.join(', ') : value
              return questions.length === 1 ? String(answer ?? '') : `${q.prompt} ${answer ?? ''}`
            }).filter(Boolean).join('\n')
        if (!text) return
        await rpc.request('turn/steer', { threadId: agentSessionId, expectedTurnId: slot.id, input: input([{ type: 'text', text }]) })
      }).catch(() => { /* turn ended or steer refused: the request was already resolved */ }).finally(() => clearInterval(watch))
    }
    const env = launchEnv(options.inheritEnv, options.env, context.profile)
    // Pin subagents to multi_agent v1 (children accept direct input); see catalog.ts.
    // A process flag, so it covers start, resume and fork alike; a reattached
    // keeper keeps the flags its app-server was started with.
    // A catalog whose format changed is left alone (feature detection, see catalog.ts); the
    // warning is shown on the first turn, when the session can display it.
    // Session context (per process: `-c mcp_servers.*` args; per thread: extraRoots, developerInstructions).
    const sessionContext = context.sessionContext ? codexContextLaunch(context.sessionContext) : undefined
    // The session's policy is per process (C3): child threads spawned by the collab tools
    // (spawnAgent) take their sandbox and approval from the app-server's loaded config, and a `-c`
    // override is that config for this process only. Nothing is written into CODEX_HOME, which
    // an account can share between sessions (verified on 0.159.2: scripts/codex-policy-probe.ts).
    // A live setPermissions reaches this thread from its next turn; child threads keep the launch
    // policy until the next launch.
    const baseArgs = [...launchArgs(options.args, context.profile), ...codexPolicyArgs(livePermissions)]
    const launch = await multiAgentV1Launch(options.command, sessionContext?.args.length ? [...baseArgs, ...sessionContext.args] : baseArgs, env, context.cwd)
    const args = launch.args
    let catalogWarning = launch.warning
    const rpc = await transport({ command: options.command, args, env, cwd: context.cwd, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes, sessionId: context.sessionId, keeper, fingerprint: context.sessionContext?.fingerprint ?? EMPTY_CONTEXT_FINGERPRINT }, dispatchNotify, fail)
    const close = async (closeOptions: CloseOptions) => {
      const mode = requireCloseMode(closeOptions)
      if (!closed) { closed = true; cancelPendingPermissions(); fail(new Error('Codex runtime closed')) }
      await rpc.close({ mode })
    }
    const setupAbort = () => { fail(new Error('Codex setup aborted')); void close({ mode: 'shutdown' }) /* abort: stop the native process */ }
    context.signal.addEventListener('abort', setupAbort, { once: true })
    const timer = setTimeout(() => { fail(new Error('Codex setup timed out')); void close({ mode: 'shutdown' }) /* setup timeout: stop the native process */ }, setupTimeoutMs)
    const hookRuntimeRequest = (threadId: string) => {
      if (!options.onRuntimeRequest) return
      const request = async (method: string, params: unknown) => {
        if (closed || fatal) throw fatal ?? new Error('Codex runtime closed')
        if (typeof method !== 'string' || !method) throw new Error('Codex runtime request method required')
        if (method.startsWith('turn/') || method.startsWith('thread/')) {
          throw new Error(`Codex runtime request refuses ${method}`)
        }
        return rpc.request(method, params)
      }
      options.onRuntimeRequest({ sessionId: context.sessionId, agentSessionId: threadId }, request)
    }
    let reattached = false
    try {
      const reattach = rpc.welcome.agentRunning === true && typeof rpc.welcome.meta.agentSessionId === 'string'
      reattached = reattach
      if (reattach) {
        const threadId = rpc.welcome.meta.agentSessionId as string
        if (context.resumeId && context.resumeId !== threadId) throw new Error('Codex thread identity mismatch')
        agentSessionId = threadId
        hookRuntimeRequest(threadId)
        const seededChildren = rpc.welcome.meta.children
        if (Array.isArray(seededChildren)) for (const id of seededChildren) if (typeof id === 'string' && id) rememberChild(id)
        const liveTurns = rpc.welcome.meta.liveTurns
        if (Array.isArray(liveTurns)) {
          for (const id of liveTurns) {
            if (typeof id !== 'string' || !id) continue
            const slot = bindLive(id, 'native')
            slot?.started.resolve(id)
          }
        }
        const buffered = pendingSetup.splice(0)
        for (const notice of buffered) dispatchNotify(notice)
        const seeded = rpc.welcome.meta.permissions
        if (seeded && typeof seeded === 'object') {
          try { livePermissions = validatePermissionsSpec(seeded) as Extract<PermissionsSpec, { kind: 'codex' }> } catch { /* keep factory spec */ }
        }
        if (fatal) throw fatal
        ready = true
      } else {
      await Promise.race([rpc.request('initialize', { clientInfo: { name: 'supermux-core', version: '0.0.0' }, capabilities: { experimentalApi: true } }), failure.promise])
      rpc.write({ method: 'initialized', params: {} })
      const model = resolvedModel()
      if (sessionContext?.extraRoots) await Promise.race([rpc.request('skills/extraRoots/set', { extraRoots: sessionContext.extraRoots }), failure.promise])
      const developerInstructions = !context.forkFrom && !context.resumeId ? sessionContext?.developerInstructions : undefined
      const result = await Promise.race([rpc.request(context.forkFrom ? 'thread/fork' : context.resumeId ? 'thread/resume' : 'thread/start', { ...(context.forkFrom ? {threadId: context.forkFrom.agentSessionId, ...(context.forkFrom.at ? {lastTurnId: context.forkFrom.at.nativeTurnId} : {})} : context.resumeId ? { threadId: context.resumeId } : {}), cwd: context.cwd, approvalPolicy: livePermissions.approvalPolicy, sandbox: livePermissions.sandbox, ...(model ? { model } : {}), ...(developerInstructions !== undefined ? { developerInstructions } : {}) }), failure.promise])
      if (typeof result?.thread?.id !== 'string' || !result.thread.id || (context.resumeId && result.thread.id !== context.resumeId)) throw new Error('Codex thread identity mismatch')
      captureNativeInitial(result)
      agentSessionId = result.thread.id
      rpc.setMeta({ agentSessionId: result.thread.id, permissions: livePermissions })
      hookRuntimeRequest(result.thread.id)
      const buffered = pendingSetup.splice(0)
      for (const notice of buffered) dispatchNotify(notice)
      if (fatal) throw fatal
      ready = true
      }
    } catch (error) { await close({ mode: 'shutdown' }); throw error } finally { clearTimeout(timer); context.signal.removeEventListener('abort', setupAbort) }
    async function interrupt() {
      const waitingOwned = owned && !owned.id ? owned : undefined
      const snapshot = liveUnfinished()
      if (!waitingOwned && !snapshot.length) return
      for (const slot of snapshot) cancelPendingPermissions(slot.id)
      const targets: { slot?: TurnSlot; owned?: OwnedSlot; started: Promise<string> }[] = snapshot.map(slot => ({ slot, started: slot.started.promise }))
      if (waitingOwned) targets.push({ owned: waitingOwned, started: waitingOwned.started.promise })
      for (const target of targets) {
        let turnId: string
        try { turnId = await target.started } catch { continue }
        const current = live.get(turnId)
        if (!current || current.finished) continue
        if (target.slot && current.generation !== target.slot.generation) continue
        try { await rpc.request('turn/interrupt', { threadId: agentSessionId, turnId }) } catch (error) { if (!current.finished) throw error }
      }
    }
    // Children remembered from before a resume: known again, and (fresh process) not loaded.
    let restoredSubagents: ReturnType<typeof normalizer.restore> | undefined
    if (context.subagents?.length) {
      restoredSubagents = normalizer.restore(context.subagents, { stillRunning: reattached })
      for (const sub of restoredSubagents) {
        if (!children.has(sub.subagentId)) rememberChild(sub.subagentId)
        if (!reattached) unloadedChildren.add(sub.subagentId)
        if (sub.name) readChildren.add(sub.subagentId)
      }
    }
    const isThreadNotFound = (error: unknown) => /thread not found|not loaded/i.test(error instanceof Error ? error.message : String(error))
    /** Codex refused input for the child (multi_agent v2: -32600): Message goes off, with its words. */
    function refusal(subagentId: string, error: unknown): CoreError | undefined {
      const code = (error as { code?: unknown })?.code
      if (code !== -32600) return
      normalizer.setNative(subagentId, { canAcceptDirectInput: false })
      refreshChild(subagentId)
      const message = error instanceof Error ? error.message : String(error)
      return new CoreError('subagent_unavailable', message ? shortReason(message) : REASON.codexNoInput, { cause: error })
    }
    const contextControl: RuntimeContextControl = {
      live(change: ContextChange) {
        if (change.kind === 'skills') return true
        // A plugin maps to extraRoots (live) plus its MCP servers (process args: a relaunch).
        if (change.kind === 'plugins') return codexPluginIsLive(change.item)
        return false
      },
      async apply(next) {
        if (fatal || closed) throw fatal ?? new Error('Codex runtime closed')
        // The full new list replaces the old one (a root left out is no longer scanned).
        await rpc.request('skills/extraRoots/set', { extraRoots: codexExtraRoots(next) })
        return []
      },
      recordFingerprint: fingerprint => rpc.setFingerprint(fingerprint),
    }
    return {
      agentSessionId: agentSessionId!, capabilities: { resume: true, steer: true, fork: true, detach: true, configure: true, history: true, permissions: true }, close, interrupt,
      nativeProtocol: 'codex-app-server' as const,
      context: contextControl,
      ...(restoredSubagents ? { restoredSubagents } : {}),
      async setPermissions(spec) {
        if (fatal || closed) throw fatal ?? new Error('Codex runtime closed')
        const next = validatePermissionsSpec(spec)
        if (next.kind !== 'codex') throw new TypeError('Codex permissions kind must be codex')
        livePermissions = next
        try { rpc.setMeta({ permissions: next }) } catch { /* */ }
        return { applied: appliedFor(next) }
      },
      normalize: normalizer,
      flush: () => normalizer.flush(),
      configuration() { return { ...overrides } },
      async configure(configuration) {
        if (fatal || closed) throw fatal ?? new Error('Codex runtime closed')
        if (owned || liveUnfinished().length) throw new Error('Configure requires an idle Codex session')
        const next = sessionOverrides(configuration)
        if (overrides.reasoningEffort != null && next.reasoningEffort == null && !canRestoreEffort()) {
          throw new Error('Cannot clear Codex reasoning effort: native default is unknown or unset')
        }
        if (overrides.model != null && next.model == null && !canRestoreModel()) {
          throw new Error('Cannot clear Codex model: native default is unknown')
        }
        overrides = next
      },
      async history(history: HistoryOptions = {}) {
        if (fatal || closed) throw fatal ?? new Error('Codex runtime closed')
        if (history.cursor !== undefined && (typeof history.cursor !== 'string' || !/^(0|[1-9]\d*)$/.test(history.cursor))) throw new Error('Invalid Codex history cursor')
        if (history.limit !== undefined && (!Number.isInteger(history.limit) || history.limit <= 0)) throw new Error('History limit must be a positive integer')
        const result = await rpc.request('thread/read', { threadId: agentSessionId, includeTurns: true })
        if (result?.thread?.id !== agentSessionId) throw new Error('Codex thread identity mismatch')
        if (!Array.isArray(result.thread.turns)) throw new Error('Codex thread history is missing turns')
        const turns = result.thread.turns
        const offset = history.cursor ? Number(history.cursor) : 0
        if (!Number.isSafeInteger(offset)) throw new Error('Invalid Codex history cursor')
        const items = history.limit == null ? turns.slice(offset) : turns.slice(offset, offset + history.limit)
        const next = offset + items.length
        return { protocol: 'native' as const, items, ...(next < turns.length ? { cursor: String(next) } : {}) }
      },
      async messageSubagent(subagentId, content) {
        if (fatal || closed) throw fatal ?? new CoreError('runtime_closed', 'Codex runtime closed')
        const converted = input(content)
        if (!children.has(subagentId)) {
          // Not seen by this process (e.g. after a restart): accept only this thread's own child.
          const read = await rpc.request('thread/read', { threadId: subagentId, includeTurns: false })
          if (read?.thread?.parentThreadId !== agentSessionId) throw new CoreError('subagent_not_found', `Unknown Codex subagent ${subagentId}`)
          rememberChild(subagentId)
          unloadedChildren.add(subagentId)
          if (!normalizer.isChild(subagentId)) normalizer.adopt(subagentId)
          readChild(subagentId)
        }
        if (normalizer.messaging(subagentId) === 'none') {
          // multi_agent_v2 children refuse direct app-server input (-32600).
          throw new CoreError('subagent_unavailable', REASON.codexNoInput)
        }
        const running = childTurns.get(subagentId)
        if (running) {
          try {
            await rpc.request('turn/steer', { threadId: subagentId, expectedTurnId: running, input: converted })
            return { via: 'direct' as const }
          } catch (error) {
            const refused = refusal(subagentId, error)
            if (refused) throw refused
            // The child turn may have ended in between; a new turn delivers it instead.
            if (childTurns.get(subagentId) === running) throw error
          }
        }
        const resume = () => rpc.request('thread/resume', { threadId: subagentId, cwd: context.cwd, approvalPolicy: livePermissions.approvalPolicy, sandbox: livePermissions.sandbox })
        if (unloadedChildren.has(subagentId)) {
          await resume()
          unloadedChildren.delete(subagentId)
        }
        // Only the policy: the child keeps its own model and effort.
        const start = () => rpc.request('turn/start', { threadId: subagentId, input: converted, approvalPolicy: livePermissions.approvalPolicy, sandboxPolicy: sandboxPolicyObject(livePermissions.sandbox) })
        try {
          await start()
        } catch (error) {
          const refused = refusal(subagentId, error)
          if (refused) throw refused
          // A thread its parent closed (close_agent) is unloaded: load it back, then deliver.
          if (!isThreadNotFound(error)) throw error
          await resume()
          unloadedChildren.delete(subagentId)
          await start()
        }
        return { via: 'direct' as const }
      },
      async stopSubagent(subagentId) {
        if (fatal || closed) throw fatal ?? new CoreError('runtime_closed', 'Codex runtime closed')
        if (!children.has(subagentId)) throw new CoreError('subagent_not_found', `Unknown Codex subagent ${subagentId}`)
        const running = childTurns.get(subagentId)
        if (!running) throw new CoreError('subagent_unavailable', REASON.notRunning)
        normalizer.markClientStop(subagentId)
        await rpc.request('turn/interrupt', { threadId: subagentId, turnId: running })
      },
      async steer(content) {
        const converted = input(content)
        const waitingStart = owned && !owned.id ? owned : undefined
        const unfinished = liveUnfinished()
        if (waitingStart && unfinished.length) throw new Error('Codex turn is ambiguous')
        if (!waitingStart && unfinished.length !== 1) {
          if (unfinished.length > 1) throw new Error('Codex turn is ambiguous')
          throw new Error('No active Codex turn to steer')
        }
        const capturedOwned = waitingStart
        const capturedSlot = unfinished[0]
        const turnId = capturedOwned ? await capturedOwned.started.promise : await capturedSlot!.started.promise
        const current = live.get(turnId)
        if (!current || current.finished) throw new Error('Codex turn already ended')
        if (capturedSlot && current.generation !== capturedSlot.generation) throw new Error('Codex turn already ended')
        if (capturedOwned && (owned !== capturedOwned || owned.id !== turnId)) throw new Error('Codex turn already ended')
        await rpc.request('turn/steer', { threadId: agentSessionId, expectedTurnId: turnId, input: converted })
      },
      async prompt(content, signal) {
        if (fatal || closed) throw fatal ?? new Error('Codex runtime closed')
        signal.throwIfAborted()
        if (owned || liveUnfinished().length) throw new Error('Codex prompt already running')
        const converted = input(content)
        if (catalogWarning && !closed) {
          context.onUpdate({ protocol: 'native', value: { method: 'warning', params: { message: catalogWarning } } })
          catalogWarning = undefined
        }
        const a: OwnedSlot = { started: deferred<string>(), completion: deferred<{stopReason:string}>(), early: [], finished: false }
        owned = a
        const abort = () => { void interrupt().catch(error => { a.completion.reject(error) }) }
        signal.addEventListener('abort', abort, { once: true })
        try {
          const result = await rpc.request('turn/start', { threadId: agentSessionId, input: converted, ...turnOverrides() })
          if (typeof result?.turn?.id !== 'string' || !result.turn.id) throw new Error('Codex turn identity missing')
          const turnId: string = result.turn.id
          if (owned !== a) throw new Error('Codex turn already ended')
          a.id = turnId
          const slot = bindLive(turnId, 'owned')
          if (slot) a.generation = slot.generation
          const early = a.early.splice(0)
          for (const update of early) dispatchNotify({ method: update.method, params: update.params })
          return await a.completion.promise
        } catch (error) { a.started.reject(error instanceof Error ? error : new Error(String(error))); throw error }
        finally { signal.removeEventListener('abort', abort); if (owned === a) owned = undefined }
      },
    }
  } }
}
