import { afterEach, expect, test, setDefaultTimeout } from 'bun:test'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createCore } from '../src/index.js'
import { acp } from '../src/acp/index.js'
import { grok } from '../src/agents/index.js'
import type { CoreEvent, DriverContext } from '../src/types.js'

setDefaultTimeout(25_000)
const fixture = fileURLToPath(new URL('./fixtures/acp-agent.mjs', import.meta.url))
const grokFixture = fileURLToPath(new URL('./fixtures/grok-agent.mjs', import.meta.url))
const attachScript = fileURLToPath(new URL('./fixtures/acp-keeper-attach.mjs', import.meta.url))
const dirs: string[] = []

function alive(pid: number) {
  if (!Number.isInteger(pid) || pid < 1) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

async function waitDead(pid: number, ms = 3000) {
  const start = Date.now()
  while (Date.now() - start < ms) {
    if (!alive(pid)) return
    await new Promise(r => setTimeout(r, 20))
  }
}

function keeperLimits() {
  return { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 }
}

function classifyActivity(update: { protocol: string; value: any }) {
  const from = (value: unknown) => {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return
    const rec = value as Record<string, unknown>
    if (rec.sessionUpdate !== 'user_message_chunk' && rec.sessionUpdate !== 'turn_completed') return
    const id = typeof rec.prompt_id === 'string' ? rec.prompt_id : undefined
    return { phase: rec.sessionUpdate === 'user_message_chunk' ? 'started' as const : 'completed' as const, ...(id ? { id } : {}) }
  }
  if (update.protocol === 'acp') return from(update.value)
  const frame = update.value as { method?: string; params?: { update?: unknown } }
  if (frame?.method !== '_x.ai/session_notification' && frame?.method !== '_x.ai/session/update') return
  return from(frame.params?.update)
}

function libraryAcp(extra: Record<string, unknown> = {}) {
  return acp({
    id: 'fixture',
    command: process.execPath,
    args: [fixture],
    inheritEnv: true,
    mcpServers: [],
    setupTimeoutMs: 5000,
    shutdownTimeoutMs: 500,
    maxFrameBytes: 16 * 1024 * 1024,
    maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250,
    cancelRetryTimeoutMs: 10_000,
    captureStderr: false,
    classifyActivity,
    permissions: { kind: 'acp', policy: 'ask', nativeMode: null },
    ...extra,
  } as Parameters<typeof acp>[0])
}

function ctx(extra: Partial<DriverContext> = {}): DriverContext {
  return {
    sessionId: 'k1',
    cwd: process.cwd(),
    signal: new AbortController().signal,
    onUpdate() {},
    onExit() {},
    requestPermission: async () => ({ outcome: { outcome: 'cancelled' } }),
    requestAnswers: async () => ({ outcome: 'cancelled' as const }),
    ...extra,
  }
}

afterEach(async () => {
  for (const dir of dirs.splice(0)) {
    try { await rm(dir, { recursive: true, force: true }) } catch { /* */ }
  }
})

async function runAttach(env: Record<string, string>) {
  const child = spawn(process.execPath, [attachScript], {
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let out = '', err = ''
  child.stdout.on('data', d => { out += d })
  child.stderr.on('data', d => { err += d })
  const code: number = await new Promise(resolve => child.on('close', c => resolve(c ?? 1)))
  if (code !== 0) throw new Error(`attach script exited ${code}: ${err}\n${out}`)
  return JSON.parse(out.trim().split('\n').filter(Boolean).at(-1)!)
}

test('process death leaves keeper; reattach skips handshake and ends owned prompt via stale response', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ak-reattach-'))
  dirs.push(stateDirectory)
  const trace = join(stateDirectory, 'trace')
  const info = await runAttach({
    KEEPER_STATE_DIR: stateDirectory,
    KEEPER_SESSION_ID: 'k1',
    KEEPER_PROMPT: 'live-hang',
    ACP_FIXTURE: fixture,
    TRACE: trace,
    TURN_ID: 'live-turn',
    FINISH_AFTER_MS: '400',
  })
  expect(typeof info.ackedSeq).toBe('number')
  expect(alive(info.keeperPid)).toBe(true)
  expect(alive(info.agentPid)).toBe(true)

  const activity: Array<{ id: string; phase: string }> = []
  const r = await libraryAcp({
    env: { TRACE: trace, TURN_ID: 'live-turn' },
    keeper: { stateDirectory, limits: keeperLimits() },
  }).open(ctx({
    sessionId: 'k1',
    resumeId: info.agentSessionId,
    onActivity: n => activity.push({ ...n }),
  }))
  try {
    const lines = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l))
    expect(lines.filter((x: any) => x.protocolVersion || x.clientInfo).length).toBe(1)
    expect(lines.filter((x: any) => x === 'new').length).toBe(1)
    for (let i = 0; i < 80 && !activity.some(a => a.phase === 'started'); i++) await new Promise(x => setTimeout(x, 20))
    expect(activity.some(a => a.phase === 'started')).toBe(true)
    const start = Date.now()
    while (Date.now() - start < 5000 && !activity.some(a => a.phase === 'completed')) await new Promise(x => setTimeout(x, 30))
    expect(activity.some(a => a.phase === 'completed')).toBe(true)
  } finally {
    await r.close({ mode: 'shutdown' })
  }
  await waitDead(info.keeperPid)
  await waitDead(info.agentPid)
})

