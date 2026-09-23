import { fileURLToPath } from 'node:url'
import { afterEach, expect, test, setDefaultTimeout } from 'bun:test'
import { mkdtempSync } from 'node:fs'
import { chmod, mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { grok, opencode, type GrokOptions } from '../src/agents/index.js'
import { MAX_OUTSTANDING_ACTIVITY } from '../src/activity.js'
import { createCore } from '../src/core.js'
import type { AgentDriver, AgentRuntime, DriverContext } from '../src/types.js'
import { TEST_LIMITS, nextId } from "./helpers.js"

setDefaultTimeout(20_000)
const fixture = fileURLToPath(new URL('./fixtures/grok-agent.mjs', import.meta.url))
const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  await Promise.all(cores.splice(0).map(c => c.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true })))
})

function signal() { return new AbortController().signal }
function ctx(extra: Partial<DriverContext> = {}): DriverContext {
  return { sessionId: 'core', cwd: process.cwd(), signal: signal(), onUpdate() {}, onExit() {}, requestPermission: async () => ({ outcome: { outcome: 'cancelled' } }), requestAnswers: async () => ({ outcome: 'cancelled' as const }), ...extra }
}
function testKeeper() {
  const stateDirectory = mkdtempSync(join(tmpdir(), 'grok-k-'))
  dirs.push(stateDirectory)
  return { stateDirectory, limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } }
}
function grokRequired(extra: Partial<GrokOptions> = {}): GrokOptions {
  return {
    id: 'grok',
    command: 'grok',
    commandArgs: [],
    permissions: { kind: 'acp', policy: 'ask', nativeMode: null },
    noLeader: true,
    inheritEnv: true,
    mcpServers: [],
    setupTimeoutMs: 4000,
    shutdownTimeoutMs: 40,
    maxFrameBytes: 16 * 1024 * 1024,
    maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250,
    cancelRetryTimeoutMs: 10_000,
    keeper: { stateDirectory: '/tmp', limits: { parkedDeadlineMs: 1, journalMaxBytes: 1, connectTimeoutMs: 1 } },
    ...extra,
  }
}
function driver(env: Record<string, string> = {}, extra: Partial<GrokOptions> = {}) {
  return grok({ ...grokRequired({ command: process.execPath, commandArgs: [fixture], env, keeper: testKeeper() }), ...extra })
}
async function traced(env: Record<string, string> = {}, extra: Parameters<typeof grok>[0] = {}, context: Partial<DriverContext> = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'grok-'))
  dirs.push(dir)
  const trace = join(dir, 'trace')
  const pidFile = join(dir, 'pid')
  const r = await driver({ ...env, TRACE: trace, PID_FILE: pidFile }, extra).open(ctx(context))
  return {
    r, dir, trace, pidFile,
    lines: async () => (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l)),
  }
}
function argvs(lines: any[]) { return lines.filter(l => l.argv).map(l => l.argv as string[]) }

test('vendor ask_user_question replies accepted answers keyed by question text', async () => {
  const { r, lines } = await traced({}, {}, {
    requestAnswers: async req => {
      expect(req.questions[0]?.question).toBe('Favorite color?')
      return { outcome: 'answered', answers: { q1: 'Blue' } }
    },
  })
  try {
    expect(await r.prompt([{ type: 'text', text: 'ask-user-question' }], signal())).toEqual({ stopReason: 'end_turn' })
    const rec = (await lines()).find((row: any) => row && row.outcome === 'accepted')
    expect(rec).toMatchObject({ outcome: 'accepted', answers: { 'Favorite color?': 'Blue' } })
  } finally { await r.close({ mode: 'shutdown' }) }
})

test('defaults never auto-approve and keep library noLeader', async () => {
  expect(() => grok({ ...grokRequired(), authPath: 'relative' })).toThrow('absolute')
  expect(() => grok({ ...grokRequired(), reasoningEffort: 'max' as 'low' })).toThrow('reasoningEffort')
  expect(() => grok({ ...grokRequired(), model: '' })).toThrow('model')
  expect(() => grok({ ...grokRequired(), commandArgs: 'fixture' as unknown as string[] })).toThrow('commandArgs')
  const { r, dir, lines } = await traced()
  try {
    expect(r.capabilities).toEqual({ resume: true, steer: false, fork: false, detach: true, configure: true, history: false, permissions: true })
    expect(r.configuration()).toEqual({})
    const argv = argvs(await lines())[0]!
    expect(argv).toContain('--no-leader')
    expect(argv).not.toContain('--always-approve')
    expect(argv.at(-1)).toBe('stdio')
    expect(argv[0]).toBe('agent')
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true, force: true }) }
})

