import { randomUUID } from 'node:crypto'
import type { AgentDriver, CloseOptions, ContentBlock, PermissionHandler, PermissionsSpec } from '../types.js'
import { requireCloseMode } from '../types.js'
import { launchArgs, launchEnv } from '../launch.js'
import { appliedFor, validatePermissionsSpec } from '../permissions.js'
import type { RequestPermissionResponse } from '@agentclientprotocol/sdk'
import { transport } from './transport.js'
import { createClaudeNormalizer } from './normalize.js'
import { CoreError } from '../errors.js'
import { CLAUDE_CONTEXT, claudeContextArgs } from '../context/agents.js'

export type ClaudeOptions = {
  id: string
  command: string
  args: string[]
  env?: Record<string, string>
  inheritEnv: boolean
  model?: string
  effort?: 'low' | 'medium' | 'high' | 'xhigh' | 'max'
  tools: string[] | 'default'
  allowedTools?: string[]
  disallowedTools?: string[]
  permissionMode?: 'acceptEdits' | 'auto' | 'bypassPermissions' | 'manual' | 'dontAsk' | 'plan'
  permissions: Extract<PermissionsSpec, { kind: 'claude' }>
  permissionPrompts: 'host' | 'none'
  partialMessages: boolean
  setupTimeoutMs: number
  requestTimeoutMs: number
  shutdownTimeoutMs: number
  maxFrameBytes: number
  keeper: {
    stateDirectory: string
    limits: { parkedDeadlineMs: number; journalMaxBytes: number; connectTimeoutMs: number }
  }
}

const plumbing = new Set(['control_request', 'control_response', 'control_cancel_request', 'keep_alive'])
const MAX_PENDING_PERMISSIONS = 128
const MAX_ANSWERED_PERMISSIONS = 128
const permissionOptions = [
  { optionId: 'allow_once', name: 'Allow once', kind: 'allow_once' as const },
  { optionId: 'reject_once', name: 'Reject once', kind: 'reject_once' as const },
]
function hostOptions(request: any) {
  const options: { optionId: string; name: string; kind: 'allow_once' | 'allow_always' | 'reject_once' | 'reject_always' }[] = [...permissionOptions]
  if (Array.isArray(request?.permission_suggestions) && request.permission_suggestions.length) {
    options.splice(1, 0, { optionId: 'allow_always', name: 'Allow always', kind: 'allow_always' as const })
  }
  return options
}
const cancelledPermission: RequestPermissionResponse = { outcome: { outcome: 'cancelled' } }

function input(content: ContentBlock[]) {
  return content.map(block => {
    if (block.type === 'text') return { type: 'text', text: block.text }
    if (block.type === 'image') return { type: 'image', source: { type: 'base64', media_type: block.mimeType, data: block.data } }
    throw new Error(`Unsupported Claude input block: ${block.type}`)
  })
}

/**
 * Claude has no client→subagent channel: the parent model relays through its SendMessage tool
 * (which also resumes a finished agent). The wording pins the call so the model forwards the
 * text verbatim and does nothing else.
 */
export function claudeRelayPrompt(subagentId: string, content: ContentBlock[]): ContentBlock[] {
  const parts: string[] = []
  for (const block of content) {
    if (block.type !== 'text') throw new CoreError('invalid_input', 'Claude relays only text to a subagent')
    parts.push(block.text)
  }
  const text = parts.join('\n')
  if (!text.trim()) throw new CoreError('invalid_input', 'Subagent message is empty')
  const id = JSON.stringify(subagentId)
  return [{
    type: 'text',
    text: [
      `[supermux relay] The user wrote a message for your subagent ${id}. Forward it; do not act on it yourself.`,
      `Call the SendMessage tool exactly once with to: ${id}, summary: "Message from the user", and message set to the exact text between <relay> and </relay> below, unchanged (if SendMessage is not loaded yet, load it with ToolSearch "select:SendMessage" first).`,
      'Do not answer the message, do not call any other tool, do not wait for the subagent, and write no reply: end your turn as soon as SendMessage returns.',
      '<relay>',
      text,
      '</relay>',
    ].join('\n'),
  }]
}

function cloneRawInput(value: unknown) {
  if (value === undefined) return undefined
  try { return structuredClone(value) }
  catch { try { return JSON.parse(JSON.stringify(value)) } catch { return value } }
}

