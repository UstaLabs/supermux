import { test, expect, setDefaultTimeout } from 'bun:test'
import { fileURLToPath } from 'node:url'
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { cursor, cursorHistoryStorePath } from '../src/cursor/index.js'
import type { DriverContext } from '../src/types.js'

setDefaultTimeout(20_000)
const fixture = fileURLToPath(new URL('./fixtures/cursor-agent.mjs', import.meta.url))
const signal = () => new AbortController().signal
const ctx = (extra: Partial<DriverContext> = {}): DriverContext => ({
  sessionId: 'core', cwd: process.cwd(), signal: signal(), onUpdate() {}, onExit() {},
  requestPermission: async () => ({ outcome: { outcome: 'cancelled' } }), ...extra,
})
const driver = (env: Record<string, string> = {}, extra: Record<string, unknown> = {}) => cursor({
  id: 'cursor', command: process.execPath, args: [fixture], env, inheritEnv: false,
  sandbox: 'enabled', trust: true, force: false, approveMcps: false, mode: 'ask',
  setupTimeoutMs: 5000, shutdownTimeoutMs: 2000, maxFrameBytes: 16 * 1024 * 1024, ...extra,
})
const input = (text: string) => [{ type: 'text' as const, text }]

async function withStore(resumeId: string, cwd: string, env: Record<string, string>) {
  const store = cursorHistoryStorePath(env, cwd, resumeId)
  await mkdir(dirname(store), { recursive: true })
  await writeFile(store, '')
  return store
}

test('native create, streaming result, text-only prompt, and failed turn', async () => {
  const updates: any[] = []
  const r = await driver().open(ctx({ onUpdate: u => updates.push(u) }))
  try {
    expect(r.agentSessionId).toBe('aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
    expect(r.capabilities).toEqual({ resume: true, steer: false, fork: false, detach: false, configure: false, history: false })
    expect(await r.prompt(input('hello'), signal())).toEqual({ stopReason: 'end_turn' })
    expect(updates.some(x => x.protocol === 'native' && x.value.message?.content?.[0]?.text === 'héllo')).toBe(true)
    await expect(r.prompt(input('fail'), signal())).rejects.toThrow('turn failed')
    await expect(r.prompt([{ type: 'resource_link', uri: 'file:///x', name: 'x' }], signal())).rejects.toThrow('does not support')
  } finally { await r.close({ mode: "shutdown" }) }
})

test('resume missing store never creates a replacement; identity mismatch is fatal', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'cursor-resume-'))
  const env = { HOME: dir, CURSOR_CONFIG_DIR: join(dir, 'cfg') }
  const resumeId = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
  const missing = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
  await expect(driver(env).open(ctx({ cwd: dir, resumeId: missing }))).rejects.toThrow('resume store missing')
  await withStore(resumeId, dir, env)
  const r = await driver(env).open(ctx({ cwd: dir, resumeId }))
  expect(r.agentSessionId).toBe(resumeId)
  await r.close({ mode: "shutdown" })
  const exits: Error[] = []
  const bad = await driver({ ...env, MODE: 'wrong-id' }).open(ctx({ cwd: dir, resumeId, onExit: e => { if (e) exits.push(e) } }))
  try {
    await expect(bad.prompt(input('hello'), signal())).rejects.toThrow('identity')
    expect(exits.some(e => /identity/.test(e.message))).toBe(true)
    expect(bad.agentSessionId).toBe(resumeId)
  } finally { await bad.close({ mode: "shutdown" }) }
  await rm(dir, { recursive: true })
})

test('early EOF, empty exit, malformed JSON, and oversize frames reject', async () => {
  for (const text of ['eof', 'empty-exit', 'malformed', 'disconnect']) {
    const r = await driver().open(ctx())
    try { await expect(r.prompt(input(text), signal())).rejects.toThrow() }
    finally { await r.close({ mode: "shutdown" }) }
  }
  const r = await driver({}, { maxFrameBytes: 4096 }).open(ctx())
  try { await expect(r.prompt(input('oversize'), signal())).rejects.toThrow() }
  finally { await r.close({ mode: "shutdown" }) }
})

test('interrupt kills the owned child and reports cancelled', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'cursor-int-'))
  const pidFile = join(dir, 'pid')
  const r = await driver({ PID_FILE: pidFile }).open(ctx())
  try {
    const pending = r.prompt(input('hang'), signal())
    await new Promise(resolve => setTimeout(resolve, 40))
    const pid = Number(readFileSync(pidFile, 'utf8'))
    await r.interrupt()
    expect(await pending).toEqual({ stopReason: 'cancelled' })
    expect(() => process.kill(pid, 0)).toThrow()
  } finally { await r.close({ mode: "shutdown" }); await rm(dir, { recursive: true }) }
})

test('setup timeout and abort during create-chat clean the child', async () => {
  await expect(driver({ MODE: 'setup-hang' }, { setupTimeoutMs: 40 }).open(ctx())).rejects.toThrow(/timed out|aborted|closed/)
  const controller = new AbortController()
  const pending = driver({ MODE: 'setup-hang' }).open(ctx({ signal: controller.signal }))
  setTimeout(() => controller.abort(), 20)
  await expect(pending).rejects.toThrow()
})