test('parked permission is delivered to the reattached host', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ak-park-'))
  dirs.push(stateDirectory)
  const trace = join(stateDirectory, 'trace')
  const info = await runAttach({
    KEEPER_STATE_DIR: stateDirectory,
    KEEPER_SESSION_ID: 'k1',
    KEEPER_PROMPT: 'permission',
    ACP_FIXTURE: fixture,
    TRACE: trace,
  })
  expect(alive(info.keeperPid)).toBe(true)

  let asked = false
  const r = await libraryAcp({
    env: { TRACE: trace },
    keeper: { stateDirectory, limits: keeperLimits() },
  }).open(ctx({
    sessionId: 'k1',
    resumeId: info.agentSessionId,
    requestPermission: async () => {
      asked = true
      return { outcome: { outcome: 'selected', optionId: 'allow' } }
    },
  }))
  try {
    const start = Date.now()
    while (Date.now() - start < 5000) {
      const raw = await readFile(trace, 'utf8').catch(() => '')
      if (asked && raw.includes('allow')) break
      await new Promise(x => setTimeout(x, 30))
    }
    expect(asked).toBe(true)
  } finally {
    await r.close({ mode: 'shutdown' })
  }
})

test('detach keeps keeper and agent; later open re-attaches', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ak-detach-'))
  dirs.push(stateDirectory)
  const r = await libraryAcp({
    keeper: { stateDirectory, limits: keeperLimits() },
  }).open(ctx({ sessionId: 'k-detach' }))
  const statusPath = join(stateDirectory, 'keepers', 'k-detach', 'status.json')
  const status = JSON.parse(await readFile(statusPath, 'utf8'))
  expect(alive(status.keeperPid)).toBe(true)
  expect(alive(status.agentPid)).toBe(true)
  const sid = r.agentSessionId
  await r.close({ mode: 'detach' })
  expect(alive(status.keeperPid)).toBe(true)
  expect(alive(status.agentPid)).toBe(true)
  const r2 = await libraryAcp({
    keeper: { stateDirectory, limits: keeperLimits() },
  }).open(ctx({ sessionId: 'k-detach', resumeId: sid }))
  expect(r2.agentSessionId).toBe(sid)
  await r2.close({ mode: 'shutdown' })
  await waitDead(status.keeperPid)
  await waitDead(status.agentPid)
})

test('shutdown kills keeper and agent', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ak-shut-'))
  dirs.push(stateDirectory)
  const r = await libraryAcp({
    keeper: { stateDirectory, limits: keeperLimits() },
  }).open(ctx({ sessionId: 'k-shut' }))
  const status = JSON.parse(await readFile(join(stateDirectory, 'keepers', 'k-shut', 'status.json'), 'utf8'))
  await r.close({ mode: 'shutdown' })
  await waitDead(status.keeperPid)
  await waitDead(status.agentPid)
  expect(alive(status.keeperPid)).toBe(false)
  expect(alive(status.agentPid)).toBe(false)
})

