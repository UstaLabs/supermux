<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { DialogOverlay, DialogContent, DialogPortal, DialogRoot } from "reka-ui"
import { useSessions } from "@/stores/sessions"
import { capabilitiesOf } from "@/lib/agent-capabilities"
import { Check } from "@lucide/vue"
import { toast } from "vue-sonner"

const props = defineProps<{ sessionId: string; open: boolean }>()
const emit = defineEmits<{ (e: "update:open", v: boolean): void }>()

const sessions = useSessions()
// Behavior comes from broker capability flags (kind fallback for old brokers);
// `agent` below stays for display only (name + help text).
const caps = computed(() => capabilitiesOf(sessions.list.find((s) => s.id === props.sessionId)))
const levels = ref<{ id: string; description?: string }[]>([])
const currentLevel = ref<string | undefined>()
const agent = ref<string>("")
const visible = ref(false)
const loading = ref(false)
const switching = ref<string | null>(null)
const pendingLevel = ref<string | null>(null)

watch(() => props.open, async (isOpen) => {
  if (!isOpen) return
  loading.value = true
  try {
    const res = await fetch(`/sessions/${encodeURIComponent(props.sessionId)}/reasoning-levels`, {})
    if (res.ok) {
      const data = await res.json()
      levels.value = data.levels ?? []
      currentLevel.value = data.current
      agent.value = data.agent ?? ""
      visible.value = data.visible !== false && levels.value.length > 1
    }
  } catch {} finally {
    loading.value = false
  }
})

async function selectLevel(levelId: string, applyNow = false) {
  if (!applyNow && levelId === currentLevel.value) { emit("update:open", false); return }
  switching.value = levelId
  try {
    const res = await fetch(`/sessions/${encodeURIComponent(props.sessionId)}/reasoning-level`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(applyNow ? { reasoningLevel: levelId, applyNow: true } : { reasoningLevel: levelId }),
    })
    if (res.ok) {
      currentLevel.value = levelId
      sessions.updateState(props.sessionId, { reasoningLevel: levelId })
      const body = await res.json().catch(() => ({}))
      if (res.status === 202 || body?.status === "queued") {
        pendingLevel.value = levelId
      } else {
        emit("update:open", false)
      }
    } else {
      const body = await res.json().catch(() => ({}))
      toast.error(`Couldn't switch thinking level: ${body?.error ?? res.status}`)
    }
  } catch (err: any) {
    toast.error(`Couldn't switch thinking level: ${err?.message ?? "network error"}`)
  } finally {
    switching.value = null
  }
}

async function applyNow() {
  if (!pendingLevel.value) return
  const levelId = pendingLevel.value
  await selectLevel(levelId, true)
  pendingLevel.value = null
  emit("update:open", false)
}
</script>

<template>
  <DialogRoot v-if="visible" :open="props.open" @update:open="(v) => emit('update:open', v)">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 bg-black/50 z-50 data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0" />
      <DialogContent
        class="fixed bottom-0 left-0 right-0 z-50 bg-popover text-popover-foreground rounded-t-2xl p-0 max-h-[70dvh] flex flex-col outline-none data-open:animate-in data-closed:animate-out data-open:slide-in-from-bottom data-closed:slide-out-to-bottom duration-200"
        @pointer-down-outside="emit('update:open', false)"
      >
        <div class="flex justify-center pt-3 pb-1">
          <div class="w-10 h-1 rounded-full bg-muted-foreground/30" />
        </div>
        <div class="px-4 pb-2">
          <h3 class="font-semibold text-base">Thinking Level</h3>
          <p class="text-xs text-muted-foreground">{{ agent }} · {{ sessions.displayName(props.sessionId) ?? props.sessionId }}</p>
          <p v-if="agent === 'claude'" class="text-xs text-muted-foreground mt-0.5">Applies live — no restart.</p>
          <p v-else class="text-xs text-muted-foreground mt-0.5">Changing the thinking level restarts the agent for this session.</p>
        </div>
        <div class="overflow-y-auto flex-1 px-2 pb-[calc(env(safe-area-inset-bottom,0px)+1rem)]">
          <div v-if="loading" class="py-8 text-center text-muted-foreground text-sm">Loading levels…</div>
          <div v-else-if="levels.length === 0" class="py-8 text-center text-muted-foreground text-sm">No levels available</div>
          <button
            v-else
            v-for="l in levels"
            :key="l.id"
            class="w-full flex items-center gap-3 px-3 py-3 rounded-lg text-left transition-colors hover:bg-accent"
            :class="{ 'bg-accent/50': l.id === currentLevel }"
            :disabled="switching !== null"
            @click="selectLevel(l.id)"
          >
            <div class="flex-1 min-w-0">
              <div class="text-sm font-medium truncate capitalize">{{ l.id }}</div>
              <div v-if="l.description" class="text-xs text-muted-foreground truncate">{{ l.description }}</div>
            </div>
            <Check v-if="l.id === currentLevel" class="size-4 text-primary shrink-0" />
            <div v-else-if="switching === l.id" class="size-4 shrink-0 border-2 border-muted-foreground/30 border-t-primary rounded-full animate-spin" />
          </button>
          <div v-if="pendingLevel" class="flex items-center justify-between gap-3 px-3 py-2 mt-1 rounded-lg bg-accent/40">
            <span class="text-xs text-muted-foreground">Will apply after this turn</span>
            <button
              v-if="caps.supportsLiveConfigChange"
              class="text-xs font-medium px-2 py-1 rounded-md hover:bg-accent transition-colors"
              :disabled="switching !== null"
              @click="applyNow"
            >
              Change now (ends current turn)
            </button>
          </div>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
