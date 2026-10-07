/**
 * Keeper process: unix-socket client + agent child + NDJSON journal.
 * The full journal is held in memory (`journal: JournalEntry[]`) up to
 * `journalMaxBytes` (default 64 MiB ⇒ ~64 MiB RAM per keeper). Replay
 * reads that in-memory array, not the file. Disk rewrites use hysteresis:
 * when bytes exceed the cap, one rewrite trims to 75% of the cap (parked
 * lines are never dropped).
 */
import { spawn, type ChildProcess } from 'node:child_process'
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { createServer, type Server, type Socket } from 'node:net'
import { join } from 'node:path'
import { TextDecoder } from 'node:util'
import { KEEPER_ENV, requestId, responseId, type FrameShape, type JournalEntry, type KeeperLimits, type KeeperMessage, type KeeperStatus } from './protocol.js'

const STATUS_COALESCE_MS = 250

type Parked = { seq: number; line: string; id: string | number; timer: ReturnType<typeof setTimeout> }

function requiredEnv(name: string): string {
  const v = process.env[name]
  if (typeof v !== 'string' || v === '') throw new TypeError(`missing env ${name}`)
  return v
}

function parseJson<T>(name: string, raw: string): T {
  try { return JSON.parse(raw) as T } catch { throw new TypeError(`invalid JSON in ${name}`) }
}

function validateLimits(limits: KeeperLimits): KeeperLimits {
  for (const key of ['maxFrameBytes', 'shutdownTimeoutMs', 'parkedDeadlineMs', 'journalMaxBytes', 'connectTimeoutMs'] as const) {
    const v = limits[key]
    if (typeof v !== 'number' || !Number.isFinite(v) || v < 0 || !Number.isInteger(v)) throw new TypeError(`invalid limits.${key}`)
  }
  if (limits.maxFrameBytes < 1) throw new TypeError('invalid limits.maxFrameBytes')
  if (limits.journalMaxBytes < 1) throw new TypeError('invalid limits.journalMaxBytes')
  if (limits.connectTimeoutMs < 1) throw new TypeError('invalid limits.connectTimeoutMs')
  return limits
}

function encode(msg: KeeperMessage): string { return JSON.stringify(msg) + '\n' }

function parseLine(line: string): unknown {
  const message = JSON.parse(line)
  if (!message || typeof message !== 'object' || Array.isArray(message)) throw new Error('Invalid message')
  return message
}

function rpcIdKey(id: unknown): string { return JSON.stringify(id) }

function deadlineReply(shape: FrameShape, id: string | number): string {
  if (shape === 'claude-control') {
    return JSON.stringify({ type: 'control_response', response: { request_id: id, subtype: 'error', error: 'no client attached within deadline' } })
  }
  return JSON.stringify({ id, error: { code: -32001, message: 'no client attached within deadline' } })
}