test('explicit commandArgs prefix is the fixture; command is the exact executable', async () => {
  const { r, dir, lines } = await traced()
  try {
    const argv = argvs(await lines())[0]!
    expect(argv[0]).toBe('agent')
    expect(argv).not.toContain(process.execPath)
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true, force: true }) }
})

test('explicit broker flags disable noLeader', async () => {
  const { r, dir, lines } = await traced({}, { noLeader: false, model: 'grok-4', reasoningEffort: 'high' })
  try {
    const argv = argvs(await lines())[0]!
    expect(argv).not.toContain('--no-leader')
    expect(argv).not.toContain('--always-approve')
    expect(argv).toEqual(expect.arrayContaining(['--model', 'grok-4', '--reasoning-effort', 'high']))
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true, force: true }) }
})

test('argv uses overrides over factory, configure clear restores factory, configuration is requested only', async () => {
  const { r, dir, lines } = await traced({}, { model: 'factory-model', reasoningEffort: 'low' })
  try {
    expect(r.configuration()).toEqual({})
    expect(argvs(await lines())[0]).toEqual(expect.arrayContaining(['--model', 'factory-model', '--reasoning-effort', 'low']))
    await r.configure({ model: 'session-model', reasoningEffort: 'high' })
    expect(r.configuration()).toEqual({ model: 'session-model', reasoningEffort: 'high' })
    expect(r.agentSessionId).toBe('grok-1')
    const after = argvs(await lines())
    expect(after).toHaveLength(2)
    expect(after[1]).toEqual(expect.arrayContaining(['--model', 'session-model', '--reasoning-effort', 'high']))
    expect(after[1]).not.toEqual(expect.arrayContaining(['--model', 'factory-model']))
    await r.configure({})
    expect(r.configuration()).toEqual({})
    expect(argvs(await lines())[2]).toEqual(expect.arrayContaining(['--model', 'factory-model', '--reasoning-effort', 'low']))
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true, force: true }) }
})

test('context.configuration is applied at open and exact session id is reused on restart', async () => {
  const { r, dir, lines } = await traced({}, { model: 'factory' }, { configuration: { model: 'reopen-model', reasoningEffort: 'medium' } })
  try {
    expect(r.configuration()).toEqual({ model: 'reopen-model', reasoningEffort: 'medium' })
    expect(r.agentSessionId).toBe('grok-1')
    expect(argvs(await lines())[0]).toEqual(expect.arrayContaining(['--model', 'reopen-model', '--reasoning-effort', 'medium']))
    await r.configure({ model: 'next' })
    expect(r.agentSessionId).toBe('grok-1')
    const methods = (await lines()).map(l => l.method).filter(Boolean)
    expect(methods.filter(m => m === 'newSession')).toEqual(['newSession'])
    expect(methods).toContain('loadSession')
    expect((await lines()).filter(l => l.method === 'loadSession').every(l => l.sessionId === 'grok-1')).toBe(true)
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true, force: true }) }
})

