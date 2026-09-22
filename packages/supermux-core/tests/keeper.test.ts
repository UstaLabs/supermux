import { afterEach, expect, test, setDefaultTimeout } from 'bun:test'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { mkdir, mkdtemp, readFile } from 'node:fs/promises'
import { createConnection } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { connectKeeper, requestId, responseId, type KeeperConnection } from '../src/keeper/index.js'
import type { KeeperLimits } from '../src/keeper/protocol.js'

setDefaultTimeout(20_000)
const fixture = fileURLToPath(new URL('./fixtures/echo-agent.mjs', import.meta.url))
const live: Array<{ conn?: KeeperConnection; dir: string; sessionId: string }> = []

function limits(over: Partial<KeeperLimits> = {}): KeeperLimits {
  return {
    maxFrameBytes: 4096,
    shutdownTimeoutMs: 200,
    parkedDeadlineMs: 5000,
    journalMaxBytes: 64_000,
    connectTimeoutMs: 4000,
    ...over,
  }
}

function alive(pid: number) {
  try { process.kill(pid, 0); return true } catch { return false }
}

async function dir() {
  return mkdtemp(join(tmpdir(), 'keeper-'))
}

async function open(sessionId: string, extraEnv: Record<string, string> = {}, lim = limits(), cursor: number | 'acked' = 0, stateDir?: string) {
  const stateDirectory = stateDir ?? await dir()
  const conn = await connectKeeper({
    stateDirectory,
    sessionId,
    spec: { command: process.execPath, args: [fixture], cwd: process.cwd(), env: { PATH: process.env.PATH ?? '', ...extraEnv }, frameShape: 'jsonrpc', captureStderr: false },
    limits: lim,
    cursor,
  })
  live.push({ conn, dir: stateDirectory, sessionId })
  return { conn, stateDirectory, sessionId }
}

async function collect(conn: KeeperConnection, n: number, ms = 3000) {
  const out: Array<{ type: string; seq: number; line: string }> = []
  const iter = conn.frames[Symbol.asyncIterator]()
  const t = setTimeout(() => { /* stop wait via race */ }, ms)
  try {
    while (out.length < n) {
      const step = await Promise.race([
        iter.next(),
        new Promise<IteratorResult<any>>(r => setTimeout(() => r({ done: true, value: undefined }), ms)),
      ])
      if (step.done) break
      out.push(step.value)
    }
  } finally { clearTimeout(t) }
  return out
}

async function statusOf(stateDirectory: string, sessionId: string) {
  return JSON.parse(await readFile(join(stateDirectory, 'keepers', sessionId, 'status.json'), 'utf8'))
}

async function waitStatus(stateDirectory: string, sessionId: string, pred: (s: any) => boolean, ms = 3000) {
  const start = Date.now()
  let last: any
  while (Date.now() - start < ms) {
    try {
      last = await statusOf(stateDirectory, sessionId)
      if (pred(last)) return last
    } catch { /* */ }
    await new Promise(r => setTimeout(r, 20))
  }
  return last
}

async function journalOf(stateDirectory: string, sessionId: string) {
  const raw = await readFile(join(stateDirectory, 'keepers', sessionId, 'journal.ndjson'), 'utf8')
  return raw.trim().split('\n').filter(Boolean).map(l => JSON.parse(l))
}

async function waitDead(pid: number, ms = 2000) {
  const start = Date.now()
  while (Date.now() - start < ms) {
    if (!alive(pid)) return
    await new Promise(r => setTimeout(r, 20))
  }
}

