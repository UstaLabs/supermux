<script setup lang="ts">
import { computed, ref, nextTick } from "vue"
import { useMessages } from "@/stores/messages"
import { useAgentState, isAgentWorking } from "@/stores/agentState"
import { Check, Pencil, Play, Loader2Icon } from "lucide-vue-next"

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
  variant?: "in_progress" | "draft" | "settled"
  projectLabel?: string
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
// actively working (thinking/running). Reads the same agent_state — and uses
// the same condition — as the chat view's "Working…" indicator, so the two
// never disagree.
const working = computed(() => isAgentWorking(agentState.get(props.id)))

// Background-task badge: open-task count from the same agent_state frame.
// Pulses while nothing else moves (waiting); steady alongside the spinner.
const bgOpen = computed(() => agentState.get(props.id).bgOpen ?? 0)

const isDraft = computed(() => props.variant === "draft")
const isSettled = computed(() => props.variant === "settled")

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
  <!-- Draft: no process, so a compact row — the saved plan plus a Start action. -->
  <a
    v-if="isDraft"
    href="#"
    class="group/row block rounded-md border border-transparent transition-colors hover:bg-card/70 active:bg-card"
    :class="[
      props.flush ? 'mx-0 my-0' : 'mx-2 my-0.5',
      props.reserveMenuSpace ? 'pl-3 pr-9 py-1.5' : 'px-3 py-1.5',
      props.active ? 'bg-card' : '',
    ]"
    @click="handleNavigate"
  >
    <div class="flex items-center gap-3">
      <div class="flex w-5 shrink-0 items-center justify-center">
        <Pencil class="size-3.5 text-primary/70" aria-label="draft" />
      </div>
      <template v-if="props.renaming">
        <input
          ref="renameInput"
          v-model="renameValue"
          class="font-medium truncate bg-transparent border-b border-primary outline-none text-foreground flex-1 min-w-0"
          @keydown.enter="commitRename"
          @keydown.escape="emit('rename-cancel')"
          @blur="commitRename"
        />
      </template>
      <span v-else class="text-[13px] font-medium truncate min-w-0 flex-1">{{ props.name }}</span>
      <span
        v-if="props.projectLabel"
        class="shrink-0 rounded border border-border/60 px-1.5 py-px font-mono text-[10px] text-muted-foreground/70"
      >{{ props.projectLabel }}</span>
      <span
        class="grid size-[22px] shrink-0 place-items-center rounded-md border border-primary/35 text-primary opacity-90"
        aria-label="Start"
      >
        <Play class="size-3" />
      </span>
    </div>
  </a>

  <!-- In-progress / settled: the full row, agent working-state only (no git). -->
  <a
    v-else
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
      <div class="flex w-5 shrink-0 items-center justify-center self-stretch pt-0.5">
        <span
          v-if="bgOpen > 0"
          class="inline-flex items-center font-mono text-[11px] text-amber-500"
          :class="{ 'animate-pulse': !working }"
          aria-label="background tasks"
        >⧗{{ bgOpen }}</span>
        <Loader2Icon v-if="working" class="size-4 animate-spin text-primary" aria-label="working" />
        <Check v-else-if="isSettled" class="size-4 text-emerald-400/80" aria-label="settled" />
        <span v-else class="size-1.5 rounded-full bg-muted-foreground/30" aria-hidden="true" />
      </div>

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
          <span v-else class="font-medium truncate" :class="{ 'text-muted-foreground': isSettled }">{{ props.name }}</span>
          <span v-if="lastTs" class="text-[11px] text-muted-foreground shrink-0">{{ rel(lastTs) }}</span>
        </div>
        <div class="flex items-center gap-2 mt-0.5">
          <span
            v-if="props.projectLabel"
            class="shrink-0 rounded border border-border/60 px-1.5 py-px font-mono text-[10px] text-muted-foreground/70"
          >{{ props.projectLabel }}</span>
          <div
            class="text-[11px] truncate min-w-0 flex-1"
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
