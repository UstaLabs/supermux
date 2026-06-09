<script setup lang="ts">
import { computed, ref } from "vue"
import { Monitor } from "lucide-vue-next"
import { useDisplays } from "@/stores/displays"
import { api } from "@/api/client"
import { toast } from "vue-sonner"
import { useDisplayTouchLock } from "@/composables/useDisplayTouchLock"
import DisplayPane from "@/components/DisplayPane.vue"
import ScrcpyPane from "@/components/ScrcpyPane.vue"

const props = defineProps<{ sessionName: string }>()

const displays = useDisplays()
const starting = ref(false)

const stream = computed(() => displays.runningForSession(props.sessionName))

const surfaceRef = ref<HTMLElement | null>(null)
// VNC-only: scrcpy handles its own touch capture on ScrcpyPane.
useDisplayTouchLock(surfaceRef, { enabled: () => stream.value?.transport !== "h264" })

async function startNew() {
  starting.value = true
  try {
    await api.startDisplay({ sessionName: props.sessionName })
    // The stream appears via the display_added ws frame → store → computed.
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to start display")
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <div ref="surfaceRef" class="w-full h-full min-h-0 bg-black overscroll-none touch-none">
    <template v-if="stream">
      <ScrcpyPane v-if="stream.transport === 'h264'" :key="stream.id" :stream-id="stream.id" />
      <DisplayPane v-else :key="stream.id" :stream-id="stream.id" :provider="stream.provider" />
    </template>
    <div v-else class="w-full h-full flex flex-col items-center justify-center text-center px-6 text-muted-foreground bg-background">
      <div class="size-14 rounded-2xl bg-card ring-1 ring-border flex items-center justify-center mb-4">
        <Monitor class="size-6 text-muted-foreground" />
      </div>
      <p class="text-sm font-medium text-foreground">No display for this session</p>
      <p class="text-xs mt-1 mb-4">Ask the agent to show the app, or start one here.</p>
      <button
        class="text-xs px-3 py-1.5 rounded-md bg-primary text-primary-foreground disabled:opacity-50"
        :disabled="starting"
        @click="startNew"
      >{{ starting ? "Starting…" : "Start display" }}</button>
    </div>
  </div>
</template>
