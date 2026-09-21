import { afterEach, expect, test, setDefaultTimeout } from 'bun:test'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createCore } from '../src/index.js'
import { TEST_LIMITS } from "./helpers.js"
import { codex } from '../src/codex/index.js'
import { transport } from '../src/codex/transport.js'
import type { DriverContext } from '../src/types.js'

setDefaultTimeout(25_000)
const fixture = fileURLToPath(new URL('./fixtures/codex-agent.mjs', import.meta.url))
const attachScript = fileURLToPath(new URL('./fixtures/codex-keeper-attach.mjs', import.meta.url))
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

function libraryCodex(extra: Record<string, unknown> = {}) {
  return {
    id: 'codex',
    command: process.execPath,
    args: [fixture],
    inheritEnv: true,
    sandbox: 'read-only' as const,
    approvalPolicy: 'never' as const,
    permissionPrompts: 'none' as const,
    maxFrameBytes: 16 * 1024 * 1024,
    ...extra,
  } as Parameters<typeof codex>[0]
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

test('process death leaves keeper; reattach replays live turn without a second handshake', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ck-reattach-'))
  dirs.push(stateDirectory)
  const trace = join(stateDirectory, 'trace')
  const info = await runAttach({
    KEEPER_STATE_DIR: stateDirectory,
    KEEPER_SESSION_ID: 'k1',
    KEEPER_PROMPT: 'hang',
    CODEX_FIXTURE: fixture,
    MODE: 'overlap-turns',
    TRACE: trace,
  })
  expect(typeof info.ackedSeq).toBe('number')
  expect(alive(info.keeperPid)).toBe(true)
  expect(alive(info.agentPid)).toBe(true)

  const activity: Array<{ id: string; phase: string }> = []
  const r = await codex(libraryCodex({
    command: process.execPath,
    args: [fixture],
    env: { MODE: 'overlap-turns', TRACE: trace },
    setupTimeoutMs: 5000,
    requestTimeoutMs: 5000,
    shutdownTimeoutMs: 500,
    keeper: { stateDirectory, limits: keeperLimits() },
  })).open(ctx({
    sessionId: 'k1',
    resumeId: info.threadId,
    onActivity: n => activity.push({ ...n }),
  }))
  try {
    const lines = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l))
    expect(lines.filter(x => x.method === 'initialize')).toHaveLength(1)
    expect(lines.filter(x => x.method === 'thread/start' || x.method === 'thread/resume' || x.method === 'thread/fork')).toHaveLength(1)
    for (let i = 0; i < 50 && !activity.some(a => a.phase === 'started'); i++) await new Promise(x => setTimeout(x, 20))
    expect(activity.some(a => a.phase === 'started')).toBe(true)
    await r.interrupt()
    const after = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l))
    expect(after.some(x => x.method === 'turn/interrupt')).toBe(true)
  } finally {
    await r.close({ mode: "shutdown" })
  }
  await waitDead(info.keeperPid)
  await waitDead(info.agentPid)
  expect(alive(info.keeperPid)).toBe(false)
  expect(alive(info.agentPid)).toBe(false)
})

test('parked approval is delivered to the reattached host', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ck-park-'))
  dirs.push(stateDirectory)
  const trace = join(stateDirectory, 'trace')
  const info = await runAttach({
    KEEPER_STATE_DIR: stateDirectory,
    KEEPER_SESSION_ID: 'k1',
    KEEPER_PROMPT: 'ask-hang',
    CODEX_FIXTURE: fixture,
    TRACE: trace,
    PERMISSION_PROMPTS: 'host',
    EXPECT_DECISION: 'accept',
  })
  expect(alive(info.keeperPid)).toBe(true)

  let asked = false
  const r = await codex(libraryCodex({
    command: process.execPath,
    args: [fixture],
    env: { TRACE: trace, EXPECT_DECISION: 'accept' },
    setupTimeoutMs: 5000,
    requestTimeoutMs: 8000,
    shutdownTimeoutMs: 500,
    permissionPrompts: 'host',
    keeper: { stateDirectory, limits: keeperLimits() },
  })).open(ctx({
    sessionId: 'k1',
    resumeId: info.threadId,
    requestPermission: async () => {
      asked = true
      return { outcome: { outcome: 'selected', optionId: 'allow_once' } }
    },
  }))
  try {
    const start = Date.now()
    while (Date.now() - start < 5000) {
      const raw = await readFile(trace, 'utf8')
      if (asked && raw.includes('"decision":"accept"')) break
      await new Promise(x => setTimeout(x, 30))
    }
    expect(asked).toBe(true)
    expect(await readFile(trace, 'utf8')).toContain('"decision":"accept"')
  } finally {
    await r.close({ mode: "shutdown" })
  }
})