async function shutdownAndAssert(conn: KeeperConnection | undefined, stateDirectory: string, sessionId: string) {
  let st: any
  try { st = await statusOf(stateDirectory, sessionId) } catch { st = undefined }
  if (conn) {
    try {
      await Promise.race([
        conn.shutdown(),
        new Promise((_, reject) => setTimeout(() => reject(new Error('shutdown wait cap')), 2500)),
      ])
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      if (!msg.includes('socket closed')) console.error(`shutdown failed for session ${sessionId}:`, e)
      if (st?.keeperPid && alive(st.keeperPid)) { try { process.kill(st.keeperPid, 'SIGTERM') } catch {} }
    }
  } else if (st?.keeperPid && alive(st.keeperPid)) {
    try { process.kill(st.keeperPid, 'SIGTERM') } catch {}
  }
  const keeperPid = st?.keeperPid
  const agentPid = st?.agentPid
  if (keeperPid) await waitDead(keeperPid)
  if (agentPid) await waitDead(agentPid)
  if (keeperPid && alive(keeperPid)) { try { process.kill(keeperPid, 'SIGKILL') } catch {} }
  if (agentPid && alive(agentPid)) { try { process.kill(agentPid, 'SIGKILL') } catch {} }
  if (keeperPid) await waitDead(keeperPid, 500)
  if (agentPid) await waitDead(agentPid, 500)
  if (keeperPid) expect(alive(keeperPid)).toBe(false)
  if (agentPid) expect(alive(agentPid)).toBe(false)
}

afterEach(async () => {
  const copy = live.splice(0)
  for (const item of copy) {
    try {
      await shutdownAndAssert(item.conn, item.dir, item.sessionId)
    } catch (e) {
      console.error(`afterEach cleanup failed for ${item.sessionId}:`, e)
      let st: any
      try { st = await statusOf(item.dir, item.sessionId) } catch { st = undefined }
      if (st?.keeperPid && alive(st.keeperPid)) { try { process.kill(st.keeperPid, 'SIGKILL') } catch {} }
      if (st?.agentPid && alive(st.agentPid)) { try { process.kill(st.agentPid, 'SIGKILL') } catch {} }
      if (st?.keeperPid) expect(alive(st.keeperPid)).toBe(false)
      if (st?.agentPid) expect(alive(st.agentPid)).toBe(false)
      throw e
    }
  }
})

test('frames flow both ways and are journaled with increasing seq', async () => {
  const { conn, stateDirectory, sessionId } = await open('flow')
  conn.write(JSON.stringify({ cmd: 'echo', n: 1 }))
  const frames = await collect(conn, 1)
  expect(frames[0]!.line).toContain('"echoed":1')
  const j = await journalOf(stateDirectory, sessionId)
  expect(j.length).toBeGreaterThanOrEqual(2)
  expect(j[0]!.dir).toBe('in')
  expect(j[1]!.dir).toBe('out')
  expect(j[1]!.seq).toBe(j[0]!.seq + 1)
  const wantSeq = j[j.length - 1]!.seq
  const st = await waitStatus(stateDirectory, sessionId, s => s.lastSeq === wantSeq)
  expect(st.lastSeq).toBe(wantSeq)
  await shutdownAndAssert(conn, stateDirectory, sessionId)
})

test('detach leaves keeper and agent alive; reconnect replays missed frames then live', async () => {
  const { conn, stateDirectory, sessionId } = await open('detach')
  conn.write(JSON.stringify({ cmd: 'echo', n: 1 }))
  const first = await collect(conn, 1)
  const lastSeen = first[0]!.seq
  const st1 = await statusOf(stateDirectory, sessionId)
  await conn.detach()
  expect(alive(st1.keeperPid)).toBe(true)
  expect(alive(st1.agentPid)).toBe(true)
  const again = await open(sessionId, {}, limits(), lastSeen, stateDirectory)
  again.conn.write(JSON.stringify({ cmd: 'echo', n: 2 }))
  const more = await collect(again.conn, 1)
  expect(more.some(f => f.line.includes('"echoed":2'))).toBe(true)
  expect(more.every(f => f.seq > lastSeen)).toBe(true)
  await shutdownAndAssert(again.conn, stateDirectory, sessionId)
})

test('second client replaces the first', async () => {
  const a = await open('replace')
  const replaced = (async () => {
    try {
      for await (const _ of a.conn.frames) { /* until replaced */ }
      return 'ended'
    } catch (e) { return e instanceof Error ? e.message : String(e) }
  })()
  const b = await open('replace', {}, limits(), 0, a.stateDirectory)
  expect(await replaced).toBe('replaced')
  b.conn.write(JSON.stringify({ cmd: 'echo', n: 9 }))
  const frames = await collect(b.conn, 1)
  expect(frames[0]!.line).toContain('"echoed":9')
  await shutdownAndAssert(b.conn, a.stateDirectory, a.sessionId)
})

