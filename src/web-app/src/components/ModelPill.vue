<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { useSessions } from "@/stores/sessions"

const props = defineProps<{
  sessionId: string
  disabled?: boolean
}>()

defineEmits<{ (e: "click"): void }>()

const sessions = useSessions()
const displayName = ref<string | null>(null)

const modelId = computed(() => sessions.byId(props.sessionId)?.model)

function fallbackLabel(id: string | undefined): string {
  if (!id) return "Model"
  const parts = id.split(/[/:]/)
  return parts[parts.length - 1] ?? id
}

const label = computed(() => displayName.value ?? fallbackLabel(modelId.value))

async function loadDisplayName() {
  displayName.value = null
  if (!modelId.value) return
  try {
    const res = await fetch(`/sessions/${encodeURIComponent(props.sessionId)}/models`, {
    })
    if (!res.ok) return
    const data = await res.json()
    const match = (data.models ?? []).find((m: { id: string }) => m.id === data.current)
    displayName.value = match?.displayName ?? fallbackLabel(data.current ?? modelId.value)
  } catch {}
}

watch(() => [props.sessionId, modelId.value] as const, () => { void loadDisplayName() }, { immediate: true })
</script>

<template>
  <button
    type="button"
    class="max-w-[8rem] truncate rounded-full px-2 py-0.5 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground disabled:pointer-events-none disabled:opacity-40"
    :disabled="props.disabled"
    :title="modelId ?? 'Switch model'"
    @click="$emit('click')"
  >
    {{ label }}
  </button>
</template>
