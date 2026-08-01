<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, nextTick } from "vue"
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, rectangularSelection } from "@codemirror/view"
import { EditorState, Compartment } from "@codemirror/state"
import { LSPPlugin } from "@codemirror/lsp-client"
import { getLSPPlugin, patchLSPPluginGet } from "@/lib/lsp-plugin"

patchLSPPluginGet()
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands"
import { syntaxHighlighting, defaultHighlightStyle, foldGutter, bracketMatching, indentOnInput, LanguageDescription } from "@codemirror/language"
import { closeBrackets, closeBracketsKeymap, completionKeymap } from "@codemirror/autocomplete"
import { editorCompletionKeymap } from "@/lib/editor-completion-keymap"
import { lintGutter, lintKeymap } from "@codemirror/lint"
import { searchKeymap, highlightSelectionMatches } from "@codemirror/search"
import { oneDark } from "@codemirror/theme-one-dark"
import { languages } from "@codemirror/language-data"
import type { Extension } from "@codemirror/state"

import { useEditorSettings } from "@/stores/editorSettings"
import { FONT_SIZE, stepFont, pinchFont } from "@/lib/editor-font-zoom"
import { useWS } from "@/api/ws"
import { lspDebug } from "@/lib/lsp-debug"
import { lspPositionToOffset } from "@/lib/lsp-symbol-navigation"

defineOptions({ inheritAttrs: false })

const props = defineProps<{
  content: string
  path: string
  active: boolean
  // LSP extensions are applied at mount (EditorPane remounts this component when LSP becomes ready).
  lspExtension?: Extension | Extension[]
  revealPosition?: { path: string; line: number; character: number; endLine?: number; nonce: number } | null
}>()

const emit = defineEmits<{
  update: [content: string]
  save: []
}>()

const containerRef = ref<HTMLElement | null>(null)
let view: EditorView | null = null

const settings = useEditorSettings()
const wrapCompartment = new Compartment()
const fontCompartment = new Compartment()
const langCompartment = new Compartment()

let langToken = 0

// ── Font zoom: Cmd/Ctrl +/−/0 and two-finger pinch (also trackpad ctrl+wheel) ──
// Applies through the shared editorSettings store (clamps + persists); the
// existing watch(fontSize) reconfigures the theme. A small badge flashes the size.
const zoomBadge = ref("")
const zoomBadgeVisible = ref(false)
let zoomBadgeTimer: ReturnType<typeof setTimeout> | undefined
let pinchBaseDist = 0
let pinchBaseFont: number = FONT_SIZE.default
let wheelZoomAccum = 0
const WHEEL_ZOOM_STEP = 30

function flashFont(px: number) {
  settings.setFontSize(px)
  zoomBadge.value = `${settings.state.fontSize}px`
  zoomBadgeVisible.value = true
  clearTimeout(zoomBadgeTimer)
  zoomBadgeTimer = setTimeout(() => { zoomBadgeVisible.value = false }, 900)
}
function bumpFont(delta: number) { flashFont(stepFont(settings.state.fontSize, delta)) }
function resetFont() { flashFont(FONT_SIZE.default) }

function touchDist(touches: TouchList): number {
  const a = touches[0], b = touches[1]
  return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY)
}
function onEditorTouchStart(e: TouchEvent) {
  if (e.touches.length === 2) {
    pinchBaseDist = touchDist(e.touches)
    pinchBaseFont = settings.state.fontSize
  }
}
function onEditorTouchMove(e: TouchEvent) {
  if (e.touches.length === 2 && pinchBaseDist > 0) {
    e.preventDefault() // don't scroll/select or let the page zoom
    flashFont(pinchFont(pinchBaseFont, pinchBaseDist, touchDist(e.touches)))
  }
}
function onEditorTouchEnd(e: TouchEvent) {
  if (e.touches.length < 2) pinchBaseDist = 0
}
// Trackpad pinch arrives as ctrl+wheel (the browser's pinch-zoom convention).
function onEditorWheel(e: WheelEvent) {
  if (!e.ctrlKey) return
  e.preventDefault()
  wheelZoomAccum += e.deltaY
  while (wheelZoomAccum <= -WHEEL_ZOOM_STEP) { bumpFont(1); wheelZoomAccum += WHEEL_ZOOM_STEP }
  while (wheelZoomAccum >= WHEEL_ZOOM_STEP) { bumpFont(-1); wheelZoomAccum -= WHEEL_ZOOM_STEP }
}
function attachZoomGestures(el: HTMLElement) {
  el.addEventListener("touchstart", onEditorTouchStart, { passive: true })
  el.addEventListener("touchmove", onEditorTouchMove, { passive: false })
  el.addEventListener("touchend", onEditorTouchEnd, { passive: true })
  el.addEventListener("touchcancel", onEditorTouchEnd, { passive: true })
  el.addEventListener("wheel", onEditorWheel, { passive: false })
}
function detachZoomGestures(el: HTMLElement) {
  el.removeEventListener("touchstart", onEditorTouchStart)
  el.removeEventListener("touchmove", onEditorTouchMove)
  el.removeEventListener("touchend", onEditorTouchEnd)
  el.removeEventListener("touchcancel", onEditorTouchEnd)
  el.removeEventListener("wheel", onEditorWheel)
}