async function waitJournal(stateDirectory: string, sessionId: string, pred: (e: any[]) => boolean, ms = 3000) {
  const start = Date.now()
  while (Date.now() - start < ms) {
    try { const j = await journalOf(stateDirectory, sessionId); if (pred(j)) return j } catch { /* */ }
    await new Promise(r => setTimeout(r, 20))
  }
  throw new Error('journal wait timed out')
}

test('parked request while detached is delivered on attach and answered', async () => {
  const { conn, stateDirectory, sessionId } = await open('park')
  conn.write(JSON.stringify({ cmd: 'ask-later', delay: 80, askId: 'park-1' }))
  await waitJournal(stateDirectory, sessionId, j => j.some(e => e.dir === 'in' && String(e.line).includes('ask-later')))
  await conn.detach()
  await waitJournal(stateDirectory, sessionId, j => j.some(e => e.dir === 'out' && String(e.line).includes('park-1')))
  const again = await open(sessionId, {}, limits(), 0, stateDirectory)
  const parked: Array<{ type: string; seq: number; line: string }> = []
  const iter = again.conn.frames[Symbol.asyncIterator]()
  const start = Date.now()
  while (Date.now() - start < 3000) {
    const step = await Promise.race([
      iter.next(),
      new Promise<IteratorResult<any>>(r => setTimeout(() => r({ done: true, value: undefined }), 500)),
    ])
    if (step.done || !step.value) continue
    parked.push(step.value)
    if (step.value.type === 'parked') break
  }
  expect(parked.some(f => f.type === 'parked' && f.line.includes('park-1'))).toBe(true)
  again.conn.write(JSON.stringify({ id: 'park-1', result: { ok: true } }))
  let answered = false
  const start2 = Date.now()
  while (Date.now() - start2 < 3000) {
    const step = await Promise.race([
      iter.next(),
      new Promise<IteratorResult<any>>(r => setTimeout(() => r({ done: true, value: undefined }), 500)),
    ])
    if (step.done || !step.value) continue
    if (String(step.value.line).includes('answered')) { answered = true; break }
  }
  expect(answered).toBe(true)
  await shutdownAndAssert(again.conn, stateDirectory, sessionId)
})

test('parked deadline answers the agent with -32001', async () => {
  const traceDir = await dir()
  const trace = join(traceDir, 'trace')
  const { conn, stateDirectory, sessionId } = await open('deadline', { TRACE: trace }, limits({ parkedDeadlineMs: 80 }))
  conn.write(JSON.stringify({ cmd: 'ask-later', delay: 30, askId: 'late-1' }))
  await waitJournal(stateDirectory, sessionId, j => j.some(e => e.dir === 'in' && String(e.line).includes('ask-later')))
  await conn.detach()
  const start = Date.now()
  let raw = ''
  while (Date.now() - start < 3000) {
    try { raw = await readFile(trace, 'utf8') } catch { raw = '' }
    if (raw.includes('-32001')) break
    await new Promise(r => setTimeout(r, 30))
  }
  expect(raw).toContain('-32001')
  expect(raw).toContain('no client attached within deadline')
  const j = await journalOf(stateDirectory, sessionId)
  expect(j.some(e => e.dir === 'in' && String(e.line).includes('-32001'))).toBe(true)
  await shutdownAndAssert(conn, stateDirectory, sessionId)
})

test('journal cap drops oldest and reports firstSeq', async () => {
  const { conn, stateDirectory, sessionId } = await open('cap', {}, limits({ journalMaxBytes: 220 }))
  for (let i = 0; i < 12; i++) {
    conn.write(JSON.stringify({ cmd: 'echo', n: i }))
    const frames = await collect(conn, 1)
    if (frames[0]) conn.ack(frames[0].seq)
  }
  const st = await statusOf(stateDirectory, sessionId)
  expect(st.firstSeq).toBeGreaterThan(1)
  const j = await journalOf(stateDirectory, sessionId)
  expect(j[0]!.seq).toBe(st.firstSeq)
  expect(st.firstSeq).toBeGreaterThan(0)
  await shutdownAndAssert(conn, stateDirectory, sessionId)
})