test('failed restart leaves wrapper retryable and core rollback restores previous argv', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'grok-core-'))
  dirs.push(stateDirectory)
  const trace = join(stateDirectory, 'trace')
  const failCount = join(stateDirectory, 'fail-count')
  const core = createCore({
    limits: TEST_LIMITS,
    stateDirectory,
    agents: [driver({ TRACE: trace, FAIL_OPEN_ONCE: '1', FAIL_COUNT_FILE: failCount }, { model: 'factory-model', reasoningEffort: 'low' })],
  })
  cores.push(core)
  const session = await core.sessions.create({ id: nextId(), agent: 'grok', cwd: process.cwd() })
  expect(session.configuration()).toEqual({})
  await expect(session.configure({ model: 'candidate', reasoningEffort: 'high' })).rejects.toThrow()
  expect(session.configuration()).toEqual({})
  expect(session.snapshot().state).toBe('idle')
  const receipt = await session.send({ content: [{ type: 'text', text: 'hello' }], whenBusy: 'queue' })
  expect(await receipt.completed).toMatchObject({ status: 'completed' })
  const parsed = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l))
  const argv = argvs(parsed)
  expect(argv.length).toBeGreaterThanOrEqual(3)
  expect(argv[0]).toEqual(expect.arrayContaining(['--model', 'factory-model', '--reasoning-effort', 'low']))
  expect(argv[1]).toEqual(expect.arrayContaining(['--model', 'candidate', '--reasoning-effort', 'high']))
  expect(argv.at(-1)).toEqual(expect.arrayContaining(['--model', 'factory-model', '--reasoning-effort', 'low']))
  expect(parsed.filter(l => l.method === 'newSession')).toHaveLength(1)
})

test('failed candidate open does not emit fatal session exit', async () => {
  const exits: Error[] = []
  const { r, dir } = await traced({ FAIL_OPEN_ONCE: '1' }, { model: 'factory' })
  try {
    const failCount = join(dir, 'fail-count')
    const live = await driver({ FAIL_OPEN_ONCE: '1', FAIL_COUNT_FILE: failCount }, { model: 'factory' }).open(ctx({ onExit: e => exits.push(e) }))
    await expect(live.configure({ model: 'candidate' })).rejects.toThrow()
    await new Promise(resolve => setTimeout(resolve, 50))
    expect(exits).toEqual([])
    await live.configure({ model: 'factory' })
    expect(live.agentSessionId).toBe('grok-1')
    await live.close({ mode: "shutdown" })
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true, force: true }) }
})

test('close reaps owned child after failed reopen; abort during setup reaps child', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'grok-own-'))
  const pidFile = join(dir, 'pid')
  const r = await driver({ PID_FILE: pidFile, FAIL_RESUME: '1' }).open(ctx())
  await expect(r.configure({ model: 'x' })).rejects.toThrow()
  const failedPid = Number(await readFile(pidFile, 'utf8'))
  await r.close({ mode: "shutdown" })
  expect(() => process.kill(failedPid, 0)).toThrow()
  const controller = new AbortController()
  const pending = driver({ PID_FILE: pidFile, FIXTURE_MODE: 'hang' }).open(ctx({ signal: controller.signal }))
  setTimeout(() => controller.abort(), 200)
  await expect(pending).rejects.toThrow()
  const hangPid = Number(await readFile(pidFile, 'utf8'))
  expect(() => process.kill(hangPid, 0)).toThrow()
  await rm(dir, { recursive: true, force: true })
})

