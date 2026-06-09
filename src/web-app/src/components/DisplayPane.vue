<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue"
import { useDisplayStream } from "@/composables/useDisplayStream"
import { useDisplayTouchLock } from "@/composables/useDisplayTouchLock"

const props = defineProps<{ streamId: string; provider: string }>()

const surfaceRef = ref<HTMLElement | null>(null)
const containerRef = ref<HTMLElement | null>(null)
useDisplayTouchLock(surfaceRef)
const password = ref("")
const needsPassword = ref(props.provider === "macos-screen")
const showPasswordPrompt = ref(props.provider === "macos-screen")

const stream = useDisplayStream(props.streamId)

function start() {
  if (!containerRef.value) return
  showPasswordPrompt.value = false
  stream.connect(containerRef.value, needsPassword.value ? password.value : undefined)
}

function toggleFullscreen() {
  const el = containerRef.value
  if (!el) return
  if (document.fullscreenElement) document.exitFullscreen()
  else el.requestFullscreen?.()
}

onMounted(() => {
  if (!needsPassword.value) start()
})

onUnmounted(() => stream.disconnect())
</script>

<template>
  <div class="relative w-full h-full min-h-0 bg-black">
    <div ref="surfaceRef" class="absolute inset-0 overscroll-none">
      <div ref="containerRef" class="w-full h-full" />
    </div>

    <!-- macOS password prompt -->
    <div v-if="showPasswordPrompt" class="absolute inset-0 flex items-center justify-center bg-black/80">
      <form class="bg-card ring-1 ring-border rounded-xl p-4 w-72 space-y-3" @submit.prevent="start">
        <p class="text-sm font-medium">Screen Sharing password</p>
        <input v-model="password" type="password" autocomplete="off"
          class="w-full rounded-md bg-background ring-1 ring-border px-3 py-2 text-sm" placeholder="VNC password" />
        <button type="submit" class="w-full rounded-md bg-primary text-primary-foreground py-2 text-sm">Connect</button>
      </form>
    </div>

    <!-- Status badge -->
    <div class="absolute top-2 right-2 pointer-events-none">
      <span :class="['inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[10px] font-medium select-none',
        stream.status.value === 'connected' ? 'bg-emerald-950/80 text-emerald-400' : 'bg-amber-950/80 text-amber-400']">
        <span :class="['inline-block size-1.5 rounded-full', stream.status.value === 'connected' ? 'bg-emerald-400' : 'bg-amber-400 animate-pulse']" />
        {{ stream.status.value === 'connected' ? 'Connected' : stream.status.value === 'connecting' ? 'Connecting…' : 'Disconnected' }}
      </span>
    </div>

    <!-- Controls -->
    <div class="absolute bottom-2 left-1/2 -translate-x-1/2 flex gap-2">
      <button class="px-2 py-1 rounded bg-card/90 ring-1 ring-border text-xs" @click="toggleFullscreen">Fullscreen</button>
      <button class="px-2 py-1 rounded bg-card/90 ring-1 ring-border text-xs" @click="stream.sendCtrlAltDel()">Ctrl-Alt-Del</button>
    </div>
  </div>
</template>
