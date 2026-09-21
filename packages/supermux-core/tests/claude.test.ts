import {afterEach, test, expect, setDefaultTimeout} from 'bun:test'
import {fileURLToPath} from 'node:url'
import {mkdtemp, readFile, rm} from 'node:fs/promises'
import {mkdtempSync, readFileSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {claude} from '../src/claude/index.js'
import { createCore } from '../src/core.js'
import type {CoreEvent, DriverContext} from '../src/types.js'
import { TEST_LIMITS, nextId } from './helpers.js'

setDefaultTimeout(20_000)
const fixture = fileURLToPath(new URL('./fixtures/claude-agent.mjs', import.meta.url))
const signal = () => new AbortController().signal
const keeperDirs: string[] = []
function keeperLimits() {
  return { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 }
}
function keeperOf() {
  const stateDirectory = mkdtempSync(join(tmpdir(), 'claude-test-'))
  keeperDirs.push(stateDirectory)
  return { stateDirectory, limits: keeperLimits() }
}
afterEach(async () => {
  for (const dir of keeperDirs.splice(0)) {
    try { await rm(dir, { recursive: true, force: true }) } catch { /* */ }
  }
})
const ctx = (extra: Partial<DriverContext> = {}): DriverContext => ({
  sessionId: 'core', cwd: process.cwd(), signal: signal(), onUpdate() {}, onExit() {},
  requestPermission: async () => ({outcome: {outcome: 'cancelled'}}), requestAnswers: async () => ({outcome: 'cancelled' as const}), ...extra,
})
const driver = (env: Record<string, string> = {}, extra: Record<string, unknown> = {}) => claude({
  id: 'claude', command: process.execPath, args: [fixture], env, inheritEnv: true, tools: [], permissionPrompts: 'none', partialMessages: false,
  setupTimeoutMs: 5000, requestTimeoutMs: 3000, shutdownTimeoutMs: 30, maxFrameBytes: 16 * 1024 * 1024,
  keeper: keeperOf(), ...extra,
})
const input = (text: string) => [{type: 'text' as const, text}]

test('native create, streaming result, filtered updates, permission deny, and failed turn', async () => {
  const updates: any[] = []
  let hostCalls = 0
  const r = await driver().open(ctx({
    onUpdate: u => updates.push(u),
    requestPermission: async () => { hostCalls++; return {outcome: {outcome: 'cancelled'}} },
  }))
  try {
    expect(r.agentSessionId).toMatch(/^[a-f0-9-]{36}$/)
    expect(r.capabilities).toEqual({resume: true, steer: false, fork: false, detach: true})
    expect(await r.prompt(input('hello'), signal())).toEqual({stopReason: 'end_turn'})
    expect(updates.some(x => x.protocol === 'native' && x.value.message?.content?.[0]?.text === 'héllo')).toBe(true)
    expect(updates.some(x => x.value.session_id === 'other-session')).toBe(false)
    expect(await r.prompt(input('permission'), signal())).toEqual({stopReason: 'end_turn'})
    expect(hostCalls).toBe(0)
    await expect(r.prompt(input('fail'), signal())).rejects.toThrow('turn failed')
    await expect(r.prompt([{type: 'resource_link', uri: 'file:///x', name: 'x'}], signal())).rejects.toThrow('Unsupported')
  } finally { await r.close({ mode: "shutdown" }) }
})

test('resume preserves identity; missing resume never creates a replacement', async () => {
  const r = await driver().open(ctx({resumeId: 'old'}))
  expect(r.agentSessionId).toBe('old')
  await r.close({ mode: "shutdown" })
  await expect(driver().open(ctx({resumeId: 'missing'}))).rejects.toThrow('missing session')
  await expect(driver({MODE: 'wrong-resume-early'}).open(ctx({resumeId: 'old'}))).rejects.toThrow('identity')
  const exits: Error[] = []
  const late = await driver({MODE: 'wrong-resume'}).open(ctx({resumeId: 'old', onExit: e => { if (e) exits.push(e) }}))
  try {
    expect(late.agentSessionId).toBe('old')
    await expect(late.prompt(input('hello'), signal())).rejects.toThrow('identity')
    expect(exits.some(e => /identity/.test(e.message))).toBe(true)
    expect(late.agentSessionId).toBe('old')
  } finally { await late.close({ mode: "shutdown" }) }
})

test('interrupt cancels the in-flight turn via control_request', async () => {
  const r = await driver().open(ctx())
  try {
    const pending = r.prompt(input('hang'), signal())
    await r.interrupt()
    expect(await pending).toEqual({stopReason: 'cancelled'})
  } finally { await r.close({ mode: "shutdown" }) }
})

test('failed interrupt does not fabricate a cancelled completion', async () => {
  const r = await driver({MODE: 'interrupt-fail'}).open(ctx())
  try {
    const pending = r.prompt(input('hang'), signal())
    await expect(r.interrupt()).rejects.toThrow('interrupt failed')
    expect(await pending).toEqual({stopReason: 'end_turn'})
  } finally { await r.close({ mode: "shutdown" }) }
})

test('missing executable, setup timeout, request timeout, and stream failures reject', async () => {
  await expect(driver({}, {command: '/not-a-claude-executable'}).open(ctx())).rejects.toThrow()
  await expect(driver({MODE: 'setup-hang'}, {setupTimeoutMs: 30}).open(ctx())).rejects.toThrow()
  await expect(driver({MODE: 'init-hang'}, {requestTimeoutMs: 40, setupTimeoutMs: 2000}).open(ctx())).rejects.toThrow()
  for (const text of ['disconnect', 'eof', 'malformed', 'oversize']) {
    const r = await driver({}, {maxFrameBytes: 4096}).open(ctx())
    try { await expect(r.prompt(input(text), signal())).rejects.toThrow() }
    finally { await r.close({ mode: "shutdown" }) }
  }
})

test('model, effort, extra argv, and profile env are forwarded; close reaps stubborn child and settles turn', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'claude-native-'))
  const pidFile = join(dir, 'pid')
  const trace = join(dir, 'trace')
  const created = await driver({TRACE: trace}).open(ctx())
  const argv = JSON.parse((await readFile(trace, 'utf8')).trim().split('\n')[0]!).argv as string[]
  expect(argv).toContain('--print')
  expect(argv.slice(argv.indexOf('--input-format'), argv.indexOf('--input-format') + 2)).toEqual(['--input-format', 'stream-json'])
  expect(argv.slice(argv.indexOf('--output-format'), argv.indexOf('--output-format') + 2)).toEqual(['--output-format', 'stream-json'])
  expect(argv).toContain('--verbose')
  expect(argv).toContain('--await-initialize')
  expect(argv).not.toContain('--bare')
  expect(argv.some(a => a.startsWith('--session-id='))).toBe(true)
  expect(argv).not.toContain('--resume')
  await created.close({ mode: "shutdown" })

  const resumedTrace = join(dir, 'resume-trace')
  const resumed = await driver({TRACE: resumedTrace}).open(ctx({resumeId: 'old'}))
  const resumeArgv = JSON.parse((await readFile(resumedTrace, 'utf8')).trim().split('\n')[0]!).argv as string[]
  expect(resumeArgv.some(a => a === '--resume=old' || a === 'old')).toBe(true)
  expect(resumeArgv.includes('--session-id') || resumeArgv.some(a => a.startsWith('--session-id='))).toBe(false)
  await resumed.close({ mode: "shutdown" })

  const configured = await driver(
    {EXPECT_DEFAULT_DENY: '0', EXPECT_MODEL: 'opus', EXPECT_EFFORT: 'high', TRACE: join(dir, 'cfg')},
    {model: 'opus', effort: 'high', args: [fixture, '--add-dir', dir], tools: ['Read']},
  ).open(ctx())
  const cfgArgv = JSON.parse((await readFile(join(dir, 'cfg'), 'utf8')).trim().split('\n')[0]!).argv as string[]
  expect(cfgArgv.slice(cfgArgv.indexOf('--model'), cfgArgv.indexOf('--model') + 2)).toEqual(['--model', 'opus'])
  expect(cfgArgv.slice(cfgArgv.indexOf('--effort'), cfgArgv.indexOf('--effort') + 2)).toEqual(['--effort', 'high'])
  expect(cfgArgv.slice(cfgArgv.indexOf('--tools'), cfgArgv.indexOf('--tools') + 2)).toEqual(['--tools', 'Read'])
  expect(cfgArgv.slice(cfgArgv.indexOf('--add-dir'), cfgArgv.indexOf('--add-dir') + 2)).toEqual(['--add-dir', dir])
  await configured.close({ mode: "shutdown" })

  const r = await driver({MODE: 'stubborn'}).open(ctx({profile: {agent: 'claude', env: {PID_FILE: pidFile, TRACE: join(dir, 'close-trace')}}}))
  const pending = r.prompt(input('hang'), signal()).catch(e => e)
  await new Promise(resolve => setTimeout(resolve, 30))
  await r.close({ mode: "shutdown" })
  expect(await pending).toBeInstanceOf(Error)
  expect(() => process.kill(Number(readFileSync(pidFile, 'utf8')), 0)).toThrow()
  expect((await readFile(join(dir, 'close-trace'), 'utf8')).includes('"subtype":"initialize"')).toBe(true)
  await rm(dir, {recursive: true})
})

