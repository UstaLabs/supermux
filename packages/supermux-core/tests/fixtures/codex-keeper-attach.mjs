import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const stateDirectory = process.env.KEEPER_STATE_DIR
const sessionId = process.env.KEEPER_SESSION_ID || 'k1'
const prompt = process.env.KEEPER_PROMPT || 'hang'
const fixture = process.env.CODEX_FIXTURE
if (!stateDirectory || !fixture) {
  process.stderr.write('KEEPER_STATE_DIR and CODEX_FIXTURE required\n')
  process.exit(2)
}

const { codex } = await import(new URL('../../src/codex/index.ts', import.meta.url).href)

const r = await codex({
  id: 'codex',
  command: process.execPath,
  args: [fixture],
  env: {
    ...(process.env.MODE ? { MODE: process.env.MODE } : {}),
    ...(process.env.TRACE ? { TRACE: process.env.TRACE } : {}),
    ...(process.env.EXPECT_DECISION ? { EXPECT_DECISION: process.env.EXPECT_DECISION } : {}),
  },
  inheritEnv: true,
  sandbox: 'read-only',
  approvalPolicy: 'never',
  permissionPrompts: process.env.PERMISSION_PROMPTS === 'host' ? 'host' : 'none',
  permissions: { kind: 'codex', approvalPolicy: 'never', sandbox: 'read-only' },
  setupTimeoutMs: 5000,
  requestTimeoutMs: 8000,
  shutdownTimeoutMs: 500,
  maxFrameBytes: 16 * 1024 * 1024,
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
    const trace = tracePath ? readFileSync(tracePath, 'utf8') : ''
    const journal = readFileSync(join(stateDirectory, 'keepers', sessionId, 'journal.ndjson'), 'utf8')
    // Die only after the turn is live in the journal, so the reattach must rebuild it from
    // keeper meta + replay rather than seeing it arrive live.
    if (trace.includes('"method":"turn/start"') && st.meta?.agentSessionId && journal.includes('turn/started')) {
      if (prompt === 'ask-hang') await new Promise(x => setTimeout(x, 80))
      const latest = JSON.parse(readFileSync(statusPath, 'utf8'))
      process.stdout.write(JSON.stringify({
        ackedSeq: latest.ackedSeq ?? 0,
        threadId: r.agentSessionId,
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
