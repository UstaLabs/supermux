import { afterEach, expect, test, setDefaultTimeout } from 'bun:test'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createCore } from '../src/index.js'
import { claude } from '../src/claude/index.js'
import { transport } from '../src/claude/transport.js'
import type { CoreEvent, DriverContext } from '../src/types.js'
import { fakeKeeper } from './fake-keeper.js'

setDefaultTimeout(25_000)
const fixture = fileURLToPath(new URL('./fixtures/claude-agent.mjs', import.meta.url))
const attachScript = fileURLToPath(new URL('./fixtures/claude-keeper-attach.mjs', import.meta.url))
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

function libraryClaude(extra: Record<string, unknown> = {}) {
  return claude({
    id: 'claude',
    command: process.execPath,
    args: [fixture],
    inheritEnv: true,
    tools: [],
    permissionPrompts: 'none',
    permissions: { kind: 'claude', permissionMode: 'dontAsk' },
    partialMessages: false,
    setupTimeoutMs: 5000,
    requestTimeoutMs: 5000,
    shutdownTimeoutMs: 500,
    maxFrameBytes: 16 * 1024 * 1024,
    ...extra,
  } as Parameters<typeof claude>[0])
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

test('process death leaves keeper; reattach skips initialize and seeds owned turn', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'clk-reattach-'))
  dirs.push(stateDirectory)
  const trace = join(stateDirectory, 'trace')
  const info = await runAttach({
    KEEPER_STATE_DIR: stateDirectory,
    KEEPER_SESSION_ID: 'k1',
    KEEPER_PROMPT: 'hang-then-result',
    CLAUDE_FIXTURE: fixture,
    TRACE: trace,
  })
  expect(typeof info.ackedSeq).toBe('number')
  expect(alive(info.keeperPid)).toBe(true)
  expect(alive(info.agentPid)).toBe(true)

  const activity: Array<{ id: string; phase: string }> = []
  const r = await libraryClaude({
    env: { TRACE: trace },
    keeper: { stateDirectory, limits: keeperLimits() },
  }).open(ctx({
    sessionId: 'k1',
    resumeId: info.sessionId,
    onActivity: n => activity.push({ ...n }),
  }))
  try {
    const lines = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(l => {
      try { return JSON.parse(l) } catch { return l }
    })
    const inits = lines.filter((x: any) => x?.type === 'control_request' && x.request?.subtype === 'initialize')
    expect(inits).toHaveLength(1)
    for (let i = 0; i < 80 && !activity.some(a => a.phase === 'started'); i++) await new Promise(x => setTimeout(x, 20))
    expect(activity.some(a => a.phase === 'started')).toBe(true)
    const start = Date.now()
    while (Date.now() - start < 8000 && !activity.some(a => a.phase === 'completed')) {
      await new Promise(x => setTimeout(x, 30))
    }
    expect(activity.some(a => a.phase === 'completed')).toBe(true)
    expect(await r.prompt([{ type: 'text', text: 'hello' }], new AbortController().signal)).toEqual({ stopReason: 'end_turn' })
  } finally {
    await r.close({ mode: 'shutdown' })
  }
  await waitDead(info.keeperPid)
  await waitDead(info.agentPid)
  expect(alive(info.keeperPid)).toBe(false)
  expect(alive(info.agentPid)).toBe(false)
})

test('parked can_use_tool is delivered to the reattached host', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'clk-park-'))
  dirs.push(stateDirectory)
  const trace = join(stateDirectory, 'trace')
  const info = await runAttach({
    KEEPER_STATE_DIR: stateDirectory,
    KEEPER_SESSION_ID: 'k1',
    KEEPER_PROMPT: 'permission',
    CLAUDE_FIXTURE: fixture,
    TRACE: trace,
    PERMISSION_PROMPTS: 'host',
    EXPECT_DEFAULT_DENY: '0',
    EXPECT_PERM: 'allow',
  })
  expect(alive(info.keeperPid)).toBe(true)

  let asked = false
  const r = await libraryClaude({
    env: { TRACE: trace, EXPECT_DEFAULT_DENY: '0', EXPECT_PERM: 'allow' },
    permissionPrompts: 'host',
    keeper: { stateDirectory, limits: keeperLimits() },
  }).open(ctx({
    sessionId: 'k1',
    resumeId: info.sessionId,
    requestPermission: async () => {
      asked = true
      return { outcome: { outcome: 'selected', optionId: 'allow_once' } }
    },
  }))
  try {
    const start = Date.now()
    while (Date.now() - start < 5000) {
      if (asked) break
      await new Promise(x => setTimeout(x, 30))
    }
    expect(asked).toBe(true)
  } finally {
    await r.close({ mode: 'shutdown' })
  }
})

