// #29: on Windows an agent CLI is often an npm shim (codex.cmd), and node's spawn runs no .cmd without a shell, so the
// keeper could not start one, or not safely. It starts its agent through @supermux/platform's spawnCommand: a .cmd goes
// through cmd.exe with every argument escaped. And it stops the agent with a tree kill, so the program behind cmd.exe
// stops too. Windows only: no other OS has .cmd shims.
import { expect, setDefaultTimeout, test } from 'bun:test'
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
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

// grok's, opencode's and cursor's commands are bare names, and an npm install of one on Windows is <name>.cmd on PATH.
// Bun's own search finds such a shim, and Windows then runs it through cmd.exe with its arguments unescaped: cmd expands a
// %VAR% in one, splits the line at an &, eats a ^. The keeper finds the shim on its agent's PATH itself and starts it
// like a named shim, every argument escaped. The shim is not in the agent's folder, so only PATH can find it.
test.skipIf(process.platform !== 'win32')('a .cmd agent named bare, as grok and opencode are, starts from its PATH with every argument intact', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'keeper-cmd-bare-'))
  let conn: KeeperConnection | undefined
  try {
    const bin = join(dir, 'bin')
    const work = join(dir, 'work')
    mkdirSync(bin)
    mkdirSync(work)
    // The agent writes down the arguments it was given, then is the echo agent.
    const argvAgent = join(bin, 'argv-agent.mjs')
    writeFileSync(argvAgent, `import { writeFileSync } from 'node:fs'\nwriteFileSync(process.env.ARGV_FILE, JSON.stringify(process.argv.slice(2)))\nawait import(${JSON.stringify(pathToFileURL(fixture).href)})\n`)
    writeFileSync(join(bin, 'bare-echo-agent.cmd'), `@"${process.execPath}" "${argvAgent}" %*\r\n`)
    // The inherited env with [bin] first on PATH, whatever its key's letter case (Windows' is Path).
    const env: Record<string, string> = {}
    let path = ''
    for (const [key, value] of Object.entries(process.env)) {
      if (value === undefined) continue
      if (key.toLowerCase() === 'path') path = value
      else env[key] = value
    }
    const pidFile = join(dir, 'agent.pid')
    const argvFile = join(dir, 'argv.json')
    const args = ['plain', 'a b', '%SUPERMUX_BARE_ARG%', 'amp&er', 'car^et', 'q"uote']
    conn = await connectKeeper({
      stateDirectory: join(dir, 'state'),
      sessionId: 'cmd-agent-bare',
      spec: {
        command: 'bare-echo-agent', args, cwd: work,
        env: { ...env, PATH: `${bin};${path}`, PID_FILE: pidFile, ARGV_FILE: argvFile, SUPERMUX_BARE_ARG: 'expanded' },
        frameShape: 'jsonrpc', captureStderr: false,
      },
      limits: { maxFrameBytes: 4096, shutdownTimeoutMs: 2_000, parkedDeadlineMs: 5_000, journalMaxBytes: 64_000, connectTimeoutMs: 30_000 },
      cursor: 0,
    })
    conn.write(JSON.stringify({ cmd: 'echo', n: 3 }))
    expect(await nextFrame(conn)).toContain('"echoed":3')
    expect(JSON.parse(readFileSync(argvFile, 'utf8'))).toEqual(args)
    await until('the agent to say where it runs', () => { try { return readFileSync(pidFile, 'utf8').length > 0 } catch { return false } })
    const agentPid = Number(readFileSync(pidFile, 'utf8'))
    await conn.shutdown()
    conn = undefined
    await until('the program behind cmd.exe to stop', () => !alive(agentPid))
  } finally {
    await conn?.shutdown().catch(() => {})
    rmSync(dir, { recursive: true, force: true })
  }
})
