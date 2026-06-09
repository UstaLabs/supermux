interface PanelResizeOptions {
  orientation: "horizontal" | "vertical"
  unit: "px" | "pct"
  min: number
  max: number
  get: () => number
  set: (v: number) => void
  containerEl?: () => HTMLElement | null
  onReset?: () => void
}

function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v))
}

// Pointer-events drag handler for a resize handle. Works for mouse and touch.
// `orientation: "horizontal"` resizes along X (a column / col-resize handle);
// `"vertical"` resizes along Y (a row / row-resize handle).
export function usePanelResize(opts: PanelResizeOptions) {
  function onPointerDown(e: PointerEvent) {
    e.preventDefault()
    const handle = e.currentTarget as HTMLElement
    handle.setPointerCapture(e.pointerId)

    const startCoord = opts.orientation === "horizontal" ? e.clientX : e.clientY
    const startValue = opts.get()

    document.body.style.userSelect = "none"
    document.body.style.cursor = opts.orientation === "horizontal" ? "col-resize" : "row-resize"

    function onMove(ev: PointerEvent) {
      const coord = opts.orientation === "horizontal" ? ev.clientX : ev.clientY
      let deltaPx = coord - startCoord
      let delta = deltaPx
      if (opts.unit === "pct") {
        const el = opts.containerEl?.()
        const size = el ? (opts.orientation === "horizontal" ? el.clientWidth : el.clientHeight) : 0
        if (!size) return
        delta = (deltaPx / size) * 100
      }
      opts.set(clamp(startValue + delta, opts.min, opts.max))
    }

    function onUp(ev: PointerEvent) {
      try { handle.releasePointerCapture(ev.pointerId) } catch {}
      handle.removeEventListener("pointermove", onMove)
      handle.removeEventListener("pointerup", onUp)
      handle.removeEventListener("pointercancel", onUp)
      document.body.style.userSelect = ""
      document.body.style.cursor = ""
    }

    handle.addEventListener("pointermove", onMove)
    handle.addEventListener("pointerup", onUp)
    handle.addEventListener("pointercancel", onUp)
  }

  function onDblClick() {
    opts.onReset?.()
  }

  return { onPointerDown, onDblClick }
}
