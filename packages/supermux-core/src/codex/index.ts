import type { AgentDriver, ContentBlock, HistoryOptions, PermissionHandler, SessionConfiguration, CloseOptions } from '../types.js'
import { requireCloseMode } from '../types.js'
import type { RequestPermissionResponse } from '@agentclientprotocol/sdk'
import { transport } from './transport.js'
import { createCodexNormalizer } from './normalize.js'

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

export function codex(options: CodexOptions): AgentDriver {
  if (!options || typeof options !== 'object') throw new TypeError('Codex options are required')
  for (const field of ['id', 'command', 'args', 'sandbox', 'approvalPolicy', 'setupTimeoutMs', 'requestTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'permissionPrompts', 'keeper', 'inheritEnv'] as const) {
    if (options[field] === undefined) throw new TypeError(`Codex ${field} is required`)
  }
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
  const sandbox = options.sandbox
  const approvalPolicy = options.approvalPolicy
  const hostPermissions = options.permissionPrompts === 'host'
  return { id: options.id, async open(context) {
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
    const failure = deferred<never>()
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
    function turnOverrides() {
      const params: { model?: string; effort?: string } = {}
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
    function hostOptions(params: any) {
      const options: { optionId: string; name: string; kind: 'allow_once' | 'allow_always' | 'reject_once' | 'reject_always' }[] = [...permissionOptions]
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
    async function askHost(params: any, signal: AbortSignal) {
      const toolCallId = typeof params?.approvalId === 'string' && params.approvalId
        ? params.approvalId
        : typeof params?.itemId === 'string' && params.itemId ? params.itemId : 'approval'
      const title = typeof params?.command === 'string' && params.command
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
            toolCall: { toolCallId, title, kind: typeof params?.command === 'string' ? 'execute' : 'edit', rawInput: params },
            options: hostOptions(params),
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
      if (!isCommand && !isFile && !isPermissions) {
        try { rpc.write({ id: message.id, error: { code: -32601, message: 'Client does not support this server request' } }) }
        catch (error) { if (!fatal) fail(error instanceof Error ? error : new Error(String(error))) }
        return
      }
      const params = message.params
      const key = JSON.stringify(message.id)
      const fingerprint = permissionFingerprint(method, params)
      const denyResult = isPermissions ? deniedPermissionsResult() : { decision: 'decline' as const }
      const requestThreadId = agentSessionId
      const requestTurnId = typeof params?.turnId === 'string' ? params.turnId : undefined
      const unfinished = liveUnfinished()
      const requestSlot = requestTurnId ? live.get(requestTurnId) : undefined
      const matching = hostPermissions
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
      if (!matching) { writeResult(message.id, denyResult); return }
      if (pendingPermissions.has(key)) return
      if (pendingPermissions.size >= MAX_PENDING_PERMISSIONS) { writeResult(message.id, denyResult); return }
      if (isPermissions) {
        rememberAnswer(key, { threadId: requestThreadId!, turnId: requestTurnId!, fingerprint, result: denyResult })
        writeResult(message.id, denyResult)
        return
      }
      const controller = new AbortController()
      const capturedGeneration = requestSlot.generation
      const capturedTurnId = requestSlot.id
      pendingPermissions.set(key, { controller, turnId: capturedTurnId, generation: capturedGeneration, threadId: requestThreadId! })
      const settle = (optionId: string | undefined) => {
        try {
          const pending = pendingPermissions.get(key)
          if (pending?.controller !== controller) return
          pendingPermissions.delete(key)
          const current = live.get(capturedTurnId)
          const liveOk = !controller.signal.aborted
            && !closed && !fatal
            && current
            && !current.finished
            && current.generation === capturedGeneration
            && current.id === capturedTurnId
            && agentSessionId === requestThreadId
          let result: Record<string, unknown> = denyResult
          if (liveOk && optionId === 'allow_once') result = { decision: 'accept' as const }
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
      void askHost(params, controller.signal).then(answer => {
        settle(answer.optionId)
      }, () => {
        settle(undefined)
      })
    }
    function dispatchNotify(message: any) {
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
        if (method === 'item/commandExecution/requestApproval' || method === 'item/fileChange/requestApproval' || method === 'item/permissions/requestApproval' || method === 'item/tool/requestUserInput' || method === 'applyPatchApproval' || method === 'execCommandApproval') {
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
    const rpc = await transport({ command: options.command, args: options.args, env: { ...(options.inheritEnv ? process.env : {}), ...options.env, ...context.profile?.env }, cwd: context.cwd, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes, sessionId: context.sessionId, keeper }, dispatchNotify, fail)
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
    try {
      const reattach = rpc.welcome.agentRunning === true && typeof rpc.welcome.meta.agentSessionId === 'string'
      if (reattach) {
        const threadId = rpc.welcome.meta.agentSessionId as string
        if (context.resumeId && context.resumeId !== threadId) throw new Error('Codex thread identity mismatch')
        agentSessionId = threadId
        hookRuntimeRequest(threadId)
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
        if (fatal) throw fatal
        ready = true
      } else {
      await Promise.race([rpc.request('initialize', { clientInfo: { name: 'supermux-core', version: '0.0.0' }, capabilities: { experimentalApi: true } }), failure.promise])
      rpc.write({ method: 'initialized', params: {} })
      const model = resolvedModel()
      const result = await Promise.race([rpc.request(context.forkFrom ? 'thread/fork' : context.resumeId ? 'thread/resume' : 'thread/start', { ...(context.forkFrom ? {threadId: context.forkFrom.agentSessionId, ...(context.forkFrom.at ? {lastTurnId: context.forkFrom.at.nativeTurnId} : {})} : context.resumeId ? { threadId: context.resumeId } : {}), cwd: context.cwd, approvalPolicy, sandbox, ...(model ? { model } : {}) }), failure.promise])
      if (typeof result?.thread?.id !== 'string' || !result.thread.id || (context.resumeId && result.thread.id !== context.resumeId)) throw new Error('Codex thread identity mismatch')
      captureNativeInitial(result)
      agentSessionId = result.thread.id
      rpc.setMeta({ agentSessionId: result.thread.id })
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
    const normalizer = createCodexNormalizer()
    return {
      agentSessionId: agentSessionId!, capabilities: { resume: true, steer: true, fork: true, detach: true, configure: true, history: true }, close, interrupt,
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
