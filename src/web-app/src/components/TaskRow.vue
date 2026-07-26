<script setup lang="ts">
// One row of the task list. Picks the desktop presentation (right-click context
// menu around a SessionRow) or the mobile one (swipe-to-reveal actions) from the
// `mobile` flag, so the list view never has to branch per platform.
import type { Session } from "@/stores/sessions"
import SessionRow from "./SessionRow.vue"
import SwipeableSessionRow from "./SwipeableSessionRow.vue"
import SessionContextMenu from "./SessionContextMenu.vue"

const props = defineProps<{
  session: Session
  mobile: boolean
  active?: boolean
  unread?: boolean
  variant: "in_progress" | "draft" | "settled"
  projectLabel?: string
  renaming?: boolean
  /** In-group card: no floating row chrome (Android-style flush rows). */
  flush?: boolean
}>()

const emit = defineEmits<{
  (e: "kill"): void
  (e: "mute"): void
  (e: "rename", newName: string): void
  (e: "rename-start"): void
  (e: "rename-cancel"): void
  (e: "settle"): void
  (e: "resume"): void
  (e: "open-draft"): void
  (e: "delete-draft"): void
  (e: "navigate"): void
  (e: "continue"): void
}>()
</script>

<template>
  <SwipeableSessionRow
    v-if="props.mobile"
    :id="props.session.id"
    :name="props.session.name"
    :workdir="props.session.workdir"
    :connected="props.session.connected"
    :mute="props.session.mute"
    :active="props.active"
    :unread="props.unread"
    :agent="props.session.agent"
    :model="props.session.model"
    :variant="props.variant"
    :project-label="props.projectLabel"
    :flush="props.flush"
    @kill="emit('kill')"
    @mute="emit('mute')"
    @rename="(_id, newName) => emit('rename', newName)"
    @settle="emit('settle')"
    @resume="emit('resume')"
    @open-draft="emit('open-draft')"
    @delete-draft="emit('delete-draft')"
    @continue="emit('continue')"
  />
  <SessionContextMenu
    v-else
    :name="props.session.name"
    :mute="props.session.mute"
    :variant="props.variant"
    @kill="emit('kill')"
    @mute="emit('mute')"
    @rename="emit('rename-start')"
    @settle="emit('settle')"
    @resume="emit('resume')"
    @open-draft="emit('open-draft')"
    @delete-draft="emit('delete-draft')"
    @continue="emit('continue')"
  >
    <template #default="{ onContextmenu }">
      <div @contextmenu="onContextmenu">
        <SessionRow
          :id="props.session.id"
          :name="props.session.name"
          :workdir="props.session.workdir"
          :connected="props.session.connected"
          :reserve-menu-space="true"
          :active="props.active"
          :unread="props.unread"
          :agent="props.session.agent"
          :model="props.session.model"
          :variant="props.variant"
          :project-label="props.projectLabel"
          :renaming="props.renaming"
          :flush="props.flush"
          @navigate="emit('navigate')"
          @rename="(newName) => emit('rename', newName)"
          @rename-cancel="emit('rename-cancel')"
        />
      </div>
    </template>
  </SessionContextMenu>
</template>
