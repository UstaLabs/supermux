import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { lstatSync, mkdirSync, realpathSync, type Stats } from 'node:fs'
import { lstat, rm, unlink } from 'node:fs/promises'
import { createConnection, type Socket } from 'node:net'
import { dirname, join, resolve } from 'node:path'
import { CoreError } from '../errors.js'
import { assertNoLinksIn, assertRealDirectory, present, readPrivateFile, realDirectoryIn, writePrivateFile } from '../private-files.js'
import { CORE_SUBCOMMANDS, selfEntry, type SelfEntry } from '../self-entry.js'
import { privateSocketPath } from '../unix-socket.js'
import { KEEPER_ENV, type KeeperLimits, type KeeperSpec, type KeeperStatus, type KeeperWelcome } from './protocol.js'
import { keeperSocketPaths } from './socket.js'

/**
 * What `connectKeeper` takes: the core's state directory (the keeper lives in `<state>/keepers/<sessionId>`), the agent to
 * keep running (`spec`), the keeper's `limits`, and where to resume the journal: `cursor` is the sequence number to replay
 * after, or `'acked'` for the last one this session acknowledged.
 * @experimental May change before 1.0.
 */
export type ConnectKeeperOptions = {
  stateDirectory: string
  sessionId: string
  spec: KeeperSpec
  limits: KeeperLimits
  cursor: number | 'acked'
}

/**
 * What the keeper sends a client, with its journal sequence number: a line of the agent's output (`frame`; `stale` when it
 * answers a request an earlier connection made, not this one), a request the agent made while nobody was attached, which
 * the keeper is holding (`parked`), or a line of the agent's stderr (`stderr`).
 * @experimental May change before 1.0.
 */
export type KeeperFrameEvent = { type: 'frame' | 'parked' | 'stderr'; seq: number; line: string; stale?: true }

/**
 * A connection to a session's keeper: what it said when it welcomed us, the agent's output as `frames`, and calls to write
 * to the agent, signal it, acknowledge frames, keep meta with the keeper, detach (leaving the agent running) or shut it down.
 * @experimental May change before 1.0.
 */
export type KeeperConnection = {
  welcome: KeeperWelcome
  frames: AsyncIterable<KeeperFrameEvent>
  write(line: string): void
  ack(seq: number): void
  setMeta(value: Record<string, unknown>): void
  detach(): Promise<void>
  shutdown(): Promise<void>
  /** Delivers a signal to the agent process (the keeper stays up and reports its exit). */
  signal(name: 'SIGINT'): void
  onFrame(cb: (event: KeeperFrameEvent) => void): void
  /** Records what the running agent now carries (after a change applied without a relaunch). */
  setFingerprint(fingerprint: string): Promise<void>
}

const sessionLocks = new Map<string, Promise<unknown>>()

function serialize<T>(sessionId: string, fn: () => Promise<T>): Promise<T> {
  const prev = sessionLocks.get(sessionId) ?? Promise.resolve()
  const run = prev.then(fn, fn)
  sessionLocks.set(sessionId, run.then(() => undefined, () => undefined))
  return run
}

function requireString(name: string, v: unknown): string {
  if (typeof v !== 'string' || v === '') throw new TypeError(`invalid ${name}`)
  return v
}

function validateLimits(limits: KeeperLimits): KeeperLimits {
  if (!limits || typeof limits !== 'object') throw new TypeError('invalid limits')
  for (const key of ['maxFrameBytes', 'shutdownTimeoutMs', 'parkedDeadlineMs', 'journalMaxBytes', 'connectTimeoutMs'] as const) {
    const v = limits[key]
    if (typeof v !== 'number' || !Number.isFinite(v) || v < 0 || !Number.isInteger(v)) throw new TypeError(`invalid limits.${key}`)
  }
  if (limits.maxFrameBytes < 1) throw new TypeError('invalid limits.maxFrameBytes')
  if (limits.journalMaxBytes < 1) throw new TypeError('invalid limits.journalMaxBytes')
  if (limits.connectTimeoutMs < 1) throw new TypeError('invalid limits.connectTimeoutMs')
  return limits
}