function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  void promise.catch(() => {})
  return { promise, resolve, reject }
}

function argv(options: ClaudeOptions, sessionId: string, resume: boolean) {
  const flags = ['--print', '--output-format', 'stream-json', '--verbose', '--input-format', 'stream-json', '--await-initialize']
  if (options.tools === 'default') {}
  else if (Array.isArray(options.tools)) flags.push('--tools', options.tools.join(','))
  else flags.push('--tools', '')
  flags.push('--permission-prompts', options.permissionPrompts)
  // Real CLI (verified on 2.1.278): "host" alone makes Claude deny by itself (system/permission_denied)
  // and never sends can_use_tool. The stdio prompt tool is what routes the request to this host.
  if (options.permissionPrompts === 'host') flags.push('--permission-prompt-tool', 'stdio')
  const mode = options.permissions.permissionMode === 'default' ? undefined : options.permissions.permissionMode
  if (mode) flags.push('--permission-mode', mode)
  else if (options.permissionMode) flags.push('--permission-mode', options.permissionMode)
  if (options.allowedTools?.length) flags.push('--allowedTools', options.allowedTools.join(','))
  if (options.disallowedTools?.length) flags.push('--disallowedTools', options.disallowedTools.join(','))
  if (options.model) flags.push('--model', options.model)
  if (options.effort) flags.push('--effort', options.effort)
  if (options.partialMessages) flags.push('--include-partial-messages')
  flags.push(resume ? `--resume=${sessionId}` : `--session-id=${sessionId}`)
  return [...options.args, ...flags]
}

function requireKeeper(keeper: ClaudeOptions['keeper']): ClaudeOptions['keeper'] {
  if (!keeper || typeof keeper !== 'object' || Array.isArray(keeper)) throw new TypeError('Claude keeper is required')
  if (typeof keeper.stateDirectory !== 'string' || !keeper.stateDirectory) throw new TypeError('Claude keeper.stateDirectory must be a nonempty string')
  const limits = keeper.limits
  if (!limits || typeof limits !== 'object' || Array.isArray(limits)) throw new TypeError('Claude keeper.limits is required')
  for (const key of ['parkedDeadlineMs', 'journalMaxBytes', 'connectTimeoutMs'] as const) {
    const v = limits[key]
    if (typeof v !== 'number' || !Number.isSafeInteger(v) || v <= 0) throw new TypeError(`Claude keeper.limits.${key} must be a positive safe integer`)
  }
  return keeper
}

