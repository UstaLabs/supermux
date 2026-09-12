import { randomUUID } from 'node:crypto'
import type { AgentDriver, ContentBlock, PermissionHandler } from '../types.js'
import type { RequestPermissionResponse } from '@agentclientprotocol/sdk'
import { transport } from './transport.js'

export type ClaudeOptions = {
  id?: string
  command?: string
  args?: string[]
  env?: Record<string, string>
  inheritEnv?: boolean
  model?: string
  effort?: 'low' | 'medium' | 'high' | 'xhigh' | 'max'
  tools?: string[] | 'default'
  allowedTools?: string[]
  disallowedTools?: string[]
  permissionMode?: 'acceptEdits' | 'auto' | 'bypassPermissions' | 'manual' | 'dontAsk' | 'plan'
  permissionPrompts?: 'host' | 'none'
  setupTimeoutMs?: number
  requestTimeoutMs?: number
  shutdownTimeoutMs?: number
  maxFrameBytes?: number
}

const plumbing = new Set(['control_request', 'control_response', 'control_cancel_request', 'keep_alive'])
const MAX_PENDING_PERMISSIONS = 128
const MAX_ANSWERED_PERMISSIONS = 128
const permissionOptions = [
  { optionId: 'allow_once', name: 'Allow once', kind: 'allow_once' as const },
  { optionId: 'reject_once', name: 'Reject once', kind: 'reject_once' as const },
]
const cancelledPermission: RequestPermissionResponse = { outcome: { outcome: 'cancelled' } }

function input(content: ContentBlock[]) {
  return content.map(block => {
    if (block.type === 'text') return { type: 'text', text: block.text }
    if (block.type === 'image') return { type: 'image', source: { type: 'base64', media_type: block.mimeType, data: block.data } }
    throw new Error(`Unsupported Claude input block: ${block.type}`)
  })
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
  flags.push('--permission-prompts', options.permissionPrompts ?? 'none')
  if (options.permissionMode) flags.push('--permission-mode', options.permissionMode)
  if (options.allowedTools?.length) flags.push('--allowedTools', options.allowedTools.join(','))
  if (options.disallowedTools?.length) flags.push('--disallowedTools', options.disallowedTools.join(','))
  if (options.model) flags.push('--model', options.model)
  if (options.effort) flags.push('--effort', options.effort)
  flags.push(resume ? `--resume=${sessionId}` : `--session-id=${sessionId}`)
  return [...(options.args ?? []), ...flags]
}

