import { isAbsolute, resolve } from "node:path"
import { lock } from "@supermux/platform"
import { STATE_DIR } from "../../shared/paths"
import { createProcessJob } from "./job-object"
import { createOrLoadSessiondSecret, sessiondEndpoint } from "./secret"
import { startSessiondServer } from "./server"
import { SessionStore } from "./session-store"

export type SessiondLock = { release(): Promise<void> }
type CloseableSessiondServer = { close(): Promise<void> }

export async function cleanupSessiondResources(
  server: CloseableSessiondServer | undefined,
  lock: SessiondLock,
): Promise<void> {
  const errors: unknown[] = []
  if (server) {
    try { await server.close() } catch (error) { errors.push(error) }
  }
  try { await lock.release() } catch (error) { errors.push(error) }
  if (errors.length > 0) throw new AggregateError(errors, "sessiond shutdown cleanup failed")
}

export function parseSessiondArgs(argv: string[]): { stateDir: string } {
  let stateDir: string | undefined
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index]!
    let value: string | undefined
    if (argument === "--state-dir") {
      value = argv[++index]
      if (!value || value.startsWith("--")) throw new Error("--state-dir requires a value")
    } else if (argument.startsWith("--state-dir=")) {
      value = argument.slice("--state-dir=".length)
      if (!value) throw new Error("--state-dir requires a value")
    } else {
      throw new Error(`unknown argument: ${argument}`)
    }
    if (stateDir !== undefined) throw new Error("--state-dir specified more than once")
    stateDir = value
  }
  const selected = stateDir ?? STATE_DIR
  return { stateDir: isAbsolute(selected) ? resolve(selected) : resolve(process.cwd(), selected) }
}

/** Stable Windows lock pipe; binding it is the lock and closing it releases it. */
export function sessiondLockEndpoint(stateDir: string): string {
  return lock.pipeName("sessiond", stateDir)
}

export type SessiondLockOptions = { platform?: NodeJS.Platform }

/**
 * sessiond's single-instance lock: the kernel-owned lock the platform package generalised from this one (a pipe on
 * Windows, an abstract socket on Linux, an identity-checked loopback port elsewhere), under the same names.
 */
export async function acquireSessiondLock(stateDir: string, options: SessiondLockOptions = {}): Promise<SessiondLock> {
  return lock.singleInstance("sessiond", stateDir, { platform: options.platform })
}

export async function runSessiondMain(argv: string[] = process.argv.slice(2)): Promise<void> {
  const { stateDir } = parseSessiondArgs(argv)
  const lock = await acquireSessiondLock(stateDir, { platform: process.platform })
  let server: Awaited<ReturnType<typeof startSessiondServer>> | undefined
  let shutdown: (() => void) | undefined
  try {
    if (process.platform === "win32") {
      const probe = createProcessJob()
      probe.close()
    }
    const secret = await createOrLoadSessiondSecret(stateDir)
    const store = new SessionStore()
    server = await startSessiondServer({ endpoint: sessiondEndpoint(stateDir), secret, backend: store })
    await new Promise<void>(resolveShutdown => {
      shutdown = () => {
        process.off("SIGINT", shutdown!)
        process.off("SIGTERM", shutdown!)
        resolveShutdown()
      }
      process.once("SIGINT", shutdown)
      process.once("SIGTERM", shutdown)
    })
  } finally {
    if (shutdown) {
      process.off("SIGINT", shutdown)
      process.off("SIGTERM", shutdown)
    }
    await cleanupSessiondResources(server, lock)
  }
}

if (import.meta.main) {
  runSessiondMain().catch(error => {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  })
}
