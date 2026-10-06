import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const stateDirectory = process.env.KEEPER_STATE_DIR
const sessionId = process.env.KEEPER_SESSION_ID || 'k1'
const prompt = process.env.KEEPER_PROMPT || 'live-hang'
const fixture = process.env.ACP_FIXTURE
if (!stateDirectory || !fixture) {
  process.stderr.write('KEEPER_STATE_DIR and ACP_FIXTURE required\n')
  process.exit(2)
}

const { acp } = await import(new URL('../../src/acp/index.ts', import.meta.url).href)

const r = await acp({
  id: 'fixture',
  command: process.execPath,
  args: [fixture],
  env: {
    ...(process.env.MODE ? { FIXTURE_MODE: process.env.MODE } : {}),
    ...(process.env.TRACE ? { TRACE: process.env.TRACE } : {}),
    ...(process.env.TURN_ID ? { TURN_ID: process.env.TURN_ID } : {}),
    ...(process.env.FINISH_AFTER_MS ? { FINISH_AFTER_MS: process.env.FINISH_AFTER_MS } : {}),
  },
  inheritEnv: true,
  mcpServers: [],
  setupTimeoutMs: 5000,
  shutdownTimeoutMs: 500,
  maxFrameBytes: 16 * 1024 * 1024,
  maxOutstandingActivity: 256,
  cancelRetryIntervalMs: 250,
  cancelRetryTimeoutMs: 10_000,
  captureStderr: false,
  permissions: { kind: 'acp', policy: 'ask', nativeMode: null },
  keeper: {
    stateDirectory,
    limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 },
  },
}).open({
  sessionId,
  cwd: process.cwd(),
  signal: new AbortController().signal,
  onUpdate() {},
  onExit() {},
  requestPermission: () => new Promise(() => {}),
  requestAnswers: () => new Promise(() => {}),
})

const pending = r.prompt([{ type: 'text', text: prompt }], new AbortController().signal)
void pending.catch(() => {})

const statusPath = join(stateDirectory, 'keepers', sessionId, 'status.json')
const tracePath = process.env.TRACE
const start = Date.now()
while (Date.now() - start < 8000) {
  try {
    const st = JSON.parse(readFileSync(statusPath, 'utf8'))
    const journal = readFileSync(join(stateDirectory, 'keepers', sessionId, 'journal.ndjson'), 'utf8')
    const live = journal.includes('user_message_chunk') || journal.includes('session/update')
    const perm = prompt === 'permission' && (journal.includes('request_permission') || journal.includes('session/request_permission'))
    const ready = prompt === 'permission' ? perm : live
    if (ready && st.meta?.agentSessionId) {
      if (prompt === 'permission') await new Promise(x => setTimeout(x, 80))
      const latest = JSON.parse(readFileSync(statusPath, 'utf8'))
      process.stdout.write(JSON.stringify({
        ackedSeq: latest.ackedSeq ?? 0,
        agentSessionId: r.agentSessionId,
        keeperPid: latest.keeperPid,
        agentPid: latest.agentPid,
      }) + '\n')
      process.exit(0)
    }
  } catch { /* */ }
  await new Promise(x => setTimeout(x, 15))
}
process.stderr.write('attach script timed out\n')
process.exit(1)