function validateSpec(spec: KeeperSpec): KeeperSpec {
  requireString('spec.command', spec.command)
  requireString('spec.cwd', spec.cwd)
  if (!Array.isArray(spec.args) || spec.args.some(a => typeof a !== 'string')) throw new TypeError('invalid spec.args')
  if (!spec.env || typeof spec.env !== 'object') throw new TypeError('invalid spec.env')
  if (spec.frameShape !== 'jsonrpc' && spec.frameShape !== 'claude-control' && spec.frameShape !== 'ndjson') throw new TypeError('invalid spec.frameShape')
  if (typeof spec.captureStderr !== 'boolean') throw new TypeError('spec.captureStderr is required')
  if (spec.fingerprint !== undefined && typeof spec.fingerprint !== 'string') throw new TypeError('invalid spec.fingerprint')
  return spec
}

function alive(pid: number): boolean {
  if (!Number.isInteger(pid) || pid < 1) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

/**
 * The keeper directory of session `sessionId`, `<real state directory>/keepers/<sessionId>`: like `keepers`, a
 * directory of this user's that no link leads to, with no link in it (an agent able to write the state directory could
 * have planted one for the keeper to follow). `create`: made (0700) when missing; else undefined when missing.
 */
function keeperDirectory(stateDirectory: string, sessionId: string, create: true): string
function keeperDirectory(stateDirectory: string, sessionId: string, create: false): string | undefined
function keeperDirectory(stateDirectory: string, sessionId: string, create: boolean): string | undefined {
  if (create) mkdirSync(stateDirectory, { recursive: true, mode: 0o700 })
  else if (!present(stateDirectory)) return undefined
  const state = realpathSync(stateDirectory)
  let dir: string
  if (create) dir = realDirectoryIn(realDirectoryIn(state, 'keepers'), sessionId)
  else {
    const keepers = join(state, 'keepers')
    dir = join(keepers, sessionId)
    if (!present(keepers)) return undefined
    assertRealDirectory(keepers)
    if (!present(dir)) return undefined
    assertRealDirectory(dir)
  }
  assertNoLinksIn(dir)
  return dir
}

/** The keeper's status; undefined when there is none or it does not parse (refused when it is a link). */
async function readStatus(path: string): Promise<KeeperStatus | undefined> {
  const text = readPrivateFile(path)
  try { return text === undefined ? undefined : JSON.parse(text) as KeeperStatus } catch { return undefined }
}

/** The socket of the keeper `status` describes: the one it recorded, else (an older keeper) `<dir>/keeper.sock`. */
function socketOf(dir: string, status: KeeperStatus | undefined): string {
  return typeof status?.socket === 'string' && status.socket ? status.socket : join(dir, 'keeper.sock')
}

function keeperEntry(): SelfEntry {
  return selfEntry(import.meta.url, 'keeper', CORE_SUBCOMMANDS.keeper)
}

/** Owned by this user (anything is, where there are no uids). */
function mine(info: Stats): boolean {
  return typeof process.getuid !== 'function' || info.uid === process.getuid()
}

/**
 * Whether `path` may be the socket of this user's keeper for `dir`: a socket this user owns which,
 * outside the keeper directory, also sits in a 0700 directory this user owns (not a link to one).
 * A short path under a shared temp dir is predictable: another user could make its directory (once a
 * reboot emptied it, say) and listen there, to be sent a session's token.
 */
function trustedSocket(dir: string, path: string): boolean {
  try {
    const socket = lstatSync(path)
    if (!socket.isSocket() || !mine(socket)) return false
    if (resolve(dirname(path)) === resolve(dir)) return true
    const parent = lstatSync(dirname(path))
    return parent.isDirectory() && mine(parent) && (parent.mode & 0o777) === 0o700
  } catch { return false }
}

/** The socket a new keeper of `dir` listens on: the first candidate whose directory this user can make private. */
function newSocketPath(dir: string): string {
  return privateSocketPath(keeperSocketPaths(dir), join(dir, 'keeper.sock'))
}

/** Deletes the socket a keeper left at `path` when it is this user's; anything else there stays (the path comes from a status file). */
async function removeSocket(path: string) {
  try {
    const info = await lstat(path)
    if (info.isSocket() && mine(info)) await unlink(path)
  } catch { /* gone */ }
}

/** Replaces a file of keeper directory `dir`, checking the directory before and right before the rename. */
function writeKeeperFile(dir: string, path: string, body: string) {
  const verify = () => assertRealDirectory(dir)
  verify()
  writePrivateFile(path, body, verify)
}

async function reap(dir: string, status: KeeperStatus | undefined, statusPath: string, sockPath: string) {
  await removeSocket(sockPath)
  const marked = { ...(status ?? {}), reaped: true, updatedAt: Date.now() }
  writeKeeperFile(dir, statusPath, JSON.stringify(marked) + '\n')
}

function sleep(ms: number) { return new Promise(r => setTimeout(r, ms)) }

async function waitPidDead(pid: number, timeoutMs: number) {
  const start = Date.now()
  while (alive(pid) && Date.now() - start < timeoutMs) await sleep(20)
}

async function reapSpawned(pid: number, sockPath: string, statusPath: string, shutdownTimeoutMs: number) {
  try { process.kill(pid, 'SIGTERM') } catch { /* */ }
  await waitPidDead(pid, shutdownTimeoutMs)
  if (alive(pid)) {
    try { process.kill(pid, 'SIGKILL') } catch { /* */ }
    await waitPidDead(pid, shutdownTimeoutMs + 2000)
  }
  try { await unlink(sockPath) } catch { /* */ }
  try { await unlink(statusPath) } catch { /* */ }
}

function readFingerprint(path: string): string | undefined {
  return readPrivateFile(path)?.trim()
}

/** The limits stopping a keeper no process is attached to takes. */
export type StopKeeperLimits = Pick<KeeperLimits, 'shutdownTimeoutMs' | 'connectTimeoutMs'>

/**
 * Stops a running keeper (one whose agent was launched for another fingerprint, or a detached one):
 * SIGTERM makes the keeper shut its agent down (SIGTERM, then SIGKILL after shutdownTimeoutMs) and exit.
 */
async function retireKeeper(status: KeeperStatus, limits: StopKeeperLimits) {
  try { process.kill(status.keeperPid, 'SIGTERM') } catch { /* already gone */ }
  await waitPidDead(status.keeperPid, limits.shutdownTimeoutMs + limits.connectTimeoutMs + 2000)
  if (alive(status.keeperPid)) {
    try { process.kill(status.keeperPid, 'SIGKILL') } catch { /* */ }
    await waitPidDead(status.keeperPid, 2000)
  }
  if (alive(status.agentPid)) {
    try { process.kill(status.agentPid, 'SIGTERM') } catch { /* */ }
    await waitPidDead(status.agentPid, limits.shutdownTimeoutMs)
    if (alive(status.agentPid)) { try { process.kill(status.agentPid, 'SIGKILL') } catch { /* */ } await waitPidDead(status.agentPid, 2000) }
  }
  if (alive(status.keeperPid) || alive(status.agentPid)) throw new Error(`keeper ${status.keeperPid} or its agent ${status.agentPid} did not stop`)
}

type KeeperState = 'up' | 'down' | 'unknown'

/**
 * Whether the keeper `status` describes runs. "up": its pid is alive and its trusted socket took a
 * connection. "down": no status, a dead pid, no trusted socket, or one that refuses or is gone (left
 * by a keeper killed outright; Bun reports every failed connect as ENOENT). "unknown": the socket
 * did not answer either way within `connectTimeoutMs`. Only "up" shows the pid is still that keeper's,
 * so nothing else is ever signalled.
 */
function probeKeeper(dir: string, status: KeeperStatus | undefined, connectTimeoutMs: number): Promise<KeeperState> {
  if (!status || !alive(status.keeperPid)) return Promise.resolve('down')
  const path = socketOf(dir, status)
  if (!trustedSocket(dir, path)) return Promise.resolve('down')
  return new Promise(settle => {
    const probe = createConnection(path)
    let timer: ReturnType<typeof setTimeout> | undefined
    const done = (state: KeeperState) => { clearTimeout(timer); probe.destroy(); settle(state) }
    timer = setTimeout(() => done('unknown'), connectTimeoutMs)
    probe.once('connect', () => done('up'))
    probe.once('error', (error: NodeJS.ErrnoException) => done(error.code === 'ECONNREFUSED' || error.code === 'ENOENT' ? 'down' : 'unknown'))
  })
}

/**
 * What stopKeeper (and a launch that has to replace a running one) refuses with when the keeper a status names did
 * not answer on its socket either way (probe "unknown"): it may still run, and nothing was signalled. A `CoreError`
 * with the code `keeper_unconfirmed`, for a host to match; `message` says what was not stopped (by default the
 * keeper), and the core says more when its close stopped the session's agent itself.
 */
export class KeeperUnconfirmed extends CoreError {
  constructor(readonly sessionId: string, readonly keeperPid: number, readonly socket: string, message?: string, options?: ErrorOptions) {
    super('keeper_unconfirmed', message ?? `The keeper of session ${sessionId} does not answer on ${socket}: it was not stopped`, options)
    this.name = 'KeeperUnconfirmed'
  }
}

/** A session id that names one directory under `keepers/` (these remove and signal what they find there). */
function requireSessionId(v: unknown): string {
  const id = requireString('sessionId', v)
  if (id === '.' || id === '..' || /[\\/]/.test(id)) throw new TypeError('invalid sessionId')
  return id
}

/**
 * Stops the keeper of session `sessionId`, with its agent, when one runs; starts nothing (for a
 * session no process is attached to). Resolves true once both are gone, false when none ran.
 */
export function stopKeeper(options: { stateDirectory: string; sessionId: string; limits: StopKeeperLimits }): Promise<boolean> {
  const stateDirectory = requireString('stateDirectory', options.stateDirectory)
  const sessionId = requireSessionId(options.sessionId)
  const limits = options.limits
  return serialize(sessionId, async () => {
    const dir = keeperDirectory(stateDirectory, sessionId, false)
    if (!dir) return false
    const status = await readStatus(join(dir, 'status.json'))
    if (!status) return false
    const state = await probeKeeper(dir, status, limits.connectTimeoutMs)
    if (state === 'down') return false
    if (state === 'unknown') throw new KeeperUnconfirmed(sessionId, status.keeperPid, socketOf(dir, status))
    await retireKeeper(status, limits)
    // A keeper killed outright leaves its socket behind.
    await removeSocket(socketOf(dir, status))
    return true
  })
}

/**
 * Deletes the keeper directory of session `sessionId` (journal, token, status) and the socket its
 * keeper left elsewhere, unless that keeper runs: resolves true once they are gone (or there were
 * none), false, deleting nothing, while it runs.
 */
export function removeKeeper(options: { stateDirectory: string; sessionId: string; connectTimeoutMs: number }): Promise<boolean> {
  const stateDirectory = requireString('stateDirectory', options.stateDirectory)
  const sessionId = requireSessionId(options.sessionId)
  return serialize(sessionId, async () => {
    // Never deleted through a link (it would delete what the link leads to).
    const dir = keeperDirectory(stateDirectory, sessionId, false)
    if (!dir) return true
    const status = await readStatus(join(dir, 'status.json'))
    // One that does not answer may be running: kept.
    if ((await probeKeeper(dir, status, options.connectTimeoutMs)) !== 'down') return false
    if (status) await removeSocket(socketOf(dir, status))
    await rm(dir, { recursive: true, force: true })
    return true
  })
}

/**
 * The `AgentDriver` methods for sessions no process is attached to, of a driver whose agents run
 * behind keepers in `stateDirectory` (every built-in driver).
 */
export function detachedKeeperHooks(stateDirectory: string, limits: StopKeeperLimits) {
  return {
    async shutdownDetached(sessionId: string): Promise<void> {
      await stopKeeper({ stateDirectory, sessionId, limits })
    },
    forgetDetached(sessionId: string): Promise<boolean> {
      return removeKeeper({ stateDirectory, sessionId, connectTimeoutMs: limits.connectTimeoutMs })
    },
  }
}

/** Waits for the new keeper's socket: a socket of this user's at `sockPath` (never connected to through a link). */
async function waitForSocket(dir: string, sockPath: string, timeoutMs: number) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    if (trustedSocket(dir, sockPath)) return
    await sleep(20)
  }
  throw new Error('keeper socket did not appear')
}

