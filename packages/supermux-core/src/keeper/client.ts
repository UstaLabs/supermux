import { spawn } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { existsSync } from 'node:fs'
import { mkdir, readFile, unlink, writeFile } from 'node:fs/promises'
import { createConnection, type Socket } from 'node:net'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { KEEPER_ENV, type KeeperLimits, type KeeperSpec, type KeeperStatus, type KeeperWelcome } from './protocol.js'

export type ConnectKeeperOptions = {
  stateDirectory: string
  sessionId: string
  spec: KeeperSpec
  limits: KeeperLimits
  cursor: number | 'acked'
}

export type KeeperFrameEvent = { type: 'frame' | 'parked' | 'stderr'; seq: number; line: string; stale?: true }

export type KeeperConnection = {
  welcome: KeeperWelcome
  frames: AsyncIterable<KeeperFrameEvent>
  write(line: string): void
  ack(seq: number): void
  setMeta(value: Record<string, unknown>): void
  detach(): Promise<void>
  shutdown(): Promise<void>
  onFrame(cb: (event: KeeperFrameEvent) => void): void
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
  if (spec.frameShape !== 'jsonrpc' && spec.frameShape !== 'claude-control') throw new TypeError('invalid spec.frameShape')
  if (typeof spec.captureStderr !== 'boolean') throw new TypeError('spec.captureStderr is required')
  return spec
}

function alive(pid: number): boolean {
  if (!Number.isInteger(pid) || pid < 1) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

async function readStatus(path: string): Promise<KeeperStatus | undefined> {
  try { return JSON.parse(await readFile(path, 'utf8')) as KeeperStatus } catch { return undefined }
}

function keeperEntry(): string {
  const js = fileURLToPath(new URL('./keeper.js', import.meta.url))
  const ts = fileURLToPath(new URL('./keeper.ts', import.meta.url))
  if (existsSync(js)) return js
  if (existsSync(ts)) return ts
  throw new Error('keeper entry not found')
}

async function reap(dir: string, status: KeeperStatus | undefined, statusPath: string, sockPath: string) {
  try { await unlink(sockPath) } catch { /* */ }
  const marked = { ...(status ?? {}), reaped: true, updatedAt: Date.now() }
  await writeFile(statusPath, JSON.stringify(marked) + '\n')
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

async function waitForSocket(sockPath: string, timeoutMs: number) {
  const start = Date.now()
  while (Date.now() - start < timeoutMs) {
    if (existsSync(sockPath)) return
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
  const dir = join(options.stateDirectory, 'keepers', options.sessionId)
  await mkdir(dir, { recursive: true })
  const sockPath = join(dir, 'keeper.sock')
  const statusPath = join(dir, 'status.json')
  const tokenPath = join(dir, 'token')

  let spawnedPid: number | undefined
  let spawnedChild: ReturnType<typeof spawn> | undefined
  let status = await readStatus(statusPath)
  if (status && alive(status.keeperPid) && !existsSync(sockPath)) {
    // Socket gone but pid alive: the keeper is finishing (it unlinks the socket before exit).
    const start = Date.now()
    while (alive(status.keeperPid) && Date.now() - start < options.limits.connectTimeoutMs) await sleep(20)
  }
  if (status && alive(status.keeperPid) && existsSync(sockPath) && status.agentExited === undefined) {
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
    const token = randomBytes(32).toString('hex')
    const entry = keeperEntry()
    const child = spawn(process.execPath, [entry], {
      detached: true,
      stdio: 'ignore',
      cwd: dirname(entry),
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
        ...(options.spec.env.SUPERMUX_KEEPER_SKIP_STATUS
          ? { SUPERMUX_KEEPER_SKIP_STATUS: options.spec.env.SUPERMUX_KEEPER_SKIP_STATUS }
          : {}),
      },
    })
    spawnedPid = child.pid
    spawnedChild = child
    if (!spawnedPid) throw new Error('keeper spawn produced no pid')
    try {
      await waitForSocket(sockPath, options.limits.connectTimeoutMs)
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
  const token = (await readFile(tokenPath, 'utf8')).trim()
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
      if (!sock.destroyed) sock.end()
      await new Promise<void>(r => sock.once('close', () => r()))
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
    onFrame(cb) { frameCbs.push(cb) },
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

