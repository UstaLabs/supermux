import { afterEach, expect, test, setDefaultTimeout } from 'bun:test'
import { fileURLToPath } from 'node:url'
import { mkdtempSync } from 'node:fs'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { cursor, type CursorOptions } from '../src/agents/index.js'
import type { DriverContext } from '../src/types.js'

setDefaultTimeout(25_000)
const fixture = fileURLToPath(new URL('./fixtures/cursor-agent.mjs', import.meta.url))
const attachScript = fileURLToPath(new URL('./fixtures/acp-keeper-attach.mjs', import.meta.url))
const dirs: string[] = []

afterEach(async () => {
  for (const dir of dirs.splice(0)) {
    try { await rm(dir, { recursive: true, force: true }) } catch { /* */ }
  }
})

function signal() { return new AbortController().signal }
function ctx(extra: Partial<DriverContext> = {}): DriverContext {
  return {
    sessionId: 'core',
    cwd: process.cwd(),
    signal: signal(),
    onUpdate() {},
    onExit() {},
    requestPermission: async () => ({ outcome: { outcome: 'cancelled' } }),
    requestAnswers: async () => ({ outcome: 'cancelled' as const }),
    ...extra,
  }
}
function keeper(stateDirectory: string) {
  return { stateDirectory, limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } }
}
function required(extra: Partial<CursorOptions> = {}): CursorOptions {
  const stateDirectory = extra.keeper?.stateDirectory ?? mkdtempSyncDir()
  return {
    id: 'cursor',
    command: process.execPath,
    commandArgs: [fixture],
    permissions: { kind: 'acp', policy: 'auto-approve', nativeMode: 'agent' },
    inheritEnv: true,
    mcpServers: [],
    setupTimeoutMs: 5000,
    shutdownTimeoutMs: 500,
    maxFrameBytes: 16 * 1024 * 1024,
    maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250,
    cancelRetryTimeoutMs: 10_000,
    keeper: extra.keeper ?? keeper(stateDirectory),
    ...extra,
  }
}
function mkdtempSyncDir() {
  const dir = mkdtempSync(join(tmpdir(), 'cursor-k-'))
  dirs.push(dir)
  return dir
}

function driver(env: Record<string, string> = {}, extra: Partial<CursorOptions> = {}) {
  return cursor(required({ env, ...extra }))
}

async function traced(env: Record<string, string> = {}, extra: Partial<CursorOptions> = {}, context: Partial<DriverContext> = {}) {
  const dir = await mkdtemp(join(tmpdir(), 'cursor-'))
  dirs.push(dir)
  const trace = join(dir, 'trace')
  const r = await driver({ ...env, TRACE: trace }, extra).open(ctx(context))
  return {
    r, dir, trace,
    lines: async () => (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l) as Record<string, unknown>),
  }
}

test('cursor() TypeError names each missing required field', () => {
  const full: Record<string, unknown> = {
    id: 'cursor', command: 'cursor-agent', commandArgs: [], permissions: { kind: 'acp', policy: 'auto-approve', nativeMode: 'agent' }, inheritEnv: true, mcpServers: [],
    setupTimeoutMs: 1, shutdownTimeoutMs: 1, maxFrameBytes: 1, maxOutstandingActivity: 1,
    cancelRetryIntervalMs: 1, cancelRetryTimeoutMs: 1,
    keeper: { stateDirectory: '/tmp', limits: { parkedDeadlineMs: 1, journalMaxBytes: 1, connectTimeoutMs: 1 } },
  }
  for (const field of ['id', 'command', 'commandArgs', 'permissions', 'inheritEnv', 'mcpServers', 'setupTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes', 'maxOutstandingActivity', 'keeper', 'cancelRetryIntervalMs', 'cancelRetryTimeoutMs']) {
    const opts = { ...full }
    delete opts[field]
    expect(() => cursor(opts as CursorOptions)).toThrow(TypeError)
    expect(() => cursor(opts as CursorOptions)).toThrow(new RegExp(`Cursor ${field} is required`))
  }
})

test('cursor argv is acp with no force/auto-review flags', async () => {
  const { r, lines } = await traced({})
  try {
    const argv = (await lines()).find(l => Array.isArray(l.argv))?.argv as string[]
    expect(argv).toEqual(['acp'])
    expect(argv).not.toContain('--force')
    expect(argv).not.toContain('--auto-review')
  } finally { await r.close({ mode: 'shutdown' }) }
})

