import { randomUUID } from "node:crypto"
import type { RuntimeViewer, SessionBackend } from "../runtime/session-backend"

export type SessiondTerminalKind = "scratch" | "agent"
export type FindExecutable = (name: string) => string | null

export type SessiondTermOptions = {
  backend: SessionBackend
  kind: SessiondTerminalKind
  deviceName: string
  sessionName: string
  terminalId: string
  agentTarget?: string
  workdir: string
  cols: number
  rows: number
  environment?: Readonly<Record<string, string>>
  findExecutable?: FindExecutable
}

const encoder = new TextEncoder()

function hex(value: string): string {
  return Buffer.from(value, "utf8").toString("hex")
}

export function sessiondTerminalGroup(sessionName: string): string {
  return `muxterm-${hex(sessionName)}`
}

export function sessiondTerminalName(terminalId: string): string {
  return `term-${hex(terminalId)}`
}

export function parseSessiondTerminalName(name: string): string | null {
  if (!name.startsWith("term-")) return null
  const encoded = name.slice("term-".length)
  if (encoded.length === 0 || encoded.length % 2 !== 0 || !/^[0-9a-f]+$/.test(encoded)) return null
  try {
    const decoded = Buffer.from(encoded, "hex")
    if (decoded.toString("hex") !== encoded) return null
    return decoded.toString("utf8")
  } catch {
    return null
  }
}

function processEnvironment(source: NodeJS.ProcessEnv = process.env): Record<string, string> {
  const environment: Record<string, string> = {}
  for (const [key, value] of Object.entries(source)) if (typeof value === "string") environment[key] = value
  return environment
}

function defaultFindExecutable(name: string): string | null {
  try {
    return Bun.which(name)
  } catch {
    return null
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export function findPowerShell(findExecutable: FindExecutable = defaultFindExecutable): string {
  for (const name of ["pwsh.exe", "powershell.exe"]) {
    const found = findExecutable(name)
    if (found) return found
  }
  throw new Error("PowerShell was not found (tried pwsh.exe and powershell.exe)")
}

export class SessiondTerm {
  readonly pid?: number
  readonly stdout: ReadableStream<Uint8Array>
  readonly exited: Promise<number>
  readonly stdin: { write(data: Uint8Array | string): boolean }

  private viewer?: RuntimeViewer
  private controller?: ReadableStreamDefaultController<Uint8Array>
  private resolveExited!: (code: number) => void
  private closed = false

  constructor(pid?: number) {
    this.pid = pid
    this.exited = new Promise(resolve => { this.resolveExited = resolve })
    this.stdout = new ReadableStream<Uint8Array>({
      start: controller => { this.controller = controller },
      cancel: () => { this.detach(143) },
    })
    this.stdin = {
      write: data => {
        if (this.closed || !this.viewer) return false
        const value = typeof data === "string" ? encoder.encode(data) : data
        try {
          return this.viewer.write(value)
        } catch {
          return false
        }
      },
    }
  }

  accept(data: Uint8Array): void {
    if (this.closed) return
    try {
      this.controller?.enqueue(data.slice())
    } catch {
      this.detach(143)
    }
  }

  bind(viewer: RuntimeViewer): void {
    if (this.closed) {
      viewer.close()
      return
    }
    this.viewer = viewer
    void viewer.exited?.then(
      code => { this.detach(code) },
      () => { this.detach(1) },
    )
  }

  resize(cols: number, rows: number): boolean {
    if (this.closed || !this.viewer) return false
    try {
      return this.viewer.resize(cols, rows)
    } catch {
      return false
    }
  }

  kill(): void {
    this.detach(143)
  }

  private detach(code: number): void {
    if (this.closed) return
    this.closed = true
    const viewer = this.viewer
    this.viewer = undefined
    try { viewer?.close() } catch {}
    try { this.controller?.close() } catch {}
    this.resolveExited(code)
  }
}

export async function createSessiondTerm(options: SessiondTermOptions): Promise<{
  proc: SessiondTerm
  targetId: string
  created: boolean
}> {
  const { backend } = options
  let targetId: string
  let created = false

  if (options.kind === "agent") {
    if (!options.agentTarget) throw new Error("agent target is required")
    targetId = options.agentTarget
    if (await backend.livePid(targetId) === null) throw new Error("agent target is not alive")
  } else {
    const group = sessiondTerminalGroup(options.sessionName)
    const name = sessiondTerminalName(options.terminalId)
    const resolved = await backend.resolve(group, name)
    if (resolved && await backend.livePid(resolved) !== null) {
      targetId = resolved
    } else {
      if (resolved) await backend.kill(resolved)
      const shell = findPowerShell(options.findExecutable)
      const environment = options.environment ? { ...options.environment } : processEnvironment()
      const target = await backend.create({
        group,
        name,
        cwd: options.workdir,
        argv: [shell, "-NoLogo"],
        env: environment,
        cols: options.cols,
        rows: options.rows,
      })
      targetId = target.id
      created = true
    }
  }

  const proc = new SessiondTerm(await backend.livePid(targetId) ?? undefined)
  const viewerId = `terminal-viewer-${randomUUID().replaceAll("-", "")}`
  try {
    const viewer = await backend.attach(targetId, viewerId, data => { proc.accept(data) })
    proc.bind(viewer)
    return { proc, targetId, created }
  } catch (error) {
    proc.kill()
    if (created) {
      try {
        await backend.kill(targetId)
      } catch (cleanupError) {
        throw new Error(
          `${errorMessage(error)}; target cleanup failed: ${errorMessage(cleanupError)}`,
          { cause: new AggregateError([error, cleanupError]) },
        )
      }
    }
    throw error
  }
}
