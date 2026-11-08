// #29: on Windows an agent CLI is often an npm shim (codex.cmd), and node's spawn runs no .cmd without a shell, so the
// keeper could not start one, or not safely. It starts its agent through @supermux/platform's spawnCommand: a .cmd goes
// through cmd.exe with every argument escaped. And it stops the agent with a tree kill, so the program behind cmd.exe
// stops too. Windows only: no other OS has .cmd shims.
import { expect, setDefaultTimeout, test } from 'bun:test'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { connectKeeper, type KeeperConnection } from '../src/keeper/index.js'

setDefaultTimeout(90_000)
const fixture = fileURLToPath(new URL('./fixtures/echo-agent.mjs', import.meta.url))

function alive(pid: number): boolean {
  try { process.kill(pid, 0); return true } catch { return false }
}

async function until(what: string, probe: () => boolean, ms = 20_000): Promise<void> {
  const deadline = Date.now() + ms
  while (!probe()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`)
    await Bun.sleep(100)
  }
}

async function nextFrame(conn: KeeperConnection, ms = 20_000): Promise<string> {
  const step = await Promise.race([
    conn.frames[Symbol.asyncIterator]().next(),
    new Promise<IteratorResult<never>>(resolve => setTimeout(() => resolve({ done: true, value: undefined as never }), ms)),
  ])
  if (step.done) throw new Error('no frame from the agent')
  return step.value.line
}

test.skipIf(process.platform !== 'win32')('the keeper starts a .cmd agent, talks to it, and stops it and the program behind it', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'keeper-cmd-'))
  try {
    // An npm-style shim: cmd.exe runs it, and it starts the real program (the echo agent) as its child.
    const shim = join(dir, 'echo agent.cmd')
    writeFileSync(shim, `@"${process.execPath}" "${fixture}" %*\r\n`)
    const pidFile = join(dir, 'agent.pid')
    const conn = await connectKeeper({
      stateDirectory: join(dir, 'state'),
      sessionId: 'cmd-agent',
      spec: {
        command: shim, args: [], cwd: dir,
        env: { ...(process.env as Record<string, string>), PID_FILE: pidFile },
        frameShape: 'jsonrpc', captureStderr: false,
      },
      limits: { maxFrameBytes: 4096, shutdownTimeoutMs: 2_000, parkedDeadlineMs: 5_000, journalMaxBytes: 64_000, connectTimeoutMs: 30_000 },
      cursor: 0,
    })
    conn.write(JSON.stringify({ cmd: 'echo', n: 7 }))
    expect(await nextFrame(conn)).toContain('"echoed":7')
    await until('the agent to say where it runs', () => { try { return readFileSync(pidFile, 'utf8').length > 0 } catch { return false } })
    const agentPid = Number(readFileSync(pidFile, 'utf8'))
    expect(alive(agentPid)).toBe(true)

    await conn.shutdown()
    await until('the program behind cmd.exe to stop', () => !alive(agentPid))
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

// An interrupt is TerminateProcess on Windows: sent to the recorded pid alone it ended cmd.exe and left the agent
// behind it running, with no keeper. The keeper treats that pid as the root of the agent's tree.
test.skipIf(process.platform !== 'win32')('an interrupt of a .cmd agent does not leave the program behind cmd.exe running', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'keeper-cmd-int-'))
  try {
    const shim = join(dir, 'echo agent.cmd')
    writeFileSync(shim, `@"${process.execPath}" "${fixture}" %*\r\n`)
    const pidFile = join(dir, 'agent.pid')
    const conn = await connectKeeper({
      stateDirectory: join(dir, 'state'),
      sessionId: 'cmd-agent-int',
      spec: {
        command: shim, args: [], cwd: dir,
        env: { ...(process.env as Record<string, string>), PID_FILE: pidFile },
        frameShape: 'jsonrpc', captureStderr: false,
      },
      limits: { maxFrameBytes: 4096, shutdownTimeoutMs: 2_000, parkedDeadlineMs: 5_000, journalMaxBytes: 64_000, connectTimeoutMs: 30_000 },
      cursor: 0,
    })
    conn.write(JSON.stringify({ cmd: 'echo', n: 1 }))
    expect(await nextFrame(conn)).toContain('"echoed":1')
    await until('the agent to say where it runs', () => { try { return readFileSync(pidFile, 'utf8').length > 0 } catch { return false } })
    const agentPid = Number(readFileSync(pidFile, 'utf8'))
    conn.signal('SIGINT')
    await until('the interrupted program behind cmd.exe to stop', () => !alive(agentPid))
    await conn.shutdown().catch(() => {})
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})
