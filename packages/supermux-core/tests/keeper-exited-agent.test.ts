// #29: the keeper sends its agent's signals as a tree kill by pid. Once the agent has exited that pid may be another
// process's (Windows reuses pids quickly), so nothing may go to it then. The keeper hears of the exit on 'close', when
// every pipe has closed, and a program the agent started can hold them long after: in between, a stop's SIGKILL or an
// interrupt still reaches the keeper. Here the agent leads its own process group (setsid), and the program it leaves
// behind is in that group: a signal by the gone agent's pid reaches that program, the way it would reach whatever took
// the pid. Linux: setsid, and /proc for the group.
import { expect, setDefaultTimeout, test } from 'bun:test'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { connectKeeper, type KeeperConnection } from '../src/keeper/index.js'

setDefaultTimeout(60_000)

// On {cmd:'linger'} it starts a program that holds its stdout and stderr (so no 'close' while that runs) and stays in
// its process group; that program says {lingering: pid} on the shared stdout once it is set up (its SIGTERM handler,
// when asked for one, in place). On {cmd:'exit'} the agent exits.
const AGENT = `import { spawn } from 'node:child_process'
import { writeFileSync } from 'node:fs'
import { createInterface } from 'node:readline'
writeFileSync(process.env.AGENT_PID_FILE, String(process.pid))
createInterface({ input: process.stdin }).on('line', line => {
  const m = JSON.parse(line)
  if (m.cmd === 'linger') {
    const body = (m.ignoreTerm ? "process.on('SIGTERM', () => {}); " : '')
      + 'process.stdout.write(JSON.stringify({ lingering: process.pid }) + String.fromCharCode(10)); setInterval(() => {}, 1000)'
    spawn(process.execPath, ['-e', body], { stdio: ['ignore', 'inherit', 'inherit'] })
    return
  }
  if (m.cmd === 'exit') process.exit(0)
})
`

function alive(pid: number): boolean {
  try { process.kill(pid, 0); return true } catch { return false }
}

/** The process group [pid] is in, from /proc/<pid>/stat (the fields after the command name's closing parenthesis). */
function processGroup(pid: number): number {
  const stat = readFileSync(`/proc/${pid}/stat`, 'utf8')
  return Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[2])
}

async function until(what: string, probe: () => boolean, ms = 20_000): Promise<void> {
  const deadline = Date.now() + ms
  while (!probe()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`)
    await Bun.sleep(50)
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

const SHUTDOWN_MS = 1_000

/** A keeper whose agent leads its own process group, and the program that agent left lingering in it. */
async function agentWithLingeringProgram(dir: string, sessionId: string, ignoreTerm: boolean) {
  const script = join(dir, 'agent.mjs')
  writeFileSync(script, AGENT)
  const agentPidFile = join(dir, 'agent.pid')
  const conn = await connectKeeper({
    stateDirectory: join(dir, 'state'),
    sessionId,
    spec: {
      // setsid without a fork (the keeper's child leads no group): the agent's pid is its group's.
      command: 'setsid', args: [process.execPath, script], cwd: dir,
      env: { ...(process.env as Record<string, string>), AGENT_PID_FILE: agentPidFile },
      frameShape: 'jsonrpc', captureStderr: false,
    },
    limits: { maxFrameBytes: 4096, shutdownTimeoutMs: SHUTDOWN_MS, parkedDeadlineMs: 5_000, journalMaxBytes: 64_000, connectTimeoutMs: 30_000 },
    cursor: 0,
  })
  conn.write(JSON.stringify({ cmd: 'linger', ignoreTerm }))
  const lingering = (JSON.parse(await nextFrame(conn)) as { lingering: number }).lingering
  const agentPid = Number(readFileSync(agentPidFile, 'utf8'))
  expect(processGroup(agentPid)).toBe(agentPid)
  expect(processGroup(lingering)).toBe(agentPid)
  return { conn, agentPid, lingering }
}

test.skipIf(process.platform !== 'linux')("a stop's SIGKILL never goes to the pid of an agent that has exited", async () => {
  const dir = mkdtempSync(join(tmpdir(), 'keeper-exited-stop-'))
  let lingering = 0
  try {
    const started = await agentWithLingeringProgram(dir, 'exited-stop', true)
    lingering = started.lingering
    // The stop's SIGTERM ends the agent; the program it started ignores it and keeps the pipes, so no 'close' yet.
    const stopping = started.conn.shutdown()
    await until('the agent to exit', () => !alive(started.agentPid))
    await Bun.sleep(SHUTDOWN_MS + 1_500)
    // The SIGKILL that follows a stop after shutdownTimeoutMs found the agent exited, and sent nothing by its pid.
    expect(alive(lingering)).toBe(true)
    process.kill(lingering, 'SIGKILL')
    await stopping
  } finally {
    if (lingering && alive(lingering)) process.kill(lingering, 'SIGKILL')
    rmSync(dir, { recursive: true, force: true })
  }
})

test.skipIf(process.platform !== 'linux')('an interrupt never goes to the pid of an agent that has exited', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'keeper-exited-int-'))
  let lingering = 0
  try {
    const started = await agentWithLingeringProgram(dir, 'exited-int', false)
    lingering = started.lingering
    started.conn.write(JSON.stringify({ cmd: 'exit' }))
    await until('the agent to exit', () => !alive(started.agentPid))
    // The keeper still runs (the lingering program keeps its pipes open) and takes the interrupt.
    started.conn.signal('SIGINT')
    await Bun.sleep(1_500)
    expect(alive(lingering)).toBe(true)
    process.kill(lingering, 'SIGKILL')
    await started.conn.shutdown()
  } finally {
    if (lingering && alive(lingering)) process.kill(lingering, 'SIGKILL')
    rmSync(dir, { recursive: true, force: true })
  }
})
