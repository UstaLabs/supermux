import { appendFileSync, closeSync, existsSync, mkdirSync, openSync, readdirSync, readFileSync, readSync, statSync } from "node:fs"
import { inboxFilePath } from "../../shared/paths"

export type InboxSender = { name: string; id: string }
export type InboxMessage = { id: string; sender: InboxSender; at: string; text: string }

const SESSION_ID = /^[A-Za-z0-9_-]+$/

let lastMs = -1
let seq = 0

/**
 * Unique and time-sortable in this process: m_ + 9 base-36 chars of ms + 4 of sequence + 4 random.
 * The time never goes backwards, so ids keep increasing even if the clock steps back.
 */
export function newMessageId(now: number = Date.now()): string {
  const ms = Math.max(now, lastMs)
  if (ms === lastMs) seq += 1
  else { lastMs = ms; seq = 0 }
  const rand = Math.floor(Math.random() * 36 ** 4).toString(36).padStart(4, "0")
  return `m_${ms.toString(36).padStart(9, "0")}${seq.toString(36).padStart(4, "0")}${rand}`
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null
}

/** True for a parsed JSON value shaped like an InboxMessage: string id, at and text, and a sender with a string name and id. */
function isInboxMessage(value: unknown): value is InboxMessage {
  return (
    isRecord(value) &&
    typeof value.id === "string" &&
    typeof value.at === "string" &&
    typeof value.text === "string" &&
    isRecord(value.sender) &&
    typeof value.sender.name === "string" &&
    typeof value.sender.id === "string"
  )
}

/**
 * The session inboxes (spec §1.1): ~/.mux/session_inboxes/<session-id>.jsonl, append-only, written only by
 * supermux. Writes are synchronous, so two sends in this process never interleave.
 */
export class InboxStore {
  constructor(readonly dir: string) {}

  pathFor(sessionId: string): string {
    if (!SESSION_ID.test(sessionId)) throw new Error(`invalid session id: ${sessionId}`)
    return inboxFilePath(sessionId, this.dir)
  }

  append(to: string, input: { sender: InboxSender; text: string }, now: Date = new Date()): InboxMessage {
    const path = this.pathFor(to)
    const message: InboxMessage = {
      id: newMessageId(now.getTime()),
      sender: { name: input.sender.name, id: input.sender.id },
      at: now.toISOString(),
      text: input.text,
    }
    mkdirSync(this.dir, { recursive: true, mode: 0o700 })
    // A crash mid-write can leave a torn last line: start on a fresh line so this one stays whole.
    const prefix = endsWithNewline(path) ? "" : "\n"
    appendFileSync(path, `${prefix}${JSON.stringify(message)}\n`, { mode: 0o600 })
    return message
  }

  /** Every message, in file order. A line that is not valid JSON (a torn write) or not shaped like a message is skipped. */
  read(sessionId: string): InboxMessage[] {
    const path = this.pathFor(sessionId)
    if (!existsSync(path)) return []
    const out: InboxMessage[] = []
    for (const line of readFileSync(path, "utf8").split("\n")) {
      if (!line.trim()) continue
      try {
        const parsed: unknown = JSON.parse(line)
        if (isInboxMessage(parsed)) out.push(parsed)
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

  /** Ids of the sessions that have an inbox file. A file whose name is not a valid session id is not listed. */
  sessions(): string[] {
    if (!existsSync(this.dir)) return []
    return readdirSync(this.dir)
      .filter((f) => f.endsWith(".jsonl"))
      .map((f) => f.slice(0, -".jsonl".length))
      .filter((id) => SESSION_ID.test(id))
  }
}
