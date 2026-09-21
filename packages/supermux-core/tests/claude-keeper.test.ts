import { afterEach, expect, test, setDefaultTimeout } from 'bun:test'
import { spawn } from 'node:child_process'
import { mkdir, mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { claude } from '../src/claude/index.js'
import { transport } from '../src/claude/transport.js'
import type { DriverContext } from '../src/types.js'

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
