import { ref } from "vue"

/**
 * Detects a long-press (press-and-hold) on touch/pointer devices, where the
 * `contextmenu` event is unreliable. Bind the returned handlers to an element.
 * `fired` is true from the moment the long-press triggers until the next
 * pointerdown — use it to suppress the click/navigation that would otherwise
 * follow the press.
 */
export function useLongPress(
  onLongPress: (e: PointerEvent) => void,
  opts: { delay?: number; moveThreshold?: number } = {},
) {
  const delay = opts.delay ?? 500
  const moveThreshold = opts.moveThreshold ?? 10
  const fired = ref(false)
  let timer: ReturnType<typeof setTimeout> | null = null
  let startX = 0
  let startY = 0

  function clear() {
    if (timer != null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function onPointerdown(e: PointerEvent) {
    // Primary button / touch only — let right-click go through contextmenu.
    if (e.button != null && e.button !== 0) return
    fired.value = false
    startX = e.clientX
    startY = e.clientY
    clear()
    timer = setTimeout(() => {
      fired.value = true
      onLongPress(e)
    }, delay)
  }

  function onPointermove(e: PointerEvent) {
    if (timer == null) return
    if (Math.abs(e.clientX - startX) > moveThreshold || Math.abs(e.clientY - startY) > moveThreshold) {
      clear()
    }
  }

  function onPointerup() { clear() }
  function onPointerleave() { clear() }
  function onPointercancel() { clear() }

  return { fired, onPointerdown, onPointermove, onPointerup, onPointerleave, onPointercancel }
}
