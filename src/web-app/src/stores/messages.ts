import { defineStore } from "pinia"
import { ref } from "vue"

export interface AttachmentRef {
  file_id: string
  kind: "photo" | "document" | "voice" | "audio" | "video" | "video_note"
  mime?: string
  size?: number
  name?: string
}

export interface MessageEntry {
  id: string
  ts: string
  direction: "inbound" | "outbound"
  channel: string
  chat_id?: string
  message_id?: string
  text?: string
  edited_at?: string
  reactions?: Array<{ emoji: string; ts: string }>
  attachments?: AttachmentRef[]
}

export const useMessages = defineStore("messages", () => {
  const bySession = ref<Record<string, MessageEntry[]>>({})
  const sentAt = ref<Record<string, number>>({})

  function markSent(session: string) { sentAt.value[session] = Date.now() }
  function clearSent(session: string) { sentAt.value[session] = 0 }
  function getSentAt(session: string) { return sentAt.value[session] ?? 0 }

  function replace(session: string, entries: MessageEntry[]) { bySession.value[session] = entries }
  function append(session: string, entry: MessageEntry) {
    if (!bySession.value[session]) bySession.value[session] = []
    const existing = bySession.value[session].findIndex((x) => x.id === entry.id)
    if (existing >= 0) {
      bySession.value[session][existing] = entry
      return
    }
    bySession.value[session].push(entry)
  }
  function update(session: string, entry_id: string, patch: { text?: string; edited_at?: string }) {
    const arr = bySession.value[session]; if (!arr) return
    const e = arr.find((x) => x.id === entry_id); if (!e) return
    Object.assign(e, patch)
  }
  function addReaction(session: string, entry_id: string, emoji: string, ts: string) {
    const arr = bySession.value[session]; if (!arr) return
    const e = arr.find((x) => x.id === entry_id); if (!e) return
    e.reactions = e.reactions ?? []
    e.reactions.push({ emoji, ts })
  }

  return { bySession, sentAt, replace, append, update, addReaction, markSent, clearSent, getSentAt }
})
