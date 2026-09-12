import { mkdir, open, readFile, rename, rm } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { basename, dirname, isAbsolute, join } from 'node:path'
import { CoreError } from '../errors.js'
import type { AgentDriver, AgentRuntime, DriverContext } from '../types.js'

export type AuthLease = { env: Record<string, string>; release(): Promise<void> }
export type AuthProvider = { prepare(context: DriverContext): Promise<AuthLease>; close(): Promise<void> }
export type CopiedCredentialsOptions = {
  source: string
  homesDirectory: string
  filename: string
  homeVariable: string
}

/** A copy transport with cooperative source locking and compare-before-promotion.
 * External login/logout writers must coordinate with the host for atomic account switching.
 * Native history stays in the per-session home after release.
 */
export function copiedCredentials(options: CopiedCredentialsOptions): AuthProvider {
  if (!isAbsolute(options.source) || !isAbsolute(options.homesDirectory)
    || !options.filename || basename(options.filename) !== options.filename || ['.', '..'].includes(options.filename)
    || !/^[A-Za-z_][A-Za-z0-9_]*$/.test(options.homeVariable)) throw new CoreError('invalid_options', 'Auth paths must be absolute and filename/environment variable valid')
  const leases = new Set<AuthLease>()
  return {
    async prepare(context) {
      context.signal.throwIfAborted()
      if (!/^[a-zA-Z0-9_-]{1,128}$/.test(context.sessionId)) throw new CoreError('invalid_session_id', 'Invalid auth session identity')
      const home = join(options.homesDirectory, context.sessionId)
      await mkdir(home, { recursive: true, mode: 0o700 })
      const lockPath = join(home, '.auth.lock')
      let lock
      try { lock = await open(lockPath, 'wx', 0o600) } catch (cause) {
        throw new CoreError('auth_home_locked', 'Agent home is already leased; verify its owner before recovering a stale lock', {cause})
      }
      let baseline: Buffer
      const target = join(home, options.filename)
      try {
        await lock.writeFile(JSON.stringify({pid:process.pid}))
        baseline = await readAuth(options.source)
        await atomicWrite(target, baseline)
        context.signal.throwIfAborted()
      } catch (error) {
        await lock.close(); await rm(lockPath, {force:true}); throw error
      }
      let released = false
      let releasing: Promise<void> | undefined
      const lease: AuthLease = {
        env: { [options.homeVariable]: home },
        release() {
          if (released) return Promise.resolve()
          if (releasing) return releasing
          releasing = (async () => {
            // Preserve a malformed refresh for inspection; don't overwrite valid host credentials.
            const refreshed = await readAuth(target)
            if (!refreshed.equals(baseline)) {
              const sourceLockPath = `${options.source}.supermux-lock`
              let sourceLock
              try { sourceLock = await open(sourceLockPath, 'wx', 0o600) } catch (cause) {
                throw new CoreError('auth_source_locked', 'Credential source is busy; retry auth cleanup', {cause})
              }
              try {
                let current: Buffer | undefined
                try { current = await readFile(options.source) } catch (e) { if ((e as NodeJS.ErrnoException).code !== 'ENOENT') throw e }
                // Missing means logout; changed means another account or refresh won.
                if (current?.equals(baseline)) await atomicWrite(options.source, refreshed)
              } finally { await sourceLock.close(); await rm(sourceLockPath, {force:true}) }
            }
            await lock.close()
            await rm(lockPath, {force:true})
            released = true
            leases.delete(lease)
          })().catch(error => { releasing = undefined; throw error })
          return releasing
        },
      }
      leases.add(lease)
      return lease
    },
    async close() {
      const results = await Promise.allSettled([...leases].map(lease => lease.release()))
      const errors = results.filter((r): r is PromiseRejectedResult => r.status === 'rejected').map(r => r.reason)
      if (errors.length) throw new AggregateError(errors, 'Authentication cleanup failed')
    },
  }
}

async function readAuth(path: string): Promise<Buffer> {
  let data: Buffer
  try { data = await readFile(path) } catch (cause) { throw new CoreError('auth_missing', 'Credential file is unavailable', {cause}) }
  try {
    const parsed: unknown = JSON.parse(data.toString('utf8'))
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) || !Object.keys(parsed).length) throw new Error('empty credentials')
  } catch (cause) { throw new CoreError('auth_invalid', 'Credential file must contain a nonempty JSON object', {cause}) }
  return data
}

async function atomicWrite(path: string, data: Buffer): Promise<void> {
  await mkdir(dirname(path), {recursive:true,mode:0o700})
  const temp = `${path}.${randomUUID()}.tmp`
  try {
    const file = await open(temp, 'wx', 0o600)
    try { await file.writeFile(data); await file.sync() } finally { await file.close() }
    await rename(temp, path)
  } finally { await rm(temp, {force:true}) }
}

/** Apply explicit authentication materialization to any driver. On failed open
 * whose auth cleanup also fails, provider.close() exposes recovery to the host.
 */
export function withAuth(driver: AgentDriver, provider: AuthProvider): AgentDriver {
  return {
    ...driver,
    async open(context) {
      if (context.forkFrom) throw new CoreError('unsupported_operation', 'Per-session copied authentication homes cannot fork native history across homes')
      const lease = await provider.prepare(context)
      let runtime: AgentRuntime
      try {
        runtime = await driver.open({ ...context, profile: { ...context.profile, agent: driver.id,
          env: { ...context.profile?.env, ...lease.env } } })
      } catch (error) {
        try { await lease.release() } catch (cleanup) { throw new AggregateError([error, cleanup], 'Driver open failed; retry provider.close() to release credentials') }
        throw error
      }
      let closing: Promise<void> | undefined
      let stopped = false
      return {
        get agentSessionId() { return runtime.agentSessionId },
        get capabilities() { return {...runtime.capabilities, fork:false} },
        prompt: (content, signal) => runtime.prompt(content, signal),
        interrupt: () => runtime.interrupt(),
        ...(runtime.steer ? {steer: (content: Parameters<NonNullable<AgentRuntime['steer']>>[0]) => runtime.steer!(content)} : {}),
        ...(runtime.configure ? {configure: (configuration: Parameters<NonNullable<AgentRuntime['configure']>>[0]) => runtime.configure!(configuration)} : {}),
        ...(runtime.configuration ? {configuration: () => runtime.configuration!()} : {}),
        ...(runtime.history ? {history: (options: Parameters<NonNullable<AgentRuntime['history']>>[0]) => runtime.history!(options)} : {}),
        close() {
          return closing ??= Promise.resolve().then(async () => {
            if (!stopped) { await runtime.close(); stopped = true }
            await lease.release()
          }).catch(error => { closing = undefined; throw error })
        },
      }
    },
  }
}