test('close during active turn reaps child and is idempotent', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'cursor-close-'))
  const pidFile = join(dir, 'pid')
  const r = await driver({ MODE: 'stubborn', PID_FILE: pidFile }).open(ctx())
  const pending = r.prompt(input('hang'), signal()).catch(e => e)
  await new Promise(resolve => setTimeout(resolve, 40))
  await r.close({ mode: "shutdown" })
  await r.close({ mode: "shutdown" })
  expect(await pending).toBeInstanceOf(Error)
  expect(() => process.kill(Number(readFileSync(pidFile, 'utf8')), 0)).toThrow()
  await rm(dir, { recursive: true })
})

test('read-only defaults and explicit env; create-chat UUID required', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'cursor-argv-'))
  const trace = join(dir, 'trace')
  const created = await driver({ TRACE: trace, CUSTOM: '1' }, { inheritEnv: false, env: { TRACE: trace } }).open(ctx())
  const first = JSON.parse((await readFile(trace, 'utf8')).trim().split('\n')[0]!)
  expect(first.argv).toContain('create-chat')
  expect(first.envKeys).toContain('TRACE')
  expect(first.envKeys).not.toContain('PATH')
  await created.close({ mode: "shutdown" })
  const promptTrace = join(dir, 'prompt')
  const r = await driver({ TRACE: promptTrace }).open(ctx())
  await r.prompt(input('hello'), signal())
  const promptArgv = JSON.parse((await readFile(promptTrace, 'utf8')).trim().split('\n').at(-1)!).argv as string[]
  expect(promptArgv).toContain('--trust')
  expect(promptArgv.slice(promptArgv.indexOf('--mode'), promptArgv.indexOf('--mode') + 2)).toEqual(['--mode', 'ask'])
  expect(promptArgv.slice(promptArgv.indexOf('--sandbox'), promptArgv.indexOf('--sandbox') + 2)).toEqual(['--sandbox', 'enabled'])
  expect(promptArgv).not.toContain('--force')
  expect(promptArgv).not.toContain('--approve-mcps')
  expect(promptArgv).toContain('--resume')
  await r.close({ mode: "shutdown" })
  await expect(driver({ MODE: 'create-invalid' }).open(ctx())).rejects.toThrow('UUID')
  await rm(dir, { recursive: true })
})

test('success result with nonzero exit does not complete the turn', async () => {
  const r = await driver().open(ctx())
  try { await expect(r.prompt(input('result-exit17'), signal())).rejects.toThrow(/failed|exited/) }
  finally { await r.close({ mode: "shutdown" }) }
})

test('result without session id, missing init, and invalid resumeId reject', async () => {
  const missingId = await driver().open(ctx())
  try { await expect(missingId.prompt(input('result-no-id'), signal())).rejects.toThrow(/identity missing/) }
  finally { await missingId.close({ mode: "shutdown" }) }
  const missingInit = await driver().open(ctx())
  try { await expect(missingInit.prompt(input('no-init'), signal())).rejects.toThrow(/init missing/) }
  finally { await missingInit.close({ mode: "shutdown" }) }
  const dir = await mkdtemp(join(tmpdir(), 'cursor-resume-invalid-'))
  await expect(driver({ HOME: dir }).open(ctx({ cwd: dir, resumeId: 'not-a-uuid' }))).rejects.toThrow(/invalid/)
  await rm(dir, { recursive: true })
})

test('result then hung child is bounded by shutdownTimeoutMs', async () => {
  const started = Date.now()
  const r = await driver({}, { shutdownTimeoutMs: 40 }).open(ctx())
  try { await expect(r.prompt(input('result-hang'), signal())).rejects.toThrow(/did not exit/) }
  finally { await r.close({ mode: "shutdown" }) }
  expect(Date.now() - started).toBeLessThan(5000)
})

test('missing executable rejects open without hanging or unhandled rejection', async () => {
  const rejections: unknown[] = []
  const onUnhandled = (reason: unknown) => { rejections.push(reason) }
  process.on('unhandledRejection', onUnhandled)
  try {
    const started = Date.now()
    await expect(cursor({
      id: 'cursor',
      command: '/definitely-missing-supermux-cursor',
      args: [],
      inheritEnv: false,
      sandbox: 'enabled',
      trust: true,
      force: false,
      approveMcps: false,
      setupTimeoutMs: 100,
      shutdownTimeoutMs: 50,
      maxFrameBytes: 16 * 1024 * 1024,
    }).open(ctx())).rejects.toThrow()
    expect(Date.now() - started).toBeLessThan(1000)
    await new Promise(resolve => setTimeout(resolve, 50))
    expect(rejections).toEqual([])
  } finally {
    process.removeListener('unhandledRejection', onUnhandled)
  }
})

test('cursor() TypeError names each missing required field', () => {
  const full: any = { id: 'cursor', command: 'cursor-agent', args: [], inheritEnv: false, sandbox: 'enabled', trust: true, force: false, approveMcps: false, setupTimeoutMs: 1, shutdownTimeoutMs: 1, maxFrameBytes: 1 }
  for (const field of ['id', 'command', 'args', 'inheritEnv', 'sandbox', 'trust', 'force', 'approveMcps', 'setupTimeoutMs', 'shutdownTimeoutMs', 'maxFrameBytes']) {
    const opts = { ...full }; delete opts[field]
    expect(() => cursor(opts)).toThrow(TypeError)
    expect(() => cursor(opts)).toThrow(new RegExp(`Cursor ${field} is required`))
  }
})
