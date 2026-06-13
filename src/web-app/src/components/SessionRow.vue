<script setup lang="ts">
import { computed, ref, nextTick } from "vue"
import { useMessages } from "@/stores/messages"
import { useAgentState, isAgentWorking } from "@/stores/agentState"
import SessionAvatar from "@/components/SessionAvatar.vue"

const props = defineProps<{
  id: string
  name: string
  workdir: string
  connected: boolean
  active?: boolean
  unread?: boolean
  agent?: string
  model?: string
  renaming?: boolean
  status?: string
  reserveMenuSpace?: boolean
  flush?: boolean
}>()

const emit = defineEmits<{
  (e: "kill"): void
  (e: "mute"): void
  (e: "rename", newName: string): void
  (e: "rename-cancel"): void
  (e: "navigate"): void
}>()

const messages = useMessages()
const agentState = useAgentState()

// Drives the chat-list running spinner: true while this session's agent is
// actively working (thinking/running) and still connected. Reads the same
// agent_state the chat view's "Working…" indicator uses.
const working = computed(() => isAgentWorking(agentState.get(props.id).phase, props.connected))

const renameValue = ref(props.name)
const renameInput = ref<HTMLInputElement | null>(null)

function startRename() {
  renameValue.value = props.name
  nextTick(() => renameInput.value?.focus())
}

function commitRename() {
  const v = renameValue.value.trim()
  if (v && v !== props.name) emit("rename", v)
  else emit("rename-cancel")
}

function handleNavigate(e: Event) {
  e.preventDefault()
  emit("navigate")
}

const lastEntry = computed(() => {
  const arr = messages.bySession[props.id]
  return arr?.[arr.length - 1]
})
const lastText = computed(() => lastEntry.value?.text ?? "")
const lastTs = computed(() => lastEntry.value?.ts)

function rel(ts?: string): string {
  if (!ts) return ""
  const d = Date.now() - new Date(ts).getTime()
  if (d < 60_000) return "just now"
  if (d < 3600_000) return `${Math.floor(d / 60_000)}m`
  if (d < 86_400_000) return `${Math.floor(d / 3_600_000)}h`
  return `${Math.floor(d / 86_400_000)}d`
}

defineExpose({ startRename })
</script>

<template>
  <a
    href="#"
    class="block rounded-md border transition-colors"
    :class="[
      props.flush ? 'mx-0 my-0' : 'mx-2 my-1',
      props.active
        ? 'bg-card border-border shadow-sm'
        : 'border-transparent hover:bg-card/70 active:bg-card',
      props.reserveMenuSpace ? 'pl-3 pr-9 py-2.5' : 'px-3 py-2.5',
    ]"
    @click="handleNavigate"
  >
    <div class="flex items-start gap-3">
      <SessionAvatar :name="props.name" :connected="props.connected" :agent="props.agent" :working="working" />

      <div class="min-w-0 flex-1">
        <div class="flex items-baseline justify-between gap-2">
          <template v-if="props.renaming">
            <input
              ref="renameInput"
              v-model="renameValue"
              class="font-medium truncate bg-transparent border-b border-primary outline-none text-foreground w-full"
              @keydown.enter="commitRename"
              @keydown.escape="emit('rename-cancel')"
              @blur="commitRename"
            />
          </template>
          <span v-else class="font-medium truncate">{{ props.name }}</span>
          <span v-if="lastTs" class="text-[11px] text-muted-foreground shrink-0">{{ rel(lastTs) }}</span>
        </div>
        <div v-if="props.status === 'suspended'" class="mt-0.5">
          <span class="inline-flex items-center text-[10px] font-medium text-amber-500/70">suspended</span>
        </div>
        <div class="flex items-center justify-between gap-2 mt-0.5">
          <div
            class="text-[11px] truncate"
            :class="lastText ? 'text-muted-foreground/65' : 'text-muted-foreground/50 italic'"
          >
            {{ lastText || "no messages yet" }}
          </div>
          <span
            v-if="props.unread"
            class="h-5 w-1 rounded-full bg-primary/70 shrink-0"
            aria-label="unread"
          />
        </div>
      </div>
    </div>
  </a>
</template>
