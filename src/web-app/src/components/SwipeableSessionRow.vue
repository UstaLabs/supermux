<script setup lang="ts">
import { ref, watch, inject, type Ref } from "vue"
import { useRouter } from "vue-router"
import { computed } from "vue"
import { Trash2, VolumeX, Volume2, Pencil, RotateCcw } from "lucide-vue-next"
import { useSwipeReveal } from "@/composables/useSwipeReveal"
import SessionRow from "./SessionRow.vue"

const props = withDefaults(
  defineProps<{
    id: string
    name: string
    workdir: string
    connected: boolean
    mute: boolean
    active?: boolean
    unread?: boolean
    agent?: string
    model?: string
    status?: string
    variant?: "in_progress" | "draft" | "settled"
    projectLabel?: string
    /** In-group card: match card surface, no outer padding. */
    flush?: boolean
  }>(),
  { variant: "in_progress", flush: false },
)

const emit = defineEmits<{
  (e: "kill", id: string): void
  (e: "mute", id: string): void
  (e: "rename", id: string, newName: string): void
  (e: "settle", id: string): void
  (e: "resume", id: string): void
  (e: "openDraft", id: string): void
  (e: "deleteDraft", id: string): void
}>()

// Drafts have no process, so mute/kill don't apply; settled rows only resume.
const showMute = computed(() => props.variant === "in_progress")

const router = useRouter()
const containerRef = ref<HTMLElement | null>(null)
const rowRef = ref<InstanceType<typeof SessionRow> | null>(null)
const renaming = ref(false)

const openRow = inject<Ref<string | null>>("openSwipeRow", ref(null))
// TaskSection sets this while a long-press reorder is active so swipe-to-
// reveal does not steal the gesture mid-drag.
const sectionReordering = inject<Ref<boolean>>("sectionReordering", ref(false))
const sectionShouldSuppressClick = inject<() => boolean>("sectionShouldSuppressClick", () => false)

const { state, close } = useSwipeReveal(containerRef, {
  leftWidth: 140,
  rightWidth: 80,
  // When the parent section is reordering, freeze swipe at idle.
  paused: sectionReordering,
})

watch(openRow, (current) => {
  if (current !== props.id && state.value !== "idle" && state.value !== "dragging") {
    close()
  }
})
watch(state, (s) => {
  if (s === "open-left" || s === "open-right") {
    openRow.value = props.id
  }
})

function handleKill() {
  close()
  emit("kill", props.id)
}

function handleDeleteDraft() {
  close()
  emit("deleteDraft", props.id)
}

function handleResume() {
  close()
  emit("resume", props.id)
}

function handleOpenDraft() {
  close()
  emit("openDraft", props.id)
}

function handleMute() {
  close()
  emit("mute", props.id)
}

function handleStartRename() {
  close()
  renaming.value = true
  rowRef.value?.startRename()
}

function handleRename(newName: string) {
  renaming.value = false
  emit("rename", props.id, newName)
}

function handleRenameCancel() {
  renaming.value = false
}

function handleNavigate() {
  if (sectionReordering.value || sectionShouldSuppressClick()) return
  if (state.value !== "idle") {
    close()
    return
  }
  if (props.variant === "draft") {
    router.push({ path: "/new", query: { draft: props.id } })
    return
  }
  router.push(`/s/${props.id}`)
}
</script>

<template>
  <div ref="containerRef" class="relative overflow-hidden">
    <div class="absolute inset-y-0 left-0 flex items-stretch">
      <button
        v-if="showMute"
        class="w-[70px] flex flex-col items-center justify-center gap-1 border-r border-border bg-[color-mix(in_oklab,var(--cmux-warning)_26%,var(--cmux-session-list))] text-foreground active:bg-[color-mix(in_oklab,var(--cmux-warning)_34%,var(--cmux-session-list))]"
        @click="handleMute"
      >
        <VolumeX v-if="!props.mute" class="size-5" />
        <Volume2 v-else class="size-5" />
        <span class="text-[10px] font-medium">{{ props.mute ? 'Unmute' : 'Mute' }}</span>
      </button>
      <button
        v-if="props.variant === 'draft'"
        class="w-[70px] flex flex-col items-center justify-center gap-1 border-r border-border bg-[color-mix(in_oklab,var(--primary)_22%,var(--cmux-session-list))] text-foreground active:bg-[color-mix(in_oklab,var(--primary)_30%,var(--cmux-session-list))]"
        @click="handleOpenDraft"
      >
        <Pencil class="size-5" />
        <span class="text-[10px] font-medium">Edit</span>
      </button>
      <button
        v-else-if="props.variant === 'settled'"
        class="w-[70px] flex flex-col items-center justify-center gap-1 border-r border-border bg-[color-mix(in_oklab,var(--primary)_22%,var(--cmux-session-list))] text-foreground active:bg-[color-mix(in_oklab,var(--primary)_30%,var(--cmux-session-list))]"
        @click="handleResume"
      >
        <RotateCcw class="size-5" />
        <span class="text-[10px] font-medium">Activate</span>
      </button>
      <button
        v-else
        class="w-[70px] flex flex-col items-center justify-center gap-1 border-r border-border bg-[color-mix(in_oklab,var(--primary)_22%,var(--cmux-session-list))] text-foreground active:bg-[color-mix(in_oklab,var(--primary)_30%,var(--cmux-session-list))]"
        @click="handleStartRename"
      >
        <Pencil class="size-5" />
        <span class="text-[10px] font-medium">Rename</span>
      </button>
    </div>

    <div class="absolute inset-y-0 right-0 flex items-stretch">
      <button
        v-if="props.variant === 'draft'"
        class="w-[80px] flex flex-col items-center justify-center gap-1 bg-[color-mix(in_oklab,var(--destructive)_28%,var(--cmux-session-list))] text-foreground active:bg-[color-mix(in_oklab,var(--destructive)_36%,var(--cmux-session-list))]"
        @click="handleDeleteDraft"
      >
        <Trash2 class="size-5" />
        <span class="text-[10px] font-medium">Delete</span>
      </button>
      <button
        v-else-if="props.variant === 'in_progress'"
        class="w-[80px] flex flex-col items-center justify-center gap-1 bg-[color-mix(in_oklab,var(--destructive)_28%,var(--cmux-session-list))] text-foreground active:bg-[color-mix(in_oklab,var(--destructive)_36%,var(--cmux-session-list))]"
        @click="handleKill"
      >
        <Trash2 class="size-5" />
        <span class="text-[10px] font-medium">Kill</span>
      </button>
    </div>

    <div
      data-swipe-content
      class="relative"
      :class="props.flush
        ? 'bg-card'
        : 'bg-[var(--cmux-session-list)] px-2 py-1'"
    >
      <SessionRow
        ref="rowRef"
        flush
        :id="props.id"
        :name="props.name"
        :workdir="props.workdir"
        :connected="props.connected"
        :active="props.active"
        :unread="props.unread"
        :agent="props.agent"
        :model="props.model"
        :status="props.status"
        :variant="props.variant"
        :project-label="props.projectLabel"
        :renaming="renaming"
        @rename="handleRename"
        @rename-cancel="handleRenameCancel"
        @navigate="handleNavigate"
      />
    </div>
  </div>
</template>
