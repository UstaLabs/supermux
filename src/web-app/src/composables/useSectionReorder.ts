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

/** Move the item at `fromIdx` to `toIdx` (target slot index in the pre-move list). */
export function moveIndex(ids: string[], fromIdx: number, toIdx: number): string[] | null {
  if (fromIdx < 0 || toIdx < 0 || fromIdx >= ids.length || toIdx >= ids.length) return null
  if (fromIdx === toIdx) return null
  const next = ids.slice()
  const [item] = next.splice(fromIdx, 1)
  next.splice(toIdx, 0, item!)
  return next
}

export interface ReorderGhost {
  label: string
  width: number
  height: number
  /** Ghost top-left in viewport coords */
  x: number
  y: number
}

const ARMED_CLASS = "cmux-session-reorder-armed"
const REORDERING_CLASS = "cmux-session-reordering"

function clearSystemSelection() {
  try {
    const sel = window.getSelection?.()
    if (sel && sel.rangeCount > 0) sel.removeAllRanges()
  } catch {
    /* ignore */
  }
}

function blockBrowserChrome(e: Event) {
  e.preventDefault()
}

/** Nearest ancestor that actually scrolls (or the document scroller). */
function findScrollParent(el: HTMLElement | null): HTMLElement {
  let node: HTMLElement | null = el
  while (node && node !== document.body && node !== document.documentElement) {
    const style = getComputedStyle(node)
    const oy = style.overflowY
    if ((oy === "auto" || oy === "scroll" || oy === "overlay") && node.scrollHeight > node.clientHeight + 1) {
      return node
    }
    node = node.parentElement
  }
  return (document.scrollingElement as HTMLElement | null) ?? document.documentElement
}

/**
 * Whole-row reorder with Sortable-style touch behaviour:
 * - Mouse: press + small move → grab
 * - Touch: long-press (~220ms) without moving past tolerance → grab
 * - Floating ghost under the finger, live list reordering, scroll locked while active
 * - Document-level non-passive pointer listeners so preventDefault actually works
 */