test('parked approval is listed on reattached Session and respond allow reaches the fixture', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ck-park-session-'))
  dirs.push(stateDirectory)
  const coreDir = await mkdtemp(join(tmpdir(), 'ck-park-core-'))
  dirs.push(coreDir)
  const trace = join(stateDirectory, 'trace')
  const info = await runAttach({
    KEEPER_STATE_DIR: stateDirectory,
    KEEPER_SESSION_ID: 'k1',
    KEEPER_PROMPT: 'ask-hang',
    CODEX_FIXTURE: fixture,
    TRACE: trace,
    PERMISSION_PROMPTS: 'host',
    EXPECT_DECISION: 'accept',
  })
  expect(alive(info.keeperPid)).toBe(true)
  const core = createCore({
    stateDirectory: coreDir,
    agents: [codex(libraryCodex({
      command: process.execPath,
      args: [fixture],
      env: { TRACE: trace, EXPECT_DECISION: 'accept' },
      setupTimeoutMs: 5000,
      requestTimeoutMs: 8000,
      shutdownTimeoutMs: 500,
      permissionPrompts: 'host',
      keeper: { stateDirectory, limits: keeperLimits() },
    }))],
    limits: TEST_LIMITS,
  })
  try {
    await core.sessions.adopt({
      id: 'k1',
      agent: 'codex',
      agentSessionId: info.threadId,
      cwd: process.cwd(),
    })
    const session = await core.sessions.resume('k1')
    const start = Date.now()
    while (session.requests.list().length === 0 && Date.now() - start < 5000) await new Promise(x => setTimeout(x, 30))
    const pending = session.requests.list()
    expect(pending.length).toBeGreaterThan(0)
    await session.requests.respond(pending[0]!.requestId, { optionId: 'allow_once' })
    const wait = Date.now()
    while (Date.now() - wait < 5000) {
      if ((await readFile(trace, 'utf8')).includes('"decision":"accept"')) break
      await new Promise(x => setTimeout(x, 30))
    }
    expect(await readFile(trace, 'utf8')).toContain('"decision":"accept"')
    await session.close({ mode: "shutdown" })
  } finally {
    await core.close({ agents: "shutdown" }).catch(() => {})
  }
})

test('orphan agent with dead keeper rejects open naming the pid', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ck-orphan-'))
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
    await expect(codex(libraryCodex({
      command: process.execPath,
      args: [fixture],
      setupTimeoutMs: 2000,
      requestTimeoutMs: 1000,
      shutdownTimeoutMs: 200,
      keeper: { stateDirectory, limits: keeperLimits() },
    })).open(ctx({ sessionId }))).rejects.toThrow(String(agentPid))
  } finally {
    try { process.kill(agentPid, 'SIGKILL') } catch { /* */ }
    await waitDead(agentPid)
  }
})

test('replaced connection fails the previous transport', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ck-repl-'))
  dirs.push(stateDirectory)
  const sessionId = 'rep'
  const opts = {
    command: process.execPath,
    args: [fixture],
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
  const b = await transport(opts, () => {}, () => {})
  const start = Date.now()
  while (Date.now() - start < 3000 && !failed) await new Promise(x => setTimeout(x, 20))
  expect(failed?.message).toBe('replaced')
  await b.close({ mode: 'shutdown' })
})

test('Codex detach keeps keeper and agent; later resume re-attaches', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ck-detach-'))
  dirs.push(stateDirectory)
  const r = await codex(libraryCodex({
    command: process.execPath,
    args: [fixture],
    setupTimeoutMs: 5000,
    requestTimeoutMs: 3000,
    shutdownTimeoutMs: 500,
    keeper: { stateDirectory, limits: keeperLimits() },
  })).open(ctx({ sessionId: 'k-detach' }))
  const statusPath = join(stateDirectory, 'keepers', 'k-detach', 'status.json')
  const status = JSON.parse(await readFile(statusPath, 'utf8'))
  expect(alive(status.keeperPid)).toBe(true)
  expect(alive(status.agentPid)).toBe(true)
  const threadId = r.agentSessionId
  await r.close({ mode: 'detach' })
  expect(alive(status.keeperPid)).toBe(true)
  expect(alive(status.agentPid)).toBe(true)
  const r2 = await codex(libraryCodex({
    command: process.execPath,
    args: [fixture],
    setupTimeoutMs: 5000,
    requestTimeoutMs: 3000,
    shutdownTimeoutMs: 500,
    keeper: { stateDirectory, limits: keeperLimits() },
  })).open(ctx({ sessionId: 'k-detach', resumeId: threadId }))
  expect(r2.agentSessionId).toBe(threadId)
  await r2.close({ mode: 'shutdown' })
  await waitDead(status.keeperPid)
  await waitDead(status.agentPid)
})

test("core.close({agents:'detach'}) leaves keeper-backed agent alive and releases the lock", async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'ck-core-detach-'))
  dirs.push(stateDirectory)
  const core = createCore({
    stateDirectory,
    agents: [codex(libraryCodex({
      command: process.execPath,
      args: [fixture],
      setupTimeoutMs: 5000,
      requestTimeoutMs: 3000,
      shutdownTimeoutMs: 500,
      keeper: { stateDirectory, limits: keeperLimits() },
    }))],
    limits: TEST_LIMITS,
  })
  const session = await core.sessions.create({ id: 'core-detach', agent: 'codex', cwd: process.cwd() })
  const record = session.snapshot()
  const status = JSON.parse(await readFile(join(stateDirectory, 'keepers', 'core-detach', 'status.json'), 'utf8'))
  expect(alive(status.keeperPid)).toBe(true)
  await core.close({ agents: 'detach' })
  expect(alive(status.keeperPid)).toBe(true)
  expect(alive(status.agentPid)).toBe(true)
  const core2 = createCore({
    stateDirectory,
    agents: [codex(libraryCodex({
      command: process.execPath,
      args: [fixture],
      setupTimeoutMs: 5000,
      requestTimeoutMs: 3000,
      shutdownTimeoutMs: 500,
      keeper: { stateDirectory, limits: keeperLimits() },
    }))],
    limits: TEST_LIMITS,
  })
  try {
    const resumed = await core2.sessions.resume('core-detach')
    expect(resumed.snapshot().agentSessionId).toBe(record.agentSessionId)
    await resumed.close({ mode: 'shutdown' })
  } finally {
    await core2.close({ agents: 'shutdown' })
  }
  await waitDead(status.keeperPid)
  await waitDead(status.agentPid)
})