const hostDriver = (env: Record<string, string> = {}) => driver(
  {EXPECT_DEFAULT_DENY: '0', ...env},
  {permissionPrompts: 'host'},
)

test('host permission allow_once uses original input and native session identity', async () => {
  const r = await hostDriver({EXPECT_PERM: 'allow'}).open(ctx({
    sessionId: 'core-1',
    requestPermission: async req => {
      expect(req.coreSessionId).toBe('core-1')
      expect(req.sessionId).toBe(r.agentSessionId)
      expect(req.toolCall.toolCallId).toBe('perm-1')
      expect(req.toolCall.title).toBe('Bash')
      expect(req.toolCall.rawInput).toEqual({command: 'pwd'})
      expect(req.options.map(o => o.optionId).sort()).toEqual(['allow_once', 'reject_once'])
      expect(req.options.every(o => o.kind === o.optionId)).toBe(true)
      if (req.toolCall.rawInput && typeof req.toolCall.rawInput === 'object') (req.toolCall.rawInput as {command: string}).command = 'rm -rf /'
      return {outcome: {outcome: 'selected', optionId: 'allow_once'}}
    },
  }))
  try { expect(await r.prompt(input('permission'), signal())).toEqual({stopReason: 'end_turn'}) }
  finally { await r.close({ mode: "shutdown" }) }
})