export function claude(options: ClaudeOptions = {}): AgentDriver {
  const setupTimeoutMs = options.setupTimeoutMs ?? 30_000
  const requestTimeoutMs = options.requestTimeoutMs ?? 30_000
  const shutdownTimeoutMs = options.shutdownTimeoutMs ?? 2_000
  const maxFrameBytes = options.maxFrameBytes ?? 16 * 1024 * 1024
  for (const value of [setupTimeoutMs, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes]) if (!Number.isSafeInteger(value) || value <= 0) throw new TypeError('Claude limits must be positive safe integers')
  return { id: options.id ?? 'claude', async open(context) {
    context.signal.throwIfAborted()
    let agentSessionId = context.resumeId ?? randomUUID(), ready = false, closed = false
    let fatal: Error | undefined
    const hostPermissions = options.permissionPrompts === 'host'
    type Active = { uuid: string; completion: ReturnType<typeof deferred<{stopReason: string}>>; interrupted: boolean }
    type PendingPermission = { controller: AbortController; turnUuid: string; input: unknown; fingerprint: string }
    type AnsweredPermission = { allow: boolean; input: unknown; turnUuid: string; fingerprint: string }
    let active: Active | undefined
    const failure = deferred<never>()
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
    function writePermission(requestId: string, allow: boolean, input: unknown) {
      const response = allow
        ? { behavior: 'allow', updatedInput: input }
        : { behavior: 'deny', message: 'Permission denied' }
      try { rpc.write({ type: 'control_response', response: { subtype: 'success', request_id: requestId, response } }) }
      catch (error) { if (!fatal) fail(error instanceof Error ? error : new Error(String(error))) }
    }
    function denyTool(requestId: string, fingerprint = '', turnUuid = active?.uuid ?? '') {
      rememberAnswer(requestId, false, undefined, turnUuid, fingerprint)
      writePermission(requestId, false, undefined)
    }
    function settlePermission(requestId: string, pending: PendingPermission, allow: boolean) {
      if (pendingPermissions.get(requestId) !== pending) return
      pendingPermissions.delete(requestId)
      const granted = allow === true
        && !pending.controller.signal.aborted
        && !closed && !fatal
        && active?.uuid === pending.turnUuid
      rememberAnswer(requestId, granted, granted ? pending.input : undefined, pending.turnUuid, pending.fingerprint)
      writePermission(requestId, granted, pending.input)
    }
    async function askHost(requestId: string, request: any, signal: AbortSignal) {
      const toolCallId = typeof request?.tool_use_id === 'string' && request.tool_use_id ? request.tool_use_id : requestId
      const title = typeof request?.tool_name === 'string' ? request.tool_name : 'tool'
      const rawInput = cloneRawInput(request?.input)
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
            options: permissionOptions,
          }, signal)).catch(() => cancelledPermission),
          cancellation,
        ])
        if (signal.aborted) return false
        try {
          return response?.outcome?.outcome === 'selected' && response.outcome.optionId === 'allow_once'
        } catch {
          return false
        }
      } finally { signal.removeEventListener('abort', cancel) }
    }
    function handleCanUseTool(message: any) {
      const requestId = message.request_id
      if (typeof requestId !== 'string') return
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
      const pending: PendingPermission = { controller, turnUuid: active.uuid, input: message.request?.input, fingerprint }
      pendingPermissions.set(requestId, pending)
      void askHost(requestId, message.request, controller.signal).then(allow => {
        settlePermission(requestId, pending, allow === true)
      }, () => {
        settlePermission(requestId, pending, false)
      })
    }
    function complete(a: Active, frame: any) {
      cancelPendingPermissions(a.uuid)
      if (a.interrupted || frame.stop_reason === 'cancelled' || frame.stop_reason === 'interrupt') a.completion.resolve({ stopReason: 'cancelled' })
      else if (frame.is_error === true || (typeof frame.subtype === 'string' && frame.subtype.startsWith('error'))) {
        const message = Array.isArray(frame.errors) ? frame.errors.find((x: unknown) => typeof x === 'string') : undefined
        a.completion.reject(new Error(message ?? frame.result ?? 'Claude turn failed'))
      } else a.completion.resolve({ stopReason: typeof frame.stop_reason === 'string' && frame.stop_reason ? frame.stop_reason : 'end_turn' })
    }
    const rpc = transport({
      command: options.command ?? 'claude', args: argv(options, agentSessionId, !!context.resumeId),
      env: { ...(options.inheritEnv === false ? {} : process.env), ...options.env, ...context.profile?.env },
      cwd: context.cwd, requestTimeoutMs, shutdownTimeoutMs, maxFrameBytes,
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
      }
      if (typeof sessionId === 'string' && agentSessionId && sessionId !== agentSessionId) return
      if (agentSessionId) context.onUpdate({ protocol: 'native', value: message })
      if (!ready && message?.type === 'result' && message.is_error) {
        fail(new Error(Array.isArray(message.errors) ? message.errors.join('; ') : 'Claude startup failed')); return
      }
      if (message?.type === 'result' && active) {
        const promptUuid = message.user_message_uuid
        if (typeof promptUuid === 'string' && promptUuid !== active.uuid) return
        complete(active, message)
      }
    }, fail)
    const close = async () => { if (!closed) { closed = true; cancelPendingPermissions(); fail(new Error('Claude runtime closed')) } await rpc.close() }
    const setupAbort = () => { fail(new Error('Claude setup aborted')); void close() }
    context.signal.addEventListener('abort', setupAbort, { once: true })
    const timer = setTimeout(() => { fail(new Error('Claude setup timed out')); void close() }, setupTimeoutMs)
    try {
      await Promise.race([rpc.request({ subtype: 'initialize' }), failure.promise])
      // Native system/init is not part of open(): CLI 2.1.261 acks initialize
      // without a session id, and emits system/init only on the first user prompt.
      // A created but never-prompted session may have no durable history.
      if (fatal) throw fatal
      ready = true
    } catch (error) { await close(); throw error } finally { clearTimeout(timer); context.signal.removeEventListener('abort', setupAbort) }
    async function interrupt() {
      const a = active
      if (!a) return
      cancelPendingPermissions(a.uuid)
      if (active === a) await rpc.request({ subtype: 'interrupt' })
      if (active === a && !fatal) a.interrupted = true
    }
    return {
      agentSessionId: agentSessionId!, capabilities: { resume: true, steer: false, fork: false, detach: false }, close, interrupt,
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
          rpc.write({ type: 'user', uuid: a.uuid, session_id: agentSessionId, message: { role: 'user', content: converted } })
          return await a.completion.promise
        } catch (error) { a.completion.reject(error instanceof Error ? error : new Error(String(error))); throw error }
        finally {
          signal.removeEventListener('abort', abort)
          cancelPendingPermissions(a.uuid)
          if (active === a) active = undefined
        }
      },
    }
  } }
}
