import {afterEach, test, expect, setDefaultTimeout} from 'bun:test'
import {fileURLToPath} from 'node:url'
import {mkdtemp, readFile, rm} from 'node:fs/promises'
import {mkdtempSync, readFileSync} from 'node:fs'
import {tmpdir} from 'node:os'
import {join} from 'node:path'
import {claude} from '../src/claude/index.js'
import { createCore } from '../src/core.js'
import type {CoreEvent, DriverContext} from '../src/types.js'
import { TEST_LIMITS, TEST_CLAUDE_PERMISSIONS, nextId } from './helpers.js'

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
  id: 'claude', command: process.execPath, args: [fixture], env, inheritEnv: true, tools: [], permissionPrompts: 'none', permissions: TEST_CLAUDE_PERMISSIONS, partialMessages: false,
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
    expect(r.capabilities).toEqual({resume: true, steer: false, fork: false, detach: true, permissions: true})
    expect(await r.prompt(input('hello'), signal())).toEqual({stopReason: 'end_turn'})
    expect(updates.some(x => x.protocol === 'native' && x.value.message?.content?.[0]?.text === 'héllo')).toBe(true)
    expect(updates.some(x => x.value.session_id === 'other-session')).toBe(false)
    expect(await r.prompt(input('permission'), signal())).toEqual({stopReason: 'end_turn'})
    expect(hostCalls).toBe(0)
    await expect(r.prompt(input('fail'), signal())).rejects.toThrow('turn failed')
    await expect(r.prompt([{type: 'resource_link', uri: 'file:///x', name: 'x'}], signal())).rejects.toThrow('Unsupported')
  } finally { await r.close({ mode: "shutdown" }) }
})

test('a Claude process that dies mid-turn: the error says how it exited and the last line it wrote to stderr', async () => {
  const exits: Error[] = []
  const r = await driver().open(ctx({ onExit: e => { exits.push(e) } }))
  try {
    const failure: any = await r.prompt(input('crash'), signal()).then(() => undefined, (e: unknown) => e)
    expect(failure).toMatchObject({ code: 'agent_exited', message: 'Claude process exited with code 3: oh no: Bun has crashed. This indicates a bug in Bun, not your code.' })
    expect(failure.exit).toEqual({ code: 3, stderr: ['panic(main thread): Segmentation fault at address 0x0', 'oh no: Bun has crashed. This indicates a bug in Bun, not your code.'] })
    expect(exits).toEqual([failure])
  } finally { await r.close({ mode: "shutdown" }) }
})