test('opaque vendor extension notifications are preserved with replay on load', async () => {
  const updates: any[] = []
  const r = await driver().open(ctx({ resumeId: 'grok-1', onUpdate: u => updates.push(u) }))
  try {
    await new Promise(resolve => setTimeout(resolve, 50))
    const init = updates.find(u => u.protocol === 'native' && u.value?.method === 'initialize')
    expect(init.replay).toBeUndefined()
    expect(init.value.params).toEqual(expect.objectContaining({ protocolVersion: 1 }))
    const acpHistory = updates.find(u => u.protocol === 'acp')
    expect(acpHistory.replay).toBe(true)
    expect(acpHistory.value.content.text).toBe('history')
    const note = updates.find(u => u.protocol === 'native' && u.value?.method === '_x.ai/session_notification')
    expect(note.replay).toBe(true)
    expect(note.value.params).toEqual({ turn_completed: true, extra: { keep: true } })
    const sessionUpdate = updates.find(u => u.protocol === 'native' && u.value?.method === '_x.ai/session/update' && u.value.params?.unsolicitedturns)
    expect(sessionUpdate.replay).toBe(true)
    expect(sessionUpdate.value.params).toEqual({ unsolicitedturns: [{ id: 'u1' }] })
    const before = updates.filter(u => u.protocol === 'native' && u.value?.method === '_x.ai/session_notification').length
    await r.prompt([{ type: 'text', text: 'hello' }], signal())
    await new Promise(resolve => setTimeout(resolve, 50))
    const notes = updates.filter(u => u.protocol === 'native' && u.value?.method === '_x.ai/session_notification')
    expect(notes.length).toBeGreaterThan(before)
    expect(notes.some(u => !u.replay && u.value.params.turn_completed === true)).toBe(true)
    expect(updates.some(u => u.protocol === 'native' && u.value?.method === '_x.ai/session/update' && !u.replay)).toBe(true)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('prompt, configure, and close guard one active operation; native extras stay unsupported', async () => {
  const { r, dir } = await traced()
  try {
    const hang = r.prompt([{ type: 'text', text: 'hang' }], signal())
    await new Promise(resolve => setTimeout(resolve, 30))
    await expect(r.configure({ model: 'x' })).rejects.toMatchObject({ code: 'busy' })
    await expect(r.prompt([{ type: 'text', text: 'hello' }], signal())).rejects.toMatchObject({ code: 'busy' })
    await r.interrupt()
    expect(await hang).toEqual({ stopReason: 'cancelled' })
    expect(r.steer).toBeUndefined()
    expect(r.history).toBeUndefined()
    await expect(driver().open(ctx({ forkFrom: { agentSessionId: 'x' } }))).rejects.toMatchObject({ code: 'unsupported_operation' })
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true, force: true }) }
})

function mockChild(close: () => Promise<void>, onOpen?: (context: DriverContext) => void): AgentDriver {
  return {
    id: 'grok',
    auth: { methods: async () => [], login: async () => {} },
    async open(context) {
      const runtime: AgentRuntime = {
        agentSessionId: context.resumeId ?? 'grok-1',
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        prompt: async () => ({ stopReason: 'end_turn' }),
        interrupt: async () => {},
        close,
      }
      onOpen?.(context)
      return runtime
    },
  }
}

test('failed child close keeps ownership so repeated close can retry', async () => {
  let attempts = 0
  const r = await grok(grokRequired(), () => mockChild(async () => {
    attempts++
    if (attempts === 1) throw new Error('cleanup failed')
  })).open(ctx())
  await expect(r.close({ mode: "shutdown" })).rejects.toThrow('cleanup failed')
  expect(attempts).toBe(1)
  await r.close({ mode: "shutdown" })
  expect(attempts).toBe(2)
  await r.close({ mode: "shutdown" })
  expect(attempts).toBe(2)
})

test('configure does not spawn a replacement until previous close succeeds', async () => {
  let opens = 0
  let failClose = true
  const r = await grok(grokRequired(), () => mockChild(async () => {
    if (failClose) throw new Error('still owned')
  }, () => { opens++ })).open(ctx())
  expect(opens).toBe(1)
  await expect(r.configure({ model: 'next' })).rejects.toThrow('still owned')
  expect(opens).toBe(1)
  failClose = false
  await r.configure({ model: 'next' })
  expect(opens).toBe(2)
  await r.close({ mode: "shutdown" })
})

test('candidate onExit before publish does not fail the current session', async () => {
  const exits: Error[] = []
  let opens = 0
  const r = await grok(grokRequired(), () => mockChild(async () => {}, context => {
    opens++
    if (opens === 2) context.onExit(new Error('candidate died during setup'))
  })).open(ctx({ onExit: e => exits.push(e) }))
  await expect(r.configure({ model: 'candidate' })).rejects.toThrow('candidate died during setup')
  expect(exits).toEqual([])
  await r.configure({ model: 'restored' })
  expect(opens).toBe(3)
  await r.close({ mode: "shutdown" })
})

test('stale child activity after configure is generation-gated', async () => {
  const activity: any[] = []
  let opens = 0
  let stale!: (notice: { id: string; phase: 'started' | 'completed' }) => void
  const r = await grok(grokRequired(), () => ({
    id: 'grok',
    auth: { methods: async () => [], login: async () => {} },
    async open(context) {
      opens++
      if (opens === 1) stale = notice => context.onActivity?.(notice)
      return {
        agentSessionId: context.resumeId ?? 'grok-1',
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        prompt: async () => ({ stopReason: 'end_turn' }),
        interrupt: async () => {},
        close: async () => {},
      }
    },
  })).open(ctx({ onActivity: n => activity.push({ ...n }) }))
  await r.configure({ model: 'next' })
  stale({ id: 'old-turn', phase: 'started' })
  stale({ id: 'old-turn', phase: 'completed' })
  expect(activity).toEqual([])
  await r.close({ mode: "shutdown" })
})

test('failed candidate activity is not forwarded', async () => {
  const activity: any[] = []
  let opens = 0
  const r = await grok(grokRequired(), () => ({
    id: 'grok',
    auth: { methods: async () => [], login: async () => {} },
    async open(context) {
      opens++
      if (opens === 2) {
        context.onActivity?.({ id: 'cand', phase: 'started' })
        context.onExit(new Error('candidate died during setup'))
      }
      return {
        agentSessionId: context.resumeId ?? 'grok-1',
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        prompt: async () => ({ stopReason: 'end_turn' }),
        interrupt: async () => {},
        close: async () => {},
      }
    },
  })).open(ctx({ onActivity: n => activity.push({ ...n }) }))
  await expect(r.configure({ model: 'candidate' })).rejects.toThrow('candidate died during setup')
  expect(activity).toEqual([])
  await r.close({ mode: "shutdown" })
})

test('grok live native activity after load is reported; replay is not', async () => {
  const activity: any[] = []
  const updates: any[] = []
  const r = await driver({ LIVE_AFTER_LOAD: '1' }).open(ctx({
    resumeId: 'grok-1',
    onUpdate: u => updates.push(u),
    onActivity: n => activity.push({ ...n }),
  }))
  try {
    const wait = Date.now()
    while (activity.filter(a => a.id === 'live-1').length < 2 && Date.now() - wait < 1000) await new Promise(resolve => setTimeout(resolve, 20))
    expect(updates.some(u => u.replay === true && u.protocol === 'native' && u.value?.params?.update?.sessionUpdate === 'user_message_chunk')).toBe(true)
    expect(activity.filter(a => a.id === 'hist-1')).toEqual([])
    expect(activity.filter(a => a.id === 'live-1').map(a => a.phase)).toEqual(['started', 'completed'])
  } finally { await r.close({ mode: "shutdown" }) }
})

test('grok no-start-id vendor completion idles without owned prompt finalizer', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'grok-noid-'))
  dirs.push(stateDirectory)
  const core = createCore({
    limits: TEST_LIMITS,
    stateDirectory,
    agents: [driver({ AUTONOMOUS_ON_OPEN: '1', NO_START_ID: '1', TURN_ID: 'b951c2c0-d2b3-4406-b68c-2b0401a48dcf' })],
  })
  cores.push(core)
  const session = await core.sessions.create({ id: nextId(), agent: 'grok', cwd: process.cwd() })
  const wait = Date.now()
  while (session.snapshot().state !== 'idle' && Date.now() - wait < 1500) await new Promise(resolve => setTimeout(resolve, 20))
  expect(session.snapshot().state).toBe('idle')
})