export function useSectionReorder(opts: {
  ids: () => string[]
  enabled: () => boolean
  onReorder: (orderedIds: string[]) => void
  /** Optional label for the floating ghost (defaults to id). */
  labelFor?: (id: string) => string
  onActiveChange?: (active: boolean) => void
}) {
  const dragId = ref<string | null>(null)
  const active = ref(false)
  /** Live order while dragging; empty when idle (render from props). */
  const orderedIds = ref<string[]>([])
  const ghost = ref<ReorderGhost | null>(null)

  let suppressClickUntil = 0
  let pointerId: number | null = null
  let startX = 0
  let startY = 0
  let grabOffsetX = 0
  let grabOffsetY = 0
  let longPressTimer: ReturnType<typeof setTimeout> | null = null
  let pendingId: string | null = null
  let captureEl: HTMLElement | null = null
  let requireLongPress = false
  let startOrder: string[] = []
  let guardsPhase: "off" | "armed" | "reordering" = "off"
  let docListening = false
  let scrollParent: HTMLElement | null = null
  let lastClientX = 0
  let lastClientY = 0
  let autoScrollRaf: number | null = null
  /** -1 = up, 0 = idle, +1 = down (strength scaled by edge proximity). */
  let autoScrollDir = 0
  let autoScrollStrength = 0

  // Sortable-ish touch timings: short delay, small movement cancels into scroll/swipe.
  const LONG_PRESS_MS = 220
  const MOVE_CANCEL_PX = 8
  const DRAG_START_PX = 5
  /** px from top/bottom of the scroll viewport that trigger auto-scroll */
  const EDGE_ZONE_PX = 56
  /** max px per frame (~60fps) when fully in the edge zone */
  const AUTO_SCROLL_MAX_PX = 18

  function clearTimer() {
    if (longPressTimer != null) {
      clearTimeout(longPressTimer)
      longPressTimer = null
    }
  }

  function bindChromeListeners() {
    document.addEventListener("selectstart", blockBrowserChrome, true)
    document.addEventListener("contextmenu", blockBrowserChrome, true)
    document.addEventListener("selectionchange", clearSystemSelection, true)
  }

  function unbindChromeListeners() {
    document.removeEventListener("selectstart", blockBrowserChrome, true)
    document.removeEventListener("contextmenu", blockBrowserChrome, true)
    document.removeEventListener("selectionchange", clearSystemSelection, true)
  }

  function setGuardsPhase(phase: "off" | "armed" | "reordering") {
    if (typeof document === "undefined") {
      guardsPhase = phase
      return
    }
    const root = document.documentElement
    if (guardsPhase === "off" && phase !== "off") bindChromeListeners()
    if (guardsPhase !== "off" && phase === "off") unbindChromeListeners()
    root.classList.toggle(ARMED_CLASS, phase === "armed")
    root.classList.toggle(REORDERING_CLASS, phase === "reordering")
    guardsPhase = phase
  }

  function handleMove(clientX: number, clientY: number, e?: Event) {
    const dx = clientX - startX
    const dy = clientY - startY

    if (!active.value) {
      if (requireLongPress) {
        if (Math.abs(dx) > MOVE_CANCEL_PX || Math.abs(dy) > MOVE_CANCEL_PX) {
          // Scroll / swipe wins — abort arming.
          clearTimer()
          pendingId = null
          pointerId = null
          captureEl = null
          detachDocListeners()
          setGuardsPhase("off")
        }
        return
      }
      if (Math.abs(dx) > DRAG_START_PX || Math.abs(dy) > DRAG_START_PX) {
        if (pendingId) beginDrag(pendingId, captureEl, clientX, clientY)
      }
      return
    }

    // Active drag: own the gesture completely.
    e?.preventDefault()
    clearSystemSelection()
    lastClientX = clientX
    lastClientY = clientY
    updateGhost(clientX, clientY)
    updateAutoScroll(clientY)
    liveReorderAt(clientY)
  }

  function onDocPointerMove(e: PointerEvent) {
    if (pointerId == null || e.pointerId !== pointerId) return
    handleMove(e.clientX, e.clientY, e)
  }

  function onDocPointerUp(e: PointerEvent) {
    if (pointerId == null || e.pointerId !== pointerId) return
    const wasActive = active.value
    if (!wasActive) setGuardsPhase("off")
    endDrag(wasActive)
  }

  function onDocTouchMove(e: TouchEvent) {
    if (pointerId == null) return
    const t = e.touches[0]
    if (!t) return
    handleMove(t.clientX, t.clientY, e)
  }

  function onDocTouchEnd(e: TouchEvent) {
    if (pointerId == null) return
    // Ignore if another finger remains
    if (e.touches.length > 0) return
    const wasActive = active.value
    if (!wasActive) setGuardsPhase("off")
    endDrag(wasActive)
  }

  function attachDocListeners() {
    if (docListening) return
    docListening = true
    // Non-passive so preventDefault can stop scroll under the finger.
    document.addEventListener("pointermove", onDocPointerMove, { capture: true, passive: false })
    document.addEventListener("pointerup", onDocPointerUp, { capture: true })
    document.addEventListener("pointercancel", onDocPointerUp, { capture: true })
    // Touch fallback: some WebViews fire touch* without reliable pointer* sequences.
    document.addEventListener("touchmove", onDocTouchMove, { capture: true, passive: false })
    document.addEventListener("touchend", onDocTouchEnd, { capture: true })
    document.addEventListener("touchcancel", onDocTouchEnd, { capture: true })
  }

  function detachDocListeners() {
    if (!docListening) return
    docListening = false
    document.removeEventListener("pointermove", onDocPointerMove, true)
    document.removeEventListener("pointerup", onDocPointerUp, true)
    document.removeEventListener("pointercancel", onDocPointerUp, true)
    document.removeEventListener("touchmove", onDocTouchMove, true)
    document.removeEventListener("touchend", onDocTouchEnd, true)
    document.removeEventListener("touchcancel", onDocTouchEnd, true)
  }

  function stopAutoScroll() {
    autoScrollDir = 0
    autoScrollStrength = 0
    if (autoScrollRaf != null) {
      cancelAnimationFrame(autoScrollRaf)
      autoScrollRaf = null
    }
  }

  /**
   * When the finger is near the top/bottom of the scroll container (or the
   * window, on full-page mobile), keep scrolling so reordering past the
   * visible window works.
   */
  function updateAutoScroll(clientY: number) {
    const scroller = scrollParent
    if (!scroller) {
      stopAutoScroll()
      return
    }

    const isDoc =
      scroller === document.documentElement ||
      scroller === document.body ||
      scroller === document.scrollingElement

    const top = isDoc ? 0 : scroller.getBoundingClientRect().top
    const bottom = isDoc ? window.innerHeight : scroller.getBoundingClientRect().bottom
    const maxScroll = Math.max(0, scroller.scrollHeight - (isDoc ? window.innerHeight : scroller.clientHeight))

    let dir = 0
    let strength = 0
    if (clientY < top + EDGE_ZONE_PX && scroller.scrollTop > 0) {
      dir = -1
      strength = Math.min(1, (top + EDGE_ZONE_PX - clientY) / EDGE_ZONE_PX)
    } else if (clientY > bottom - EDGE_ZONE_PX && scroller.scrollTop < maxScroll - 0.5) {
      dir = 1
      strength = Math.min(1, (clientY - (bottom - EDGE_ZONE_PX)) / EDGE_ZONE_PX)
    }

    autoScrollDir = dir
    autoScrollStrength = strength
    if (dir === 0) {
      if (autoScrollRaf != null) {
        cancelAnimationFrame(autoScrollRaf)
        autoScrollRaf = null
      }
      return
    }
    if (autoScrollRaf == null) autoScrollRaf = requestAnimationFrame(tickAutoScroll)
  }

  function tickAutoScroll() {
    autoScrollRaf = null
    if (!active.value || autoScrollDir === 0 || !scrollParent) return

    const scroller = scrollParent
    const isDoc =
      scroller === document.documentElement ||
      scroller === document.body ||
      scroller === document.scrollingElement
    const maxScroll = Math.max(0, scroller.scrollHeight - (isDoc ? window.innerHeight : scroller.clientHeight))
    // Ease-in: stronger when deeper in the edge band (quadratic feels snappier).
    const px = AUTO_SCROLL_MAX_PX * autoScrollStrength * autoScrollStrength
    const next = Math.max(0, Math.min(maxScroll, scroller.scrollTop + autoScrollDir * px))
    if (next !== scroller.scrollTop) {
      scroller.scrollTop = next
      // Finger is still; re-evaluate insert position against newly revealed rows.
      liveReorderAt(lastClientY)
      updateGhost(lastClientX, lastClientY)
    }

    // Keep going while still in the edge zone and there's room to scroll.
    const canContinue =
      (autoScrollDir < 0 && scroller.scrollTop > 0) ||
      (autoScrollDir > 0 && scroller.scrollTop < maxScroll - 0.5)
    if (canContinue && autoScrollDir !== 0) {
      autoScrollRaf = requestAnimationFrame(tickAutoScroll)
    } else {
      autoScrollDir = 0
      autoScrollStrength = 0
    }
  }

  function updateGhost(clientX: number, clientY: number) {
    const g = ghost.value
    if (!g) return
    ghost.value = {
      ...g,
      x: clientX - grabOffsetX,
      y: clientY - grabOffsetY,
    }
  }

  /**
   * Place the dragged id so its slot midpoint is nearest to `clientY`.
   * Mutates `orderedIds` live so the list reflows under the ghost.
   */
  function liveReorderAt(clientY: number) {
    const id = dragId.value
    if (!id) return
    const ids = orderedIds.value
    const fromIdx = ids.indexOf(id)
    if (fromIdx < 0) return

    type Slot = { id: string; mid: number }
    const slots: Slot[] = []
    for (const rid of ids) {
      const el = document.querySelector<HTMLElement>(`[data-reorder-id="${CSS.escape(rid)}"]`)
      if (!el) continue
      const r = el.getBoundingClientRect()
      slots.push({ id: rid, mid: r.top + r.height / 2 })
    }
    if (slots.length === 0) return

    // Prefer the slot whose midpoint the finger has crossed, with a small
    // hysteresis band so tiny jitter does not thrash neighbors.
    let toIdx = fromIdx
    for (let i = 0; i < slots.length; i++) {
      if (i === fromIdx) continue
      const mid = slots[i]!.mid
      if (i < fromIdx && clientY < mid - 6) toIdx = i
      if (i > fromIdx && clientY > mid + 6) toIdx = i
    }
    // Fall back to nearest midpoint if hysteresis did not move.
    if (toIdx === fromIdx) {
      let bestIdx = fromIdx
      let bestDist = Infinity
      for (let i = 0; i < slots.length; i++) {
        const d = Math.abs(slots[i]!.mid - clientY)
        if (d < bestDist) {
          bestDist = d
          bestIdx = i
        }
      }
      // Only snap when clearly closer to another slot.
      if (bestIdx !== fromIdx && bestDist + 12 < Math.abs(slots[fromIdx]!.mid - clientY)) {
        toIdx = bestIdx
      }
    }

    if (toIdx < 0 || toIdx === fromIdx) return
    const next = moveIndex(ids, fromIdx, toIdx)
    if (next) orderedIds.value = next
  }

  function beginDrag(id: string, el: HTMLElement | null, clientX: number, clientY: number) {
    if (!opts.enabled()) return
    clearSystemSelection()
    const ids = opts.ids()
    startOrder = ids.slice()
    orderedIds.value = ids.slice()
    dragId.value = id
    scrollParent = findScrollParent(el)
    lastClientX = clientX
    lastClientY = clientY
    stopAutoScroll()

    const rect = el?.getBoundingClientRect()
    grabOffsetX = rect ? clientX - rect.left : 24
    grabOffsetY = rect ? clientY - rect.top : 20
    const label = opts.labelFor?.(id) ?? id
    ghost.value = {
      label,
      width: rect?.width ?? 280,
      height: rect?.height ?? 56,
      x: clientX - grabOffsetX,
      y: clientY - grabOffsetY,
    }

    active.value = true
    setGuardsPhase("reordering")
    opts.onActiveChange?.(true)
    // Do NOT setPointerCapture on the row: live reordering mutates the v-for
    // DOM and drops capture → pointercancel → aborted drag. Document-level
    // non-passive listeners (attachDocListeners) own the gesture instead.
    try {
      navigator.vibrate?.(12)
    } catch {
      /* ignore */
    }
  }

  function endDrag(commit: boolean) {
    clearTimer()
    stopAutoScroll()
    scrollParent = null
    const from = dragId.value
    const finalOrder = orderedIds.value.slice()
    const changed =
      commit &&
      from != null &&
      finalOrder.length > 0 &&
      (finalOrder.length !== startOrder.length || finalOrder.some((id, i) => id !== startOrder[i]))

    dragId.value = null
    orderedIds.value = []
    ghost.value = null
    pendingId = null
    pointerId = null
    captureEl = null
    const wasActive = active.value
    active.value = false
    setGuardsPhase("off")
    detachDocListeners()
    clearSystemSelection()

    if (wasActive) opts.onActiveChange?.(false)

    if (commit && from) {
      suppressClickUntil = Date.now() + 450
    }
    if (changed) opts.onReorder(finalOrder)
  }

  function shouldSuppressClick(): boolean {
    return Date.now() < suppressClickUntil || active.value || dragId.value != null
  }



  function armFromPoint(id: string, el: HTMLElement, clientX: number, clientY: number, coarse: boolean, pid: number) {
    if (!opts.enabled()) return
    const t = document.elementFromPoint(clientX, clientY) as HTMLElement | null
    if (t?.closest("input, textarea, button, [data-no-reorder]")) return

    clearTimer()
    clearSystemSelection()
    pendingId = id
    pointerId = pid
    startX = clientX
    startY = clientY
    captureEl = el
    requireLongPress = coarse
    attachDocListeners()

    if (coarse) {
      setGuardsPhase("armed")
      longPressTimer = setTimeout(() => {
        longPressTimer = null
        if (pendingId !== id) return
        beginDrag(id, el, startX, startY)
      }, LONG_PRESS_MS)
    }
  }

  function rowProps(id: string) {
    return {
      "data-reorder-id": id,
      onPointerdown: (e: PointerEvent) => {
        if (e.button != null && e.button !== 0) return
        // Prefer pointer path; skip if this is a touch-originated pointer (touchstart will arm).
        // Actually on iOS both may fire — use pointer only when not touch.
        if (e.pointerType === "touch" || e.pointerType === "pen") {
          armFromPoint(id, e.currentTarget as HTMLElement, e.clientX, e.clientY, true, e.pointerId)
          return
        }
        armFromPoint(id, e.currentTarget as HTMLElement, e.clientX, e.clientY, false, e.pointerId)
      },
      onTouchstart: (e: TouchEvent) => {
        // Dual path for environments where pointer events are incomplete.
        if (e.touches.length !== 1) return
        const touch = e.touches[0]!
        armFromPoint(id, e.currentTarget as HTMLElement, touch.clientX, touch.clientY, true, touch.identifier + 1000)
      },
      onDragstart: (e: DragEvent) => e.preventDefault(),
      onContextmenu: (e: Event) => {
        if (active.value || pendingId) e.preventDefault()
      },
      onSelectstart: (e: Event) => e.preventDefault(),
    }
  }

  onUnmounted(() => {
    clearTimer()
    detachDocListeners()
    setGuardsPhase("off")
    endDrag(false)
  })

  return {
    dragId: dragId as Ref<string | null>,
    active: active as Ref<boolean>,
    orderedIds: orderedIds as Ref<string[]>,
    ghost: ghost as Ref<ReorderGhost | null>,
    shouldSuppressClick,
    rowProps,
  }
}
