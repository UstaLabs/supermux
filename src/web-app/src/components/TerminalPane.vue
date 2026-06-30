<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, onActivated, nextTick, toRef } from "vue"
import { Terminal } from "@xterm/xterm"
import { FitAddon } from "@xterm/addon-fit"
import { WebLinksAddon } from "@xterm/addon-web-links"
import { WebglAddon } from "@xterm/addon-webgl"
import { ClipboardPaste } from "lucide-vue-next"
import "@xterm/xterm/css/xterm.css"
import { useTerminal } from "@/composables/useTerminal"
import { linesFromPixels, wheelEventsFromLines } from "@/lib/touch-scroll"
import { PredictionEngine } from "@/lib/predictive-echo/engine"
import { XtermPredictionAdapter } from "@/lib/predictive-echo/xterm-adapter"
import { decodeInput, DEFAULT_CONFIG, type DisplayOp } from "@/lib/predictive-echo/types"

const props = defineProps<{
  sessionName: string
  terminalId: string
  active: boolean
  kind?: "scratch" | "agent"
}>()

const emit = defineEmits<{ exit: [] }>()

const containerRef = ref<HTMLElement | null>(null)

let term: Terminal | null = null
let fitAddon: FitAddon | null = null
let ro: ResizeObserver | null = null
let lastSentSize: { cols: number; rows: number } | null = null

let predictor: PredictionEngine | null = null
let predAdapter: XtermPredictionAdapter | null = null
// id → cell, plus `prev`: the char that was in the cell before we drew the dim
// guess, so a rollback can restore it (the cell can't be re-read correctly at
// rollback time — reconcile runs before the server's bytes are painted).
const predCells = new Map<number, { row: number; col: number; prev: string }>()
// performance.now() of the last keystroke still awaiting its echo (0 = none).
// Feeds the latency gate from a real keystroke→echo measurement, INDEPENDENTLY
// of the prediction path — without this the gate could never open: latency
// starts at 0, predictions need latency ≥ threshold, and latency was otherwise
// only ever learned from confirmed predictions (which need the gate already open).
let lastKeyAt = 0

// Apply the engine's display ops to the terminal: draw dim predicts, dismiss on
// confirm (the real server byte overwrites the cell), erase on rollback.
function applyPredOps(ops: DisplayOp[]) {
  if (!predAdapter) return
  for (const op of ops) {
    if (op.op === "predict") {
      // Snapshot the cell BEFORE the dim guess overwrites it, so a rollback can
      // restore what was there. Caveat: a cell re-predicted before any confirm
      // (backspace-then-retype over non-space text) snapshots the intervening dim
      // guess, so a rollback may restore a space until the next server redraw —
      // narrow and self-healing, and still better than re-affirming a wrong glyph.
      const prev = predAdapter.readCell(op.row, op.col)
      predCells.set(op.id, { row: op.row, col: op.col, prev })
      predAdapter.apply([op])
    } else if (op.op === "confirm") {
      predCells.delete(op.id)
    } else if (op.op === "rollback") {
      for (const id of op.ids) {
        const c = predCells.get(id)
        if (c) predAdapter.restoreCell(c.row, c.col, c.prev)
        predCells.delete(id)
      }
    }
  }
}

// Touch-drag scrolling. xterm v6 ships a gesture engine but never registers the
// terminal as a target, so finger swipes are ignored; we drive scrollLines()
// directly instead. State for the in-progress drag:
let touchSurface: HTMLElement | null = null
let touchActive = false
let touchLastY = 0
let touchAccumPx = 0

const terminal = useTerminal(toRef(() => props.sessionName), toRef(() => props.terminalId), toRef(() => props.kind ?? "scratch"))

function cssVar(name: string, fallback: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback
}

/** True when the container is laid out (not display:none / zero-size). */
function containerVisible(): boolean {
  const el = containerRef.value
  return !!el && el.offsetWidth > 0 && el.offsetHeight > 0
}

function fit() {
  if (!fitAddon || !term || !containerVisible()) return
  try {
    fitAddon.fit()
  } catch {
    return
  }
  const { cols, rows } = term
  if (cols < 1 || rows < 1) return
  if (lastSentSize?.cols === cols && lastSentSize?.rows === rows) return
  lastSentSize = { cols, rows }
  terminal.resize(cols, rows)
}

function scheduleFit() {
  nextTick(() => {
    requestAnimationFrame(() => fit())
  })
}

async function pasteFromClipboard() {
  try {
    const text = await navigator.clipboard.readText()
    if (text) {
      term?.paste(text)
      term?.focus()
    }
  } catch {}
}