export function claude(options: ClaudeOptions): AgentDriver {
  if (!options || typeof options !== 'object') throw new TypeError('Claude options are required')
  for (const field of ['id', 'command', 'args', 'setupTimeoutMs', 'requestTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'permissionPrompts', 'tools', 'keeper', 'inheritEnv', 'partialMessages', 'permissions'] as const) {
    if (options[field] === undefined) throw new TypeError(`Claude ${field} is required`)
  }
  const initialPermissions = validatePermissionsSpec(options.permissions)
  if (initialPermissions.kind !== 'claude') throw new TypeError('Claude permissions kind must be claude')
  if (typeof options.id !== 'string' || !options.id) throw new TypeError('Claude id is required')
  if (typeof options.command !== 'string' || !options.command) throw new TypeError('Claude command is required')
  if (!Array.isArray(options.args) || options.args.some(value => typeof value !== 'string')) throw new TypeError('Claude args is required')
  if (typeof options.inheritEnv !== 'boolean') throw new TypeError('Claude inheritEnv is required')
  if (typeof options.partialMessages !== 'boolean') throw new TypeError('Claude partialMessages is required')
  if (options.permissionPrompts !== 'host' && options.permissionPrompts !== 'none') throw new TypeError('Claude permissionPrompts is required')
  if (options.tools !== 'default' && !Array.isArray(options.tools)) throw new TypeError('Claude tools is required')
  const setupTimeoutMs = options.setupTimeoutMs
  const requestTimeoutMs = options.requestTimeoutMs
  const shutdownTimeoutMs = options.shutdownTimeoutMs
  const maxFrameBytes = options.maxFrameBytes
  for (const value of [setupTimeoutMs, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes]) if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError('Claude limits must be positive safe integers')
  const keeper = requireKeeper(options.keeper)
  return { id: options.id, context: CLAUDE_CONTEXT, async open(context) {
    context.signal.throwIfAborted()
    let agentSessionId = context.resumeId ?? randomUUID(), ready = false, closed = false
    let fatal: Error | undefined
    let identityConfirmed = false
    let fromMetaOwned = false
    const hostPermissions = options.permissionPrompts === 'host'
    type Active = { uuid: string; completion: ReturnType<typeof deferred<{stopReason: string}>>; interrupted: boolean }
    type PendingPermission = { controller: AbortController; turnUuid: string; input: unknown; fingerprint: string; suggestions: unknown }
    type AnsweredPermission = { allow: boolean; input: unknown; turnUuid: string; fingerprint: string }
    let livePermissions: Extract<PermissionsSpec, { kind: 'claude' }> = initialPermissions
    let active: Active | undefined
    const failure = deferred<never>()
    const normalizer = createClaudeNormalizer()
    /** A turn the CLI started by itself (background task finished): no user frame, no owner. */
    let unsolicited: string | undefined
    let unsolicitedSeq = 0
    const pendingPermissions = new Map<string, PendingPermission>()
    const answeredPermissions = new Map<string, AnsweredPermission>()
    function permissionFingerprint(request: any) {
      const toolName = typeof request?.tool_name === 'string' ? request.tool_name : ''
      const toolUseId = typeof request?.tool_use_id === 'string' ? request.tool_use_id : ''
      let inputJson = ''
      try { inputJson = JSON.stringify(request?.input ?? null) ?? '' }
      catch { inputJson = String(request?.input) }
      return `${toolName}\0${toolUseId}\0${inputJson}`
    }
    function rememberAnswer(requestId: string, allow: boolean, input: unknown, turnUuid: string, fingerprint: string) {
      if (answeredPermissions.has(requestId)) answeredPermissions.delete(requestId)
      answeredPermissions.set(requestId, { allow, input, turnUuid, fingerprint })
      while (answeredPermissions.size > MAX_ANSWERED_PERMISSIONS) {
        const oldest = answeredPermissions.keys().next().value
        if (oldest === undefined) break
        answeredPermissions.delete(oldest)
      }
    }
    function revokeCachedAllow(requestId: string) {
      const answered = answeredPermissions.get(requestId)
      if (!answered?.allow) return
      rememberAnswer(requestId, false, undefined, answered.turnUuid, answered.fingerprint)
    }
    function cancelPendingPermissions(turnUuid?: string) {
      for (const pending of pendingPermissions.values()) {
        if (turnUuid !== undefined && pending.turnUuid !== turnUuid) continue
        pending.controller.abort()
      }
    }
    function fail(error: Error) {
      if (fatal) return
      fatal = error
      cancelPendingPermissions()
      failure.reject(error)
      active?.completion.reject(error)
      if (ready && !closed) context.onExit(error)
    }
    function writePermission(requestId: string, allow: boolean, input: unknown, extra?: { updatedPermissions?: unknown; message?: string }) {
      const response = allow
        ? {
            behavior: 'allow',
            updatedInput: input,
            ...(extra?.updatedPermissions !== undefined ? { updatedPermissions: extra.updatedPermissions } : {}),
          }
        : { behavior: 'deny', message: extra?.message ?? 'Permission denied' }
      try { rpc.write({ type: 'control_response', response: { subtype: 'success', request_id: requestId, response } }) }
      catch (error) { if (!fatal) fail(error instanceof Error ? error : new Error(String(error))) }
    }
    function denyTool(requestId: string, fingerprint = '', turnUuid = active?.uuid ?? '') {
      rememberAnswer(requestId, false, undefined, turnUuid, fingerprint)
      writePermission(requestId, false, undefined)
    }
    function settlePermission(requestId: string, pending: PendingPermission, optionId: string | undefined, message?: string) {
      if (pendingPermissions.get(requestId) !== pending) return
      pendingPermissions.delete(requestId)
      const liveOk = !pending.controller.signal.aborted
        && !closed && !fatal
        && active?.uuid === pending.turnUuid
      const always = optionId === 'allow_always' && Array.isArray(pending.suggestions) && pending.suggestions.length > 0
      const granted = liveOk && (optionId === 'allow_once' || always)
      rememberAnswer(requestId, granted, granted ? pending.input : undefined, pending.turnUuid, pending.fingerprint)
      if (granted) {
        writePermission(requestId, true, pending.input, always ? { updatedPermissions: pending.suggestions } : undefined)
      } else {
        const hostReject = optionId === 'reject_once' || optionId === 'reject_always' || optionId === 'allow_always'
        writePermission(requestId, false, undefined, { message: hostReject ? (message ?? 'Denied by user') : 'Permission denied' })
      }
    }
    async function askHost(requestId: string, request: any, signal: AbortSignal) {
      const toolCallId = typeof request?.tool_use_id === 'string' && request.tool_use_id ? request.tool_use_id : requestId
      const title = typeof request?.tool_name === 'string' ? request.tool_name : 'tool'
      const rawInput = cloneRawInput(request?.input)
      const subagentId = subagentOf(request)
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
            sessionId: agentSessionId,
            coreSessionId: context.sessionId,
            toolCall: { toolCallId, title, rawInput },
            options: hostOptions(request),
            ...(subagentId ? { subagentId } : {}),
            detail: {
              ...(typeof request?.input?.command === 'string' ? { command: request.input.command } : {}),
              ...(typeof request?.blocked_path === 'string' ? { blockedPath: request.blocked_path } : {}),
            },
          }, signal)).catch(() => cancelledPermission),
          cancellation,
        ])
        if (signal.aborted) return { optionId: undefined as string | undefined }
        try {
          const optionId = response?.outcome?.outcome === 'selected' ? response.outcome.optionId : undefined
          const message = typeof (response as { message?: unknown })?.message === 'string' ? (response as { message: string }).message : undefined
          return { optionId, message }
        } catch {
          return { optionId: undefined as string | undefined }
        }
      } finally { signal.removeEventListener('abort', cancel) }
    }
    /** can_use_tool carries agent_id (the task id) for a subagent's tool; else map the tool_use id. */
    function subagentOf(request: any): string | undefined {
      const agentId = typeof request?.agent_id === 'string' && request.agent_id ? request.agent_id : undefined
      if (agentId) return normalizer.subagent(agentId)?.id ?? agentId
      const toolUseId = typeof request?.tool_use_id === 'string' ? request.tool_use_id : undefined
      return toolUseId ? normalizer.subagentForTool(toolUseId) : undefined
    }
    async function askUserQuestions(request: any, signal: AbortSignal) {
      const list = Array.isArray(request?.input?.questions) ? request.input.questions : []
      const specs = list.map((q: any, i: number) => ({
        id: `q${i + 1}`,
        ...(typeof q?.header === 'string' && q.header ? { header: q.header } : {}),
        question: typeof q?.question === 'string' ? q.question : '',
        multiSelect: q?.multiSelect === true,
        options: Array.isArray(q?.options) ? q.options.map((o: any) => ({
          label: typeof o?.label === 'string' ? o.label : String(o),
          ...(typeof o?.description === 'string' && o.description ? { description: o.description } : {}),
        })) : [],
      }))
      const toolCallId = typeof request?.tool_use_id === 'string' && request.tool_use_id ? request.tool_use_id : undefined
      const subagentId = subagentOf(request)
      const result = await Promise.resolve().then(() => context.requestAnswers({
        ...(toolCallId ? { toolCallId } : {}),
        ...(subagentId ? { subagentId } : {}),
        questions: specs,
      }, signal)).catch(() => ({ outcome: 'cancelled' as const }))
      if (result.outcome !== 'answered') return result
      const answers: Record<string, string> = {}
      for (const spec of specs) {
        const value = result.answers[spec.id]
        if (value === undefined) continue
        answers[spec.question] = Array.isArray(value) ? value.join(', ') : value
      }
      return { outcome: 'answered' as const, answers }
    }
    function handleAskUserQuestion(message: any) {
      const requestId = message.request_id
      if (typeof requestId !== 'string') return
      if (!hostPermissions || closed || fatal || !active) {
        writePermission(requestId, false, undefined, { message: 'User declined to answer' })
        return
      }
      if (pendingPermissions.has(requestId)) return
      if (pendingPermissions.size >= MAX_PENDING_PERMISSIONS) {
        writePermission(requestId, false, undefined, { message: 'User declined to answer' })
        return
      }
      const controller = new AbortController()
      const pending: PendingPermission = {
        controller,
        turnUuid: active.uuid,
        input: message.request?.input,
        fingerprint: permissionFingerprint(message.request),
        suggestions: undefined,
      }
      pendingPermissions.set(requestId, pending)
      void askUserQuestions(message.request, controller.signal).then(result => {
        if (pendingPermissions.get(requestId) !== pending) return
        pendingPermissions.delete(requestId)
        const liveOk = !pending.controller.signal.aborted && !closed && !fatal && active?.uuid === pending.turnUuid
        if (!liveOk || result.outcome !== 'answered') {
          writePermission(requestId, false, undefined, { message: 'User declined to answer' })
          return
        }
        const base = pending.input && typeof pending.input === 'object' && !Array.isArray(pending.input)
          ? { ...(pending.input as Record<string, unknown>) }
          : {}
        writePermission(requestId, true, { ...base, answers: result.answers })
      }, () => {
        if (pendingPermissions.get(requestId) !== pending) return
        pendingPermissions.delete(requestId)
        writePermission(requestId, false, undefined, { message: 'User declined to answer' })
      })
    }
    function handleCanUseTool(message: any) {
      const requestId = message.request_id
      if (typeof requestId !== 'string') return
      if (message.request?.tool_name === 'AskUserQuestion') {
        handleAskUserQuestion(message)
        return
      }
      const fingerprint = permissionFingerprint(message.request)
      if (!hostPermissions || closed || fatal || !active) { denyTool(requestId, fingerprint); return }
      const answered = answeredPermissions.get(requestId)
      if (answered) {
        const identical = answered.turnUuid === active.uuid && answered.fingerprint === fingerprint
        if (identical) writePermission(requestId, answered.allow, answered.input)
        else writePermission(requestId, false, undefined)
        return
      }
      if (pendingPermissions.has(requestId)) return
      if (pendingPermissions.size >= MAX_PENDING_PERMISSIONS) { denyTool(requestId, fingerprint, active.uuid); return }
      const controller = new AbortController()
      const pending: PendingPermission = {
        controller,
        turnUuid: active.uuid,
        input: message.request?.input,
        fingerprint,
        suggestions: message.request?.permission_suggestions,
      }
      pendingPermissions.set(requestId, pending)
      void askHost(requestId, message.request, controller.signal).then(answer => {
        settlePermission(requestId, pending, answer.optionId, answer.message)
      }, () => {
        settlePermission(requestId, pending, undefined)
      })
    }
    function startsMainTurn(message: any): boolean {
      if (!message || typeof message !== 'object' || message.parent_tool_use_id) return false
      if (message.type === 'system') return message.subtype === 'init' || (message.subtype === 'status' && message.status === 'requesting')
      return message.type === 'stream_event' || message.type === 'assistant'
    }
    function complete(a: Active, frame: any) {
      cancelPendingPermissions(a.uuid)
      if (a.interrupted || frame.stop_reason === 'cancelled' || frame.stop_reason === 'interrupt') a.completion.resolve({ stopReason: 'cancelled' })
      else if (frame.is_error === true || (typeof frame.subtype === 'string' && frame.subtype.startsWith('error'))) {
        const message = Array.isArray(frame.errors) ? frame.errors.find((x: unknown) => typeof x === 'string') : undefined
        a.completion.reject(new Error(message ?? frame.result ?? 'Claude turn failed'))
      } else a.completion.resolve({ stopReason: typeof frame.stop_reason === 'string' && frame.stop_reason ? frame.stop_reason : 'end_turn' })
    }
    const baseArgs = launchArgs(argv(options, agentSessionId, !!context.resumeId), context.profile)
    // Session context goes last: it carries a host's own appended prompt (see claudeContextArgs).
    const args = context.sessionContext ? [...baseArgs, ...claudeContextArgs(context.sessionContext, baseArgs)] : baseArgs
    const rpc = await transport({
      command: options.command, args,
      env: launchEnv(options.inheritEnv, options.env, context.profile),
      cwd: context.cwd, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes,
      sessionId: context.sessionId,
      keeper,
    }, message => {
      if (message?.type === 'control_request' && message.request?.subtype === 'can_use_tool' && typeof message.request_id === 'string') {
        handleCanUseTool(message)
        return
      }
      if (message?.type === 'control_cancel_request' && typeof message.request_id === 'string') {
        pendingPermissions.get(message.request_id)?.controller.abort()
        revokeCachedAllow(message.request_id)
        return
      }
      if (plumbing.has(message?.type)) return
      const sessionId = message?.session_id
      if (message?.type === 'system' && message.subtype === 'init') {
        if (typeof message.session_id !== 'string' || !message.session_id) { fail(new Error('Claude session identity missing')); return }
        if (message.session_id !== agentSessionId) { fail(new Error('Claude session identity mismatch')); return }
        try { rpc.setMeta({ agentSessionId: message.session_id }) } catch { /* */ }
      }
      if (typeof sessionId === 'string' && agentSessionId && sessionId !== agentSessionId) return
      if (typeof sessionId === 'string' && !identityConfirmed && message?.type === 'result') {
        try { rpc.setMeta({ agentSessionId: sessionId }) } catch { /* */ }
        identityConfirmed = true
      }
      if (message?.type === 'system' && message.subtype === 'init') identityConfirmed = true
      if (!active && !unsolicited && startsMainTurn(message)) {
        // Background work finished and Claude began a turn on its own (result.origin
        // task-notification). Report it as native activity so Core runs a real turn for it.
        unsolicited = `claude-unsolicited:${++unsolicitedSeq}`
        try { context.onActivity?.({ id: unsolicited, phase: 'started' }) } catch { /* */ }
      }
      if (agentSessionId) context.onUpdate({ protocol: 'native', value: message })
      if (message?.type === 'result' && !active && unsolicited) {
        const finished = unsolicited
        unsolicited = undefined
        try { context.onActivity?.({ id: finished, phase: 'completed' }) } catch { /* */ }
        return
      }
      if (!ready && message?.type === 'result' && message.is_error) {
        fail(new Error(Array.isArray(message.errors) ? message.errors.join('; ') : 'Claude startup failed')); return
      }
      if (message?.type === 'result' && active) {
        const promptUuid = message.user_message_uuid
        if (typeof promptUuid === 'string' && promptUuid !== active.uuid && !fromMetaOwned) return
        const finishing = active
        complete(finishing, message)
        try { context.onActivity?.({ id: finishing.uuid, phase: 'completed' }) } catch { /* */ }
        try { rpc.setMeta({ ownedTurn: null }) } catch { /* */ }
        fromMetaOwned = false
        if (active === finishing) active = undefined
      }
    }, fail)
    const close = async (closeOptions: CloseOptions) => {
      const mode = requireCloseMode(closeOptions)
      if (!closed) { closed = true; cancelPendingPermissions(); fail(new Error('Claude runtime closed')) }
      await rpc.close({ mode })
    }
    const setupAbort = () => { fail(new Error('Claude setup aborted')); void close({ mode: 'shutdown' }) /* abort: stop the native process */ }
    context.signal.addEventListener('abort', setupAbort, { once: true })
    const timer = setTimeout(() => { fail(new Error('Claude setup timed out')); void close({ mode: 'shutdown' }) /* setup timeout: stop the native process */ }, setupTimeoutMs)
    let reattached = false
    try {
      const reattach = rpc.welcome.agentRunning === true && typeof rpc.welcome.meta.agentSessionId === 'string'
      reattached = reattach
      if (reattach) {
        const sid = rpc.welcome.meta.agentSessionId as string
        if (context.resumeId && context.resumeId !== sid) throw new Error('Claude session identity mismatch')
        agentSessionId = sid
        identityConfirmed = true
        const owned = rpc.welcome.meta.ownedTurn
        if (owned && typeof owned === 'object' && !Array.isArray(owned) && typeof (owned as { uuid?: unknown }).uuid === 'string') {
          const uuid = (owned as { uuid: string }).uuid
          fromMetaOwned = true
          const a: Active = { uuid, completion: deferred<{stopReason: string}>(), interrupted: false }
          active = a
          try { context.onActivity?.({ id: uuid, phase: 'started' }) } catch { /* */ }
        }
        const seeded = rpc.welcome.meta.permissions
        if (seeded && typeof seeded === 'object') {
          try { livePermissions = validatePermissionsSpec(seeded) as Extract<PermissionsSpec, { kind: 'claude' }> } catch { /* keep factory spec */ }
        }
        if (fatal) throw fatal
        ready = true
      } else {
        await Promise.race([rpc.request({ subtype: 'initialize' }), failure.promise])
        // Native system/init is not part of open(): CLI 2.1.261 acks initialize
        // without a session id, and emits system/init only on the first user prompt.
        // A created but never-prompted session may have no durable history.
        rpc.setMeta({ agentSessionId, permissions: livePermissions })
        if (fatal) throw fatal
        ready = true
      }
    } catch (error) { await close({ mode: 'shutdown' }); throw error } finally { clearTimeout(timer); context.signal.removeEventListener('abort', setupAbort) }
    async function interrupt() {
      const a = active
      if (!a) {
        if (unsolicited) await rpc.request({ subtype: 'interrupt' })
        return
      }
      cancelPendingPermissions(a.uuid)
      if (active === a) await rpc.request({ subtype: 'interrupt' })
      if (active === a && !fatal) a.interrupted = true
    }
    // A re-attached process still runs what it ran; a fresh one cannot.
    const restoredSubagents = context.subagents?.length ? normalizer.restore(context.subagents, { stillRunning: reattached }) : undefined
    return {
      agentSessionId: agentSessionId!, capabilities: { resume: true, steer: false, fork: false, detach: true, permissions: true }, close, interrupt,
      nativeProtocol: 'claude-stream-json' as const,
      ...(restoredSubagents ? { restoredSubagents } : {}),
      async setPermissions(spec) {
        if (fatal || closed) throw fatal ?? new Error('Claude runtime closed')
        const next = validatePermissionsSpec(spec)
        if (next.kind !== 'claude') throw new TypeError('Claude permissions kind must be claude')
        if (JSON.stringify(livePermissions) !== JSON.stringify(next)) {
          await rpc.request({ subtype: 'set_permission_mode', mode: next.permissionMode })
        }
        livePermissions = next
        try { rpc.setMeta({ permissions: next }) } catch { /* */ }
        return { applied: appliedFor(next) }
      },
      normalize: normalizer,
      flush: () => normalizer.flush(),
      async messageSubagent(subagentId, content) {
        if (fatal || closed) throw fatal ?? new CoreError('runtime_closed', 'Claude runtime closed')
        return { via: 'relay' as const, relay: claudeRelayPrompt(normalizer.subagent(subagentId)?.taskId ?? subagentId, content) }
      },
      async stopSubagent(subagentId) {
        if (fatal || closed) throw fatal ?? new CoreError('runtime_closed', 'Claude runtime closed')
        const known = normalizer.subagent(subagentId)
        if (!known) throw new CoreError('subagent_not_found', `Unknown Claude subagent ${subagentId}`)
        // stop_task and the parent's own TaskStop produce identical frames; only we know this one is ours.
        normalizer.markClientStop(known.id)
        try { await rpc.request({ subtype: 'stop_task', task_id: known.taskId }) }
        catch (error) { normalizer.clearClientStop(known.id); throw error }
      },
      async prompt(content, signal) {
        if (fatal || closed) throw fatal ?? new Error('Claude runtime closed')
        signal.throwIfAborted()
        if (active) throw new Error('Claude prompt already running')
        const converted = input(content)
        const a: Active = { uuid: randomUUID(), completion: deferred<{stopReason: string}>(), interrupted: false }
        active = a
        const abort = () => { void interrupt().catch(error => { a.completion.reject(error) }) }
        signal.addEventListener('abort', abort, { once: true })
        try {
          rpc.setMeta({ ownedTurn: { uuid: a.uuid } })
          rpc.write({ type: 'user', uuid: a.uuid, session_id: agentSessionId, message: { role: 'user', content: converted } })
          return await a.completion.promise
        } catch (error) { a.completion.reject(error instanceof Error ? error : new Error(String(error))); throw error }
        finally {
          signal.removeEventListener('abort', abort)
          cancelPendingPermissions(a.uuid)
          if (active === a) active = undefined
          try { rpc.setMeta({ ownedTurn: null }) } catch { /* */ }
        }
      },
    }
  } }
}
