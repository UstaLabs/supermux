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
const currentLevel = ref<string | undefined>()

const agent = computed(() => sessions.byId(props.sessionId)?.agent)

watch(() => [props.sessionId, agent.value] as const, async () => {
  visible.value = false
  currentLevel.value = undefined
  if (agent.value === "cursor") return
  try {
    const res = await fetch(`/sessions/${encodeURIComponent(props.sessionId)}/reasoning-levels`, {})
    if (!res.ok) return
    const data = await res.json()
    visible.value = data.visible !== false && (data.levels?.length ?? 0) > 1
    currentLevel.value = data.current
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