function connectSock(sockPath: string): Promise<Socket> {
  return new Promise((resolve, reject) => {
    const sock = createConnection(sockPath)
    sock.once('connect', () => resolve(sock))
    sock.once('error', reject)
  })
}

/** True when a keeper for the session is alive with its agent still running (no spawn, no connect). */
export async function keeperAgentRunning(stateDirectory: string, sessionId: string): Promise<boolean> {
  const dir = keeperDirectory(stateDirectory, sessionId, false)
  if (!dir) return false
  const status = await readStatus(join(dir, 'status.json'))
  return !!status && alive(status.keeperPid) && trustedSocket(dir, socketOf(dir, status)) && status.agentExited === undefined
}

/**
 * Connects to the keeper of session `options.sessionId`, which keeps `options.spec`'s agent running between connections:
 * re-attaches to the one that runs it (stopping it first when it was launched for another `spec.fingerprint`), else starts
 * a keeper and its agent; then replays the journal from `options.cursor`.
 * @experimental With the types it takes and returns: may change before 1.0.
 */
export function connectKeeper(options: ConnectKeeperOptions): Promise<KeeperConnection> {
  const stateDirectory = requireString('stateDirectory', options.stateDirectory)
  const sessionId = requireString('sessionId', options.sessionId)
  if (options.cursor !== 'acked' && (typeof options.cursor !== 'number' || !Number.isInteger(options.cursor) || options.cursor < 0)) throw new TypeError('invalid cursor')
  const spec = validateSpec(options.spec)
  const limits = validateLimits(options.limits)
  return serialize(sessionId, () => connectLocked({ stateDirectory, sessionId, spec, limits, cursor: options.cursor }))
}