test('orphan agent with dead keeper rejects open naming the pid', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ak-orphan-'))
  dirs.push(stateDirectory)
  const sessionId = 'orphan'
  const keepers = join(stateDirectory, 'keepers', sessionId)
  await mkdir(keepers, { recursive: true })
  const orphan = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' })
  const agentPid = orphan.pid!
  try {
    await writeFile(join(keepers, 'status.json'), JSON.stringify({
      keeperPid: 999999,
      agentPid,
      startedAt: 1,
      lastSeq: 0,
      firstSeq: 1,
      ackedSeq: 0,
      updatedAt: 1,
      meta: {},
    }) + '\n')
    await expect(libraryAcp({
      keeper: { stateDirectory, limits: keeperLimits() },
    }).open(ctx({ sessionId }))).rejects.toThrow(String(agentPid))
  } finally {
    try { process.kill(agentPid, 'SIGKILL') } catch { /* */ }
    await waitDead(agentPid)
  }
})

test('Grok configure restart through the keeper keeps the native id', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ak-grok-'))
  dirs.push(stateDirectory)
  const r = await grok({
    id: 'grok',
    command: process.execPath,
    commandArgs: [grokFixture],
    permissions: { kind: 'acp', policy: 'ask', nativeMode: null },
    noLeader: true,
    inheritEnv: true,
    mcpServers: [],
    setupTimeoutMs: 5000,
    shutdownTimeoutMs: 500,
    maxFrameBytes: 16 * 1024 * 1024,
    maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250,
    cancelRetryTimeoutMs: 10_000,
    keeper: { stateDirectory, limits: keeperLimits() },
  }).open(ctx({ sessionId: 'g1' }))
  const native = r.agentSessionId
  expect(native).toBeTruthy()
  await r.configure({ model: 'grok-4' })
  expect(r.agentSessionId).toBe(native)
  await r.close({ mode: 'shutdown' })
})

async function until(done: () => boolean | Promise<boolean>, ms = 5000) {
  const start = Date.now()
  while (!(await done()) && Date.now() - start < ms) await new Promise(x => setTimeout(x, 20))
}

/** The lines the keeper journaled for a session, each way. */
async function journaled(stateDirectory: string, sessionId: string, dir: 'in' | 'out'): Promise<any[]> {
  const raw = await readFile(join(stateDirectory, 'keepers', sessionId, 'journal.ndjson'), 'utf8').catch(() => '')
  return raw.split('\n').filter(Boolean).map(l => JSON.parse(l)).filter(e => e.dir === dir).map(e => JSON.parse(e.line))
}