function report(kind: string, err: unknown) {
  const e = err as Error | undefined
  try { useWS().send({ type: "client_error", kind, message: String(e?.message ?? err), stack: e?.stack }) } catch { /* ws not ready */ }
}

function wrapExtension() {
  return settings.state.lineWrap ? EditorView.lineWrapping : []
}

function fontExtension() {
  return EditorView.theme({ "&": { fontSize: `${settings.state.fontSize}px` } })
}

async function applyLanguage(path: string) {
  if (!view) return
  const token = ++langToken
  const filename = path.split("/").pop() ?? path
  const desc = LanguageDescription.matchFilename(languages, filename)
  if (!desc) {
    view.dispatch({ effects: langCompartment.reconfigure([]) })
    return
  }
  try {
    const support = await desc.load()
    if (token !== langToken || !view) return
    view.dispatch({ effects: langCompartment.reconfigure(support) })
  } catch (err) {
    if (token === langToken && view) {
      view.dispatch({ effects: langCompartment.reconfigure([]) })
    }
    console.warn(`Failed to load syntax highlighting for ${filename}`, err)
    report(`grammar-load:${filename}`, err)
  }
}

function lspExtensions(): Extension | Extension[] {
  const ext = props.lspExtension
  if (!ext) return []
  return ext
}

function revealRequestedPosition() {
  if (!view || !props.revealPosition || props.revealPosition.path !== props.path) return
  const offset = lspPositionToOffset(view.state.doc, props.revealPosition)
  view.focus()
  view.dispatch({
    selection: { anchor: offset },
    scrollIntoView: true,
    userEvent: "select.symbol-navigation",
  })
}

function createState(content: string): EditorState {
  const lsp = lspExtensions()
  return EditorState.create({
    doc: content,
    extensions: [
      lineNumbers(),
      highlightActiveLineGutter(),
      highlightActiveLine(),
      history(),
      foldGutter(),
      drawSelection(),
      rectangularSelection(),
      bracketMatching(),
      closeBrackets(),
      lintGutter(),
      indentOnInput(),
      highlightSelectionMatches(),
      wrapCompartment.of(wrapExtension()),
      fontCompartment.of(fontExtension()),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      oneDark,
      langCompartment.of([]),
      lspExtCount() ? lspExtensions() : [],
      editorCompletionKeymap,
      keymap.of([
        ...closeBracketsKeymap,
        ...completionKeymap,
        ...lintKeymap,
        ...defaultKeymap,
        ...searchKeymap,
        ...historyKeymap,
        indentWithTab,
        { key: "Mod-s", run: () => { emit("save"); return true } },
        { key: "Mod-=", run: () => { bumpFont(1); return true } },
        { key: "Mod-+", run: () => { bumpFont(1); return true } },
        { key: "Shift-Mod-=", run: () => { bumpFont(1); return true } },
        { key: "Mod--", run: () => { bumpFont(-1); return true } },
        { key: "Mod-0", run: () => { resetFont(); return true } },
      ]),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          emit("update", update.state.doc.toString())
        }
        if (!pluginCheckDone && lspExtCount() > 0) {
          const plugin = getLSPPlugin(update.view)
          if (plugin) {
            pluginCheckDone = true
            listPluginState("check.firstUpdate")
          }
        }
      }),
      EditorView.theme({
        "&": { height: "100%", background: "var(--cmux-code)" },
        ".cm-editor": { background: "var(--cmux-code)" },
        ".cm-content": { background: "var(--cmux-code)" },
        ".cm-gutters": { background: "var(--cmux-session-list)", borderRightColor: "var(--border)" },
        ".cm-scroller": { fontFamily: '"JetBrains Mono", "Fira Code", "Cascadia Code", monospace' },
      }),
    ],
  })
}