async function connectLocked(options: {
  stateDirectory: string; sessionId: string; spec: KeeperSpec; limits: KeeperLimits; cursor: number | 'acked'
}): Promise<KeeperConnection> {
  // Checked before anything is read, written, started or connected to; its real path is what the keeper gets.
  const dir = keeperDirectory(options.stateDirectory, options.sessionId, true)
  const statusPath = join(dir, 'status.json')
  const tokenPath = join(dir, 'token')
  const fingerprintPath = join(dir, 'fingerprint')

  let spawnedPid: number | undefined
  let spawnedChild: ReturnType<typeof spawn> | undefined
  let status = await readStatus(statusPath)
  // The socket of the keeper the status describes; one started below listens on newSocketPath(dir).
  // Only a trusted socket is ever connected to (it is sent the session's token).
  let sockPath = socketOf(dir, status)
  if (status && alive(status.keeperPid) && !trustedSocket(dir, sockPath)) {
    // Socket gone (or not one to trust) but pid alive: the keeper is finishing (it unlinks the
    // socket before exit), or the pid belongs to another process by now.
    const start = Date.now()
    while (alive(status.keeperPid) && Date.now() - start < options.limits.connectTimeoutMs) await sleep(20)
  }
  if (status && alive(status.keeperPid) && trustedSocket(dir, sockPath) && status.agentExited === undefined && options.spec.fingerprint !== undefined) {
    // A running agent launched for something else (another session context) is never re-attached:
    // the caller asked for a process with the new launch, so the old one is stopped first, and only
    // once its socket answered (the pid may belong to another process by now).
    const recorded = readFingerprint(fingerprintPath)
    if (recorded !== undefined && recorded !== options.spec.fingerprint) {
      const state = await probeKeeper(dir, status, options.limits.connectTimeoutMs)
      if (state === 'unknown') {
        throw new KeeperUnconfirmed(options.sessionId, status.keeperPid, sockPath,
          `The keeper of session ${options.sessionId} does not answer on ${sockPath}: its outdated launch was not stopped`)
      }
      if (state === 'up') await retireKeeper(status, options.limits)
      status = await readStatus(statusPath)
    }
  }
  if (status && alive(status.keeperPid) && trustedSocket(dir, sockPath) && status.agentExited === undefined) {
    // reuse a live keeper whose agent is still running
  } else {
    if (status && alive(status.keeperPid) && status.agentExited !== undefined) {
      // Agent already exited: the keeper is finishing; wait for it before reaping.
      const start = Date.now()
      while (alive(status.keeperPid) && Date.now() - start < options.limits.connectTimeoutMs) await sleep(20)
    }
    if (status && !alive(status.keeperPid) && alive(status.agentPid)) {
      throw new Error(`orphan agent pid ${status.agentPid}: keeper is dead but the app-server is still running`)
    }
    if (status && status.keeperPid && !alive(status.keeperPid)) await reap(dir, status, statusPath, sockPath)
    sockPath = newSocketPath(dir)
    const token = randomBytes(32).toString('hex')
    const entry = keeperEntry()
    const child = spawn(entry.command, entry.args, {
      detached: true,
      stdio: 'ignore',
      cwd: entry.cwd,
      env: {
        ...process.env,
        [KEEPER_ENV.sessionDir]: dir,
        [KEEPER_ENV.command]: options.spec.command,
        [KEEPER_ENV.args]: JSON.stringify(options.spec.args),
        [KEEPER_ENV.cwd]: options.spec.cwd,
        [KEEPER_ENV.agentEnv]: JSON.stringify(options.spec.env),
        [KEEPER_ENV.token]: token,
        [KEEPER_ENV.limits]: JSON.stringify(options.limits),
        [KEEPER_ENV.frameShape]: options.spec.frameShape,
        [KEEPER_ENV.captureStderr]: String(options.spec.captureStderr),
        [KEEPER_ENV.socket]: sockPath,
        ...(options.spec.env.SUPERMUX_KEEPER_SKIP_STATUS
          ? { SUPERMUX_KEEPER_SKIP_STATUS: options.spec.env.SUPERMUX_KEEPER_SKIP_STATUS }
          : {}),
      },
    })
    spawnedPid = child.pid
    spawnedChild = child
    if (!spawnedPid) throw new Error('keeper spawn produced no pid')
    try {
      if (options.spec.fingerprint !== undefined) writeKeeperFile(dir, fingerprintPath, options.spec.fingerprint + '\n')
      else await unlink(fingerprintPath).catch(() => {})
      await waitForSocket(dir, sockPath, options.limits.connectTimeoutMs)
      // The keeper creates the socket before it writes status.json; wait for the status
      // of THIS keeper so shutdown()/reuse decisions never act on a stale predecessor's file.
      const started = Date.now()
      for (;;) {
        status = await readStatus(statusPath)
        if (status && status.keeperPid === spawnedPid) break
        if (Date.now() - started > options.limits.connectTimeoutMs) throw new Error('keeper status did not appear')
        await sleep(20)
      }
    } catch (err) {
      await reapSpawned(spawnedPid, sockPath, statusPath, options.limits.shutdownTimeoutMs)
      throw err
    }
  }

  try {
  const token = readPrivateFile(tokenPath)?.trim()
  if (!token) throw new Error(`The keeper of session ${options.sessionId} left no token in ${tokenPath}`)
  const sock = await connectSock(sockPath)

  const queue: Array<KeeperFrameEvent | { err: string } | { done: true }> = []
  const waiters: Array<(v: typeof queue[number]) => void> = []
  const frameCbs: Array<(e: KeeperFrameEvent) => void> = []
  let closed = false
  let exitCode: number | null | undefined
  let welcome!: KeeperWelcome
  let replaced = false
  let welcomeSettled = false

  function push(item: typeof queue[number]) {
    const w = waiters.shift()
    if (w) w(item)
    else queue.push(item)
  }

  function send(obj: object) {
    if (sock.destroyed) throw new Error('keeper socket closed')
    sock.write(JSON.stringify(obj) + '\n')
  }

  const welcomeP = new Promise<KeeperWelcome>((resolve, reject) => {
    let buffer = Buffer.alloc(0)
    const failWelcome = (err: Error) => {
      if (welcomeSettled) { push({ err: err.message }); return }
      welcomeSettled = true
      reject(err)
    }
    sock.on('data', (chunk: Buffer) => {
      let offset = 0
      while (offset < chunk.length) {
        const newline = chunk.indexOf(10, offset)
        const end = newline < 0 ? chunk.length : newline
        buffer = Buffer.concat([buffer, chunk.subarray(offset, end)])
        offset = end + 1
        if (newline < 0) break
        const raw = buffer.toString('utf8').trim()
        buffer = Buffer.alloc(0)
        if (!raw) continue
        let msg: any
        try { msg = JSON.parse(raw) } catch (e) { failWelcome(e instanceof Error ? e : new Error(String(e))); return }
        if (msg.type === 'welcome') {
          welcome = msg
          if (!welcomeSettled) { welcomeSettled = true; resolve(msg) }
          continue
        }
        if (msg.type === 'error') {
          const message = typeof msg.message === 'string' ? msg.message : 'keeper error'
          if (!welcomeSettled) failWelcome(new Error(message))
          else push({ err: message })
          sock.end()
          continue
        }
        if (msg.type === 'replaced') { replaced = true; push({ err: 'replaced' }); sock.end(); continue }
        if (msg.type === 'exit') { exitCode = msg.code ?? null; push({ done: true }); continue }
        if (msg.type === 'frame' || msg.type === 'parked' || msg.type === 'stderr') {
          const ev: KeeperFrameEvent = { type: msg.type, seq: msg.seq, line: msg.line }
          if (msg.stale === true) ev.stale = true
          for (const cb of frameCbs) cb(ev)
          push(ev)
        }
      }
    })
    sock.on('error', err => failWelcome(err instanceof Error ? err : new Error(String(err))))
    sock.on('close', () => {
      closed = true
      if (!welcomeSettled) {
        welcomeSettled = true
        reject(new Error('socket closed'))
      }
      push({ done: true })
    })
  })

  send({ type: 'hello', token, cursor: options.cursor })
  const t = setTimeout(() => { sock.destroy(new Error('welcome timeout')) }, options.limits.connectTimeoutMs)
  try {
    await welcomeP
  } catch (err) {
    if (spawnedPid) {
      try { sock.destroy() } catch { /* */ }
      await reapSpawned(spawnedPid, sockPath, statusPath, options.limits.shutdownTimeoutMs)
    }
    throw err
  } finally { clearTimeout(t) }

  const frames: AsyncIterable<KeeperFrameEvent> = {
    [Symbol.asyncIterator]() {
      return {
        async next(): Promise<IteratorResult<KeeperFrameEvent>> {
          const item = queue.length ? queue.shift()! : await new Promise<typeof queue[number]>(r => waiters.push(r))
          if (item && typeof item === 'object' && 'err' in item) throw new Error(item.err)
          if ('done' in item && item.done) return { done: true, value: undefined }
          return { done: false, value: item as KeeperFrameEvent }
        },
      }
    },
  }

  const conn: KeeperConnection = {
    welcome,
    frames,
    write(line: string) {
      if (typeof line !== 'string') throw new TypeError('invalid line')
      send({ type: 'frame', line })
    },
    ack(seq: number) {
      if (typeof seq !== 'number' || !Number.isInteger(seq) || seq < 0) throw new TypeError('invalid ack seq')
      send({ type: 'ack', seq })
    },
    setMeta(value: Record<string, unknown>) {
      if (!value || typeof value !== 'object' || Array.isArray(value)) throw new TypeError('invalid meta')
      send({ type: 'meta', value })
    },
    async detach() {
      // The keeper may already be gone (it exits right after its agent, or crashed) without an
      // `exit` frame: then 'close' has already fired and waiting for it would never settle.
      if (closed) return
      if (!sock.destroyed) sock.end()
      await new Promise<void>(r => { if (closed) r(); else sock.once('close', () => r()) })
    },
    async shutdown() {
      // A destroyed socket means the keeper already went away (or is finishing); do not throw,
      // just wait for its process to be gone so a same-id reopen cannot race it.
      if (!sock.destroyed) send({ type: 'shutdown' })
      const start = Date.now()
      const keeperPid = (await readStatus(statusPath))?.keeperPid ?? status?.keeperPid ?? 0
      // Resolve only once the keeper PROCESS is gone (not merely the socket): a same-id
      // connect right after shutdown must not find a dying keeper's pid still alive.
      while (Date.now() - start < options.limits.shutdownTimeoutMs + options.limits.connectTimeoutMs + 2000) {
        if (!alive(keeperPid)) return
        await sleep(20)
      }
      throw new Error('keeper shutdown timed out')
    },
    signal(name: 'SIGINT') {
      if (name !== 'SIGINT') throw new TypeError('invalid signal')
      send({ type: 'signal', signal: name })
    },
    onFrame(cb) { frameCbs.push(cb) },
    async setFingerprint(fingerprint: string) {
      if (typeof fingerprint !== 'string') throw new TypeError('invalid fingerprint')
      writeKeeperFile(dir, fingerprintPath, fingerprint + '\n')
    },
  }
  spawnedChild?.unref()
  return conn
  } catch (err) {
    if (spawnedPid) {
      await reapSpawned(spawnedPid, sockPath, statusPath, options.limits.shutdownTimeoutMs)
    }
    throw err
  }
}

