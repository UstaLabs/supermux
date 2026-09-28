import { randomBytes } from 'node:crypto'
import { createServer } from 'node:net'

/**
 * OpenCode's own HTTP server, which `opencode acp` always runs next to the ACP stdio channel.
 *
 * Why the ACP runtime needs it (verified on OpenCode 1.16.2): OpenCode's ACP agent relays a
 * `permission.asked` event only for sessions registered with its ACP session manager (the one
 * the client created, loaded or resumed). A `task` subagent runs in a CHILD session nobody
 * registered, so its asks are dropped without a reply and the child waits forever. The child's
 * session id reaches ACP only on the task's completion, and `session/list` lists roots only, so
 * the client cannot register the child in time over ACP. The HTTP API lists every pending ask
 * (`GET /permission`), answers one (`POST /permission/:id/reply`), and shows a running task's
 * child session (`metadata.sessionId` on the task part of `GET /session/:id/message`).
 *
 * The server listens on 127.0.0.1 only; a per-process random password (OpenCode's own
 * OPENCODE_SERVER_PASSWORD basic auth, which its in-process ACP client also sends) keeps other
 * local processes out.
 */
export type OpenCodeServerInfo = { port: number; password: string }

export type OpenCodePendingAsk = {
  id: string
  sessionID: string
  permission: string
  patterns?: string[]
  metadata?: Record<string, unknown>
  tool?: { messageID?: string; callID?: string }
}

export type OpenCodeReply = 'once' | 'always' | 'reject'

/** OpenCode's ACP permission options, unchanged, so a child ask reads like a parent ask. */
export const OPENCODE_PERMISSION_OPTIONS = [
  { optionId: 'once', kind: 'allow_once', name: 'Allow once' },
  { optionId: 'always', kind: 'allow_always', name: 'Always allow' },
  { optionId: 'reject', kind: 'reject_once', name: 'Reject' },
] as const

/** OpenCode's own ACP tool-kind mapping (1.16.2), so the live policy classifies child asks the same way. */
export function openCodeToolKind(permission: string): string {
  switch (permission.toLowerCase()) {
    case 'bash': case 'shell': return 'execute'
    case 'webfetch': return 'fetch'
    case 'edit': case 'apply_patch': case 'patch': case 'write': return 'edit'
    case 'grep': case 'glob': case 'context': return 'search'
    case 'read': return 'read'
    case 'task': return 'think'
    default: return 'other'
  }
}

/** A child ask in the shape of ACP `session/request_permission` params. */
export function openCodeAskToAcp(ask: OpenCodePendingAsk) {
  return {
    sessionId: ask.sessionID,
    toolCall: {
      toolCallId: ask.tool?.callID ?? ask.id,
      status: 'pending' as const,
      title: ask.permission,
      rawInput: ask.metadata ?? {},
      kind: openCodeToolKind(ask.permission),
    },
    options: OPENCODE_PERMISSION_OPTIONS.map(o => ({ ...o })),
  }
}

export function newOpenCodeServerInfo(port: number): OpenCodeServerInfo {
  return { port, password: randomBytes(24).toString('base64url') }
}

export function readOpenCodeServerInfo(value: unknown): OpenCodeServerInfo | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return
  const rec = value as { port?: unknown; password?: unknown }
  if (typeof rec.port !== 'number' || !Number.isSafeInteger(rec.port) || rec.port <= 0 || rec.port > 65535) return
  if (typeof rec.password !== 'string' || !rec.password) return
  return { port: rec.port, password: rec.password }
}

/** A currently free loopback port. OpenCode binds it right after; the window is a few ms. */
export function freeLoopbackPort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = createServer()
    server.unref()
    server.once('error', reject)
    server.listen(0, '127.0.0.1', () => {
      const address = server.address()
      const port = address && typeof address === 'object' ? address.port : 0
      server.close(() => port ? resolve(port) : reject(new Error('no loopback port')))
    })
  })
}

export type OpenCodeServerClient = {
  pendingAsks(signal: AbortSignal): Promise<OpenCodePendingAsk[]>
  reply(id: string, reply: OpenCodeReply, signal: AbortSignal): Promise<void>
  /** Running task tool calls of `sessionId`'s latest messages → their child session ids. */
  runningTaskChildren(sessionId: string, signal: AbortSignal): Promise<Map<string, string>>
  parentOf(sessionId: string, signal: AbortSignal): Promise<string | undefined>
}

export function openCodeServerClient(info: OpenCodeServerInfo, directory: string, timeoutMs = 5000): OpenCodeServerClient {
  const base = `http://127.0.0.1:${info.port}`
  const auth = `Basic ${Buffer.from(`opencode:${info.password}`).toString('base64')}`
  const query = `directory=${encodeURIComponent(directory)}`
  async function call(path: string, signal: AbortSignal, init: { method?: string; body?: unknown } = {}): Promise<unknown> {
    const sep = path.includes('?') ? '&' : '?'
    const response = await fetch(`${base}${path}${sep}${query}`, {
      method: init.method ?? 'GET',
      headers: { Authorization: auth, ...(init.body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      ...(init.body !== undefined ? { body: JSON.stringify(init.body) } : {}),
      signal: AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]),
    })
    if (!response.ok) throw new Error(`OpenCode server ${init.method ?? 'GET'} ${path}: HTTP ${response.status}`)
    return response.json()
  }
  return {
    async pendingAsks(signal) {
      const list = await call('/permission', signal)
      if (!Array.isArray(list)) return []
      return list.filter((a): a is OpenCodePendingAsk => !!a && typeof a === 'object' && typeof (a as OpenCodePendingAsk).id === 'string' && typeof (a as OpenCodePendingAsk).sessionID === 'string' && typeof (a as OpenCodePendingAsk).permission === 'string')
    },
    async reply(id, reply, signal) {
      await call(`/permission/${encodeURIComponent(id)}/reply`, signal, { method: 'POST', body: { reply } })
    },
    async runningTaskChildren(sessionId, signal) {
      const out = new Map<string, string>()
      const messages = await call(`/session/${encodeURIComponent(sessionId)}/message?limit=6`, signal)
      if (!Array.isArray(messages)) return out
      for (const message of messages) {
        const parts = (message as { parts?: unknown })?.parts
        if (!Array.isArray(parts)) continue
        for (const part of parts) {
          const p = part as { type?: unknown; tool?: unknown; callID?: unknown; state?: { metadata?: { sessionId?: unknown } } }
          const child = p?.state?.metadata?.sessionId
          if (p?.type === 'tool' && p.tool === 'task' && typeof p.callID === 'string' && typeof child === 'string' && child) out.set(p.callID, child)
        }
      }
      return out
    },
    async parentOf(sessionId, signal) {
      const info = await call(`/session/${encodeURIComponent(sessionId)}`, signal) as { parentID?: unknown } | null
      return typeof info?.parentID === 'string' && info.parentID ? info.parentID : undefined
    },
  }
}