test('sessionConfig model+mode is sent after session/new', async () => {
  const { r, lines } = await traced({}, { model: 'composer-1', mode: 'plan' })
  try {
    const rec = await lines()
    const sets = rec.filter(l => l.setConfig) as Array<{ setConfig: { configId: string; value: string } }>
    // The picker-style name resolves to the option's parameterised wire value.
    expect(sets.map(s => s.setConfig)).toEqual([
      { configId: 'model', value: 'composer-1[fast=true]' },
      { configId: 'mode', value: 'plan' },
    ])
    expect(rec.findIndex(l => l === 'new')).toBeLessThan(rec.findIndex(l => l.setConfig))
  } finally { await r.close({ mode: 'shutdown' }) }
})

test('sessionConfig model+mode is sent after session/load', async () => {
  const { r, lines } = await traced({}, { model: 'composer-1', mode: 'ask' }, { resumeId: 'old' })
  try {
    const rec = await lines()
    expect(rec).toContain('load')
    // session/load carries no option lists: one turn-less session/new reads them
    // (never persisted by the agent) so the picker-style name still resolves.
    expect(rec.indexOf('new')).toBeGreaterThan(rec.indexOf('load'))
    const sets = rec.filter(l => l.setConfig) as Array<{ setConfig: { configId: string; value: string } }>
    expect(sets.map(s => s.setConfig)).toEqual([
      { configId: 'model', value: 'composer-1[fast=true]' },
      { configId: 'mode', value: 'ask' },
    ])
    expect(rec.findIndex(l => l === 'load')).toBeLessThan(rec.findIndex(l => l.setConfig))
  } finally { await r.close({ mode: 'shutdown' }) }
})

test('permission request round trip', async () => {
  const { r } = await traced({}, { permissions: { kind: 'acp', policy: 'ask', nativeMode: 'agent' } }, {
    requestPermission: async () => ({ outcome: { outcome: 'selected', optionId: 'allow' } }),
  })
  try {
    expect(await r.prompt([{ type: 'text', text: 'permission' }], signal())).toEqual({ stopReason: 'end_turn' })
  } finally { await r.close({ mode: 'shutdown' }) }
})

test('resume via session/load keeps the native id', async () => {
  const r = await driver().open(ctx({ resumeId: 'old' }))
  try {
    expect(r.agentSessionId).toBe('old')
    expect(r.capabilities.resume).toBe(true)
    expect(r.capabilities.detach).toBe(true)
    expect(r.capabilities.fork).toBe(false)
    expect(await r.prompt([{ type: 'text', text: 'hello' }], signal())).toEqual({ stopReason: 'end_turn' })
  } finally { await r.close({ mode: 'shutdown' }) }
})

function alive(pid: number) {
  if (!Number.isInteger(pid) || pid < 1) return false
  try { process.kill(pid, 0); return true } catch { return false }
}

async function runAttach(env: Record<string, string>) {
  const child = spawn(process.execPath, [attachScript], {
    env: { ...process.env, ...env },
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let out = '', err = ''
  child.stdout.on('data', d => { out += String(d) })
  child.stderr.on('data', d => { err += String(d) })
  const code: number = await new Promise(resolve => child.on('close', c => resolve(c ?? 1)))
  if (code !== 0) throw new Error(`attach script exited ${code}: ${err}\n${out}`)
  return JSON.parse(out.trim().split('\n').filter(Boolean).at(-1)!) as { agentSessionId: string; keeperPid: number; agentPid: number }
}

test('keeper detach then re-attach skips a second session/new', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'cursor-keep-'))
  dirs.push(stateDirectory)
  const trace = join(stateDirectory, 'trace')
  const r = await driver({ TRACE: trace }, { keeper: keeper(stateDirectory) }).open(ctx({ sessionId: 'k-cur' }))
  const sid = r.agentSessionId
  await r.close({ mode: 'detach' })
  const again = await driver({ TRACE: trace }, { keeper: keeper(stateDirectory) }).open(ctx({ sessionId: 'k-cur', resumeId: sid }))
  try {
    const rec = (await readFile(trace, 'utf8')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l) as unknown)
    expect(rec.filter(l => l === 'new')).toHaveLength(1)
    expect(again.agentSessionId).toBe(sid)
  } finally { await again.close({ mode: 'shutdown' }) }
})

test('parked permission is delivered to the reattached cursor host', async () => {
  const stateDirectory = await mkdtemp(join(tmpdir(), 'cursor-park-'))
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
  const r = await driver({ TRACE: trace }, { keeper: keeper(stateDirectory), permissions: { kind: 'acp', policy: 'ask', nativeMode: 'agent' } }).open(ctx({
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
  } finally { await r.close({ mode: 'shutdown' }) }
})
