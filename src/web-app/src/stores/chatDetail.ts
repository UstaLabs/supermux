import { defineStore } from "pinia"
import { computed, reactive, watch } from "vue"
import {
  CHAT_DETAIL_DEFAULT,
  CHAT_DETAIL_LABELS,
  effectiveChatDetail,
  isChatDetailImplemented,
  parseChatDetailLevel,
  type ChatDetailLevel,
} from "@/lib/chat-detail"

const KEY = "cmux:chat-detail"

export interface ChatDetailState {
  level: ChatDetailLevel
}

function load(): ChatDetailState {
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return { level: CHAT_DETAIL_DEFAULT }
    const p = JSON.parse(raw)
    if (!p || typeof p !== "object") return { level: CHAT_DETAIL_DEFAULT }
    return { level: parseChatDetailLevel((p as { level?: unknown }).level) }
  } catch {
    return { level: CHAT_DETAIL_DEFAULT }
  }
}

export const useChatDetail = defineStore("chatDetail", () => {
  const state = reactive<ChatDetailState>(load())

  // If storage had high/garbage, write back clamped medium so disk matches effective state.
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) {
      const p = JSON.parse(raw)
      if (p && typeof p === "object" && (p as { level?: unknown }).level !== state.level) {
        localStorage.setItem(KEY, JSON.stringify(state))
      }
    }
  } catch { /* ignore */ }

  watch(state, () => {
    try { localStorage.setItem(KEY, JSON.stringify({ level: state.level })) } catch {}
  }, { deep: true })

  const renderMode = computed(() => effectiveChatDetail(state.level))
  const levelLabel = computed(() => CHAT_DETAIL_LABELS[state.level] ?? CHAT_DETAIL_LABELS.medium)

  function setLevel(level: ChatDetailLevel) {
    if (!isChatDetailImplemented(level)) return
    state.level = level
  }

  function cycleImplemented() {
    state.level = state.level === "low" ? "medium" : "low"
  }

  return { state, renderMode, levelLabel, setLevel, cycleImplemented }
})
