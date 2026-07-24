<script setup lang="ts">
// One task-state section (In Progress / Drafts / Settled): a small header plus
// its rows. Settled collapses; non-settled sections are drag-reorderable on
// desktop. Row actions bubble up carrying the session id so the list view's
// handlers stay in one place.
import { ref } from "vue"
import { ChevronDown } from "lucide-vue-next"
import type { PathGroupSection, SectionKey } from "@/composables/usePathGroups"
import { projectLabel } from "@/composables/usePathGroups"
import { useUnread } from "@/stores/unread"
import type { Session } from "@/stores/sessions"
import TaskRow from "./TaskRow.vue"

const props = defineProps<{
  section: PathGroupSection
  mobile: boolean
  showProjectLabel: boolean
  homeDir?: string | null
  activeId: string
  renamingName: string | null
  expanded: boolean
  reorderable: boolean
}>()

const emit = defineEmits<{
  (e: "navigate", id: string): void
  (e: "kill", id: string): void
  (e: "mute", id: string): void
  (e: "rename-start", name: string): void
  (e: "rename", id: string, newName: string): void
  (e: "rename-cancel"): void
  (e: "settle", id: string): void
  (e: "resume", id: string): void
  (e: "open-draft", id: string): void
  (e: "delete-draft", id: string): void
  (e: "toggle-expanded"): void
  (e: "reorder", orderedIds: string[]): void
}>()

const unread = useUnread()

const isSettled = (k: SectionKey) => k === "settled"
const canDrag = (k: SectionKey) => props.reorderable && !isSettled(k)
function tagFor(s: Session): string | undefined {
  return props.showProjectLabel ? projectLabel(s, props.homeDir) : undefined
}

const dragId = ref<string | null>(null)
function onDragStart(id: string) { dragId.value = id }
function onDrop(targetId: string) {
  const from = dragId.value
  dragId.value = null
  if (!from || from === targetId) return
  const ids = props.section.sessions.map((s) => s.id)
  const fromIdx = ids.indexOf(from)
  const toIdx = ids.indexOf(targetId)
  if (fromIdx < 0 || toIdx < 0) return
  ids.splice(toIdx, 0, ids.splice(fromIdx, 1)[0]!)
  emit("reorder", ids)
}
</script>

<template>
  <div>
    <!-- Settled: collapsible. Other sections: a plain label. -->
    <button
      v-if="isSettled(props.section.key)"
      type="button"
      class="flex w-full items-center gap-1.5 px-3 py-1 text-left transition-colors hover:bg-muted/40"
      :aria-expanded="props.expanded"
      @click="emit('toggle-expanded')"
    >
      <ChevronDown
        class="size-3 shrink-0 text-muted-foreground/70 transition-transform duration-150"
        :class="{ '-rotate-90': !props.expanded }"
      />
      <span class="truncate text-[10px] font-medium uppercase tracking-wide text-muted-foreground/70">{{ props.section.label }}</span>
      <span class="text-[10px] tabular-nums text-muted-foreground/50">{{ props.section.sessions.length }}</span>
    </button>
    <div v-else class="flex items-center gap-1.5 px-3 pt-2 pb-1">
      <span class="truncate text-[10px] font-medium uppercase tracking-wide text-muted-foreground/70">{{ props.section.label }}</span>
      <span v-if="props.section.sessions.length > 1" class="text-[10px] tabular-nums text-muted-foreground/50">{{ props.section.sessions.length }}</span>
    </div>

    <template v-if="!isSettled(props.section.key) || props.expanded">
      <div
        v-for="s in props.section.sessions"
        :key="s.id"
        :draggable="canDrag(props.section.key)"
        @dragstart="onDragStart(s.id)"
        @dragover.prevent
        @drop="onDrop(s.id)"
      >
        <TaskRow
          :session="s"
          :mobile="props.mobile"
          :variant="props.section.key"
          :active="s.id === props.activeId"
          :unread="unread.isUnread(s.id)"
          :project-label="tagFor(s)"
          :renaming="props.renamingName === s.name"
          @navigate="emit('navigate', s.id)"
          @kill="emit('kill', s.id)"
          @mute="emit('mute', s.id)"
          @rename-start="emit('rename-start', s.name)"
          @rename="(newName) => emit('rename', s.id, newName)"
          @rename-cancel="emit('rename-cancel')"
          @settle="emit('settle', s.id)"
          @resume="emit('resume', s.id)"
          @open-draft="emit('open-draft', s.id)"
          @delete-draft="emit('delete-draft', s.id)"
        />
      </div>
    </template>
  </div>
</template>
