/**
 * The limits a keeper (and a client of it) runs under: the longest frame; how long the agent gets to stop (SIGTERM, then
 * SIGKILL) and how long a client waits for a keeper; how long a request the agent makes while nobody is attached waits
 * before the keeper answers it with an error; the journal's size.
 * @experimental Part of what `connectKeeper` takes: may change before 1.0.
 */
export type KeeperLimits = {
  maxFrameBytes: number
  shutdownTimeoutMs: number
  parkedDeadlineMs: number
  journalMaxBytes: number
  connectTimeoutMs: number
}

/**
 * How the keeper tells the agent's requests from its responses, to park a request made while nobody is attached:
 * `jsonrpc` (JSON-RPC 2.0 lines, as ACP and Codex speak), `claude-control` (Claude's `control_request` and
 * `control_response` lines), or `ndjson`: plain line stream with no request/response pairing (nothing is ever parked).
 * @experimental Part of what `connectKeeper` takes: may change before 1.0.
 */
export type FrameShape = 'jsonrpc' | 'claude-control' | 'ndjson'

/**
 * The agent a keeper keeps running: its command line and environment, how its lines are framed, and what it was launched for.
 * @experimental Part of what `connectKeeper` takes: may change before 1.0.
 */
export type KeeperSpec = {
  command: string
  args: string[]
  cwd: string
  env: NodeJS.ProcessEnv
  frameShape: FrameShape
  /** Forward the agent's stderr lines to the attacher as `stderr` messages (journaled as dir 'err'). */
  captureStderr: boolean
  /**
   * What the agent process was launched for (e.g. the session context). Set: a running keeper
   * whose recorded fingerprint differs is shut down and a NEW agent process is started (a
   * re-attach would keep the old process and its old args). A keeper with no recorded
   * fingerprint (started before this field existed, or without one) is reused. Absent: always reuse.
   */
  fingerprint?: string
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
  /** The socket the keeper listens on (see keeperSocketPath). Absent: an older keeper, listening on `<keeper dir>/keeper.sock`. */
  socket?: string
}

export type JournalEntry = {
  seq: number
  dir: 'in' | 'out' | 'err'
  line: string
  stale?: boolean
}

export type ClientHello = { type: 'hello'; token: string; cursor: number | 'acked' }
export type ClientFrame = { type: 'frame'; line: string }
export type ClientMeta = { type: 'meta'; value: Record<string, unknown> }
export type ClientAck = { type: 'ack'; seq: number }
export type ClientShutdown = { type: 'shutdown' }
export type ClientSignal = { type: 'signal'; signal: 'SIGINT' }
export type ClientMessage = ClientHello | ClientFrame | ClientMeta | ClientAck | ClientShutdown | ClientSignal

/**
 * A keeper's answer to a client's hello: the journal's first, last and acknowledged sequence numbers, whether the agent runs
 * (else how it exited), the meta a client stored with `setMeta`, and the highest request id a client has sent the agent
 * (a re-attaching client numbers its next request after it).
 * @experimental Part of what `connectKeeper` returns: may change before 1.0.
 */
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
export type KeeperStderr = { type: 'stderr'; seq: number; line: string }
export type KeeperExit = { type: 'exit'; code: number | null }
export type KeeperReplaced = { type: 'replaced' }
export type KeeperError = { type: 'error'; message: string }
export type KeeperMessage =
  | KeeperWelcome
  | KeeperFrame
  | KeeperParked
  | KeeperStderr
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
  captureStderr: 'SUPERMUX_KEEPER_CAPTURE_STDERR',
  /** Where to listen; absent: `<session dir>/keeper.sock`. */
  socket: 'SUPERMUX_KEEPER_SOCKET',
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
  if (shape === 'ndjson') return undefined
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
  if (shape === 'ndjson') return undefined
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