function alive(pid: number): boolean {
  if (!pid || pid < 1) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

async function main() {
  const sessionDir = requiredEnv(KEEPER_ENV.sessionDir)
  const command = requiredEnv(KEEPER_ENV.command)
  const args = parseJson<string[]>(KEEPER_ENV.args, requiredEnv(KEEPER_ENV.args))
  const cwd = requiredEnv(KEEPER_ENV.cwd)
  const agentEnv = parseJson<NodeJS.ProcessEnv>(KEEPER_ENV.agentEnv, requiredEnv(KEEPER_ENV.agentEnv))
  const token = requiredEnv(KEEPER_ENV.token)
  const limits = validateLimits(parseJson<KeeperLimits>(KEEPER_ENV.limits, requiredEnv(KEEPER_ENV.limits)))
  const frameShapeRaw = requiredEnv(KEEPER_ENV.frameShape)
  if (frameShapeRaw !== 'jsonrpc' && frameShapeRaw !== 'claude-control') throw new TypeError('invalid spec.frameShape')
  const frameShape: FrameShape = frameShapeRaw
  const captureStderrRaw = requiredEnv(KEEPER_ENV.captureStderr)
  if (captureStderrRaw !== 'true' && captureStderrRaw !== 'false') throw new TypeError('invalid spec.captureStderr')
  const captureStderr = captureStderrRaw === 'true'
  if (!Array.isArray(args) || args.some(a => typeof a !== 'string')) throw new TypeError('invalid args')

  mkdirSync(sessionDir, { recursive: true, mode: 0o700 })
  chmodSync(sessionDir, 0o700)

  const sockPath = join(sessionDir, 'keeper.sock')
  const journalPath = join(sessionDir, 'journal.ndjson')
  const statusPath = join(sessionDir, 'status.json')
  const tokenPath = join(sessionDir, 'token')
  const skipStatus = process.env.SUPERMUX_KEEPER_SKIP_STATUS === '1'

  function writeSecure(path: string, body: string, flag?: string) {
    writeFileSync(path, body, flag ? { mode: 0o600, flag } : { mode: 0o600 })
    chmodSync(path, 0o600)
  }

  writeSecure(tokenPath, token)

  let lastSeq = 0
  let firstSeq = 1
  let ackedSeq = 0
  let journalBytes = 0
  const journal: JournalEntry[] = []
  // A keeper starting means a NEW agent process (re-attach only ever joins a live keeper).
  // A journal left by a previous keeper of the same session belongs to a dead process and
  // must not be replayed into a fresh handshake; keep it aside for inspection, start at seq 1.
  if (existsSync(journalPath)) {
    try { renameSync(journalPath, journalPath + '.prev') } catch { /* best effort */ }
  }
  writeSecure(journalPath, '')

  const startedAt = Date.now()
  let meta: Record<string, unknown> = {}
  let agentExited: number | null | undefined
  let statusError: string | undefined
  let client: Socket | undefined
  let maxRequestId = 0
  const parked = new Map<string, Parked>()
  const inflight = new Map<string, { seq: number; line: string; id: string | number }>()
  const staleIds = new Set<string>()
  const currentAttacherRequestIds = new Set<string>()
  let shuttingDown = false
  let exitedKeeper = false
  let journalRewrites = 0
  let statusTimer: ReturnType<typeof setTimeout> | undefined
  let lastFlushedKeys = { agentPid: 0, agentExited: undefined as number | null | undefined, metaKey: '', error: undefined as string | undefined, firstSeq: 1 }
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let stdoutBuffer = Buffer.alloc(0)

  function parkedSeqs(): Set<number> {
    const s = new Set<number>()
    for (const p of parked.values()) s.add(p.seq)
    return s
  }

  function rewriteJournal() {
    const keep = parkedSeqs()
    const target = Math.floor(limits.journalMaxBytes * 0.75)
    let bytes = journal.reduce((n, e) => n + Buffer.byteLength(JSON.stringify(e) + '\n'), 0)
    while (bytes > target && journal.length) {
      const idx = journal.findIndex(e => e.seq <= ackedSeq && !keep.has(e.seq))
      if (idx < 0) break
      const [removed] = journal.splice(idx, 1)
      bytes -= Buffer.byteLength(JSON.stringify(removed) + '\n')
    }
    firstSeq = journal[0]?.seq ?? lastSeq + 1
    const body = journal.map(e => JSON.stringify(e) + '\n').join('')
    journalBytes = Buffer.byteLength(body)
    const tmp = journalPath + '.tmp'
    writeSecure(tmp, body)
    renameSync(tmp, journalPath)
    chmodSync(journalPath, 0o600)
    journalRewrites += 1
  }

  function appendJournal(entry: JournalEntry) {
    journal.push(entry)
    const line = JSON.stringify(entry) + '\n'
    journalBytes += Buffer.byteLength(line)
    writeSecure(journalPath, line, 'a')
    if (journalBytes > limits.journalMaxBytes) rewriteJournal()
    else firstSeq = journal[0]?.seq ?? lastSeq + 1
  }

  function flushStatus() {
    if (skipStatus) return
    if (statusTimer) { clearTimeout(statusTimer); statusTimer = undefined }
    const status: KeeperStatus & { journalRewrites: number } = {
      keeperPid: process.pid,
      agentPid: agent.pid ?? 0,
      startedAt,
      lastSeq,
      firstSeq,
      ackedSeq,
      updatedAt: Date.now(),
      meta,
      journalRewrites,
    }
    if (agentExited !== undefined) status.agentExited = agentExited
    if (statusError) status.error = statusError
    const tmp = statusPath + '.tmp'
    writeSecure(tmp, JSON.stringify(status) + '\n')
    renameSync(tmp, statusPath)
    chmodSync(statusPath, 0o600)
    lastFlushedKeys = {
      agentPid: status.agentPid,
      agentExited,
      metaKey: JSON.stringify(meta),
      error: statusError,
      firstSeq,
    }
  }

  function writeStatus(immediate = false) {
    const keysChanged =
      (agent.pid ?? 0) !== lastFlushedKeys.agentPid ||
      agentExited !== lastFlushedKeys.agentExited ||
      JSON.stringify(meta) !== lastFlushedKeys.metaKey ||
      statusError !== lastFlushedKeys.error ||
      firstSeq !== lastFlushedKeys.firstSeq
    if (immediate || keysChanged) {
      flushStatus()
      return
    }
    if (!statusTimer) statusTimer = setTimeout(() => flushStatus(), STATUS_COALESCE_MS)
  }

  function sendClient(sock: Socket | undefined, msg: KeeperMessage) {
    if (!sock || sock.destroyed) return
    try { sock.write(encode(msg)) } catch { /* closed */ }
  }

  const agent: ChildProcess = spawn(command, args, {
    cwd,
    env: agentEnv,
    stdio: ['pipe', 'pipe', 'pipe'],
    shell: false,
  })
  if (!captureStderr) agent.stderr?.resume()
  else {
    // Some agents (OpenCode with --print-logs) only report provider failures on stderr.
    let errBuffer = ''
    agent.stderr?.on('data', (chunk: Buffer) => {
      errBuffer += chunk.toString('utf8')
      let nl: number
      while ((nl = errBuffer.indexOf('\n')) >= 0) {
        const line = errBuffer.slice(0, nl); errBuffer = errBuffer.slice(nl + 1)
        if (!line.trim()) continue
        if (Buffer.byteLength(line) > limits.maxFrameBytes) continue
        lastSeq += 1
        const seq = lastSeq
        appendJournal({ seq, dir: 'err', line })
        writeStatus()
        sendClient(client, { type: 'stderr', seq, line })
      }
    })
  }

  function fail(message: string) {
    statusError = message
    writeStatus(true)
    sendClient(client, { type: 'error', message })
    void shutdownAgent()
  }

  function onAgentChunk(chunk: Buffer) {
    if (shuttingDown) return
    let offset = 0
    while (offset < chunk.length) {
      const newline = chunk.indexOf(10, offset)
      const end = newline < 0 ? chunk.length : newline
      if (stdoutBuffer.length + end - offset > limits.maxFrameBytes) {
        fail('frame exceeds size limit')
        return
      }
      stdoutBuffer = Buffer.concat([stdoutBuffer, chunk.subarray(offset, end)])
      offset = end + 1
      if (newline < 0) break
      let line: string
      try {
        line = decoder.decode(stdoutBuffer).trim()
        stdoutBuffer = Buffer.alloc(0)
      } catch (e) {
        fail(e instanceof Error ? e.message : String(e))
        return
      }
      if (!line) continue
      lastSeq += 1
      const seq = lastSeq
      appendJournal({ seq, dir: 'out', line })
      writeStatus()
      const req = requestId(line, frameShape)
      const resp = responseId(line, frameShape)
      if (!client && req !== undefined) {
        const key = rpcIdKey(req)
        const timer = setTimeout(() => {
          parked.delete(key)
          staleIds.add(key)
          const reply = deadlineReply(frameShape, req)
          lastSeq += 1
          appendJournal({ seq: lastSeq, dir: 'in', line: reply })
          try { agent.stdin?.write(reply + '\n') } catch { /* gone */ }
          writeStatus()
        }, limits.parkedDeadlineMs)
        parked.set(key, { seq, line, id: req, timer })
        writeStatus()
        continue
      }
      const stale = Boolean(resp !== undefined && !currentAttacherRequestIds.has(rpcIdKey(resp)))
      sendClient(client, stale ? { type: 'frame', seq, line, stale: true } : { type: 'frame', seq, line })
      if (req !== undefined) inflight.set(rpcIdKey(req), { seq, line, id: req })
    }
  }

  agent.stdout?.on('data', onAgentChunk)
  agent.on('error', err => fail(err.message))
  agent.on('close', (code, signal) => {
    agentExited = code ?? (signal ? 1 : 0)
    writeStatus(true)
    sendClient(client, { type: 'exit', code: agentExited })
    finish()
  })

  function finish() {
    if (exitedKeeper) return
    exitedKeeper = true
    try { server.close() } catch { /* */ }
    try { unlinkSync(sockPath) } catch { /* */ }
    writeStatus(true)
    setTimeout(() => process.exit(0), 10)
  }

  async function shutdownAgent() {
    if (shuttingDown) return
    shuttingDown = true
    for (const p of parked.values()) clearTimeout(p.timer)
    parked.clear()
    if (!alive(agent.pid ?? 0)) { finish(); return }
    try { agent.kill('SIGTERM') } catch { /* */ }
    const t = setTimeout(() => { try { agent.kill('SIGKILL') } catch { /* */ } }, limits.shutdownTimeoutMs)
    agent.once('close', () => clearTimeout(t))
  }

  function attach(sock: Socket, cursor: number) {
    if (client && client !== sock) {
      sendClient(client, { type: 'replaced' })
      try { client.end() } catch { /* */ }
    }
    client = sock
    currentAttacherRequestIds.clear()
    sendClient(sock, {
      type: 'welcome',
      lastSeq,
      firstSeq,
      ackedSeq,
      agentRunning: alive(agent.pid ?? 0) && agentExited === undefined,
      agentExited: agentExited ?? null,
      meta,
      maxRequestId,
    })
    const keep = parkedSeqs()
    // Replay only agent -> client frames. Client -> agent ('in') lines stay in the journal
    // for audit and maxRequestId; replaying them would make a re-attaching driver read its
    // own past requests as if the agent had sent them.
    for (const e of journal) {
      if (e.seq <= cursor) continue
      if (e.dir === 'in') continue
      if (e.dir === 'err') { sendClient(sock, { type: 'stderr', seq: e.seq, line: e.line }); continue }
      if (keep.has(e.seq)) continue
      const resp = responseId(e.line, frameShape)
      const stale = Boolean(resp !== undefined && !currentAttacherRequestIds.has(rpcIdKey(resp)))
      sendClient(sock, stale ? { type: 'frame', seq: e.seq, line: e.line, stale: true } : { type: 'frame', seq: e.seq, line: e.line })
      const req = requestId(e.line, frameShape)
      if (req !== undefined) inflight.set(rpcIdKey(req), { seq: e.seq, line: e.line, id: req })
    }
    for (const p of [...parked.values()].sort((a, b) => a.seq - b.seq)) {
      sendClient(sock, { type: 'parked', seq: p.seq, line: p.line })
    }
  }

  function onClientLine(sock: Socket, raw: string) {
    let msg: any
    try { msg = parseLine(raw.trim()) } catch { sendClient(sock, { type: 'error', message: 'invalid json' }); sock.end(); return }
    if (sock !== client && msg.type !== 'hello') { sock.end(); return }
    if (msg.type === 'hello') {
      if (typeof msg.token !== 'string' || msg.token !== token) {
        sendClient(sock, { type: 'error', message: 'invalid token' })
        sock.end()
        return
      }
      if (msg.cursor === 'acked') {
        attach(sock, ackedSeq)
        return
      }
      if (typeof msg.cursor !== 'number' || !Number.isInteger(msg.cursor) || msg.cursor < 0) {
        sendClient(sock, { type: 'error', message: 'invalid cursor' })
        sock.end()
        return
      }
      attach(sock, msg.cursor)
      return
    }
    if (msg.type === 'ack') {
      if (typeof msg.seq !== 'number' || !Number.isInteger(msg.seq) || msg.seq < 0) {
        sendClient(sock, { type: 'error', message: 'invalid ack' })
        return
      }
      if (msg.seq > ackedSeq) ackedSeq = msg.seq
      writeStatus()
      return
    }
    if (msg.type === 'meta') {
      if (!msg.value || typeof msg.value !== 'object' || Array.isArray(msg.value)) {
        sendClient(sock, { type: 'error', message: 'invalid meta' })
        return
      }
      meta = { ...meta, ...msg.value }
      writeStatus(true)
      return
    }
    if (msg.type === 'shutdown') {
      void shutdownAgent()
      return
    }
    if (msg.type === 'frame') {
      if (typeof msg.line !== 'string') { sendClient(sock, { type: 'error', message: 'invalid frame' }); return }
      const line = msg.line.endsWith('\n') ? msg.line.slice(0, -1) : msg.line
      const bytes = Buffer.byteLength(line + '\n')
      if (bytes > limits.maxFrameBytes) {
        fail('outgoing frame exceeds size limit')
        return
      }
      const resp = responseId(line, frameShape)
      if (resp !== undefined) {
        const key = rpcIdKey(resp)
        const p = parked.get(key)
        inflight.delete(key)
        if (p) { clearTimeout(p.timer); parked.delete(key) }
        else if (staleIds.has(key)) {
          lastSeq += 1
          appendJournal({ seq: lastSeq, dir: 'in', line, stale: true })
          writeStatus()
          return
        }
      }
      const req = requestId(line, frameShape)
      if (req !== undefined) {
        currentAttacherRequestIds.add(rpcIdKey(req))
        if (frameShape === 'jsonrpc' && typeof req === 'number') maxRequestId = Math.max(maxRequestId, req)
      }
      lastSeq += 1
      appendJournal({ seq: lastSeq, dir: 'in', line })
      writeStatus()
      try { agent.stdin?.write(line + '\n') } catch (e) { fail(e instanceof Error ? e.message : String(e)) }
      return
    }
  }

  function bindSocket(sock: Socket) {
    let buffer = Buffer.alloc(0)
    sock.on('data', (chunk: Buffer) => {
      let offset = 0
      while (offset < chunk.length) {
        const newline = chunk.indexOf(10, offset)
        const end = newline < 0 ? chunk.length : newline
        if (buffer.length + end - offset > limits.maxFrameBytes) {
          sendClient(sock, { type: 'error', message: 'frame exceeds size limit' })
          sock.end()
          return
        }
        buffer = Buffer.concat([buffer, chunk.subarray(offset, end)])
        offset = end + 1
        if (newline < 0) break
        const line = buffer.toString('utf8')
        buffer = Buffer.alloc(0)
        if (line.trim()) onClientLine(sock, line)
      }
    })
    function parkInflight() {
      for (const [key, item] of inflight) {
        if (parked.has(key)) continue
        const parsedId = item.id
        const timer = setTimeout(() => {
          parked.delete(key)
          staleIds.add(key)
          const reply = deadlineReply(frameShape, parsedId)
          lastSeq += 1
          appendJournal({ seq: lastSeq, dir: 'in', line: reply })
          try { agent.stdin?.write(reply + '\n') } catch { /* gone */ }
          writeStatus()
        }, limits.parkedDeadlineMs)
        parked.set(key, { seq: item.seq, line: item.line, id: item.id, timer })
      }
      inflight.clear()
    }
    sock.on('close', () => {
      if (client === sock) { client = undefined; parkInflight() }
      writeStatus(true)
    })
    sock.on('error', () => {
      if (client === sock) { client = undefined; parkInflight() }
      writeStatus(true)
    })
  }

  try { unlinkSync(sockPath) } catch { /* */ }
  const server: Server = createServer(bindSocket)
  await new Promise<void>((resolve, reject) => {
    server.listen(sockPath, () => {
      try { chmodSync(sockPath, 0o600) } catch { /* */ }
      writeStatus(true)
      resolve()
    })
    server.on('error', reject)
  })

  process.on('SIGTERM', () => { void shutdownAgent() })
  process.on('SIGINT', () => { void shutdownAgent() })
}

const isEntry = existsSync(process.argv[1] ?? '') && (
  process.argv[1]?.endsWith('keeper.ts') || process.argv[1]?.endsWith('keeper.js')
)
if (isEntry || process.env[KEEPER_ENV.token]) {
  main().catch(err => {
    process.stderr.write(String(err) + '\n')
    process.exit(1)
  })
}

