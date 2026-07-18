import { createHash } from "node:crypto"
import { isAbsolute, normalize, resolve } from "node:path"
import { connect, createServer, type Server, type Socket } from "node:net"
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

function stateDirHash(stateDir: string, windows = false): string {
  const absolute = normalize(isAbsolute(stateDir) ? stateDir : resolve(stateDir))
  const canonical = windows ? absolute.replaceAll("/", "\\").toLowerCase() : absolute
  return createHash("sha256").update(canonical).digest("hex").slice(0, 32)
}

/** Stable Windows lock pipe; binding it is the lock and closing it releases it. */
export function sessiondLockEndpoint(stateDir: string): string {
  return `\\\\.\\pipe\\supermux-sessiond-${stateDirHash(stateDir, true)}-lock`
}

export type SessiondLockOptions = { platform?: NodeJS.Platform }

function closeServer(server: Server): Promise<void> {
  return new Promise((resolveClose, reject) => server.close(error => error ? reject(error) : resolveClose()))
}

function listenPathLock(path: string): Promise<Server> {
  const server = createServer(socket => socket.destroy())
  return new Promise((resolveListen, reject) => {
    const onError = (error: Error) => { server.close(); reject(error) }
    server.once("error", onError)
    server.listen({ path, exclusive: true, readableAll: false, writableAll: false }, () => {
      server.off("error", onError)
      server.on("error", () => {})
      resolveListen(server)
    })
  })
}

function tcpPorts(identity: string): number[] {
  const ports: number[] = []
  for (let index = 0; ports.length < 16; index++) {
    const hash = createHash("sha256").update(`${identity}:${index}`).digest()
    const port = 20_000 + (hash.readUInt32BE(0) % 40_000)
    if (!ports.includes(port)) ports.push(port)
  }
  return ports
}

function probeTcpIdentity(port: number): Promise<string | undefined> {
  return new Promise(resolveProbe => {
    const socket = connect({ host: "127.0.0.1", port })
    const chunks: Buffer[] = []
    const timer = setTimeout(() => { socket.destroy(); resolveProbe(undefined) }, 100)
    const finish = (value?: string) => { clearTimeout(timer); socket.destroy(); resolveProbe(value) }
    socket.on("data", chunk => {
      chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
      if (chunks.reduce((sum, value) => sum + value.length, 0) > 128) finish()
    })
    socket.once("end", () => finish(Buffer.concat(chunks).toString("utf8")))
    socket.once("error", () => finish())
  })
}

async function listenTcpLock(identity: string): Promise<Server> {
  for (const port of tcpPorts(identity)) {
    const server = createServer((socket: Socket) => socket.end(identity))
    try {
      await new Promise<void>((resolveListen, reject) => {
        const onError = (error: Error) => { server.close(); reject(error) }
        server.once("error", onError)
        server.listen({ host: "127.0.0.1", port, exclusive: true }, () => {
          server.off("error", onError); resolveListen()
        })
      })
      server.on("error", () => {})
      return server
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EADDRINUSE") throw error
      if (await probeTcpIdentity(port) === identity) throw new Error("mux-sessiond is already running")
    }
  }
  throw new Error("could not acquire mux-sessiond kernel lock: all loopback candidates are occupied")
}

export async function acquireSessiondLock(stateDir: string, options: SessiondLockOptions = {}): Promise<SessiondLock> {
  const platform = options.platform ?? process.platform
  const identity = `supermux-sessiond-lock:${stateDirHash(stateDir, platform === "win32")}`
  let server: Server
  try {
    if (platform === "win32") server = await listenPathLock(sessiondLockEndpoint(stateDir))
    else if (platform === "linux") server = await listenPathLock(`\0${identity}`)
    else server = await listenTcpLock(identity)
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "EADDRINUSE") throw new Error("mux-sessiond is already running")
    throw error
  }
  let released = false
  return {
    async release() {
      if (released) return
      released = true
      await closeServer(server)
    },
  }
}

export async function runSessiondMain(argv: string[] = process.argv.slice(2)): Promise<void> {
  const { stateDir } = parseSessiondArgs(argv)
  const lock = await acquireSessiondLock(stateDir, { platform: process.platform })
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
