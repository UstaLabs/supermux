import { readFileSync } from 'node:fs'
import { join } from 'node:path'

const stateDirectory = process.env.KEEPER_STATE_DIR
const sessionId = process.env.KEEPER_SESSION_ID || 'k1'
const prompt = process.env.KEEPER_PROMPT || 'hang'
const fixture = process.env.CLAUDE_FIXTURE
if (!stateDirectory || !fixture) {
  process.stderr.write('KEEPER_STATE_DIR and CLAUDE_FIXTURE required\n')
  process.exit(2)
}

const { claude } = await import(new URL('../../src/claude/index.ts', import.meta.url).href)

const r = await claude({
  id: 'claude',
  command: process.execPath,
  args: [fixture],
  env: {
    ...(process.env.TRACE ? { TRACE: process.env.TRACE } : {}),
    ...(process.env.EXPECT_DEFAULT_DENY ? { EXPECT_DEFAULT_DENY: process.env.EXPECT_DEFAULT_DENY } : {}),
    ...(process.env.EXPECT_PERM ? { EXPECT_PERM: process.env.EXPECT_PERM } : {}),
  },
  inheritEnv: true,
  tools: [],
  permissionPrompts: process.env.PERMISSION_PROMPTS === 'host' ? 'host' : 'none',
  partialMessages: false,
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
})

const pending = r.prompt([{ type: 'text', text: prompt }], new AbortController().signal)
void pending.catch(() => {})

const statusPath = join(stateDirectory, 'keepers', sessionId, 'status.json')
const journalPath = join(stateDirectory, 'keepers', sessionId, 'journal.ndjson')
const start = Date.now()
while (Date.now() - start < 8000) {
  try {
    const st = JSON.parse(readFileSync(statusPath, 'utf8'))
    const journal = readFileSync(journalPath, 'utf8')
    const owned = st.meta?.ownedTurn
    const liveTurn = owned && typeof owned === 'object' && typeof owned.uuid === 'string' && typeof st.meta?.agentSessionId === 'string' && journal.includes(prompt)
    const parkedAsk = prompt === 'permission' || prompt === 'ask-while-detached'
      ? journal.includes('can_use_tool')
      : true
    if (liveTurn && parkedAsk) {
      if (prompt === 'permission' || prompt === 'ask-while-detached') await new Promise(x => setTimeout(x, 80))
      const latest = JSON.parse(readFileSync(statusPath, 'utf8'))
      process.stdout.write(JSON.stringify({
        ackedSeq: latest.ackedSeq ?? 0,
        sessionId: r.agentSessionId,
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
