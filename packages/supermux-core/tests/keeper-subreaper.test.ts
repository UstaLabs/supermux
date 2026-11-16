// supermux #156: the keeper is a child subreaper on Linux, so a process its agent detaches stays its descendant, and it
// reaps such orphans once they exit (Bun waits only for children it spawned, so they would stay zombies otherwise).
// Runs in a child process: the subreaper flag sticks to whichever process sets it.
import { expect, test } from 'bun:test'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

const keeper = join(import.meta.dir, '..', 'src', 'keeper', 'keeper.ts')

test.if(process.platform === 'linux')('a detached grandchild is re-parented to the keeper, stays a zombie until reaped, then is gone', () => {
  const dir = mkdtempSync(join(tmpdir(), 'keeper-reap-'))
  try {
    const orphan = join(dir, 'orphan.sh'), pidFile = join(dir, 'orphan.pid')
    writeFileSync(orphan, `#!/bin/sh\necho $$ > ${pidFile}\nsleep 0.3\n`, { mode: 0o755 })
    const script = `
      const { becomeChildSubreaper } = await import(${JSON.stringify(keeper)})
      const { spawn } = await import('node:child_process')
      const { readFileSync } = await import('node:fs')
      const reap = await becomeChildSubreaper(() => false, 1e9)
      if (!reap.active) throw new Error('the subreaper is not active')
      spawn('bash', ['-c', 'setsid ${orphan} </dev/null >/dev/null 2>&1 & exit 0'], { stdio: 'ignore' })
      const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
      await sleep(150)
      const pid = Number(readFileSync(${JSON.stringify(pidFile)}, 'utf8').trim())
      const st = () => { try { const s = readFileSync('/proc/' + pid + '/stat', 'utf8'); const f = s.slice(s.lastIndexOf(')') + 2).split(' '); return { state: f[0], ppid: Number(f[1]) } } catch { return null } }
      const running = st()
      await sleep(600)
      const exited = st()
      const reaped = reap()
      console.log(JSON.stringify({ adopted: running?.ppid === process.pid, zombie: exited?.state, reaped, gone: st() === null }))
      process.exit(0)
    `
    const r = spawnSync(process.execPath, ['-e', script], { encoding: 'utf8' })
    expect(r.stderr).toBe('')
    expect(JSON.parse(r.stdout.trim().split('\n').pop()!)).toEqual({ adopted: true, zombie: 'Z', reaped: 1, gone: true })
  } finally { rmSync(dir, { recursive: true, force: true }) }
})