test('the initialize reply reaches the host as a native update carrying the command list', async () => {
  // Claude 2.1.308 lists its slash commands in the initialize reply; no prompt is needed (card #13).
  const commands = [{name: 'verify', description: 'Check work', argumentHint: '[what]'}]
  const updates: any[] = []
  const r = await driver({COMMANDS: JSON.stringify(commands)}).open(ctx({onUpdate: u => updates.push(u)}))
  try {
    const replies = updates.filter(u => u.protocol === 'native' && u.value?.type === 'control_response')
    expect(replies).toHaveLength(1)
    expect(replies[0].value.response.response.commands).toEqual(commands)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('resuming a conversation that was never sent to starts it fresh under the same id', async () => {
  // Restarted before its first message: Claude has no conversation file and says "No conversation found".
  const r = await driver().open(ctx({resumeId: 'never-sent'}))
  try {
    expect(r.agentSessionId).toBe('never-sent')
    expect(await r.prompt(input('hello'), signal())).toEqual({stopReason: 'end_turn'})
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

test('/clear moves the runtime to the conversation Claude starts instead of failing it', async () => {
  // Claude 2.1.308 answers /clear (and /reset, /new) with a new conversation under a new session id: conversation_reset,
  // then frames under the new id. It used to fail the session as an identity mismatch (card #55).
  const resets: unknown[] = []
  const exits: Error[] = []
  const updates: any[] = []
  const r = await driver().open(ctx({ onConversationReset: reset => resets.push(reset), onExit: e => exits.push(e), onUpdate: u => updates.push(u) }))
  try {
    const first = r.agentSessionId
    expect(await r.prompt(input('hello'), signal())).toEqual({stopReason: 'end_turn'})
    expect(await r.prompt(input('/clear'), signal())).toEqual({stopReason: 'end_turn'})
    const second = r.agentSessionId
    expect(second).not.toBe(first)
    // The new conversation's turns are this runtime's turns.
    const before = updates.length
    expect(await r.prompt(input('hello'), signal())).toEqual({stopReason: 'end_turn'})
    expect(updates.slice(before).some(u => u.value?.session_id === second && u.value?.message?.content?.[0]?.text === 'héllo')).toBe(true)
    expect(await r.prompt(input('/clear'), signal())).toEqual({stopReason: 'end_turn'})
    expect(r.agentSessionId).not.toBe(second)
    expect(resets).toEqual([
      {agentSessionId: second, previousAgentSessionId: first, trigger: 'clear'},
      {agentSessionId: r.agentSessionId, previousAgentSessionId: second, trigger: 'clear'},
    ])
    expect(exits).toEqual([])
  } finally { await r.close({ mode: "shutdown" }) }
})

test('/clear as the very first message: no system/init under the launch id, still followed', async () => {
  const resets: any[] = []
  const r = await driver().open(ctx({ onConversationReset: reset => resets.push(reset) }))
  try {
    const launched = r.agentSessionId
    expect(await r.prompt(input('/clear'), signal())).toEqual({stopReason: 'end_turn'})
    expect(resets).toEqual([{agentSessionId: r.agentSessionId, previousAgentSessionId: launched, trigger: 'clear'}])
    expect(await r.prompt(input('hello'), signal())).toEqual({stopReason: 'end_turn'})
  } finally { await r.close({ mode: "shutdown" }) }
})

test('a conversation_reset that keeps the id ends with its turn: a later foreign id is still not this session', async () => {
  const resets: unknown[] = []
  const updates: any[] = []
  const r = await driver().open(ctx({ onConversationReset: reset => resets.push(reset), onUpdate: u => updates.push(u) }))
  try {
    const id = r.agentSessionId
    expect(await r.prompt(input('reset-same-id'), signal())).toEqual({stopReason: 'end_turn'})
    // The fixture's normal reply starts with frames of 'other-session'.
    expect(await r.prompt(input('hello'), signal())).toEqual({stopReason: 'end_turn'})
    expect(r.agentSessionId).toBe(id)
    expect(resets).toEqual([])
    expect(updates.some(u => u.value?.session_id === 'other-session')).toBe(false)
    expect(updates.some(u => u.value?.session_id === id && u.value?.message?.content?.[0]?.text === 'héllo')).toBe(true)
  } finally { await r.close({ mode: "shutdown" }) }
})

test('interrupt cancels the in-flight turn via control_request', async () => {
  const r = await driver().open(ctx())
  try {
    const pending = r.prompt(input('hang'), signal())
    await r.interrupt()
    expect(await pending).toEqual({stopReason: 'cancelled'})
  } finally { await r.close({ mode: "shutdown" }) }
})

test('interrupt whose error result arrives before its reply still cancels the turn', async () => {
  const r = await driver({MODE: 'interrupt-error-first'}).open(ctx())
  try {
    const pending = r.prompt(input('hang'), signal())
    pending.catch(() => {})
    await r.interrupt()
    expect(await pending).toEqual({stopReason: 'cancelled'})
  } finally { await r.close({ mode: "shutdown" }) }
})

test('aborting the prompt signal cancels the turn when the error result comes first', async () => {
  const r = await driver({MODE: 'interrupt-error-first'}).open(ctx())
  try {
    const controller = new AbortController()
    const pending = r.prompt(input('hang'), controller.signal)
    await new Promise(resolve => setTimeout(resolve, 50))
    controller.abort()
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

test('an abort that lands while claude is still starting ends setup at once, not at the setup timeout', async () => {
  const controller = new AbortController()
  // Budgets no test outlives: only the abort can end this setup.
  const pending = driver({MODE: 'setup-hang'}, {setupTimeoutMs: 600_000, requestTimeoutMs: 600_000}).open(ctx({signal: controller.signal}))
  // open() is now inside its first await (starting claude): the abort lands before setup listens for one.
  controller.abort()
  await expect(pending).rejects.toThrow()
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
  expect(argv).not.toContain('--settings')
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
    {model: 'opus', effort: 'high', fastMode: true, args: [fixture, '--add-dir', dir], tools: ['Read']},
  ).open(ctx())
  const cfgArgv = JSON.parse((await readFile(join(dir, 'cfg'), 'utf8')).trim().split('\n')[0]!).argv as string[]
  expect(cfgArgv.slice(cfgArgv.indexOf('--model'), cfgArgv.indexOf('--model') + 2)).toEqual(['--model', 'opus'])
  expect(cfgArgv.slice(cfgArgv.indexOf('--effort'), cfgArgv.indexOf('--effort') + 2)).toEqual(['--effort', 'high'])
  expect(cfgArgv.slice(cfgArgv.indexOf('--settings'), cfgArgv.indexOf('--settings') + 2)).toEqual(['--settings', '{"fastMode":true}'])
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

test('through Core, the command list Claude reports while opening becomes commands-update: fresh and on every re-attach', async () => {
  // The initialize reply (fresh) and the repeat initialize's reply (re-attach) usually arrive before Core has
  // made the Session, while it holds updates; they must still come out normalized (card #13).
  const stateDirectory = mkdtempSync(join(tmpdir(), 'claude-core-cmds-'))
  keeperDirs.push(stateDirectory)
  const commands = [{name: 'verify', description: 'Check work', argumentHint: '[what]'}]
  const core = createCore({stateDirectory, agents: [driver({COMMANDS: JSON.stringify(commands)})], limits: TEST_LIMITS})
  const events: CoreEvent[] = []
  core.subscribe(e => { events.push(e) })
  const listed = () => events.flatMap(e => e.type === 'session.event' && e.event.kind === 'commands-update' ? [e.event.commands] : [])
  const until = async (ok: () => boolean) => { for (let i = 0; i < 150 && !ok(); i++) await new Promise(r => setTimeout(r, 20)) }
  try {
    const id = nextId()
    let session = await core.sessions.create({agent: 'claude', cwd: process.cwd(), id})
    expect(listed()).toEqual([commands])
    for (let round = 1; round <= 3; round++) {
      const before = listed().length
      await session.close({mode: 'detach'})
      session = await core.sessions.resume(id)
      await until(() => listed().length > before)
      expect(listed().slice(before)).toEqual([commands])
    }
  } finally {
    await core.close({agents: 'shutdown'})
  }
})

test('through Core, /clear keeps the session and its record follows the new conversation, so a resume continues it', async () => {
  const stateDirectory = mkdtempSync(join(tmpdir(), 'claude-core-clear-'))
  keeperDirs.push(stateDirectory)
  const core = createCore({stateDirectory, agents: [driver()], limits: TEST_LIMITS})
  const events: CoreEvent[] = []
  core.subscribe(e => { events.push(e) })
  const resets = () => events.flatMap(e => e.type === 'session.event' && e.event.kind === 'conversation-reset' ? [e.event] : [])
  const until = async (ok: () => boolean) => { for (let i = 0; i < 150 && !ok(); i++) await new Promise(r => setTimeout(r, 20)) }
  const run = async (session: Awaited<ReturnType<typeof core.sessions.create>>, text: string) => (await (await session.send({content: input(text), whenBusy: 'queue'})).completed).status
  try {
    const id = nextId()
    const session = await core.sessions.create({agent: 'claude', cwd: process.cwd(), id})
    const first = session.snapshot().agentSessionId
    expect(await run(session, 'hello')).toBe('completed')
    expect(await run(session, '/clear')).toBe('completed')
    const second = session.snapshot().agentSessionId
    expect(second).not.toBe(first)
    await until(() => resets().length > 0)
    expect(resets()).toMatchObject([{kind: 'conversation-reset', agentSessionId: second, previousAgentSessionId: first, trigger: 'clear'}])
    expect(await core.sessions.get(id)).toMatchObject({agentSessionId: second, previousAgentSessionId: first})
    expect(await run(session, 'hello')).toBe('completed')
    expect(events.some(e => e.type === 'session.failed')).toBe(false)
    // A relaunch resumes the cleared conversation, never the one before it.
    await session.close({mode: 'shutdown'})
    const resumed = await core.sessions.resume(id)
    expect(resumed.snapshot().agentSessionId).toBe(second)
    expect(await run(resumed, 'hello')).toBe('completed')
  } finally {
    await core.close({agents: 'shutdown'})
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
  const full: any = { id: 'claude', command: 'claude', args: [], inheritEnv: true, tools: [], permissionPrompts: 'none', permissions: TEST_CLAUDE_PERMISSIONS, partialMessages: false, setupTimeoutMs: 1, requestTimeoutMs: 1, shutdownTimeoutMs: 1, maxFrameBytes: 1, keeper }
  for (const field of ['id', 'command', 'args', 'setupTimeoutMs', 'requestTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'permissionPrompts', 'tools', 'keeper', 'inheritEnv', 'partialMessages', 'permissions']) {
    const opts = { ...full }; delete opts[field]
    expect(() => claude(opts)).toThrow(TypeError)
    expect(() => claude(opts)).toThrow(new RegExp(`Claude ${field} is required`))
  }
})
test('a session permissions spec decides the launch permission mode', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'claude-perm-'))
  const trace = join(dir, 'env.json')
  const r = await driver({ ENV_TRACE: trace }).open(ctx({ permissions: { kind: 'claude', permissionMode: 'acceptEdits' } }))
  try {
    const { argv } = JSON.parse(await readFile(trace, 'utf8')) as { argv: string[] }
    expect(argv[argv.indexOf('--permission-mode') + 1]).toBe('acceptEdits')
  } finally {
    await r.close({ mode: 'shutdown' })
    await rm(dir, { recursive: true, force: true })
  }
})
test('a session default mode launches without --permission-mode even when the factory is bypass', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'claude-perm-'))
  const trace = join(dir, 'env.json')
  // A broker-shaped factory: bypass in the spec, plus the legacy top-level option (removed from ClaudeOptions)
  // that an old caller may still pass. Neither may widen what the session asked for.
  const factory = { permissions: { kind: 'claude', permissionMode: 'bypassPermissions' }, permissionMode: 'bypassPermissions' }
  const r = await driver({ ENV_TRACE: trace }, factory).open(ctx({ permissions: { kind: 'claude', permissionMode: 'default' } }))
  try {
    const { argv } = JSON.parse(await readFile(trace, 'utf8')) as { argv: string[] }
    expect(argv).not.toContain('--permission-mode')
  } finally {
    await r.close({ mode: 'shutdown' })
    await rm(dir, { recursive: true, force: true })
  }
})
test('allowedTools reach the launch as one comma-joined --allowedTools value, rules with parentheses intact; an empty list passes no flag', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'claude-allow-'))
  const rule = 'Read(//home/u/.mux/session_inboxes/**)'
  const argvOf = async (name: string, extra: Record<string, unknown>) => {
    const trace = join(dir, name)
    const r = await driver({ ENV_TRACE: trace }, extra).open(ctx())
    try { return (JSON.parse(await readFile(trace, 'utf8')) as { argv: string[] }).argv }
    finally { await r.close({ mode: 'shutdown' }) }
  }
  try {
    const argv = await argvOf('with', { allowedTools: [rule, 'Bash(git log:*)'], disallowedTools: ['WebFetch'] })
    expect(argv[argv.indexOf('--allowedTools') + 1]).toBe(`${rule},Bash(git log:*)`)
    expect(argv[argv.indexOf('--disallowedTools') + 1]).toBe('WebFetch')
    expect(argv).not.toContain('--add-dir')
    expect(await argvOf('empty', { allowedTools: [] })).not.toContain('--allowedTools')
    expect(await argvOf('absent', {})).not.toContain('--allowedTools')
  } finally { await rm(dir, { recursive: true, force: true }) }
})

/**
 * How Claude 2.1.308 splits the values after `--allowedTools` (module helper `qu` in the bundled JS, copied
 * from the binary): at commas and spaces OUTSIDE parentheses, once per argv value. Inside `( ... )` both stay.
 */
function claudeCliSplit(values: string[]): string[] {
  const out: string[] = []
  for (const value of values) {
    if (!value) continue
    let current = ''
    let inParens = false
    for (const ch of value) {
      if (ch === '(') { inParens = true; current += ch }
      else if (ch === ')') { inParens = false; current += ch }
      else if (ch === ',') { if (inParens) current += ch; else { if (current.trim()) out.push(current.trim()); current = '' } }
      else if (ch === ' ') { if (inParens) current += ch; else if (current.trim()) { out.push(current.trim()); current = '' } }
      else if (current || ch.trim()) current += ch
    }
    if (current.trim()) out.push(current.trim())
  }
  return out
}

test('rules with spaces inside their parentheses (exact Bash commands) survive the launch: Claude splits the one comma-joined value back into exactly them', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'claude-allow-'))
  const file = '/home/u/.mux/session_inboxes/7c1d2e9a-0000.jsonl'
  const rules = ['Read(//home/u/.mux/session_inboxes/**)', `Bash(cat ${file})`, `Bash(head ${file})`, `Bash(tail ${file})`, `Bash(wc -l ${file})`]
  const trace = join(dir, 'trace')
  const r = await driver({ ENV_TRACE: trace }, { allowedTools: rules }).open(ctx())
  try {
    const { argv } = JSON.parse(await readFile(trace, 'utf8')) as { argv: string[] }
    const at = argv.indexOf('--allowedTools')
    expect(argv[at + 1]).toBe(rules.join(','))        // ONE argv value after the flag ...
    expect(argv[at + 2]?.startsWith('-')).toBe(true)  // ... and a flag right behind it (nothing else is swallowed)
    expect(claudeCliSplit([argv[at + 1]!])).toEqual(rules)
    // The splitter is the same for one argv value per rule, so either way of passing them reads back alike.
    expect(claudeCliSplit(rules)).toEqual(rules)
  } finally {
    await r.close({ mode: 'shutdown' })
    await rm(dir, { recursive: true, force: true })
  }
})
