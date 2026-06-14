<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, onActivated, nextTick, toRef } from "vue"
import { Terminal } from "@xterm/xterm"
import { FitAddon } from "@xterm/addon-fit"
import { WebLinksAddon } from "@xterm/addon-web-links"
import { ClipboardPaste } from "lucide-vue-next"
import "@xterm/xterm/css/xterm.css"
import { useTerminal } from "@/composables/useTerminal"

const props = defineProps<{
  sessionName: string
  terminalId: string
  active: boolean
}>()

const emit = defineEmits<{ exit: [] }>()

const containerRef = ref<HTMLElement | null>(null)

let term: Terminal | null = null
let fitAddon: FitAddon | null = null
let ro: ResizeObserver | null = null
let lastSentSize: { cols: number; rows: number } | null = null

const terminal = useTerminal(toRef(() => props.sessionName), toRef(() => props.terminalId))

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
  fit()

  // Pipe terminal input → WS
  term.onData((data) => {
    const encoder = new TextEncoder()
    terminal.sendInput(encoder.encode(data))
  })

  // Handle paste via Ctrl+V / mobile paste menu
  term.attachCustomKeyEventHandler((e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "v" && e.type === "keydown") {
      navigator.clipboard.readText().then((text) => {
        if (text) term?.paste(text)
      }).catch(() => {})
      return false
    }
    return true
  })

  containerRef.value.addEventListener("paste", (e: ClipboardEvent) => {
    const text = e.clipboardData?.getData("text")
    if (text) {
      term?.paste(text)
      e.preventDefault()
    }
  })

  // Pipe WS binary → terminal
  terminal.onData((data: Uint8Array) => {
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

  terminal.connect()
})

onUnmounted(() => {
  ro?.disconnect()
  terminal.disconnect()
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