/** Rendered height of one terminal row, for converting drag pixels → lines. */
function rowHeightPx(): number {
  const screen = containerRef.value?.querySelector<HTMLElement>(".xterm-screen")
  if (screen && term && term.rows > 0) {
    const h = screen.clientHeight / term.rows
    if (h > 0) return h
  }
  // Before first paint, fall back to the configured fontSize × lineHeight.
  return 13 * 1.2
}

/**
 * Scroll by whole rows. A plain shell has xterm's own scrollback, so we scroll
 * that locally. But under a full-screen app that grabbed the mouse (tmux with
 * `mouse on`, the default for both terminal kinds) the screen lives in the
 * alternate buffer, which has NO xterm scrollback — scrollLines() is a silent
 * no-op. There we forward mouse-wheel events to the app instead, exactly like
 * the desktop mouse wheel does, and let it scroll its own history. The wheel
 * coordinate just has to land in the pane; our windows are single-pane.
 */
function scrollByLines(lines: number) {
  if (!term) return
  if (term.modes.mouseTrackingMode !== "none") {
    const col = Math.max(1, Math.ceil(term.cols / 2))
    const row = Math.max(1, Math.ceil(term.rows / 2))
    const seq = wheelEventsFromLines(lines, col, row)
    if (seq) terminal.sendInput(new TextEncoder().encode(seq))
  } else {
    term.scrollLines(lines)
  }
}

function onTouchStart(e: TouchEvent) {
  if (e.touches.length !== 1) {
    touchActive = false
    return
  }
  touchActive = true
  touchLastY = e.touches[0]!.clientY
  touchAccumPx = 0
}

function onTouchMove(e: TouchEvent) {
  if (!touchActive || e.touches.length !== 1 || !term) return
  const y = e.touches[0]!.clientY
  touchAccumPx += touchLastY - y
  touchLastY = y
  const { lines, remainderPx } = linesFromPixels(touchAccumPx, rowHeightPx())
  touchAccumPx = remainderPx
  if (lines !== 0) scrollByLines(lines)
  // The terminal owns vertical drags — stop the PWA viewport from scrolling or
  // rubber-banding underneath. Requires the non-passive listener registered below.
  if (e.cancelable) e.preventDefault()
}

function endTouch() {
  touchActive = false
}

onMounted(() => {
  if (!containerRef.value) return

  term = new Terminal({
    theme: {
      background: cssVar("--cmux-terminal", "#141511"),
      foreground: cssVar("--cmux-terminal-foreground", "#d8ded3"),
      cursor: cssVar("--primary", "#4cc2aa"),
      selectionBackground: "rgba(76, 194, 170, 0.28)",
      black: "#050605",
      brightBlack: "#5c6359",
      white: "#d8ded3",
      brightWhite: "#ffffff",
    },
    fontFamily: '"JetBrains Mono", "Fira Code", "Cascadia Code", monospace',
    fontSize: 13,
    lineHeight: 1.2,
    cursorBlink: true,
    allowProposedApi: true,
  })

  fitAddon = new FitAddon()
  term.loadAddon(fitAddon)
  term.loadAddon(new WebLinksAddon())

  term.open(containerRef.value)

  // GPU renderer. Must load AFTER open(). xterm falls back to its DOM renderer
  // automatically if we never attach a renderer addon, so any failure here
  // (no WebGL2, context creation refused, addon throws) must be swallowed — a
  // missing GPU path is a perf regression, never a broken terminal.
  try {
    const webgl = new WebglAddon()
    // The browser can drop the GL context (OOM, tab backgrounded, GPU reset).
    // Dispose on loss so xterm reverts to the DOM renderer instead of freezing.
    webgl.onContextLoss(() => { try { webgl.dispose() } catch {} })
    term.loadAddon(webgl)
  } catch { /* no WebGL2 → DOM renderer stays */ }

  fit()

  // Predictive local echo: pure-logic engine + xterm dim-render adapter.
  predictor = new PredictionEngine(DEFAULT_CONFIG, () => performance.now())
  predAdapter = new XtermPredictionAdapter(term)

  // Pipe terminal input → WS (+ predictive local echo: show the keystroke instantly)
  term.onData((data) => {
    if (predictor && predAdapter) applyPredOps(predictor.onInput(decodeInput(data), predAdapter.cursor()))
    lastKeyAt = performance.now() // mark for the keystroke→echo RTT measured below
    const encoder = new TextEncoder()
    terminal.sendInput(encoder.encode(data))
  })

  // Paste is handled entirely by xterm's built-in clipboard support: Ctrl/Cmd+V,
  // right-click, and the OS paste menu all fire a native `paste` event that xterm
  // turns into a single term.paste() → onData above.
  // Do NOT add a custom Ctrl+V key handler or a `paste` DOM listener here: xterm
  // already pastes natively, so any extra path duplicates every paste (and
  // navigator.clipboard.readText() also needs a permission grant and is missing
  // in Firefox). For touch devices, the explicit paste button below is the
  // reliable fallback.

  // Pipe WS binary → terminal (reconcile predictions against the authoritative
  // bytes first — confirm/rollback — then render the bytes).
  terminal.onData((data: Uint8Array) => {
    // Coarse keystroke→echo RTT drives the latency gate. Deliberately rough: the
    // first server byte after a keystroke may be unrelated output, and during
    // rapid typing only the most recent keystroke is timed (a slight underestimate
    // that biases conservatively, toward not predicting). The engine's EWMA and
    // threshold absorb the noise; a stray low sample just declines to predict for
    // a beat. The point is that this runs even while predictions are inert, which
    // is what lets the gate open in the first place.
    if (predictor && lastKeyAt > 0) {
      predictor.setLatencyEstimate(performance.now() - lastKeyAt)
      lastKeyAt = 0
    }
    if (predictor && predAdapter) applyPredOps(predictor.onServerData(data))
    term?.write(data)
  })

  // Handle session exit — the shell/tmux session actually ended (a detach does
  // NOT fire this), so let the panel drop this tab.
  terminal.onExit((code: number) => {
    const msg = code === 0
      ? "\r\n\x1b[90mSession ended.\x1b[0m\r\n"
      : `\r\n\x1b[90mSession ended (exit ${code}).\x1b[0m\r\n`
    term?.write(msg)
    emit("exit")
  })

  // ResizeObserver to refit when container size changes
  ro = new ResizeObserver(() => { fit() })
  ro.observe(containerRef.value)

  // Touch-drag scrolling (see notes by the touch state above). touchmove must be
  // non-passive so preventDefault() can stop the page from scrolling instead.
  touchSurface = containerRef.value
  touchSurface.addEventListener("touchstart", onTouchStart, { passive: true })
  touchSurface.addEventListener("touchmove", onTouchMove, { passive: false })
  touchSurface.addEventListener("touchend", endTouch, { passive: true })
  touchSurface.addEventListener("touchcancel", endTouch, { passive: true })

  terminal.connect()
})

