import { appendFileSync, closeSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync, readSync, statSync } from "node:fs"
import { join } from "node:path"

export type InboxSender = { name: string; id: string }
export type InboxMessage = { id: string; sender: InboxSender; at: string; text: string }

const SESSION_ID = /^[A-Za-z0-9_-]+$/

let lastMs = -1
let seq = 0

/** Unique and time-sortable in this process: m_ + 9 base-36 chars of ms + 4 of sequence + 4 random. */
export function newMessageId(now: number = Date.now()): string {
  if (now === lastMs) seq += 1
  else { lastMs = now; seq = 0 }
  const rand = Math.floor(Math.random() * 36 ** 4).toString(36).padStart(4, "0")
  return `m_${now.toString(36).padStart(9, "0")}${seq.toString(36).padStart(4, "0")}${rand}`
}

function endsWithNewline(path: string): boolean {
  if (!existsSync(path)) return true
  const size = statSync(path).size
  if (size === 0) return true
  const fd = openSync(path, "r")
  try {
    const byte = Buffer.alloc(1)
    readSync(fd, byte, 0, 1, size - 1)
    return byte[0] === 0x0a
  } finally {
    closeSync(fd)
  }
}

/**
 * The session inboxes (spec §1.1): ~/.mux/session_inboxes/<session-id>.jsonl, append-only, written only by
 * supermux. Writes are synchronous, so two sends in this process never interleave.
 */
export class InboxStore {
  constructor(readonly dir: string) {}

  pathFor(sessionId: string): string {
    if (!SESSION_ID.test(sessionId)) throw new Error(`invalid session id: ${sessionId}`)
    return join(this.dir, `${sessionId}.jsonl`)
  }

  append(to: string, input: { sender: InboxSender; text: string }, now: Date = new Date()): InboxMessage {
    const path = this.pathFor(to)
    const message: InboxMessage = { id: newMessageId(now.getTime()), sender: input.sender, at: now.toISOString(), text: input.text }
    mkdirSync(this.dir, { recursive: true, mode: 0o700 })
    // A crash mid-write can leave a torn last line: start on a fresh line so this one stays whole.
    const prefix = endsWithNewline(path) ? "" : "\n"
    appendFileSync(path, `${prefix}${JSON.stringify(message)}\n`, { mode: 0o600 })
    return message
  }

  /** Every message, in file order. A malformed line (a torn write) is skipped. */
  read(sessionId: string): InboxMessage[] {
    const path = this.pathFor(sessionId)
    if (!existsSync(path)) return []
    const out: InboxMessage[] = []
    for (const line of readFileSync(path, "utf8").split("\n")) {
      if (!line.trim()) continue
      try {
        out.push(JSON.parse(line) as InboxMessage)
      } catch {
        // torn write
      }
    }
    return out
  }

  /** The messages after `lastId`; all of them when it is undefined or no longer in the file. */
  after(sessionId: string, lastId: string | undefined): InboxMessage[] {
    const all = this.read(sessionId)
    if (!lastId) return all
    const index = all.findIndex((m) => m.id === lastId)
    return index < 0 ? all : all.slice(index + 1)
  }

  /** Ids of the sessions that have an inbox file. */
  sessions(): string[] {
    if (!existsSync(this.dir)) return []
    return readdirSync(this.dir).filter((f) => f.endsWith(".jsonl")).map((f) => f.slice(0, -".jsonl".length))
  }
}