test('journal cap hysteresis rewrites far fewer times than frames appended', async () => {
  const { conn, stateDirectory, sessionId } = await open('hyst', {}, limits({ journalMaxBytes: 400 }))
  const n = 40
  for (let i = 0; i < n; i++) {
    conn.write(JSON.stringify({ cmd: 'echo', n: i }))
    const frames = await collect(conn, 1)
    if (frames[0]) conn.ack(frames[0].seq)
  }
  const st = await statusOf(stateDirectory, sessionId)
  expect(st.firstSeq).toBeGreaterThan(1)
  expect(st.journalRewrites).toBeGreaterThan(0)
  expect(st.journalRewrites).toBeLessThan(n)
  const j = await journalOf(stateDirectory, sessionId)
  const bytes = (await readFile(join(stateDirectory, 'keepers', sessionId, 'journal.ndjson'))).byteLength
  expect(bytes).toBeLessThanOrEqual(400)
  expect(j[0]!.seq).toBe(st.firstSeq)
  await shutdownAndAssert(conn, stateDirectory, sessionId)
})

test('shutdown terminates stubborn agent and keeper exits', async () => {
  const { conn, stateDirectory, sessionId } = await open('kill', { MODE: 'stubborn' }, limits({ shutdownTimeoutMs: 50 }))
  const st = await statusOf(stateDirectory, sessionId)
  await conn.shutdown()
  await new Promise(r => setTimeout(r, 100))
  expect(alive(st.keeperPid)).toBe(false)
  expect(alive(st.agentPid)).toBe(false)
})

test('stale status with dead pid is reaped and respawned', async () => {
  const stateDirectory = await dir()
  const sessionId = 'stale'
  const keepers = join(stateDirectory, 'keepers', sessionId)
  await mkdir(keepers, { recursive: true })
  await Bun.write(join(keepers, 'status.json'), JSON.stringify({
    keeperPid: 999999, agentPid: 999998, startedAt: 1, lastSeq: 0, firstSeq: 1, updatedAt: 1, meta: {},
  }))
  await Bun.write(join(keepers, 'keeper.sock'), '')
  const { conn } = await open(sessionId, {}, limits(), 0, stateDirectory)
  const st = await statusOf(stateDirectory, sessionId)
  expect(st.keeperPid).not.toBe(999999)
  expect(alive(st.keeperPid)).toBe(true)
  conn.write(JSON.stringify({ cmd: 'echo', n: 1 }))
  const frames = await collect(conn, 1)
  expect(frames[0]!.line).toContain('"echoed":1')
  await shutdownAndAssert(conn, stateDirectory, sessionId)
})

test('wrong token is rejected', async () => {
  const { conn, stateDirectory, sessionId } = await open('tok')
  const sockPath = join(stateDirectory, 'keepers', sessionId, 'keeper.sock')
  const sock = createConnection(sockPath)
  const got: string[] = []
  await new Promise<void>((resolve, reject) => {
    sock.on('connect', () => sock.write(JSON.stringify({ type: 'hello', token: 'nope', cursor: 0 }) + '\n'))
    sock.on('data', d => { got.push(d.toString()); sock.end() })
    sock.on('close', () => resolve())
    sock.on('error', reject)
  })
  expect(got.join('')).toContain('"type":"error"')
  await shutdownAndAssert(conn, stateDirectory, sessionId)
})

test('oversize frame fails honestly', async () => {
  const { conn, stateDirectory, sessionId } = await open('big', {}, limits({ maxFrameBytes: 256 }))
  const drain = (async () => { try { for await (const _ of conn.frames) { /* */ } } catch { /* error frame */ } })()
  conn.write(JSON.stringify({ cmd: 'oversize', n: 2000 }))
  const start = Date.now()
  let st: any
  while (Date.now() - start < 3000) {
    st = await statusOf(stateDirectory, sessionId)
    if (st.error) break
    await new Promise(r => setTimeout(r, 30))
  }
  expect(st.error).toMatch(/frame exceeds/)
  await drain
  await shutdownAndAssert(conn, stateDirectory, sessionId)
})

