import { existsSync, mkdirSync, readFileSync, writeFileSync } from "fs"
import { dirname, join } from "path"
import { STATE_DIR } from "../../shared/paths"
import type { Message } from "./messages"
import type { Session } from "./types"

export const SOUL_SETUP_INVOCATION = "/mux:soul"
export const SOUL_SETUP_CHAT_ID = "web:setup"
export const SOUL_SETUP_STATE_FILE = join(STATE_DIR, "soul-setup.json")

export type SoulSetupStatus = "pending" | "skipped" | "completed"
export interface SoulSetupState {
  status: SoulSetupStatus
  updatedAt?: string
}

export function readSoulSetupState(file = SOUL_SETUP_STATE_FILE): SoulSetupState {
  try {
    if (!existsSync(file)) return { status: "pending" }
    const parsed = JSON.parse(readFileSync(file, "utf8"))
    if (parsed?.status === "skipped" || parsed?.status === "completed" || parsed?.status === "pending") {
      return {
        status: parsed.status,
        updatedAt: typeof parsed.updatedAt === "string" ? parsed.updatedAt : undefined,
      }
    }
  } catch {
    // Corrupt state should not brick onboarding; treat it as pending.
  }
  return { status: "pending" }
}

export function writeSoulSetupState(status: Exclude<SoulSetupStatus, "pending">, file = SOUL_SETUP_STATE_FILE): void {
  mkdirSync(dirname(file), { recursive: true })
  writeFileSync(file, JSON.stringify({ status, updatedAt: new Date().toISOString() }, null, 2) + "\n")
}

export function shouldAutoSendSoulSetup(opts: {
  session: Pick<Session, "role" | "is_default">
  messages: Pick<Message, "direction" | "channel" | "chat_id" | "text">[]
  state: SoulSetupState
  alreadyQueued: boolean
}): boolean {
  if (opts.alreadyQueued) return false
  if (opts.session.role !== "personal_assistant") return false
  if (!opts.session.is_default) return false
  if (opts.state.status !== "pending") return false
  if (opts.messages.some((m) => m.direction === "inbound" && !isSyntheticSoulSetupMessage(m))) return false
  return true
}

function isSyntheticSoulSetupMessage(message: Pick<Message, "channel" | "chat_id" | "text">): boolean {
  return message.channel === "web" && message.chat_id === SOUL_SETUP_CHAT_ID && message.text === SOUL_SETUP_INVOCATION
}

export async function appendSoulSetupInvocation(opts: {
  session: Pick<Session, "id">
  messageLog: { append(sessionId: string, entry: Message): void }
  deliver(sessionId: string, text: string, meta: Record<string, string>): Promise<void>
  now?: () => Date
}): Promise<void> {
  const now = opts.now?.() ?? new Date()
  const ts = now.toISOString()
  const messageId = `soul-setup:${now.getTime()}`
  const entry: Message = {
    id: `in:${SOUL_SETUP_CHAT_ID}:${messageId}`,
    ts,
    direction: "inbound",
    channel: "web",
    chat_id: SOUL_SETUP_CHAT_ID,
    message_id: messageId,
    text: SOUL_SETUP_INVOCATION,
  }
  opts.messageLog.append(opts.session.id, entry)
  await opts.deliver(opts.session.id, SOUL_SETUP_INVOCATION, {
    chat_id: SOUL_SETUP_CHAT_ID,
    message_id: messageId,
    user: "supermux",
    user_id: "system",
    ts,
    system_generated: "soul_setup",
  })
}
