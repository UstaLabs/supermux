import { randomUUID } from "node:crypto"
import { mkdir, open, readFile, unlink } from "node:fs/promises"
import { isAbsolute, join, resolve } from "node:path"
import { STATE_DIR } from "../../shared/paths"
import { createProcessJob } from "./job-object"
import { createOrLoadSessiondSecret, sessiondEndpoint } from "./secret"
import { startSessiondServer } from "./server"
import { SessionStore } from "./session-store"

export type SessiondLock = { release(): Promise<void> }

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

function processIsLive(pid: number): boolean {
  if (!Number.isSafeInteger(pid) || pid <= 0) return false
  try { process.kill(pid, 0); return true } catch (error) {
    return (error as NodeJS.ErrnoException).code === "EPERM"
  }
}

export async function acquireSessiondLock(stateDir: string): Promise<SessiondLock> {
  await mkdir(stateDir, { recursive: true, mode: 0o700 })
  const path = join(stateDir, "sessiond.lock")
  for (let attempt = 0; attempt < 4; attempt++) {
    const token = randomUUID()
    try {
      const file = await open(path, "wx", 0o600)
      try { await file.writeFile(JSON.stringify({ pid: process.pid, token }), "utf8"); await file.sync() } finally { await file.close() }
      let released = false
      return {
        async release() {
          if (released) return
          released = true
          try {
            const current = JSON.parse(await readFile(path, "utf8")) as { token?: unknown }
            if (current.token === token) await unlink(path)
          } catch (error) {
            if ((error as NodeJS.ErrnoException).code !== "ENOENT" && !(error instanceof SyntaxError)) throw error
          }
        },
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error
      let owner: { pid?: unknown } = {}
      let raw = ""
      for (let readAttempt = 0; readAttempt < 20; readAttempt++) {
        try {
          raw = await readFile(path, "utf8")
          owner = JSON.parse(raw) as { pid?: unknown }
          break
        } catch (readError) {
          if ((readError as NodeJS.ErrnoException).code === "ENOENT") break
          if (readError instanceof SyntaxError || raw.length === 0) {
            await new Promise(resolveDelay => setTimeout(resolveDelay, 5))
            continue
          }
          throw readError
        }
      }
      if (typeof owner.pid === "number" && processIsLive(owner.pid)) {
        throw new Error(`mux-sessiond is already running (pid ${owner.pid})`)
      }
      try { await unlink(path) } catch (unlinkError) {
        if ((unlinkError as NodeJS.ErrnoException).code !== "ENOENT") throw unlinkError
      }
    }
  }
  throw new Error("could not acquire mux-sessiond single-instance lock")
}

export async function runSessiondMain(argv: string[] = process.argv.slice(2)): Promise<void> {
  const { stateDir } = parseSessiondArgs(argv)
  const lock = await acquireSessiondLock(stateDir)
  let server: Awaited<ReturnType<typeof startSessiondServer>> | undefined
  try {
    if (process.platform === "win32") {
      const probe = createProcessJob()
      probe.close()
    }
    const secret = await createOrLoadSessiondSecret(stateDir)
    const store = new SessionStore()
    server = await startSessiondServer({ endpoint: sessiondEndpoint(stateDir), secret, backend: store })
    await new Promise<void>(resolveShutdown => {
      const shutdown = () => resolveShutdown()
      process.once("SIGINT", shutdown)
      process.once("SIGTERM", shutdown)
    })
  } finally {
    await server?.close()
    await lock.release()
  }
}

if (import.meta.main) {
  runSessiondMain().catch(error => {
    console.error(error instanceof Error ? error.message : String(error))
    process.exitCode = 1
  })
}
