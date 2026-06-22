import type { Message } from "../session-manager/messages"

export interface VoicePayload {
  draft: string
  context: { recentMessages: { role: "user" | "assistant"; text: string }[]; skills: string[] }
}

export function buildVoicePayload(
  draft: string,
  messages: Pick<Message, "direction" | "text">[],
  skills: string[],
): VoicePayload {
  const recentMessages = messages
    .filter((m) => typeof m.text === "string" && m.text.trim().length > 0)
    .map((m) => ({ role: m.direction === "inbound" ? "user" as const : "assistant" as const, text: m.text!.trim() }))
  return { draft, context: { recentMessages, skills } }
}