test('explicit grok A ignores unknown B, C, and no-id complete until A ends', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'grok-explicit-'))
  dirs.push(stateDirectory)
  const core = createCore({
    limits: TEST_LIMITS,
    stateDirectory,
    agents: [driver()],
  })
  cores.push(core)
  const session = await core.sessions.create({ id: nextId(), agent: 'grok', cwd: process.cwd() })
  const sent = session.send({ content: [{ type: 'text', text: 'explicit-adversarial' }], whenBusy: 'reject' })
  const waitRun = Date.now()
  while (session.snapshot().state !== 'running' && Date.now() - waitRun < 1500) await new Promise(resolve => setTimeout(resolve, 20))
  expect(session.snapshot().state).toBe('running')
  await new Promise(resolve => setTimeout(resolve, 80))
  expect(session.snapshot().state).toBe('running')
  expect((await (await sent).completed).status).toBe('completed')
  const waitIdle = Date.now()
  while (session.snapshot().state !== 'idle' && Date.now() - waitIdle < 1500) await new Promise(resolve => setTimeout(resolve, 20))
  expect(session.snapshot().state).toBe('idle')
})

test('repeated grok completed prompt_id does not end the next generation', async () => {
  const activity: any[] = []
  const r = await driver({ AUTONOMOUS_ON_OPEN: '1', NO_START_ID: '1', TURN_ID: 'same-uuid' }).open(ctx({ onActivity: n => activity.push({ ...n }) }))
  try {
    const wait = Date.now()
    while (activity.filter(a => a.phase === 'completed').length < 1 && Date.now() - wait < 2500) await new Promise(resolve => setTimeout(resolve, 20))
    expect(activity.filter(a => a.phase === 'started')).toHaveLength(1)
    expect(activity.filter(a => a.phase === 'completed')).toHaveLength(1)
    await r.prompt([{ type: 'text', text: 'stale-uuid' }], signal())
    expect(activity.filter(a => a.phase === 'started').length).toBeGreaterThanOrEqual(2)
    expect(activity.filter(a => a.phase === 'completed')).toHaveLength(1)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('initial onExit candidate close failure stays owned until core.close retries', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'grok-owned-open-'))
  dirs.push(stateDirectory)
  let closes = 0
  let allowClose = false
  const core = createCore({
    limits: TEST_LIMITS,
    stateDirectory,
    agents: [grok(grokRequired(), () => mockChild(async () => {
      closes++
      if (!allowClose) throw new Error('cleanup blocked')
    }, context => { context.onExit(new Error('candidate died during setup')) }))],
  })
  cores.push(core)
  await expect(core.sessions.create({ id: nextId(), agent: 'grok', cwd: stateDirectory })).rejects.toThrow()
  expect(closes).toBeGreaterThanOrEqual(1)
  const afterCreate = closes
  await expect(core.close({ agents: "shutdown" })).rejects.toThrow()
  expect(closes).toBe(afterCreate + 1)
  allowClose = true
  await core.close({ agents: "shutdown" })
  expect(closes).toBe(afterCreate + 2)
})