// Not yet for ACP: on re-attach the owned prompt's (stale) response ends the turn as soon as the keeper
// replays it, while the session/update frames before it still go through the SDK; those then arrive
// outside the turn and stay raw updates (Codex and Claude: codex-keeper / claude-keeper tests).
test.todo('a turn that ended while no host was attached arrives as normalized events on re-attach', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ak-ended-'))
  dirs.push(stateDirectory)
  const release = join(stateDirectory, 'release')
  const mk = () => createCore({
    stateDirectory: join(stateDirectory, 'core'),
    limits: { interruptTimeoutMs: 5000, maxPending: 8, outstandingActivity: 64 },
    agents: [libraryAcp({ env: { RELEASE: release }, keeper: { stateDirectory, limits: keeperLimits() } })],
  })
  const core1 = mk()
  const s1 = await core1.sessions.create({ id: 'ended', agent: 'fixture', cwd: process.cwd() })
  await s1.send({ content: [{ type: 'text', text: 'until-released' }], whenBusy: 'reject' })
  const prompted = async () => (await journaled(stateDirectory, 'ended', 'in')).some(m => m.method === 'session/prompt')
  await until(prompted)
  const started = await prompted()
  await s1.close({ mode: 'detach' })
  await core1.close({ agents: 'detach' })
  // The turn ends only now, with no host attached: the keeper journals its end (the old host had stopped reading).
  await writeFile(release, '')
  const ended = async () => (await journaled(stateDirectory, 'ended', 'out')).some(m => m.result?.stopReason === 'end_turn')
  await until(ended, 10_000)
  const endedDetached = started && await ended()

  const core2 = mk()
  const events: CoreEvent[] = []
  core2.subscribe(e => { events.push(e) })
  try {
    const s2 = await core2.sessions.resume('ended')
    expect(endedDetached).toBe(true)
    const normalized = () => events.flatMap(e => e.type === 'session.event' ? [e.event] : [])
    await until(() => normalized().some(b => b.kind === 'turn-complete'))
    const bodies = normalized()
    const kinds = bodies.map(b => b.kind)
    const start = kinds.indexOf('turn-start')
    const message = bodies.findIndex(b => b.kind === 'assistant-message' && b.text === 'héllo')
    const complete = kinds.indexOf('turn-complete')
    expect(start).toBeGreaterThanOrEqual(0)
    expect(message).toBeGreaterThan(start)
    expect(complete).toBeGreaterThan(message)
    expect(bodies[complete]).toMatchObject({ reason: 'ok' })
    // One turn, new to this host: live, not replay.
    for (const body of bodies.slice(start, complete + 1)) expect(body).toMatchObject({ turnId: bodies[start]!.turnId, replay: false, origin: 'live' })
    // In order with the raw updates, before the session reports itself resumed.
    expect(events.findIndex(e => e.type === 'session.resumed')).toBeGreaterThan(events.findIndex(e => e.type === 'session.event' && e.event.kind === 'turn-complete'))
    expect(s2.snapshot().state).toBe('idle')
    await s2.close({ mode: 'shutdown' })
  } finally {
    await core2.close({ agents: 'shutdown' })
  }
})

test('no leaked keeper after this file', async () => {
  const pids = await readdir('/proc').catch(() => [] as string[])
  for (const pid of pids) {
    if (!/^\d+$/.test(pid)) continue
    try {
      const env = await readFile(join('/proc', pid, 'environ'), 'utf8')
      if (env.includes('SUPERMUX_KEEPER_SESSION_DIR')) {
        const cwdHint = env.split('\0').find(x => x.startsWith('SUPERMUX_KEEPER_SESSION_DIR='))
        if (cwdHint && dirs.some(d => cwdHint.includes(d))) {
          throw new Error(`leaked keeper ${pid} ${cwdHint}`)
        }
      }
    } catch (e) {
      if (e instanceof Error && e.message.startsWith('leaked')) throw e
    }
  }
})

test('a different sessionConfig on re-attach is applied to the live agent; the same one is not re-sent', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ak-cfg-'))
  dirs.push(stateDirectory)
  const trace = join(stateDirectory, 'trace')
  const lines = async () => (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l))
  const r = await libraryAcp({ env: { TRACE: trace }, sessionConfig: { model: 'a' }, keeper: { stateDirectory, limits: keeperLimits() } }).open(ctx({ sessionId: 'k-cfg' }))
  const sid = r.agentSessionId
  expect((await lines()).filter(l => l.setConfig).map(l => l.setConfig.value)).toEqual(['a'])
  await r.close({ mode: 'detach' })
  const same = await libraryAcp({ env: { TRACE: trace }, sessionConfig: { model: 'a' }, keeper: { stateDirectory, limits: keeperLimits() } }).open(ctx({ sessionId: 'k-cfg', resumeId: sid }))
  expect((await lines()).filter(l => l.setConfig).map(l => l.setConfig.value)).toEqual(['a'])
  await same.close({ mode: 'detach' })
  const other = await libraryAcp({ env: { TRACE: trace }, sessionConfig: { model: 'b' }, keeper: { stateDirectory, limits: keeperLimits() } }).open(ctx({ sessionId: 'k-cfg', resumeId: sid }))
  expect((await lines()).filter(l => l.setConfig).map(l => l.setConfig.value)).toEqual(['a', 'b'])
  expect((await lines()).filter(l => l === 'new')).toHaveLength(1)
  await other.close({ mode: 'shutdown' })
})