onUnmounted(() => {
  ro?.disconnect()
  if (touchSurface) {
    touchSurface.removeEventListener("touchstart", onTouchStart)
    touchSurface.removeEventListener("touchmove", onTouchMove)
    touchSurface.removeEventListener("touchend", endTouch)
    touchSurface.removeEventListener("touchcancel", endTouch)
    touchSurface = null
  }
  terminal.disconnect()
  predictor = null
  predAdapter = null
  predCells.clear()
  term?.dispose()
  term = null
  fitAddon = null
})

// When the tab becomes active, refit after the DOM has fully rendered
watch(
  () => props.active,
  (active) => {
    if (active) scheduleFit()
  },
)

onActivated(() => {
  scheduleFit()
})
</script>

<template>
  <div class="relative w-full h-full bg-[var(--cmux-terminal)]">
    <!-- Terminal container: xterm handles its own padding -->
    <div ref="containerRef" class="w-full h-full" />

    <!-- Connection status badge -->
    <div class="absolute top-2 right-2 pointer-events-none">
      <span
        :class="[
          'inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[10px] font-medium select-none',
          terminal.status.value === 'connected'
            ? 'bg-primary/15 text-primary border border-primary/30'
            : 'bg-[color-mix(in_oklab,var(--cmux-warning)_14%,transparent)] text-[var(--cmux-warning)] border border-[color-mix(in_oklab,var(--cmux-warning)_30%,transparent)]',
        ]"
      >
        <span
          :class="[
            'inline-block size-1.5 rounded-full',
            terminal.status.value === 'connected'
              ? 'bg-primary'
              : 'bg-[var(--cmux-warning)] animate-pulse',
          ]"
        />
        {{
          terminal.status.value === 'connected'
            ? 'Connected'
            : terminal.status.value === 'connecting'
              ? 'Connecting…'
              : 'Disconnected'
        }}
      </span>
    </div>

    <!-- Paste button: long-press paste is unreliable on touch, so offer an explicit tap target -->
    <button
      type="button"
      class="absolute bottom-3 right-3 size-10 rounded-full bg-[var(--cmux-header)]/95 text-foreground border border-border shadow-lg flex items-center justify-center active:scale-95 transition-transform"
      style="bottom: calc(env(safe-area-inset-bottom, 0px) + 0.75rem)"
      aria-label="Paste from clipboard"
      @click="pasteFromClipboard"
    >
      <ClipboardPaste class="size-5" />
    </button>
  </div>
</template>