test('failed owned open returns close-only facade until cleanup succeeds', async () => {
  const exits: Error[] = []
  let allowClose = false
  let closes = 0
  const r = await grok(grokRequired(), () => mockChild(async () => {
    closes++
    if (!allowClose) throw new Error('cleanup blocked')
  }, context => { context.onExit(new Error('candidate died during setup')) })).open(ctx({ onExit: e => exits.push(e) }))
  expect(exits.some(e => e instanceof AggregateError
    ? e.errors.some(inner => inner instanceof Error && inner.message.includes('candidate died'))
    : e.message.includes('candidate died'))).toBe(true)
  expect(r.capabilities.configure).toBe(false)
  await expect(r.prompt([{ type: 'text', text: 'x' }], signal())).rejects.toMatchObject({ code: 'runtime_closed' })
  await expect(r.configure({ model: 'x' })).rejects.toMatchObject({ code: 'runtime_closed' })
  await expect(r.interrupt()).rejects.toMatchObject({ code: 'runtime_closed' })
  await expect(r.close({ mode: "shutdown" })).rejects.toThrow('cleanup blocked')
  const blocked = closes
  expect(blocked).toBeGreaterThanOrEqual(1)
  allowClose = true
  await r.close({ mode: "shutdown" })
  expect(closes).toBe(blocked + 1)
})

test('config candidate close failure does not spawn replacement until cleanup confirms', async () => {
  let opens = 0
  let allowCandidateClose = false
  const r = await grok(grokRequired(), () => mockChild(async () => {
    if (opens === 2 && !allowCandidateClose) throw new Error('candidate cleanup blocked')
  }, context => {
    opens++
    if (opens === 2) context.onExit(new Error('candidate died during setup'))
  })).open(ctx())
  expect(opens).toBe(1)
  await expect(r.configure({ model: 'candidate' })).rejects.toThrow()
  expect(opens).toBe(2)
  await expect(r.configure({ model: 'rollback' })).rejects.toThrow('candidate cleanup blocked')
  expect(opens).toBe(2)
  allowCandidateClose = true
  await r.configure({ model: 'recovered' })
  expect(opens).toBe(3)
  await r.close({ mode: "shutdown" })
})

