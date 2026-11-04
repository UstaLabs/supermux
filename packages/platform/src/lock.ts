// One instance per name and scope (a state directory), held by the kernel: a named pipe on Windows, an abstract socket
// on Linux, an identity-checked loopback port elsewhere (macOS has no abstract sockets, and a socket file outlives a
// crash). The OS drops the lock with its owner, so a crash never leaves it stale and a reused pid never holds it: the
// two ways the file locks (broker.pid, .core.lock) went wrong on Windows (#36). Generalised from sessiond's lock, whose
// endpoint names it keeps, so an older sessiond and a newer one still exclude each other.
import { createHash } from "node:crypto"
import { connect, createServer, type Server, type Socket } from "node:net"
import { isAbsolute, normalize, resolve, win32 } from "node:path"

/** A held lock. Releasing it twice is harmless; dying releases it too. */
export interface InstanceLock {
  release(): Promise<void>
}

export interface SingleInstanceOptions {
  /** The OS whose kind of lock to take; the running one by default (tests force the others). */
  platform?: NodeJS.Platform
}

/** Another process holds [name]'s lock for this scope. */
export class AlreadyRunning extends Error {
  readonly code = "already_running"
  constructor(readonly lockName: string) {
    super(`${lockName} is already running`)
  }
}

/** The scope's hash, as sessiond always spelled it: a Windows path in Windows' case-folded, backslashed form. */
function scopeHash(scope: string, windows: boolean): string {
  const canonical = windows
    ? win32.normalize(win32.isAbsolute(scope) ? scope : win32.resolve(scope)).replaceAll("/", "\\").toLowerCase()
    : normalize(isAbsolute(scope) ? scope : resolve(scope))
  return createHash("sha256").update(canonical).digest("hex").slice(0, 32)
}

/** The Windows pipe that is [name]'s lock for [scope]: binding it is the lock, closing it releases it. */
export function pipeName(name: string, scope: string): string {
  return `\\\\.\\pipe\\supermux-${name}-${scopeHash(scope, true)}-lock`
}

/**
 * Takes [name]'s lock for [scope] (a state directory), or rejects with [AlreadyRunning] while another live process
 * holds it. Hold the returned lock for as long as the process is the instance.
 */
export async function singleInstance(name: string, scope: string, options: SingleInstanceOptions = {}): Promise<InstanceLock> {
  const platform = options.platform ?? process.platform
  const identity = `supermux-${name}-lock:${scopeHash(scope, platform === "win32")}`
  let server: Server
  try {
    if (platform === "win32") server = await listenPathLock(pipeName(name, scope))
    else if (platform === "linux") server = await listenPathLock(`\0${identity}`)
    else server = await listenTcpLock(identity, name)
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "EADDRINUSE") throw new AlreadyRunning(name)
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

function closeServer(server: Server): Promise<void> {
  return new Promise((resolveClose, reject) => server.close((error) => (error ? reject(error) : resolveClose())))
}

function listenPathLock(path: string): Promise<Server> {
  const server = createServer((socket) => socket.destroy())
  return new Promise((resolveListen, reject) => {
    const onError = (error: Error) => {
      server.close()
      reject(error)
    }
    server.once("error", onError)
    server.listen({ path, exclusive: true, readableAll: false, writableAll: false }, () => {
      server.off("error", onError)
      server.on("error", () => {})
      resolveListen(server)
    })
  })
}

/** Sixteen loopback ports derived from the identity: the first free one is the lock. */
function tcpPorts(identity: string): number[] {
  const ports: number[] = []
  for (let index = 0; ports.length < 16; index++) {
    const hash = createHash("sha256").update(`${identity}:${index}`).digest()
    const port = 20_000 + (hash.readUInt32BE(0) % 40_000)
    if (!ports.includes(port)) ports.push(port)
  }
  return ports
}

/** What the listener on [port] says it is (the holder answers its identity and hangs up); undefined for a stranger. */
function probeTcpIdentity(port: number): Promise<string | undefined> {
  return new Promise((resolveProbe) => {
    const socket = connect({ host: "127.0.0.1", port })
    const chunks: Buffer[] = []
    const timer = setTimeout(() => {
      socket.destroy()
      resolveProbe(undefined)
    }, 100)
    const finish = (value?: string) => {
      clearTimeout(timer)
      socket.destroy()
      resolveProbe(value)
    }
    socket.on("data", (chunk) => {
      chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
      if (chunks.reduce((sum, value) => sum + value.length, 0) > 128) finish()
    })
    socket.once("end", () => finish(Buffer.concat(chunks).toString("utf8")))
    socket.once("error", () => finish())
  })
}

async function listenTcpLock(identity: string, name: string): Promise<Server> {
  for (const port of tcpPorts(identity)) {
    const server = createServer((socket: Socket) => socket.end(identity))
    try {
      await new Promise<void>((resolveListen, reject) => {
        const onError = (error: Error) => {
          server.close()
          reject(error)
        }
        server.once("error", onError)
        server.listen({ host: "127.0.0.1", port, exclusive: true }, () => {
          server.off("error", onError)
          resolveListen()
        })
      })
      server.on("error", () => {})
      return server
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EADDRINUSE") throw error
      if ((await probeTcpIdentity(port)) === identity) throw new AlreadyRunning(name)
    }
  }
  throw new Error(`could not take ${name}'s lock: every loopback candidate is taken`)
}