test('orphan agent with dead keeper rejects open naming the pid', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'clk-orphan-'))
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
    await expect(libraryClaude({
      setupTimeoutMs: 2000,
      requestTimeoutMs: 1000,
      shutdownTimeoutMs: 200,
      keeper: { stateDirectory, limits: keeperLimits() },
    }).open(ctx({ sessionId }))).rejects.toThrow(String(agentPid))
  } finally {
    try { process.kill(agentPid, 'SIGKILL') } catch { /* */ }
    await waitDead(agentPid)
  }
})

test('replaced connection fails the previous transport', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'clk-repl-'))
  dirs.push(stateDirectory)
  const sessionId = 'rep'
  const opts = {
    command: process.execPath,
    // The protocol flags the driver always passes. Without them the fixture exits 10 at once, the
    // keeper finishes with it, and the test races that exit: `a` saw `exit` ("Claude process
    // exited") or `b` connected to a keeper already going away (ECONNRESET before its welcome).
    args: [fixture, '--print', '--output-format', 'stream-json', '--verbose', '--input-format', 'stream-json', '--await-initialize', '--tools', '', '--permission-prompts', 'none'],
    env: { ...process.env },
    cwd: process.cwd(),
    requestTimeoutMs: 3000,
    shutdownTimeoutMs: 500,
    maxFrameBytes: 4096,
    sessionId,
    keeper: { stateDirectory, limits: keeperLimits() },
  }
  let failed: Error | undefined
  const a = await transport(opts, () => {}, err => { failed = err })
  a.start()
  const b = await transport(opts, () => {}, () => {})
  b.start()
  const start = Date.now()
  while (Date.now() - start < 3000 && !failed) await new Promise(x => setTimeout(x, 20))
  expect(failed?.message).toBe('replaced')
  await b.close({ mode: 'shutdown' })
})

test('Claude detach keeps keeper and agent; later resume re-attaches', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'clk-detach-'))
  dirs.push(stateDirectory)
  const r = await libraryClaude({
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
  const r2 = await libraryClaude({
    keeper: { stateDirectory, limits: keeperLimits() },
  }).open(ctx({ sessionId: 'k-detach', resumeId: sid }))
  expect(r2.agentSessionId).toBe(sid)
  await r2.close({ mode: 'shutdown' })
  await waitDead(status.keeperPid)
  await waitDead(status.agentPid)
})

test('a keeper another process started under its own runtime dir, too deep for sun_path, is re-attached at its recorded socket', async () => {
  const base = await mkdtemp(join(tmpdir(), 'clk-deep-'))
  dirs.push(base)
  const stateDirectory = join(base, 'x'.repeat(100))
  // The starting process puts the short socket path under its XDG_RUNTIME_DIR; this one has another, or none.
  const otherRuntime = await mkdtemp(join(tmpdir(), 'clk-run-'))
  dirs.push(otherRuntime)
  const trace = join(base, 'trace')
  const info = await runAttach({
    KEEPER_STATE_DIR: stateDirectory,
    KEEPER_SESSION_ID: 'k1',
    CLAUDE_FIXTURE: fixture,
    TRACE: trace,
    XDG_RUNTIME_DIR: otherRuntime,
  })
  try {
    const status = JSON.parse(await readFile(join(stateDirectory, 'keepers', 'k1', 'status.json'), 'utf8'))
    expect(status.socket.startsWith(otherRuntime + '/')).toBe(true)
    const r = await libraryClaude({
      env: { TRACE: trace },
      keeper: { stateDirectory, limits: keeperLimits() },
    }).open(ctx({ sessionId: 'k1', resumeId: info.sessionId }))
    try {
      const lines = (await readFile(trace, 'utf8')).trim().split('\n').map(l => JSON.parse(l))
      // One launch and one initialize: this process joined the agent the other one started.
      expect(lines.filter((x: any) => Array.isArray(x?.argv))).toHaveLength(1)
      expect(lines.filter((x: any) => x?.type === 'control_request' && x.request?.subtype === 'initialize')).toHaveLength(1)
    } finally {
      await r.close({ mode: 'shutdown' })
    }
    await waitDead(info.keeperPid)
    await waitDead(info.agentPid)
    expect(alive(info.keeperPid)).toBe(false)
    expect(alive(info.agentPid)).toBe(false)
  } finally {
    for (const pid of [info.keeperPid, info.agentPid]) if (alive(pid)) try { process.kill(pid, 'SIGKILL') } catch { /* gone */ }
  }
})

