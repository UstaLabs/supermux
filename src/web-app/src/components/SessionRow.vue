<script setup lang="ts">
import { computed, ref, nextTick, inject } from "vue"
import { useMessages } from "@/stores/messages"
import { useAgentState, isAgentWorking } from "@/stores/agentState"
import { sessionListRailKind } from "@/lib/sessionListRail"
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
  (e: "mute"): void
  (e: "rename", newName: string): void
  (e: "rename-cancel"): void
  (e: "navigate"): void
}>()

const messages = useMessages()
const agentState = useAgentState()

// When the section just finished (or is mid) a reorder, ignore the synthetic click.
const sectionShouldSuppressClick = inject<() => boolean>("sectionShouldSuppressClick", () => false)

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

// Leading rail kind (native SessionStatusRail parity): working spinner › green unread › other.
const railKind = computed(() =>
  sessionListRailKind({
    active: !!props.active,
    working: working.value,
    unread: !!props.unread,
  }),
)
const showUnread = computed(() => railKind.value === "unread")
const showWorking = computed(() => railKind.value === "working")

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
  if (sectionShouldSuppressClick()) return
  emit("navigate")
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === "Enter" || e.key === " ") {
    e.preventDefault()
    if (sectionShouldSuppressClick()) return
    emit("navigate")
  }
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
  <!--
    Use a div (not <a>) so whole-row reorder can own the pointer. Anchors are
    draggable-by-default in browsers and hijack drag as a URL drag, which made
    reorder feel like it only worked from a thin "handle".
  -->
  <!-- Draft: compact row — saved plan plus a Start action. -->
  <div
    v-if="isDraft"
    data-testid="session-row"
    :data-session-id="props.id"
    role="button"
    tabindex="0"
    class="group/row block border border-transparent transition-colors cursor-pointer select-none [-webkit-touch-callout:none]"
    :class="[
      props.flush
        ? 'mx-0 my-0 rounded-none hover:bg-muted/40 active:bg-muted/55'
        : 'mx-2 my-0.5 rounded-md hover:bg-card/70 active:bg-card',
      props.reserveMenuSpace ? 'pl-3 pr-9 py-1.5' : 'px-3 py-1.5',
      props.active
        ? (props.flush ? 'bg-primary/8' : 'bg-card')
        : '',
    ]"
    @click="handleNavigate"
    @keydown="handleKeydown"
  >
    <div class="flex items-center gap-2.5 min-w-0">
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
          @click.stop
        />
      </template>
      <div v-else class="min-w-0 flex-1 flex items-center gap-1.5">
        <span class="text-[13px] font-medium truncate min-w-0">{{ props.name }}</span>
        <span
          v-if="props.projectLabel"
          class="shrink-0 max-w-[40%] truncate rounded border border-border/60 px-1.5 py-px font-mono text-[10px] text-muted-foreground/70"
        >{{ props.projectLabel }}</span>
      </div>
      <span
        class="grid size-[22px] shrink-0 place-items-center rounded-md border border-primary/35 text-primary opacity-90"
        aria-label="Start"
      >
        <Play class="size-3" />
      </span>
    </div>
  </div>

  <!-- In-progress / settled: full row, agent working-state only (no git). -->
  <div
    v-else
    data-testid="session-row"
    :data-session-id="props.id"
    role="button"
    tabindex="0"
    class="block border transition-colors cursor-pointer select-none [-webkit-touch-callout:none]"
    :class="[
      props.flush
        ? 'mx-0 my-0 rounded-none'
        : 'mx-2 my-1 rounded-md',
      props.active
        ? (props.flush
            ? 'bg-primary/8 border-transparent'
            : 'bg-card border-border shadow-sm')
        : (props.flush
            ? 'border-transparent hover:bg-muted/40 active:bg-muted/55'
            : 'border-transparent hover:bg-card/70 active:bg-card'),
      props.reserveMenuSpace ? 'pl-3 pr-9 py-2.5' : 'px-3 py-2.5',
    ]"
    @click="handleNavigate"
    @keydown="handleKeydown"
  >
    <div class="flex items-start gap-2.5 min-w-0">
      <!-- Leading rail (native SessionStatusRail parity): working spinner wins;
           idle + unread → larger green dot; else settled check / quiet gray. -->
      <div class="flex w-5 shrink-0 items-center justify-center self-stretch pt-0.5 gap-0.5">
        <span
          v-if="bgOpen > 0"
          class="inline-flex items-center font-mono text-[11px] text-amber-500"
          :class="{ 'animate-pulse': !working }"
          aria-label="background tasks"
        >⧗{{ bgOpen }}</span>
        <Loader2Icon
          v-if="showWorking"
          class="size-4 animate-spin text-primary"
          aria-label="working"
          data-testid="session-rail-working"
        />
        <span
          v-else-if="showUnread"
          class="relative grid size-2.5 place-items-center rounded-full ring-[1.5px] ring-emerald-400/40"
          aria-label="unread"
          data-testid="session-rail-unread"
        >
          <span class="size-1.5 rounded-full bg-emerald-400" />
        </span>
        <Check v-else-if="isSettled" class="size-4 text-emerald-400/80" aria-label="settled" />
        <span
          v-else
          class="size-1.5 rounded-full bg-muted-foreground/30"
          aria-hidden="true"
          data-testid="session-rail-neutral"
        />
      </div>

      <div class="min-w-0 flex-1">
        <div class="flex items-baseline justify-between gap-2 min-w-0">
          <template v-if="props.renaming">
            <input
              ref="renameInput"
              v-model="renameValue"
              class="font-medium truncate bg-transparent border-b border-primary outline-none text-foreground w-full"
              @keydown.enter="commitRename"
              @keydown.escape="emit('rename-cancel')"
              @blur="commitRename"
              @click.stop
            />
          </template>
          <div v-else class="min-w-0 flex-1 flex items-baseline gap-1.5">
            <span
              class="truncate min-w-0"
              :class="[
                isSettled ? 'text-muted-foreground' : '',
                showUnread || props.active ? 'font-semibold' : 'font-medium',
              ]"
            >{{ props.name }}</span>
            <span
              v-if="props.projectLabel"
              class="shrink-0 max-w-[36%] truncate rounded border border-border/60 px-1.5 py-px font-mono text-[10px] text-muted-foreground/70"
            >{{ props.projectLabel }}</span>
          </div>
          <span v-if="lastTs" class="text-[11px] text-muted-foreground shrink-0">{{ rel(lastTs) }}</span>
        </div>
        <div class="mt-0.5 min-w-0">
          <div
            class="text-[11px] truncate min-w-0"
            :class="lastText ? 'text-muted-foreground/65' : 'text-muted-foreground/50 italic'"
          >
            {{ lastText || "no messages yet" }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
