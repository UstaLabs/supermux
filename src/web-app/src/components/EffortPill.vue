<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { useSessions } from "@/stores/sessions"

const props = defineProps<{
  sessionId: string
  disabled?: boolean
}>()

defineEmits<{ (e: "click"): void }>()

const sessions = useSessions()
const visible = ref(false)
const fetchedLevel = ref<string | undefined>()

const agent = computed(() => sessions.byId(props.sessionId)?.agent)

// byId() may return an ArchivedSession, which carries no reasoningLevel — read it
// off the live list instead of narrowing the union at every use.
const currentLevel = computed(() =>
  sessions.list.find((s) => s.id === props.sessionId)?.reasoningLevel ?? fetchedLevel.value,
)

watch(() => [props.sessionId, agent.value] as const, async () => {
  visible.value = false
  fetchedLevel.value = undefined
  if (agent.value === "cursor") return
  try {
    const res = await fetch(`/sessions/${encodeURIComponent(props.sessionId)}/reasoning-levels`, {})
    if (!res.ok) return
    const data = await res.json()
    visible.value = data.visible !== false && (data.levels?.length ?? 0) > 1
    fetchedLevel.value = data.current
  } catch {}
}, { immediate: true })
</script>

<template>
  <button
    v-if="visible && currentLevel"
    type="button"
    class="max-w-[5rem] truncate rounded-full px-2 py-0.5 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:pointer-events-none disabled:opacity-40"
    :disabled="props.disabled"
    :title="`Thinking level: ${currentLevel}`"
    @click="$emit('click')"
  >
    {{ currentLevel }}
  </button>
</template>
