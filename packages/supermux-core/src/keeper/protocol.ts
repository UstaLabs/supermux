export type KeeperLimits = {
  maxFrameBytes: number
  shutdownTimeoutMs: number
  parkedDeadlineMs: number
  journalMaxBytes: number
  connectTimeoutMs: number
}

export type FrameShape = 'jsonrpc' | 'claude-control'

export type KeeperSpec = {
  command: string
  args: string[]
  cwd: string
  env: NodeJS.ProcessEnv
  frameShape: FrameShape
}

export type KeeperStatus = {
  keeperPid: number
  agentPid: number
  startedAt: number
  lastSeq: number
  firstSeq: number
  ackedSeq: number
  agentExited?: number | null
  updatedAt: number
  meta: Record<string, unknown>
  error?: string
  reaped?: boolean
}

export type JournalEntry = {
  seq: number
  dir: 'in' | 'out'
  line: string
  stale?: boolean
}

export type ClientHello = { type: 'hello'; token: string; cursor: number | 'acked' }
export type ClientFrame = { type: 'frame'; line: string }
export type ClientMeta = { type: 'meta'; value: Record<string, unknown> }
export type ClientAck = { type: 'ack'; seq: number }
export type ClientShutdown = { type: 'shutdown' }
export type ClientMessage = ClientHello | ClientFrame | ClientMeta | ClientAck | ClientShutdown

export type KeeperWelcome = {
  type: 'welcome'
  lastSeq: number
  firstSeq: number
  ackedSeq: number
  agentRunning: boolean
  agentExited: number | null
  meta: Record<string, unknown>
  maxRequestId: number
}
export type KeeperFrame = { type: 'frame'; seq: number; line: string; stale?: true }
export type KeeperParked = { type: 'parked'; seq: number; line: string }
export type KeeperExit = { type: 'exit'; code: number | null }
export type KeeperReplaced = { type: 'replaced' }
export type KeeperError = { type: 'error'; message: string }
export type KeeperMessage =
  | KeeperWelcome
  | KeeperFrame
  | KeeperParked
  | KeeperExit
  | KeeperReplaced
  | KeeperError

export const KEEPER_ENV = {
  sessionDir: 'SUPERMUX_KEEPER_SESSION_DIR',
  command: 'SUPERMUX_KEEPER_COMMAND',
  args: 'SUPERMUX_KEEPER_ARGS',
  cwd: 'SUPERMUX_KEEPER_CWD',
  agentEnv: 'SUPERMUX_KEEPER_AGENT_ENV',
  token: 'SUPERMUX_KEEPER_TOKEN',
  limits: 'SUPERMUX_KEEPER_LIMITS',
  frameShape: 'SUPERMUX_KEEPER_FRAME_SHAPE',
} as const

function parseObject(line: string): any | undefined {
  try {
    const message = JSON.parse(line)
    if (!message || typeof message !== 'object' || Array.isArray(message)) return undefined
    return message
  } catch {
    return undefined
  }
}

/** Agent→client (or client→agent) request id for the given frame shape, else undefined. */
export function requestId(line: string, shape: FrameShape): string | number | undefined {
  const message = parseObject(line)
  if (!message) return undefined
  if (shape === 'jsonrpc') {
    if (typeof message.method === 'string' && message.id !== null && message.id !== undefined) return message.id as string | number
    return undefined
  }
  if (message.type === 'control_request' && typeof message.request_id === 'string') return message.request_id
  return undefined
}

/** Response id matching a prior request for the given frame shape, else undefined. */
export function responseId(line: string, shape: FrameShape): string | number | undefined {
  const message = parseObject(line)
  if (!message) return undefined
  if (shape === 'jsonrpc') {
    if (typeof message.method === 'string') return undefined
    if (message.id === null || message.id === undefined) return undefined
    if ('result' in message || 'error' in message) return message.id as string | number
    return undefined
  }
  if (message.type === 'control_response' && message.response && typeof message.response.request_id === 'string') {
    return message.response.request_id
  }
  return undefined
}