test('missing limits throw TypeError', () => {
  expect(() => connectKeeper({
    stateDirectory: '/tmp', sessionId: 'x', cursor: 0,
    spec: { command: 'x', args: [], cwd: '/', env: {}, frameShape: 'jsonrpc', captureStderr: false },
    limits: { maxFrameBytes: 1 } as any,
  })).toThrow(TypeError)
})

test('ack records ackedSeq; hello cursor acked skips already-handled frames', async () => {
  const { conn, stateDirectory, sessionId } = await open('ack')
  conn.write(JSON.stringify({ cmd: 'echo', n: 1 }))
  conn.write(JSON.stringify({ cmd: 'echo', n: 2 }))
  const frames = await collect(conn, 2)
  expect(frames).toHaveLength(2)
  conn.ack(frames[0]!.seq)
  const st = await waitStatus(stateDirectory, sessionId, s => s.ackedSeq === frames[0]!.seq)
  expect(st.ackedSeq).toBe(frames[0]!.seq)
  expect(conn.welcome.ackedSeq).toBe(0)
  await conn.detach()
  const again = await open(sessionId, {}, limits(), 'acked', stateDirectory)
  expect(again.conn.welcome.ackedSeq).toBe(frames[0]!.seq)
  const replayed = await collect(again.conn, 1)
  expect(replayed).toHaveLength(1)
  expect(replayed[0]!.seq).toBe(frames[1]!.seq)
  expect(replayed[0]!.line).toContain('"echoed":2')
  await shutdownAndAssert(again.conn, stateDirectory, sessionId)
})

test('replay after reattach carries only agent frames, never the client\'s own lines', async () => {
  const { conn, stateDirectory, sessionId } = await open('replay-dir')
  conn.write(JSON.stringify({ cmd: 'echo', n: 1 }))
  conn.write(JSON.stringify({ cmd: 'echo', n: 2 }))
  await collect(conn, 2)
  await conn.detach()
  const again = await open(sessionId, {}, limits(), 0, stateDirectory)
  const replayed = await collect(again.conn, 2)
  expect(replayed.length).toBe(2)
  expect(replayed.every(f => f.line.includes('"echoed"'))).toBe(true)
  expect(replayed.some(f => f.line.includes('"cmd"'))).toBe(false)
  const j = await journalOf(stateDirectory, sessionId)
  expect(j.filter((e: any) => e.dir === 'in').length).toBe(2)
})

test('response for a previous attacher request is delivered stale; current attacher same id is not', async () => {
  const { conn: a, stateDirectory, sessionId } = await open('stale-rpc')
  a.write(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'ping', delay: 180 }))
  await a.detach()
  live[live.length - 1]!.conn = undefined
  await new Promise(r => setTimeout(r, 250))
  const b = await connectKeeper({
    stateDirectory,
    sessionId,
    spec: { command: process.execPath, args: [fixture], cwd: process.cwd(), env: { PATH: process.env.PATH ?? '' }, frameShape: 'jsonrpc', captureStderr: false },
    limits: limits(),
    cursor: 'acked',
  })
  live.push({ conn: b, dir: stateDirectory, sessionId })
  const first = await collect(b, 1, 4000)
  expect(first.length).toBe(1)
  expect(first[0]!.stale).toBe(true)
  expect(JSON.parse(first[0]!.line).id).toBe(1)
  const j = await journalOf(stateDirectory, sessionId)
  const outResp = j.find((e: any) => e.dir === 'out' && e.line.includes('"id":1') && (e.line.includes('"result"') || e.line.includes('"error"')))
  expect(outResp).toBeTruthy()
  expect(outResp.dir).toBe('out')
  b.write(JSON.stringify({ jsonrpc: '2.0', id: 1, method: 'ping' }))
  const second = await collect(b, 1, 4000)
  expect(second.length).toBe(1)
  expect(second[0]!.stale).toBeUndefined()
  expect(JSON.parse(second[0]!.line).id).toBe(1)
  await shutdownAndAssert(b, stateDirectory, sessionId)
})

