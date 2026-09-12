import { mkdir, open, readFile, readdir, rename, rm } from "node:fs/promises"
import { isAbsolute, join, resolve } from "node:path"
import { randomUUID } from "node:crypto"
import { CoreError } from "./errors.js"
import type { SessionConfiguration, SessionRecord } from "./types.js"

export class SessionStore {
  readonly directory: string
  private owner = false
  private closed = false

  constructor(directory: string) {
    this.directory = resolve(directory)
  }

  async open(): Promise<void> {
    if (this.owner) return
    if (this.closed) throw new CoreError("store_closed", "The session store is closed")
    await mkdir(this.directory, { recursive: true, mode: 0o700 })
    let lock
    try {
      lock = await open(join(this.directory, ".core.lock"), "wx", 0o600)
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "EEXIST") {
        throw new CoreError("state_locked", "State directory is already owned. After a crash, verify its owner has exited before removing .core.lock.")
      }
      throw error
    }
    this.owner = true
    try {
      await lock.writeFile(JSON.stringify({ pid: process.pid, startedAt: new Date().toISOString() }))
      await mkdir(join(this.directory, "sessions"), { recursive: true, mode: 0o700 })
    } catch (error) {
      await this.close()
      throw error
    } finally { await lock.close() }
  }

  private path(id: string): string {
    if (!/^[a-zA-Z0-9_-]{1,128}$/.test(id)) throw new CoreError("invalid_session_id", "Invalid session ID")
    return join(this.directory, "sessions", `${id}.json`)
  }

  private assertOpen(): void {
    if (!this.owner || this.closed) throw new CoreError("store_closed", "The session store is not open")
  }

  async get(id: string): Promise<SessionRecord | undefined> {
    this.assertOpen()
    let text: string
    try { text = await readFile(this.path(id), "utf8") } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") return undefined
      throw error
    }
    try {
      const value = JSON.parse(text)
      if (!validRecord(value) || value.id !== id) throw new Error("Invalid record shape or identity")
      return value
    } catch (cause) {
      throw new CoreError("invalid_session_record", `Cannot read saved session ${id}`, { cause })
    }
  }

  async list(): Promise<SessionRecord[]> {
    this.assertOpen()
    const files = await readdir(join(this.directory, "sessions"))
    const records: SessionRecord[] = []
    for (const file of files.filter(file => file.endsWith(".json")).sort()) {
      const record = await this.get(file.slice(0, -5))
      if (record) records.push(record)
    }
    return records.sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id))
  }

  async put(record: SessionRecord): Promise<void> {
    this.assertOpen()
    if (!validRecord(record)) throw new CoreError("invalid_session_record", "Invalid saved session")
    const path = this.path(record.id)
    const temp = `${path}.${randomUUID()}.tmp`
    try {
      const file = await open(temp, "wx", 0o600)
      try { await file.writeFile(JSON.stringify(record)); await file.sync() } finally { await file.close() }
      await rename(temp, path)
    } finally { await rm(temp, { force: true }) }
  }

  async remove(id: string): Promise<void> {
    this.assertOpen()
    await rm(this.path(id), { force: true })
  }

  async close(): Promise<void> {
    this.closed = true
    if (this.owner) {
      await rm(join(this.directory, ".core.lock"), { force: true })
      this.owner = false
    }
  }
}

function validRecord(value: unknown): value is SessionRecord {
  if (!value || typeof value !== "object") return false
  const r = value as Record<string, unknown>
  return r.version === 1 && typeof r.id === "string" && typeof r.agent === "string" && !!r.agent
    && typeof r.agentSessionId === "string" && !!r.agentSessionId
    && typeof r.cwd === "string" && isAbsolute(r.cwd)
    && typeof r.createdAt === "string" && Number.isFinite(Date.parse(r.createdAt))
    && (r.authProfile === undefined || typeof r.authProfile === "string")
    && (r.lineage === undefined || (!!r.lineage && typeof r.lineage === "object"
      && typeof (r.lineage as Record<string, unknown>).parentSessionId === "string"
      && ((r.lineage as Record<string, unknown>).nativeTurnId === undefined || typeof (r.lineage as Record<string, unknown>).nativeTurnId === "string")))
    && validConfiguration(r.configuration)
}

function validConfiguration(value: unknown): value is SessionConfiguration | undefined {
  if (value === undefined) return true
  if (!value || typeof value !== "object" || Array.isArray(value)) return false
  const config = value as Record<string, unknown>
  for (const key of Object.keys(config)) {
    if (key !== "model" && key !== "reasoningEffort") return false
    const entry = config[key]
    if (entry !== undefined && (typeof entry !== "string" || !entry)) return false
  }
  return true
}
