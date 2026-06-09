import { ref, watch, onUnmounted, type Ref } from "vue"
import { useMediaQuery } from "@vueuse/core"

export interface SwipeRevealOptions {
  leftWidth?: number
  rightWidth?: number
  threshold?: number
  velocityThreshold?: number
}

export function useSwipeReveal(
  el: Ref<HTMLElement | null>,
  options: SwipeRevealOptions = {},
) {
  const {
    leftWidth = 140,
    rightWidth = 80,
    threshold = 0.3,
    velocityThreshold = 0.5,
  } = options

  const offset = ref(0)
  const state = ref<"idle" | "dragging" | "open-left" | "open-right">("idle")
  const isFinePointer = useMediaQuery("(pointer: fine)")

  let startX = 0
  let startY = 0
  let startTime = 0
  let committed = false
  let pointerId: number | null = null

  function clamp(val: number): number {
    const maxRight = rightWidth * 1.2
    const maxLeft = leftWidth * 1.2
    return Math.max(-maxRight, Math.min(maxLeft, val))
  }

  function snapTo(target: number) {
    const element = el.value
    if (!element) return
    const content = element.querySelector<HTMLElement>("[data-swipe-content]")
    if (!content) return
    content.style.transition = "transform 200ms ease-out"
    offset.value = target
    content.style.transform = `translateX(${target}px)`
    const onEnd = () => {
      content.style.transition = ""
      content.removeEventListener("transitionend", onEnd)
    }
    content.addEventListener("transitionend", onEnd, { once: true })
  }

  function close() {
    if (state.value === "idle") return
    state.value = "idle"
    snapTo(0)
  }

  function onPointerDown(e: PointerEvent) {
    if (isFinePointer.value) return
    if (e.button !== 0) return
    startX = e.clientX
    startY = e.clientY
    startTime = Date.now()
    committed = false
    pointerId = e.pointerId
    ;(e.currentTarget as HTMLElement).setPointerCapture(e.pointerId)
  }

  function onPointerMove(e: PointerEvent) {
    if (pointerId === null || e.pointerId !== pointerId) return
    const dx = e.clientX - startX
    const dy = e.clientY - startY

    if (!committed) {
      if (Math.abs(dx) < 10 && Math.abs(dy) < 10) return
      if (Math.abs(dy) > Math.abs(dx)) {
        pointerId = null
        return
      }
      committed = true
      state.value = "dragging"
      e.preventDefault()
    }

    if (committed) {
      e.preventDefault()
      offset.value = clamp(dx)
      const content = el.value?.querySelector<HTMLElement>("[data-swipe-content]")
      if (content) {
        content.style.transition = ""
        content.style.transform = `translateX(${offset.value}px)`
      }
    }
  }

  function onPointerUp(e: PointerEvent) {
    if (pointerId === null || e.pointerId !== pointerId) return
    pointerId = null

    if (!committed) return

    const dx = e.clientX - startX
    const dt = (Date.now() - startTime) / 1000
    const velocity = Math.abs(dx) / dt
    const width = el.value?.offsetWidth ?? 300
    const ratio = Math.abs(offset.value) / width

    if (offset.value < 0 && (ratio > threshold || velocity > velocityThreshold)) {
      state.value = "open-left"
      snapTo(-rightWidth)
    } else if (offset.value > 0 && (ratio > threshold || velocity > velocityThreshold)) {
      state.value = "open-right"
      snapTo(leftWidth)
    } else {
      state.value = "idle"
      snapTo(0)
    }
  }

  function attach(element: HTMLElement) {
    element.addEventListener("pointerdown", onPointerDown)
    element.addEventListener("pointermove", onPointerMove)
    element.addEventListener("pointerup", onPointerUp)
    element.addEventListener("pointercancel", onPointerUp)
    element.style.touchAction = "pan-y"
  }

  function detach(element: HTMLElement) {
    element.removeEventListener("pointerdown", onPointerDown)
    element.removeEventListener("pointermove", onPointerMove)
    element.removeEventListener("pointerup", onPointerUp)
    element.removeEventListener("pointercancel", onPointerUp)
  }

  watch(el, (newEl, oldEl) => {
    if (oldEl) detach(oldEl)
    if (newEl) attach(newEl)
  }, { immediate: true })

  onUnmounted(() => {
    if (el.value) detach(el.value)
  })

  return { offset, state, close }
}
