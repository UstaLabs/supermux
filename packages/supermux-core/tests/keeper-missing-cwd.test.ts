// A session's folder that no longer exists made the agent's spawn fail with "ENOENT: posix_spawn 'claude'", which
// reads as a missing program (Muxie, 2026-11-08). The keeper says what is missing instead.
import { expect, test } from 'bun:test'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { connectKeeper } from '../src/keeper/index.js'

test("a session folder that no longer exists is named as the reason the agent can't start", async () => {
  const dir = mkdtempSync(join(tmpdir(), 'keeper-cwd-'))
  try {
    const gone = join(dir, 'workspace', 'Supermux')
    await expect(connectKeeper({
      stateDirectory: join(dir, 'state'),
      sessionId: 'missing-cwd',
      spec: { command: process.execPath, args: ['-e', ''], cwd: gone, env: { ...(process.env as Record<string, string>) }, frameShape: 'jsonrpc', captureStderr: false },
      limits: { maxFrameBytes: 4096, shutdownTimeoutMs: 1_000, parkedDeadlineMs: 5_000, journalMaxBytes: 64_000, connectTimeoutMs: 15_000 },
      cursor: 0,
    })).rejects.toThrow(`The session's folder ${gone} no longer exists`)
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
