<script setup lang="ts">
// One task-state section (In Progress / Drafts / Settled): a small header plus
// its rows. Settled collapses; non-settled sections are whole-row reorderable
// (mouse drag or touch long-press with floating ghost + live insert).
import { computed, provide, toRef } from "vue"
import { ChevronDown } from "lucide-vue-next"
import type { PathGroupSection, SectionKey } from "@/composables/usePathGroups"
import { projectLabel } from "@/composables/usePathGroups"
import { useSectionReorder } from "@/composables/useSectionReorder"
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
const canDrag = computed(() => props.reorderable && !isSettled(props.section.key))
function tagFor(s: Session): string | undefined {
  return props.showProjectLabel ? projectLabel(s, props.homeDir) : undefined
}

const byId = computed(() => {
  const m = new Map<string, Session>()
  for (const s of props.section.sessions) m.set(s.id, s)
  return m
})

const { dragId, active: reorderActive, orderedIds, ghost, shouldSuppressClick, rowProps } =
  useSectionReorder({
    ids: () => props.section.sessions.map((s) => s.id),
    enabled: () => canDrag.value,
    onReorder: (ids) => emit("reorder", ids),
    labelFor: (id) => byId.value.get(id)?.name ?? id,
  })

// While dragging, render the live-reordered sequence; otherwise the store order.
const displaySessions = computed<Session[]>(() => {
  if (!reorderActive.value || orderedIds.value.length === 0) return props.section.sessions
  const out: Session[] = []
  for (const id of orderedIds.value) {
    const s = byId.value.get(id)
    if (s) out.push(s)
  }
  for (const s of props.section.sessions) {
    if (!orderedIds.value.includes(s.id)) out.push(s)
  }
  return out
})

provide("sectionReordering", reorderActive)
provide("sectionShouldSuppressClick", shouldSuppressClick)

const mobile = toRef(props, "mobile")
</script>

<template>
  <div :class="{ 'touch-none': reorderActive }">
    <button
      v-if="isSettled(props.section.key)"
      type="button"
      class="flex w-full items-center gap-1.5 text-left transition-colors hover:bg-muted/40"
      :class="mobile ? 'px-3 py-1.5' : 'px-3 py-1'"
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
    <div
      v-else
      class="flex items-center gap-1.5"
      :class="mobile ? 'px-3 pt-2.5 pb-0.5' : 'px-3 pt-2 pb-1'"
    >
      <span class="truncate text-[10px] font-medium uppercase tracking-wide text-muted-foreground/70">{{ props.section.label }}</span>
      <span v-if="props.section.sessions.length > 1" class="text-[10px] tabular-nums text-muted-foreground/50">{{ props.section.sessions.length }}</span>
    </div>

    <template v-if="!isSettled(props.section.key) || props.expanded">
      <div
        v-for="s in displaySessions"
        :key="s.id"
        v-bind="canDrag ? rowProps(s.id) : { 'data-reorder-id': s.id }"
        class="touch-manipulation transition-[transform,opacity,box-shadow,background-color] duration-150 ease-out"
        :class="{
          'select-none [-webkit-user-select:none] [-webkit-touch-callout:none] [-webkit-user-drag:none]': canDrag,
          'opacity-30 scale-[0.98]': dragId === s.id,
          'cursor-grab': canDrag && !reorderActive,
          'cursor-grabbing touch-none': reorderActive,
        }"
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

    <Teleport to="body">
      <div
        v-if="ghost"
        class="pointer-events-none fixed z-[9999] rounded-md border border-primary/40 bg-card px-3 py-2.5 shadow-xl ring-1 ring-primary/20"
        :style="{
          left: ghost.x + 'px',
          top: ghost.y + 'px',
          width: ghost.width + 'px',
          minHeight: ghost.height + 'px',
          transform: 'scale(1.03)',
        }"
        aria-hidden="true"
      >
        <div class="flex items-center gap-2">
          <span class="size-1.5 shrink-0 rounded-full bg-primary" />
          <span class="truncate text-[13px] font-medium text-foreground">{{ ghost.label }}</span>
        </div>
        <div class="mt-0.5 text-[10px] text-muted-foreground/70">Release to drop</div>
      </div>
    </Teleport>
  </div>
</template>