test('rejected pending candidate does not replace accepted id when cleanup fails', async () => {
  let opens = 0
  let allowCandidateClose = false
  const r = await grok(grokRequired(), () => ({
    id: 'grok',
    auth: { methods: async () => [], login: async () => {} },
    async open(context) {
      const n = ++opens
      return {
        agentSessionId: n === 2 ? 'WRONG' : (context.resumeId ?? 'accepted'),
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        prompt: async () => ({ stopReason: 'end_turn' as const }),
        interrupt: async () => {},
        close: async () => {
          if (n === 2 && !allowCandidateClose) throw new Error('cleanup blocked')
        },
      }
    },
  })).open(ctx())
  expect(r.agentSessionId).toBe('accepted')
  let error: unknown
  try {
    await r.configure({ model: 'candidate' })
  } catch (caught) {
    error = caught
  }
  expect(error).toBeInstanceOf(AggregateError)
  const aggregate = error as AggregateError
  expect(aggregate.message).toBe('Session opening and runtime cleanup failed')
  expect(aggregate.errors.some(e => e instanceof Error && e.message.includes('did not restore the same agent session id'))).toBe(true)
  expect(aggregate.errors.some(e => e instanceof Error && e.message.includes('cleanup blocked'))).toBe(true)
  expect(aggregate.cause).toBeInstanceOf(Error)
  expect((aggregate.cause as Error).message).toContain('did not restore the same agent session id')
  expect(r.agentSessionId).toBe('accepted')
  expect(opens).toBe(2)
  await expect(r.configure({ model: 'rollback' })).rejects.toThrow('cleanup blocked')
  expect(opens).toBe(2)
  expect(r.agentSessionId).toBe('accepted')
  allowCandidateClose = true
  await r.configure({ model: 'recovered' })
  expect(opens).toBe(3)
  expect(r.agentSessionId).toBe('accepted')
  await r.close({ mode: "shutdown" })
})

test('wrong candidate id is not used to resume rollback', async () => {
  const resumes: Array<string | undefined> = []
  let opens = 0
  const r = await grok(grokRequired(), () => ({
    id: 'grok',
    auth: { methods: async () => [], login: async () => {} },
    async open(context) {
      opens++
      resumes.push(context.resumeId)
      return {
        agentSessionId: opens === 2 ? 'wrong-candidate' : (context.resumeId ?? 'grok-1'),
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        prompt: async () => ({ stopReason: 'end_turn' }),
        interrupt: async () => {},
        close: async () => {},
      }
    },
  })).open(ctx())
  expect(r.agentSessionId).toBe('grok-1')
  await expect(r.configure({ model: 'candidate' })).rejects.toMatchObject({ code: 'session_identity' })
  expect(r.agentSessionId).toBe('grok-1')
  await r.configure({ model: 'restored' })
  expect(resumes).toEqual([undefined, 'grok-1', 'grok-1'])
  expect(r.agentSessionId).toBe('grok-1')
  await r.close({ mode: "shutdown" })
})

test('prepublication activity copies notices and compact cycles stay bounded', async () => {
  const activity: any[] = []
  let emit!: (notice: { id: string; phase: 'started' | 'completed'; extra?: string }) => void
  const r = await grok(grokRequired(), () => ({
    id: 'grok',
    auth: { methods: async () => [], login: async () => {} },
    async open(context) {
      emit = notice => context.onActivity?.(notice as any)
      for (let i = 0; i < 40; i++) {
        context.onActivity?.({ id: `cycle-${i}`, phase: 'started' })
        context.onActivity?.({ id: `cycle-${i}`, phase: 'completed' })
      }
      const mutable = { id: 'live-a', phase: 'started' as const, extra: 'before' }
      context.onActivity?.(mutable as any)
      mutable.id = 'mutated'
      mutable.phase = 'completed'
      return {
        agentSessionId: 'grok-1',
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        prompt: async () => ({ stopReason: 'end_turn' }),
        interrupt: async () => {},
        close: async () => {},
      }
    },
  })).open(ctx({ onActivity: n => activity.push(n) }))
  expect(activity).toEqual([{ id: 'live-a', phase: 'started' }])
  emit({ id: 'live-a', phase: 'completed' })
  expect(activity).toEqual([{ id: 'live-a', phase: 'started' }, { id: 'live-a', phase: 'completed' }])
  await r.close({ mode: "shutdown" })
})