test('host permission tool_use_id is preferred for toolCallId; reject_once and unrecognized deny', async () => {
  const r = await hostDriver().open(ctx({
    requestPermission: async req => {
      expect(req.toolCall.toolCallId).toBe('tool-99')
      return {outcome: {outcome: 'selected', optionId: 'reject_once'}}
    },
  }))
  try {
    expect(await r.prompt(input('permission-tool-use-id'), signal())).toEqual({stopReason: 'end_turn'})
  } finally { await r.close({ mode: "shutdown" }) }

  const r2 = await hostDriver().open(ctx({
    requestPermission: async () => ({outcome: {outcome: 'selected', optionId: 'allow_always'}}),
  }))
  try { expect(await r2.prompt(input('permission'), signal())).toEqual({stopReason: 'end_turn'}) }
  finally { await r2.close({ mode: "shutdown" }) }
})

test('host permission callback failure denies the tool', async () => {
  const r = await hostDriver().open(ctx({
    requestPermission: async () => { throw new Error('host exploded') },
  }))
  try { expect(await r.prompt(input('permission'), signal())).toEqual({stopReason: 'end_turn'}) }
  finally { await r.close({ mode: "shutdown" }) }
})

test('duplicate can_use_tool request_id does not call the host twice', async () => {
  let calls = 0
  const r = await hostDriver({EXPECT_PERM: 'allow'}).open(ctx({
    requestPermission: async () => {
      calls++
      return {outcome: {outcome: 'selected', optionId: 'allow_once'}}
    },
  }))
  try {
    expect(await r.prompt(input('permission-duplicate'), signal())).toEqual({stopReason: 'end_turn'})
    expect(calls).toBe(1)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('cross-turn reused can_use_tool request_id denies without a stale allow or second host ask', async () => {
  let calls = 0
  const r = await hostDriver({EXPECT_PERM: 'allow'}).open(ctx({
    requestPermission: async () => {
      calls++
      return {outcome: {outcome: 'selected', optionId: 'allow_once'}}
    },
  }))
  try {
    expect(await r.prompt(input('permission-cross-turn'), signal())).toEqual({stopReason: 'end_turn'})
    expect(await r.prompt(input('permission-cross-turn'), signal())).toEqual({stopReason: 'end_turn'})
    expect(calls).toBe(1)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('same-turn reused request_id with different input denies without a second host ask', async () => {
  let calls = 0
  const r = await hostDriver({EXPECT_PERM: 'allow'}).open(ctx({
    requestPermission: async () => {
      calls++
      return {outcome: {outcome: 'selected', optionId: 'allow_once'}}
    },
  }))
  try {
    expect(await r.prompt(input('permission-changed-input'), signal())).toEqual({stopReason: 'end_turn'})
    expect(calls).toBe(1)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('native cancel after answered allow revokes cached allow for that request', async () => {
  let calls = 0
  const r = await hostDriver({EXPECT_PERM: 'allow'}).open(ctx({
    requestPermission: async () => {
      calls++
      return {outcome: {outcome: 'selected', optionId: 'allow_once'}}
    },
  }))
  try {
    expect(await r.prompt(input('permission-cancel-after-allow'), signal())).toEqual({stopReason: 'end_turn'})
    expect(calls).toBe(1)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('never-resolving host permission is cancelled by interrupt and by close', async () => {
  let permissionSignal: AbortSignal | undefined
  let requested!: () => void
  const seen = new Promise<void>(resolve => { requested = resolve })
  const r = await hostDriver({PERM_WAIT_INTERRUPT: '1'}).open(ctx({
    requestPermission: async (_, signal) => {
      permissionSignal = signal
      requested()
      return new Promise(() => {})
    },
  }))
  try {
    const pending = r.prompt(input('permission'), signal())
    await seen
    await r.interrupt()
    expect(await pending).toEqual({stopReason: 'cancelled'})
    expect(permissionSignal!.aborted).toBe(true)
  } finally { await r.close({ mode: "shutdown" }) }

  let requested2!: () => void
  const seen2 = new Promise<void>(resolve => { requested2 = resolve })
  const r2 = await hostDriver().open(ctx({
    requestPermission: async (_, signal) => {
      permissionSignal = signal
      requested2()
      return new Promise(() => {})
    },
  }))
  const pending2 = r2.prompt(input('permission'), signal()).catch(e => e)
  await seen2
  await r2.close({ mode: "shutdown" })
  expect(await pending2).toBeInstanceOf(Error)
  expect(permissionSignal!.aborted).toBe(true)
})

test('malformed host permission response denies without failing the turn', async () => {
  const r = await hostDriver().open(ctx({
    requestPermission: async () => ({outcome: null} as any),
  }))
  try { expect(await r.prompt(input('permission'), signal())).toEqual({stopReason: 'end_turn'}) }
  finally { await r.close({ mode: "shutdown" }) }
})

test('late host allow after turn completion does not grant the prior request', async () => {
  let release!: (value: {outcome: {outcome: 'selected'; optionId: 'allow_once'}}) => void
  const gate = new Promise<{outcome: {outcome: 'selected'; optionId: 'allow_once'}}>(resolve => { release = resolve })
  let calls = 0
  const r = await hostDriver().open(ctx({
    requestPermission: async () => {
      calls++
      return gate
    },
  }))
  try {
    expect(await r.prompt(input('permission-late'), signal())).toEqual({stopReason: 'end_turn'})
    expect(await r.prompt(input('hello'), signal())).toEqual({stopReason: 'end_turn'})
    release({outcome: {outcome: 'selected', optionId: 'allow_once'}})
    await new Promise(resolve => setTimeout(resolve, 30))
    expect(calls).toBe(1)
    expect(await r.prompt(input('hello'), signal())).toEqual({stopReason: 'end_turn'})
  } finally { await r.close({ mode: "shutdown" }) }
})

test('native control_cancel_request aborts the host callback and denies', async () => {
  let permissionSignal: AbortSignal | undefined
  const r = await hostDriver().open(ctx({
    requestPermission: async (_, signal) => {
      permissionSignal = signal
      return new Promise(() => {})
    },
  }))
  try {
    expect(await r.prompt(input('permission-native-cancel'), signal())).toEqual({stopReason: 'end_turn'})
    expect(permissionSignal!.aborted).toBe(true)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('host allow_always writes updatedPermissions when suggestions exist', async () => {
  const r = await hostDriver({EXPECT_PERM: 'always'}).open(ctx({
    requestPermission: async req => {
      expect(req.options.map(o => o.optionId)).toContain('allow_always')
      expect(req.detail?.blockedPath).toBe('/tmp/x')
      return {outcome: {outcome: 'selected', optionId: 'allow_always'}}
    },
  }))
  try { expect(await r.prompt(input('permission-always'), signal())).toEqual({stopReason: 'end_turn'}) }
  finally { await r.close({ mode: "shutdown" }) }
})

test('AskUserQuestion through Core emits user-question and never permission-request', async () => {
  const stateDirectory = mkdtempSync(join(tmpdir(), 'claude-core-q-'))
  keeperDirs.push(stateDirectory)
  const core = createCore({
    stateDirectory,
    agents: [hostDriver({EXPECT_PERM: 'question', EXPECT_DEFAULT_DENY: '0'})],
    limits: TEST_LIMITS,
  })
  const events: CoreEvent[] = []
  core.subscribe(e => { events.push(e) })
  try {
    const session = await core.sessions.create({ agent: 'claude', cwd: process.cwd(), id: nextId() })
    const receipt = await session.send({ content: [{ type: 'text', text: 'ask-user-question' }], whenBusy: 'queue' })
    const start = Date.now()
    while (session.requests.list().length === 0 && Date.now() - start < 4000) await new Promise(r => setTimeout(r, 10))
    const pending = session.requests.list()
    expect(pending).toHaveLength(1)
    expect(pending[0]!.kind).toBe('question')
    expect(events.some(e => e.type === 'session.event' && e.event.kind === 'permission-request')).toBe(false)
    expect(events.some(e => e.type === 'session.event' && e.event.kind === 'user-question')).toBe(true)
    const qid = pending[0]!.body.questions[0]!.id
    const oid = pending[0]!.body.questions[0]!.options[0]!.id
    await session.requests.respond(pending[0]!.requestId, { answers: { [qid]: oid } })
    expect((await receipt.completed).status).toBe('completed')
  } finally {
    await core.close({ agents: 'shutdown' })
  }
})

test('AskUserQuestion is a question, not a permission, and updatedInput.answers uses question text', async () => {
  const events: any[] = []
  const r = await hostDriver({EXPECT_PERM: 'question'}).open(ctx({
    onUpdate: u => events.push(u),
    requestPermission: async () => { throw new Error('must not request permission') },
    requestAnswers: async req => {
      expect(req.questions[0]?.question).toBe('Favorite color?')
      return { outcome: 'answered', answers: { q1: 'Blue' } }
    },
  }))
  try {
    expect(await r.prompt(input('ask-user-question'), signal())).toEqual({stopReason: 'end_turn'})
  } finally { await r.close({ mode: "shutdown" }) }
})

test('host reject_once deny message is forwarded', async () => {
  const r = await hostDriver({EXPECT_PERM: 'deny-message', EXPECT_DENY_MESSAGE: 'nope'}).open(ctx({
    requestPermission: async () => ({outcome: {outcome: 'selected', optionId: 'reject_once'}, message: 'nope'}),
  }))
  try { expect(await r.prompt(input('permission'), signal())).toEqual({stopReason: 'end_turn'}) }
  finally { await r.close({ mode: "shutdown" }) }
})

test('claude() TypeError names each missing required field', () => {
  const keeper = { stateDirectory: '/tmp', limits: { parkedDeadlineMs: 1, journalMaxBytes: 1, connectTimeoutMs: 1 } }
  const full: any = { id: 'claude', command: 'claude', args: [], inheritEnv: true, tools: [], permissionPrompts: 'none', partialMessages: false, setupTimeoutMs: 1, requestTimeoutMs: 1, shutdownTimeoutMs: 1, maxFrameBytes: 1, keeper }
  for (const field of ['id', 'command', 'args', 'setupTimeoutMs', 'requestTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'permissionPrompts', 'tools', 'keeper', 'inheritEnv', 'partialMessages']) {
    const opts = { ...full }; delete opts[field]
    expect(() => claude(opts)).toThrow(TypeError)
    expect(() => claude(opts)).toThrow(new RegExp(`Claude ${field} is required`))
  }
})