function lspExtCount(): number {
  const ext = lspExtensions()
  return Array.isArray(ext) ? ext.length : ext ? 1 : 0
}

function listPluginState(phase: string) {
  if (!view) return
  const plugin = getLSPPlugin(view)
  const facetGet = LSPPlugin.get(view)
  const internal = view as unknown as {
    plugins?: Array<{ plugin?: { id: number }; value: unknown }>
  }
  const instances = internal.plugins ?? []
  const lspInst = instances.find((p) => p.value instanceof LSPPlugin)
  lspDebug(`codeEditor.${phase}`, {
    path: props.path,
    lspExtCount: lspExtCount(),
    hasPlugin: !!plugin,
    facetGetWorks: !!facetGet,
    uri: plugin?.uri,
    pluginInstances: instances.length,
    lspInstanceAlive: !!lspInst,
    lspInstanceCrashed: !!lspInst && !lspInst.value,
  })
}

let pluginCheckDone = false

function schedulePluginChecks() {
  pluginCheckDone = false
  const check = (phase: string) => {
    if (!view || pluginCheckDone) return
    const plugin = getLSPPlugin(view)
  const facetGet = LSPPlugin.get(view)
    if (plugin) pluginCheckDone = true
    listPluginState(phase)
  }
  nextTick(() => check("check.nextTick"))
  requestAnimationFrame(() => {
    check("check.raf1")
    requestAnimationFrame(() => check("check.raf2"))
  })
  window.setTimeout(() => check("check.100ms"), 100)
}

onMounted(() => {
  if (!containerRef.value) return
  try {
    view = new EditorView({
      state: createState(props.content),
      parent: containerRef.value,
    })
    attachZoomGestures(containerRef.value)
    applyLanguage(props.path)
    revealRequestedPosition()
    listPluginState("mounted")
    schedulePluginChecks()
  } catch (err) {
    console.error("editor init failed", err)
    report("editor-init", err)
  }
})

onUnmounted(() => {
  if (containerRef.value) detachZoomGestures(containerRef.value)
  clearTimeout(zoomBadgeTimer)
  view?.destroy()
  view = null
})

watch(() => props.content, (newContent) => {
  if (!view) return
  const current = view.state.doc.toString()
  if (current !== newContent) {
    view.dispatch({
      changes: { from: 0, to: view.state.doc.length, insert: newContent },
    })
  }
})

watch(() => props.path, (path) => {
  applyLanguage(path)
})

watch(() => props.revealPosition?.nonce, () => {
  nextTick(revealRequestedPosition)
})

watch(() => props.active, (active) => {
  if (active) nextTick(() => view?.requestMeasure())
})

watch(() => settings.state.lineWrap, () => {
  view?.dispatch({ effects: wrapCompartment.reconfigure(wrapExtension()) })
})

watch(() => settings.state.fontSize, () => {
  view?.dispatch({ effects: fontCompartment.reconfigure(fontExtension()) })
})
</script>

<template>
  <div class="relative h-full w-full" v-bind="$attrs">
    <div ref="containerRef" class="h-full w-full overflow-hidden bg-[var(--cmux-code)] text-foreground" />
    <Transition name="cm-font-badge">
      <div
        v-if="zoomBadgeVisible"
        class="pointer-events-none absolute right-3 top-3 z-10 rounded-md bg-black/70 px-2 py-1 text-xs font-medium tabular-nums text-white shadow-sm"
      >{{ zoomBadge }}</div>
    </Transition>
  </div>
</template>

<style scoped>
.cm-font-badge-enter-active,
.cm-font-badge-leave-active { transition: opacity 150ms ease; }
.cm-font-badge-enter-from,
.cm-font-badge-leave-to { opacity: 0; }
</style>