test('live wrapper caps outstanding activity even without an onActivity callback', async () => {
  const exits: Error[] = []
  let emit!: (notice: { id: string; phase: 'started' | 'completed' }) => void
  const r = await grok(grokRequired(), () => ({
    id: 'grok',
    auth: { methods: async () => [], login: async () => {} },
    async open(context) {
      emit = notice => context.onActivity?.(notice)
      return {
        agentSessionId: 'grok-1',
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        prompt: async () => ({ stopReason: 'end_turn' }),
        interrupt: async () => {},
        close: async () => {},
      }
    },
  })).open(ctx({ onExit: e => exits.push(e) }))
  for (let i = 0; i < MAX_OUTSTANDING_ACTIVITY; i++) emit({ id: `live-${i}`, phase: 'started' })
  emit({ id: 'overflow', phase: 'started' })
  expect(exits.some(e => (e as any).code === 'activity_overflow')).toBe(true)
  await expect(r.prompt([{ type: 'text', text: 'x' }], signal())).rejects.toMatchObject({ code: 'busy' })
  await r.close({ mode: "shutdown" })
})

test('prepublication activity overflow fails setup without publishing notices', async () => {
  const activity: any[] = []
  const exits: Error[] = []
  await expect(grok(grokRequired(), () => mockChild(async () => {}, context => {
    for (let i = 0; i < MAX_OUTSTANDING_ACTIVITY + 1; i++) {
      context.onActivity?.({ id: `open-${i}`, phase: 'started' })
    }
  })).open(ctx({ onActivity: n => activity.push(n), onExit: e => exits.push(e) }))).rejects.toMatchObject({ code: 'activity_overflow' })
  expect(activity).toEqual([])
  expect(exits).toEqual([])
})

test('grok wrapper rejects configure during known native activity', async () => {
  const { r } = await traced({ AUTONOMOUS_ON_OPEN: '1', AUTONOMOUS_HOLD: '1', TURN_ID: 'wrap-1' })
  try {
    await new Promise(resolve => setTimeout(resolve, 40))
    await expect(r.configure({ model: 'x' })).rejects.toMatchObject({ code: 'busy' })
    await expect(r.prompt([{ type: 'text', text: 'hello' }], signal())).rejects.toMatchObject({ code: 'busy' })
    await r.interrupt()
    await new Promise(resolve => setTimeout(resolve, 40))
    expect(await r.prompt([{ type: 'text', text: 'hello' }], signal())).toEqual({ stopReason: 'end_turn' })
  } finally { await r.close({ mode: "shutdown" }) }
})

test('grok() TypeError names each missing required field', () => {
  const full: any = grokRequired()
  for (const field of ['id', 'command', 'commandArgs', 'permissions', 'noLeader', 'inheritEnv', 'mcpServers', 'setupTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'maxOutstandingActivity', 'keeper', 'cancelRetryIntervalMs', 'cancelRetryTimeoutMs']) {
    const opts = { ...full }; delete opts[field]
    expect(() => grok(opts)).toThrow(TypeError)
    expect(() => grok(opts)).toThrow(new RegExp(`Grok ${field} is required`))
  }
})

test('opencode() TypeError names each missing required field', () => {
  const full: any = {
    id: 'opencode', command: 'opencode', inheritEnv: true, mcpServers: [],
    setupTimeoutMs: 1, shutdownTimeoutMs: 1, maxFrameBytes: 1, maxOutstandingActivity: 1,
    cancelRetryIntervalMs: 1, cancelRetryTimeoutMs: 1,
    keeper: { stateDirectory: '/tmp', limits: { parkedDeadlineMs: 1, journalMaxBytes: 1, connectTimeoutMs: 1 } },
    permissions: { kind: 'acp', policy: 'ask', nativeMode: null },
  }
  for (const field of ['id', 'command', 'inheritEnv', 'mcpServers', 'setupTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'maxOutstandingActivity', 'keeper', 'cancelRetryIntervalMs', 'cancelRetryTimeoutMs', 'permissions']) {
    const opts = { ...full }; delete opts[field]
    expect(() => opencode(opts)).toThrow(TypeError)
    expect(() => opencode(opts)).toThrow(new RegExp(`OpenCode ${field} is required`))
  }
})

