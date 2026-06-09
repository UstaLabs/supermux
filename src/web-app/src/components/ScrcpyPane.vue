<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue"
import { useScrcpyStream } from "@/composables/useScrcpyStream"

const props = defineProps<{ streamId: string }>()
const surfaceRef = ref<HTMLElement | null>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
const stream = useScrcpyStream(props.streamId)
let ctx: CanvasRenderingContext2D | null = null
let pointerActive = false

function draw(frame: any) {
  const c = canvasRef.value
  if (!c) return
  if (c.width !== frame.displayWidth || c.height !== frame.displayHeight) {
    c.width = frame.displayWidth
    c.height = frame.displayHeight
  }
  ctx = ctx ?? c.getContext("2d")
  try { ctx?.drawImage(frame, 0, 0) } catch {}
}

function mapClient(clientX: number, clientY: number) {
  const c = canvasRef.value
  const { width, height } = stream.dims.value
  if (!c || !width || !height) return null
  const r = c.getBoundingClientRect()
  if (r.width === 0 || r.height === 0) return null
  const cx = Math.max(r.left, Math.min(r.right, clientX))
  const cy = Math.max(r.top, Math.min(r.bottom, clientY))
  return {
    x: Math.round(((cx - r.left) / r.width) * width),
    y: Math.round(((cy - r.top) / r.height) * height),
    width,
    height,
  }
}

function sendTouch(action: number, clientX: number, clientY: number) {
  const mapped = mapClient(clientX, clientY)
  if (!mapped) return
  stream.sendInput({ type: "touch", action, ...mapped })
}

function onTouchStart(e: TouchEvent) {
  for (const t of e.changedTouches) sendTouch(0, t.clientX, t.clientY)
}

function onTouchMove(e: TouchEvent) {
  // Blocks iOS PWA shell scroll while dragging on the display surface.
  if (e.cancelable) e.preventDefault()
  for (const t of e.changedTouches) sendTouch(2, t.clientX, t.clientY)
}

function onTouchEnd(e: TouchEvent) {
  for (const t of e.changedTouches) sendTouch(1, t.clientX, t.clientY)
}

function onPointerDown(e: PointerEvent) {
  if (e.pointerType === "touch") return
  pointerActive = true
  ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
  sendTouch(0, e.clientX, e.clientY)
}

function onPointerMove(e: PointerEvent) {
  if (e.pointerType === "touch" || !pointerActive) return
  sendTouch(2, e.clientX, e.clientY)
}

function onPointerUp(e: PointerEvent) {
  if (e.pointerType === "touch" || !pointerActive) return
  pointerActive = false
  sendTouch(1, e.clientX, e.clientY)
}

function onKey(e: KeyboardEvent, action: number) {
  if (action === 0 && e.key.length === 1) {
    stream.sendInput({ type: "text", text: e.key })
    e.preventDefault()
    return
  }
  stream.sendInput({ type: "key", action, key: e.key })
}

onMounted(() => {
  stream.onFrame(draw)
  stream.connect()
  surfaceRef.value?.addEventListener("touchmove", onTouchMove, { passive: false })
})
onUnmounted(() => {
  surfaceRef.value?.removeEventListener("touchmove", onTouchMove)
  stream.disconnect()
})
</script>

<template>
  <div
    ref="surfaceRef"
    class="relative w-full h-full min-h-0 bg-black flex items-center justify-center overscroll-none touch-none"
    data-cmux-keyboard-owner="scrcpy"
    tabindex="0"
    @touchstart.passive="onTouchStart"
    @touchend.passive="onTouchEnd"
    @touchcancel.passive="onTouchEnd"
    @keydown="(e) => onKey(e, 0)"
    @keyup="(e) => onKey(e, 1)"
  >
    <canvas
      ref="canvasRef"
      class="max-w-full max-h-full"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
    />

    <div v-if="stream.status.value === 'unsupported'" class="absolute inset-0 flex items-center justify-center text-center px-6 text-muted-foreground bg-background">
      <p class="text-sm">This browser lacks WebCodecs video decoding. Try the VNC stream instead.</p>
    </div>

    <div class="absolute top-2 right-2 pointer-events-none">
      <span :class="['inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[10px] font-medium select-none',
        stream.status.value === 'connected' ? 'bg-emerald-950/80 text-emerald-400' : 'bg-amber-950/80 text-amber-400']">
        <span :class="['inline-block size-1.5 rounded-full', stream.status.value === 'connected' ? 'bg-emerald-400' : 'bg-amber-400 animate-pulse']" />
        {{ stream.status.value === 'connected' ? 'Connected' : stream.status.value === 'connecting' ? 'Connecting…' : stream.status.value === 'unsupported' ? 'Unsupported' : 'Disconnected' }}
      </span>
    </div>
  </div>
</template>
