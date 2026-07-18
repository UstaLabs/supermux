import { randomUUID } from "node:crypto"
import { mkdir, open, readFile, readdir, rename, unlink } from "node:fs/promises"
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

const LOCK_PREFIX = "sessiond.lock."
const PENDING_LOCK_PREFIX = `${LOCK_PREFIX}pending.`
const LOCK_STABILIZE_MS = 30

type LockOwner = { pid: number; token: string }
type Candidate = LockOwner & { name: string; path: string; raw: string }

function parseLockOwner(raw: string): LockOwner | undefined {
  try {
    const value = JSON.parse(raw) as { pid?: unknown; token?: unknown }
    if (!Number.isSafeInteger(value.pid) || (value.pid as number) <= 0 || typeof value.token !== "string" || value.token.length === 0) return
    return { pid: value.pid as number, token: value.token }
  } catch { return }
}

async function removeUniqueCandidate(path: string, expectedRaw: string): Promise<void> {
  try {
    if (await readFile(path, "utf8") !== expectedRaw) return
    await unlink(path)
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error
  }
}

async function liveCandidates(stateDir: string): Promise<Candidate[]> {
  const names = await readdir(stateDir)
  const live: Candidate[] = []
  for (const name of names) {
    if (!name.startsWith(LOCK_PREFIX)) continue
    const path = join(stateDir, name)
    let raw: string
    try { raw = await readFile(path, "utf8") } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") continue
      throw error
    }
    const owner = parseLockOwner(raw)
    if (name.startsWith(PENDING_LOCK_PREFIX)) {
      const filenamePid = Number.parseInt(name.slice(PENDING_LOCK_PREFIX.length).split(".", 1)[0] ?? "", 10)
      const pendingPid = owner?.pid ?? filenamePid
      if (!processIsLive(pendingPid)) await removeUniqueCandidate(path, raw)
      continue
    }
    if (!owner || !processIsLive(owner.pid)) {
      await removeUniqueCandidate(path, raw)
      continue
    }
    live.push({ ...owner, name, path, raw })
  }
  return live.sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0)
}

export async function acquireSessiondLock(stateDir: string): Promise<SessiondLock> {
  await mkdir(stateDir, { recursive: true, mode: 0o700 })
  const token = randomUUID()
  const raw = JSON.stringify({ pid: process.pid, token })
  const pendingName = `${PENDING_LOCK_PREFIX}${process.pid}.${token}`
  const pendingPath = join(stateDir, pendingName)
  let candidatePath: string | undefined
  try {
    const file = await open(pendingPath, "wx", 0o600)
    try { await file.writeFile(raw, "utf8"); await file.sync() } finally { await file.close() }

    // This stamp is allocated only after the unique pending file is durable.
    // A slower contender therefore cannot later publish an earlier candidate.
    const monotonic = process.hrtime.bigint().toString().padStart(24, "0")
    const wall = Date.now().toString().padStart(13, "0")
    const candidateName = `${LOCK_PREFIX}${monotonic}.${wall}.${process.pid}.${token}`
    candidatePath = join(stateDir, candidateName)
    await rename(pendingPath, candidatePath)
    await new Promise(resolveDelay => setTimeout(resolveDelay, LOCK_STABILIZE_MS))

    const contenders = await liveCandidates(stateDir)
    const winner = contenders[0]
    if (!winner || winner.token !== token || winner.path !== candidatePath) {
      await removeUniqueCandidate(candidatePath, raw)
      const owner = winner ? ` (pid ${winner.pid})` : ""
      throw new Error(`mux-sessiond is already running${owner}`)
    }

    let released = false
    return {
      async release() {
        if (released) return
        released = true
        await removeUniqueCandidate(candidatePath!, raw)
      },
    }
  } catch (error) {
    await removeUniqueCandidate(candidatePath ?? pendingPath, raw).catch(() => undefined)
    throw error
  }
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