test('frames a re-attach replays at once reach the driver after it applied the keeper welcome', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'clk-welcome-'))
  dirs.push(stateDirectory)
  const sid = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
  // The owned turn's frames come with the welcome: before it is seeded, they would start a turn of their own.
  const keeper = await fakeKeeper(stateDirectory, 'k-welcome', { agentSessionId: sid, ownedTurn: { uuid: 'u1' } }, [
    { type: 'assistant', session_id: sid, uuid: 'a1', user_message_uuid: 'u1', message: { role: 'assistant', content: [{ type: 'text', text: 'héllo' }] } },
    { type: 'result', subtype: 'success', is_error: false, session_id: sid, uuid: 'r1', user_message_uuid: 'u1', stop_reason: 'end_turn', result: 'ok', duration_ms: 1, duration_api_ms: 1, num_turns: 1, total_cost_usd: 0, usage: {}, modelUsage: {}, permission_denials: [] },
  ])
  const updates: string[] = []
  const activity: string[] = []
  let ended!: () => void
  const turnEnded = new Promise<void>(resolve => { ended = resolve })
  const r = await libraryClaude({ keeper: { stateDirectory, limits: keeperLimits() } }).open(ctx({
    sessionId: 'k-welcome',
    resumeId: sid,
    onUpdate: u => { updates.push((u.value as { type: string }).type) },
    onActivity: n => { activity.push(`${n.phase} ${n.id}`); if (n.phase === 'completed') ended() },
  }))
  try {
    await turnEnded
    expect(activity).toEqual(['started u1', 'completed u1'])
    expect(updates).toEqual(['assistant', 'result'])
  } finally {
    await r.close({ mode: 'detach' })
    await keeper.close()
  }
})

async function until(done: () => boolean | Promise<boolean>, ms = 5000) {
  const start = Date.now()
  while (!(await done()) && Date.now() - start < ms) await new Promise(x => setTimeout(x, 20))
}

/** Agent → host lines the keeper journaled for a session. */
async function journaled(stateDirectory: string, sessionId: string): Promise<any[]> {
  const raw = await readFile(join(stateDirectory, 'keepers', sessionId, 'journal.ndjson'), 'utf8').catch(() => '')
  return raw.split('\n').filter(Boolean).map(l => JSON.parse(l)).filter(e => e.dir === 'out').map(e => JSON.parse(e.line))
}

test('a turn that ended while no host was attached arrives as normalized events on re-attach', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'clk-ended-'))
  dirs.push(stateDirectory)
  const release = join(stateDirectory, 'release')
  const mk = () => createCore({
    stateDirectory: join(stateDirectory, 'core'),
    limits: { interruptTimeoutMs: 5000, maxPending: 8, outstandingActivity: 64 },
    agents: [libraryClaude({ env: { RELEASE: release }, keeper: { stateDirectory, limits: keeperLimits() } })],
  })
  const core1 = mk()
  const seen: CoreEvent[] = []
  core1.subscribe(e => { seen.push(e) })
  const s1 = await core1.sessions.create({ id: 'ended', agent: 'claude', cwd: process.cwd() })
  const sid = s1.snapshot().agentSessionId
  await s1.send({ content: [{ type: 'text', text: 'until-released' }], whenBusy: 'reject' })
  const native = (type: string) => seen.some(e => e.type === 'session.update' && (e.update.value as any)?.type === type)
  // system/init: the CLI took the prompt and the turn runs.
  await until(() => seen.some(e => e.type === 'session.update' && (e.update.value as any)?.subtype === 'init'))
  const started = native('system')
  await s1.close({ mode: 'detach' })
  await core1.close({ agents: 'detach' })
  // The turn ends only now, with no host attached: the keeper journals its end, the old host never saw it.
  await writeFile(release, '')
  const ended = async () => (await journaled(stateDirectory, 'ended')).some(m => m.type === 'result' && m.session_id === sid)
  await until(ended, 10_000)
  const endedDetached = started && await ended() && !native('result')

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
