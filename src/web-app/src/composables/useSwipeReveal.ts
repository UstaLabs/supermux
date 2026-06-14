import { ref, watch, onUnmounted, type Ref } from "vue"
import { useMediaQuery } from "@vueuse/core"

export interface SwipeRevealOptions {
  leftWidth?: number
  rightWidth?: number
  threshold?: number
  velocityThreshold?: number
}

export type SwipeTargetState = "idle" | "open-left" | "open-right"

export interface SwipeGeometry {
  leftWidth: number
  rightWidth: number
  threshold: number
  velocityThreshold: number
}

/**
 * Decide where a swipe should settle once the finger lifts.
 *
 * `offset` is the cumulative horizontal translate of the row content (px):
 * positive reveals the left-hand buttons, negative reveals the right-hand
 * button. `velocity` is SIGNED px/ms (positive = finger moving right).
 *
 * A flick faster than `velocityThreshold` wins in its own direction. That is
 * what lets a swipe back toward centre CLOSE an already-open row instead of
 * flinging the opposite side open. Without a decisive flick, the row settles by
 * distance: it only holds a side open once dragged past `threshold` of that
 * side's width, so a partial drag back to centre closes.
 */
export function resolveSwipeTarget(
  offset: number,
  velocity: number,
  geo: SwipeGeometry,
): { target: number; state: SwipeTargetState } {
  const { leftWidth, rightWidth, threshold, velocityThreshold } = geo

  const settle = (target: number): { target: number; state: SwipeTargetState } => ({
    target,
    state: target > 0 ? "open-right" : target < 0 ? "open-left" : "idle",
  })

  // Decisive flick: snap in the direction of travel. From a closed row this
  // opens the side being swiped toward; from a row open the other way it falls
  // through to 0 and closes.
  if (velocity >= velocityThreshold) return settle(offset >= 0 ? leftWidth : 0)
  if (velocity <= -velocityThreshold) return settle(offset <= 0 ? -rightWidth : 0)

  // Slow release: hold a side open only once dragged past its threshold.
  if (offset > leftWidth * threshold) return settle(leftWidth)
  if (offset < -rightWidth * threshold) return settle(-rightWidth)
  return settle(0)
}

export function useSwipeReveal(
  el: Ref<HTMLElement | null>,
  options: SwipeRevealOptions = {},
) {
  const {
    leftWidth = 140,
    rightWidth = 80,
    threshold = 0.3,
    // Flick speed in px/ms (~300 px/s). A deliberate swipe clears this easily,
    // so swiping back toward centre reliably closes the row.
    velocityThreshold = 0.3,
  } = options

  const offset = ref(0)
  const state = ref<"idle" | "dragging" | "open-left" | "open-right">("idle")
  const isFinePointer = useMediaQuery("(pointer: fine)")

  let startX = 0
  let startY = 0
  let startTime = 0
  let baseOffset = 0
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
    // Resume from wherever the row currently rests so an open row drags
    // continuously back toward centre instead of jumping.
    baseOffset = offset.value
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
      offset.value = clamp(baseOffset + dx)
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
    const dtMs = Math.max(Date.now() - startTime, 1)
    const velocity = dx / dtMs // signed px/ms

    const { target, state: next } = resolveSwipeTarget(offset.value, velocity, {
      leftWidth,
      rightWidth,
      threshold,
      velocityThreshold,
    })
    state.value = next
    snapTo(target)
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
