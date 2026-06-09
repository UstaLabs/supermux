import { watch, type Ref } from "vue"

type TouchLockOptions = {
  /** When false, listeners are not attached (e.g. scrcpy manages its own touch). */
  enabled?: () => boolean
}

/**
 * Prevent iOS Safari / installed PWAs from scrolling the document while the
 * user drags on a display surface (VNC, scrcpy, letterboxed canvas, etc.).
 * Must use a non-passive touchmove listener — CSS touch-action alone is not
 * enough on WebKit standalone mode.
 */
export function useDisplayTouchLock(
  target: Ref<HTMLElement | null>,
  options: TouchLockOptions = {},
) {
  const blockScroll = (e: TouchEvent) => {
    if (e.cancelable) e.preventDefault()
  }

  watch(
    () => [target.value, options.enabled?.() ?? true] as const,
    ([el, enabled], _, onCleanup) => {
      if (!el || !enabled) return
      el.style.touchAction = "none"
      el.addEventListener("touchmove", blockScroll, { passive: false })
      onCleanup(() => {
        el.removeEventListener("touchmove", blockScroll)
        el.style.touchAction = ""
      })
    },
    { immediate: true },
  )
}