test('frameShape is required; classifiers match jsonrpc and claude-control', () => {
  expect(() => connectKeeper({
    stateDirectory: '/tmp', sessionId: 'x', cursor: 0,
    spec: { command: 'x', args: [], cwd: '/', env: {} } as any,
    limits: limits(),
  })).toThrow(TypeError)
  expect(requestId(JSON.stringify({ id: 3, method: 'ping' }), 'jsonrpc')).toBe(3)
  expect(responseId(JSON.stringify({ id: 3, result: {} }), 'jsonrpc')).toBe(3)
  expect(requestId(JSON.stringify({ type: 'control_request', request_id: 'u1', request: { subtype: 'initialize' } }), 'claude-control')).toBe('u1')
  expect(responseId(JSON.stringify({ type: 'control_response', response: { request_id: 'u1', subtype: 'success' } }), 'claude-control')).toBe('u1')
  expect(responseId(JSON.stringify({ type: 'result', session_id: 's' }), 'claude-control')).toBeUndefined()
})

test('claude-control parks a control_request while detached and does not bump maxRequestId', async () => {
  const claudeAsk = `import {createInterface} from 'node:readline'
createInterface({input:process.stdin}).on('line', line => {
  const m = JSON.parse(line)
  if (m.cmd === 'ask') process.stdout.write(JSON.stringify({type:'control_request',request_id:m.askId||'c1',request:{subtype:'can_use_tool'}})+'\\n')
  if (m.type === 'control_response') process.stdout.write(JSON.stringify({answered:m.response?.request_id})+'\\n')
})`
  const stateDirectory = await dir()
  const sessionId = 'claude-park'
  const conn = await connectKeeper({
    stateDirectory,
    sessionId,
    spec: {
      command: process.execPath,
      args: ['-e', claudeAsk],
      cwd: process.cwd(),
      env: { PATH: process.env.PATH ?? '' },
      frameShape: 'claude-control', captureStderr: false,
    },
    limits: limits({ parkedDeadlineMs: 15_000 }),
    cursor: 'acked',
  })
  live.push({ conn, dir: stateDirectory, sessionId })
  expect(conn.welcome.maxRequestId).toBe(0)
  conn.write(JSON.stringify({ cmd: 'ask', askId: 'perm-uuid' }))
  await conn.detach()
  live[live.length - 1]!.conn = undefined
  await waitJournal(stateDirectory, sessionId, j => j.some(e => e.dir === 'out' && String(e.line).includes('perm-uuid')))
  const again = await connectKeeper({
    stateDirectory,
    sessionId,
    spec: {
      command: process.execPath,
      args: ['-e', claudeAsk],
      cwd: process.cwd(),
      env: { PATH: process.env.PATH ?? '' },
      frameShape: 'claude-control', captureStderr: false,
    },
    limits: limits(),
    cursor: 'acked',
  })
  live.push({ conn: again, dir: stateDirectory, sessionId })
  expect(again.welcome.maxRequestId).toBe(0)
  const parked: Array<{ type: string; line: string }> = []
  const iter = again.frames[Symbol.asyncIterator]()
  const start = Date.now()
  while (Date.now() - start < 3000) {
    const step = await Promise.race([
      iter.next(),
      new Promise<IteratorResult<any>>(r => setTimeout(() => r({ done: true, value: undefined }), 500)),
    ])
    if (step.done || !step.value) continue
    parked.push(step.value)
    if (step.value.type === 'parked') break
  }
  expect(parked.some(f => f.type === 'parked' && f.line.includes('perm-uuid'))).toBe(true)
  again.write(JSON.stringify({ type: 'control_response', response: { request_id: 'perm-uuid', subtype: 'success', response: { behavior: 'allow' } } }))
  let answered = false
  const start2 = Date.now()
  while (Date.now() - start2 < 3000) {
    const step = await Promise.race([
      iter.next(),
      new Promise<IteratorResult<any>>(r => setTimeout(() => r({ done: true, value: undefined }), 500)),
    ])
    if (step.done || !step.value) continue
    if (String(step.value.line).includes('answered')) { answered = true; break }
  }
  expect(answered).toBe(true)
  await shutdownAndAssert(again, stateDirectory, sessionId)
})

