import { ref, onUnmounted, type Ref } from "vue"

/** Move `fromId` to the index currently occupied by `toId`. Returns a new array. */
export function moveId(ids: string[], fromId: string, toId: string): string[] | null {
  if (fromId === toId) return null
  const fromIdx = ids.indexOf(fromId)
  const toIdx = ids.indexOf(toId)
  if (fromIdx < 0 || toIdx < 0) return null
  const next = ids.slice()
  next.splice(toIdx, 0, next.splice(fromIdx, 1)[0]!)
  return next
}

/**
 * Whole-row reorder for a vertical list of session ids.
 *
 * Why not HTML5 DnD alone:
 * - Nested interactive children (and default-draggable anchors) hijack drag as
 *   link-drag, so only non-interactive padding acts like a "handle".
 * - Touch browsers (iOS Safari especially) barely support HTML5 drag events.
 *
 * Interaction:
 * - Fine pointer (mouse): press + move past a small threshold starts a drag.
 * - Coarse pointer (touch): long-press (~350ms) without moving, then drag.
 * Horizontal swipe / scroll still win if the finger moves before long-press.
 */
export function useSectionReorder(opts: {
  /** Current ordered ids for the section being reordered. */
  ids: () => string[]
  /** Whether reorder is allowed right now. */
  enabled: () => boolean
  /** Fired with the new order after a successful drop. */
  onReorder: (orderedIds: string[]) => void
  /** Optional: called when a drag begins / ends so swipe handlers can pause. */
  onActiveChange?: (active: boolean) => void
}) {
  const dragId = ref<string | null>(null)
  const overId = ref<string | null>(null)
  const active = ref(false)
  // Click fires after pointerup; keep a short window so the drop doesn't navigate.
  let suppressClickUntil = 0

  let pointerId: number | null = null
  let startX = 0
  let startY = 0
  let longPressTimer: ReturnType<typeof setTimeout> | null = null
  let pendingId: string | null = null
  // Coarse pointers need a long-press; fine pointers drag immediately after move.
  let requireLongPress = false

  const LONG_PRESS_MS = 350
  const MOVE_CANCEL_PX = 10
  const DRAG_START_PX = 6

  function clearTimer() {
    if (longPressTimer != null) {
      clearTimeout(longPressTimer)
      longPressTimer = null
    }
  }

  function setActive(next: boolean) {
    if (active.value === next) return
    active.value = next
    opts.onActiveChange?.(next)
  }

  function beginDrag(id: string, el?: HTMLElement | null) {
    if (!opts.enabled()) return
    dragId.value = id
    overId.value = id
    setActive(true)
    if (el && pointerId != null) {
      try {
        el.setPointerCapture(pointerId)
      } catch {
        /* ignore */
      }
    }
    try {
      navigator.vibrate?.(10)
    } catch {
      /* ignore */
    }
  }

  function endDrag(commit: boolean) {
    clearTimer()
    const from = dragId.value
    const to = overId.value
    dragId.value = null
    overId.value = null
    pendingId = null
    pointerId = null
    setActive(false)

    if (commit && from) {
      // Always suppress the synthetic click after a real drag, even if order unchanged.
      suppressClickUntil = Date.now() + 400
    }
    if (!commit || !from || !to) return
    const next = moveId(opts.ids(), from, to)
    if (next) opts.onReorder(next)
  }

  function shouldSuppressClick(): boolean {
    return Date.now() < suppressClickUntil || active.value || dragId.value != null
  }

  function isCoarsePointer(e: PointerEvent): boolean {
    return e.pointerType === "touch" || e.pointerType === "pen"
  }

  function onPointerDown(id: string, e: PointerEvent, el: HTMLElement) {
    if (!opts.enabled()) return
    if (e.button != null && e.button !== 0) return
    const t = e.target as HTMLElement | null
    if (t?.closest("input, textarea, button, [data-no-reorder]")) return

    clearTimer()
    pendingId = id
    pointerId = e.pointerId
    startX = e.clientX
    startY = e.clientY
    requireLongPress = isCoarsePointer(e)

    if (requireLongPress) {
      longPressTimer = setTimeout(() => {
        longPressTimer = null
        if (pendingId !== id) return
        beginDrag(id, el)
      }, LONG_PRESS_MS)
    } else {
      try {
        el.setPointerCapture(e.pointerId)
      } catch {
        /* ignore */
      }
    }
  }

  function onPointerMove(e: PointerEvent) {
    if (pointerId == null || e.pointerId !== pointerId) return
    const dx = e.clientX - startX
    const dy = e.clientY - startY

    if (!active.value) {
      if (requireLongPress) {
        if (Math.abs(dx) > MOVE_CANCEL_PX || Math.abs(dy) > MOVE_CANCEL_PX) {
          clearTimer()
          pendingId = null
          pointerId = null
        }
        return
      }
      if (Math.abs(dx) > DRAG_START_PX || Math.abs(dy) > DRAG_START_PX) {
        if (pendingId) beginDrag(pendingId)
      }
      return
    }

    e.preventDefault()
    const stack = document.elementsFromPoint(e.clientX, e.clientY)
    for (const node of stack) {
      if (!(node instanceof HTMLElement)) continue
      const id = node.dataset.reorderId
      if (id && id !== dragId.value) {
        overId.value = id
        break
      }
    }
  }

  function onPointerUp(e: PointerEvent) {
    if (pointerId == null || e.pointerId !== pointerId) return
    const wasActive = active.value
    endDrag(wasActive)
  }

  function rowProps(id: string) {
    return {
      "data-reorder-id": id,
      onPointerdown: (e: PointerEvent) => {
        const el = e.currentTarget as HTMLElement
        onPointerDown(id, e, el)
      },
      onPointermove: onPointerMove,
      onPointerup: onPointerUp,
      onPointercancel: onPointerUp,
      onDragstart: (e: DragEvent) => e.preventDefault(),
    }
  }

  onUnmounted(() => {
    clearTimer()
    endDrag(false)
  })

  return {
    dragId: dragId as Ref<string | null>,
    overId: overId as Ref<string | null>,
    active: active as Ref<boolean>,
    shouldSuppressClick,
    rowProps,
  }
}
