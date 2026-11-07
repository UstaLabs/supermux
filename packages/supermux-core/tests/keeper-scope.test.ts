// #29: on Linux, the broker's systemd unit kills every process in its cgroup when it stops (the default
// KillMode=control-group), so a restart of the unit took the keepers, their agents and any turn in flight with it,
// whatever the broker asked. Under a systemd service a keeper starts in a scope of its own, as tmux's server does.
import { afterEach, expect, setDefaultTimeout, test } from 'bun:test'
import { readFileSync } from 'node:fs'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { connectKeeper, keeperLaunch, keepersScoped, resetKeepersScopedForTests } from '../src/keeper/client.js'

setDefaultTimeout(20_000)
const fixture = fileURLToPath(new URL('./fixtures/echo-agent.mjs', import.meta.url))

afterEach(() => resetKeepersScopedForTests())

test('a scoped keeper starts through systemd-run, which execs it in place; any other starts directly', () => {
  const entry = { command: '/opt/bun', args: ['/opt/core/keeper.js', 'keeper'] }
  expect(keeperLaunch(entry, true)).toEqual({
    command: 'systemd-run',
    args: ['--user', '--scope', '--quiet', '--collect', '--', '/opt/bun', '/opt/core/keeper.js', 'keeper'],
  })
  expect(keeperLaunch(entry, false)).toEqual(entry)
})

test('keepers are scoped only under a systemd service, where systemd-run works; it is asked once', async () => {
  let probes = 0
  const works = async () => { probes++; return true }
  // No INVOCATION_ID: not a systemd service (macOS, Windows, a terminal, Docker).
  expect(await keepersScoped({}, works)).toBe(false)
  expect(probes).toBe(0)
  expect(await keepersScoped({ INVOCATION_ID: 'a' }, works)).toBe(true)
  expect(await keepersScoped({ INVOCATION_ID: 'a' }, works)).toBe(true)
  expect(probes).toBe(1)
  resetKeepersScopedForTests()
  // No systemd-run, or no user service manager to reach (a container, CI): keepers start as before.
  expect(await keepersScoped({ INVOCATION_ID: 'a' }, async () => false)).toBe(false)
})

// Only where this user's service manager answers (a desktop, a server with lingering); CI runners have none.
const scopesWork = await keepersScoped({ INVOCATION_ID: 'probe' })
resetKeepersScopedForTests()

test.skipIf(!scopesWork)('under a systemd service, a real keeper runs in a scope of its own, outside the service', async () => {
  const saved = process.env.INVOCATION_ID
  process.env.INVOCATION_ID = saved ?? 'keeper-scope-test'
  const stateDirectory = await mkdtemp(join(tmpdir(), 'keeper-scope-'))
  try {
    const conn = await connectKeeper({
      stateDirectory,
      sessionId: 'scoped',
      spec: { command: process.execPath, args: [fixture], cwd: process.cwd(), env: { PATH: process.env.PATH ?? '' }, frameShape: 'jsonrpc', captureStderr: false },
      limits: { maxFrameBytes: 4096, shutdownTimeoutMs: 200, parkedDeadlineMs: 5000, journalMaxBytes: 64_000, connectTimeoutMs: 4000 },
      cursor: 0,
    })
    try {
      const status = JSON.parse(await readFile(join(stateDirectory, 'keepers', 'scoped', 'status.json'), 'utf8'))
      const own = readFileSync('/proc/self/cgroup', 'utf8').trim()
      const keeper = readFileSync(`/proc/${status.keeperPid}/cgroup`, 'utf8').trim()
      const agent = readFileSync(`/proc/${status.agentPid}/cgroup`, 'utf8').trim()
      expect(keeper).not.toBe(own)
      expect(keeper).toMatch(/\.scope$/)
      expect(agent).toBe(keeper)
    } finally {
      await conn.shutdown()
    }
  } finally {
    if (saved === undefined) delete process.env.INVOCATION_ID
    else process.env.INVOCATION_ID = saved
    await rm(stateDirectory, { recursive: true, force: true })
  }
})