function pidsForSession(sessionDir: string): number[] {
  const needle = `SUPERMUX_KEEPER_SESSION_DIR=${sessionDir}`
  const pids: number[] = []
  for (const ent of readdirSync('/proc')) {
    if (!/^\d+$/.test(ent)) continue
    try {
      const env = readFileSync(`/proc/${ent}/environ`, 'utf8')
      if (env.split('\0').includes(needle)) pids.push(Number(ent))
    } catch { /* gone */ }
  }
  return pids
}

test('spawned keeper is reaped if status.json never appears', async () => {
  const stateDirectory = await dir()
  const sessionId = 'nostatus'
  const sessionDir = join(stateDirectory, 'keepers', sessionId)
  const pending = connectKeeper({
    stateDirectory,
    sessionId,
    spec: {
      command: process.execPath, args: [fixture], cwd: process.cwd(),
      env: { PATH: process.env.PATH ?? '', SUPERMUX_KEEPER_SKIP_STATUS: '1' },
      frameShape: 'jsonrpc', captureStderr: false,
    },
    limits: limits({ connectTimeoutMs: 400, shutdownTimeoutMs: 200 }),
    cursor: 0,
  })
  let pids: number[] = []
  const start = Date.now()
  while (Date.now() - start < 2000) {
    pids = pidsForSession(sessionDir)
    if (pids.length) break
    await new Promise(r => setTimeout(r, 20))
  }
  await expect(pending).rejects.toThrow()
  expect(pids.length).toBeGreaterThan(0)
  for (const pid of pids) expect(() => process.kill(pid, 0)).toThrow()
  expect(existsSync(join(sessionDir, 'keeper.sock'))).toBe(false)
})

test('session dir is 0700 and journal/status files are 0600', async () => {
  const { conn, stateDirectory, sessionId } = await open('modes')
  const sessionDir = join(stateDirectory, 'keepers', sessionId)
  expect(statSync(sessionDir).mode & 0o777).toBe(0o700)
  for (const name of ['journal.ndjson', 'status.json', 'token']) {
    expect(statSync(join(sessionDir, name)).mode & 0o777).toBe(0o600)
  }
  await shutdownAndAssert(conn, stateDirectory, sessionId)
})

test('journal cap never drops unacked frames; replay is complete then file shrinks after ack', async () => {
  const { conn, stateDirectory, sessionId } = await open('unacked-cap', {}, limits({ journalMaxBytes: 220 }))
  const n = 16
  for (let i = 0; i < n; i++) {
    conn.write(JSON.stringify({ cmd: 'echo', n: i }))
    await collect(conn, 1)
  }
  const before = (await readFile(join(stateDirectory, 'keepers', sessionId, 'journal.ndjson'))).byteLength
  expect(before).toBeGreaterThan(220)
  await conn.detach()
  live[live.length - 1]!.conn = undefined
  const again = await connectKeeper({
    stateDirectory,
    sessionId,
    spec: { command: process.execPath, args: [fixture], cwd: process.cwd(), env: { PATH: process.env.PATH ?? '' }, frameShape: 'jsonrpc', captureStderr: false },
    limits: limits({ journalMaxBytes: 220 }),
    cursor: 'acked',
  })
  live.push({ conn: again, dir: stateDirectory, sessionId })
  const replayed = await collect(again, n, 4000)
  expect(replayed.length).toBe(n)
  for (let i = 0; i < n; i++) {
    expect(JSON.parse(replayed[i]!.line).echoed).toBe(i)
    if (i > 0) expect(replayed[i]!.seq).toBeGreaterThan(replayed[i - 1]!.seq)
  }
  again.ack(replayed[replayed.length - 1]!.seq)
  for (let i = 0; i < 8; i++) {
    again.write(JSON.stringify({ cmd: 'echo', n: 100 + i }))
    const extra = await collect(again, 1)
    if (extra[0]) again.ack(extra[0].seq)
  }
  await waitStatus(stateDirectory, sessionId, s => s.journalRewrites > 0)
  const after = (await readFile(join(stateDirectory, 'keepers', sessionId, 'journal.ndjson'))).byteLength
  expect(after).toBeLessThan(before)
  await shutdownAndAssert(again, stateDirectory, sessionId)
})
