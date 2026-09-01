// CodeMirror 6 bundle for the Android WebView editor — mirrors the web's
// CodeEditor.vue setup (minus LSP), with a curated language set so there are
// no dynamic imports (which can't load from a file:// WebView origin).
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, rectangularSelection, Decoration, gutter, GutterMarker } from "@codemirror/view"
import { EditorState, Compartment } from "@codemirror/state"
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands"
import { syntaxHighlighting, defaultHighlightStyle, foldGutter, bracketMatching, indentOnInput, StreamLanguage } from "@codemirror/language"
import { closeBrackets, closeBracketsKeymap, completionKeymap, autocompletion, startCompletion } from "@codemirror/autocomplete"
import { lintGutter, lintKeymap } from "@codemirror/lint"
import { searchKeymap, highlightSelectionMatches } from "@codemirror/search"
import { oneDark } from "@codemirror/theme-one-dark"

import { javascript } from "@codemirror/lang-javascript"
import { python } from "@codemirror/lang-python"
import { java } from "@codemirror/lang-java"
import { cpp } from "@codemirror/lang-cpp"
import { rust } from "@codemirror/lang-rust"
import { go } from "@codemirror/lang-go"
import { php } from "@codemirror/lang-php"
import { sql } from "@codemirror/lang-sql"
import { json } from "@codemirror/lang-json"
import { markdown } from "@codemirror/lang-markdown"
import { html } from "@codemirror/lang-html"
import { css } from "@codemirror/lang-css"
import { xml } from "@codemirror/lang-xml"
import { yaml } from "@codemirror/lang-yaml"
import { vue } from "@codemirror/lang-vue"
import { wast } from "@codemirror/lang-wast"
import { shell } from "@codemirror/legacy-modes/mode/shell"
import { kotlin, dart, csharp, scala, objectiveC, objectiveCpp, shader } from "@codemirror/legacy-modes/mode/clike"
import { ruby } from "@codemirror/legacy-modes/mode/ruby"
import { swift } from "@codemirror/legacy-modes/mode/swift"
import { groovy } from "@codemirror/legacy-modes/mode/groovy"
import { lua } from "@codemirror/legacy-modes/mode/lua"
import { perl } from "@codemirror/legacy-modes/mode/perl"
import { r } from "@codemirror/legacy-modes/mode/r"
import { julia } from "@codemirror/legacy-modes/mode/julia"
import { haskell } from "@codemirror/legacy-modes/mode/haskell"
import { erlang } from "@codemirror/legacy-modes/mode/erlang"
import { fSharp, oCaml } from "@codemirror/legacy-modes/mode/mllike"
import { clojure } from "@codemirror/legacy-modes/mode/clojure"
import { elm } from "@codemirror/legacy-modes/mode/elm"
import { crystal } from "@codemirror/legacy-modes/mode/crystal"
import { coffeeScript } from "@codemirror/legacy-modes/mode/coffeescript"
import { toml } from "@codemirror/legacy-modes/mode/toml"
import { properties } from "@codemirror/legacy-modes/mode/properties"
import { powerShell } from "@codemirror/legacy-modes/mode/powershell"
import { protobuf } from "@codemirror/legacy-modes/mode/protobuf"
import { stex } from "@codemirror/legacy-modes/mode/stex"
import { diff } from "@codemirror/legacy-modes/mode/diff"
import { pug } from "@codemirror/legacy-modes/mode/pug"
import { fortran } from "@codemirror/legacy-modes/mode/fortran"
import { pascal } from "@codemirror/legacy-modes/mode/pascal"
import { vb } from "@codemirror/legacy-modes/mode/vb"
import { vbScript } from "@codemirror/legacy-modes/mode/vbscript"
import { haxe } from "@codemirror/legacy-modes/mode/haxe"
import { cmake } from "@codemirror/legacy-modes/mode/cmake"
import { dockerFile } from "@codemirror/legacy-modes/mode/dockerfile"
import { nginx } from "@codemirror/legacy-modes/mode/nginx"

// LSP (language-server) support — additive and gated. Inactive unless a native
// host opens an LSP bridge via window.cmLspConnect (no bridge → plain editor).
import {
  LSPClient,
  LSPPlugin,
  serverDiagnostics,
  serverCompletionSource,
  hoverTooltips,
  signatureHelp,
  formatKeymap,
  renameKeymap,
  jumpToDefinitionKeymap,
  findReferencesKeymap,
} from "@codemirror/lsp-client"

function langFor(filename) {
  const base = String(filename || "").split(/[/\\]/).pop() || ""
  const lower = base.toLowerCase()
  const st = (mode) => StreamLanguage.define(mode)
  if (lower === "dockerfile" || lower.startsWith("dockerfile.")) return st(dockerFile)
  if (lower === "cmakelists.txt") return st(cmake)
  if (lower === "nginx.conf") return st(nginx)
  const ext = (lower.split(".").pop() || "")
  switch (ext) {
    case "js": case "mjs": case "cjs": return javascript()
    case "jsx": return javascript({ jsx: true })
    case "ts": case "mts": case "cts": return javascript({ typescript: true })
    case "tsx": return javascript({ jsx: true, typescript: true })
    case "py": case "pyi": return python()
    case "java": return java()
    case "c": case "h": case "cc": case "cpp": case "hpp": case "cxx": case "hxx": return cpp()
    case "cs": case "csx": return st(csharp)
    case "m": return st(objectiveC)
    case "mm": return st(objectiveCpp)
    case "rs": return rust()
    case "go": return go()
    case "php": return php()
    case "swift": return st(swift)
    case "kt": case "kts": return st(kotlin)
    case "dart": return st(dart)
    case "scala": case "sc": return st(scala)
    case "rb": return st(ruby)
    case "groovy": case "gradle": return st(groovy)
    case "lua": return st(lua)
    case "pl": case "pm": return st(perl)
    case "r": return st(r)
    case "jl": return st(julia)
    case "hs": return st(haskell)
    case "erl": case "hrl": return st(erlang)
    case "fs": case "fsx": case "fsi": return st(fSharp)
    case "ml": case "mli": return st(oCaml)
    case "clj": case "cljs": case "cljc": return st(clojure)
    case "elm": return st(elm)
    case "cr": return st(crystal)
    case "coffee": return st(coffeeScript)
    case "sql": return sql()
    case "json": case "jsonc": return json()
    case "md": case "markdown": case "mdx": return markdown()
    case "html": case "htm": return html()
    case "vue": return vue()
    case "css": case "scss": case "sass": case "less": return css()
    case "xml": case "svg": return xml()
    case "yaml": case "yml": return yaml()
    case "toml": return st(toml)
    case "ini": case "properties": return st(properties)
    case "sh": case "bash": case "zsh": return st(shell)
    case "ps1": case "psm1": case "psd1": return st(powerShell)
    case "proto": return st(protobuf)
    case "tex": case "latex": return st(stex)
    case "diff": case "patch": return st(diff)
    case "wat": case "wast": return wast()
    case "pug": case "jade": return st(pug)
    case "f": case "for": case "f90": case "f95": return st(fortran)
    case "pas": return st(pascal)
    case "vb": return st(vb)
    case "vbs": return st(vbScript)
    case "hx": return st(haxe)
    case "glsl": case "frag": case "vert": case "geom": return st(shader)
    case "cmake": return st(cmake)
    default: return []
  }
}

let view = null
const wrapC = new Compartment()
const fontC = new Compartment()
const langC = new Compartment()
const lspC = new Compartment()
const diffC = new Compartment()
const bridge = () => (typeof window !== "undefined" ? window.AndroidEditor : null)
const wrapExt = (on) => (on ? EditorView.lineWrapping : [])
const fontExt = (px) => EditorView.theme({ "&": { fontSize: (px || 13) + "px" } })

// ── Font zoom: Cmd/Ctrl +/−/0 + two-finger pinch. Mirrors the web app's
// src/web-app/src/lib/editor-font-zoom.ts — keep the two in sync. ─────────────
const FONT_MIN = 10, FONT_MAX = 24, FONT_DEFAULT = 13
let currentFontSize = FONT_DEFAULT
function clampFont(v) {
  if (typeof v !== "number" || Number.isNaN(v)) return FONT_DEFAULT
  return Math.min(FONT_MAX, Math.max(FONT_MIN, Math.round(v)))
}
const stepFont = (cur, delta) => clampFont(cur + delta)
const pinchFont = (baseFont, baseDist, curDist) =>
  baseDist > 0 ? clampFont(baseFont * (curDist / baseDist)) : clampFont(baseFont)

// Apply a size + remember it. NO badge, NO native notify — used by cmInit and the
// native->JS cmSetFontSize push, so a document push never flashes the badge and a
// native-driven change never loops back to native.
function reconfigureFont(px) {
  currentFontSize = clampFont(px)
  if (view) view.dispatch({ effects: fontC.reconfigure(fontExt(currentFontSize)) })
  return currentFontSize
}
// A user gesture (keyboard/pinch): apply, flash the badge, tell native to persist.
function setFontFromUser(px) {
  const next = reconfigureFont(px)
  showFontBadge(next)
  try { bridge() && bridge().onFontSize(next) } catch (e) {}
}

let badgeEl = null, badgeTimer = 0
function showFontBadge(px) {
  const parent = document.getElementById("editor")
  if (!parent) return
  if (!badgeEl) {
    badgeEl = document.createElement("div")
    badgeEl.style.cssText = "position:absolute;top:10px;right:12px;z-index:10;padding:3px 8px;" +
      "border-radius:6px;background:rgba(0,0,0,0.7);color:#fff;pointer-events:none;opacity:0;" +
      "transition:opacity 150ms ease;font:600 12px/1.2 system-ui,-apple-system,sans-serif;" +
      "font-variant-numeric:tabular-nums;"
    parent.appendChild(badgeEl)
  }
  badgeEl.textContent = px + "px"
  badgeEl.style.opacity = "1"
  clearTimeout(badgeTimer)
  badgeTimer = setTimeout(() => { if (badgeEl) badgeEl.style.opacity = "0" }, 900)
}

// Two-finger pinch. index.html sets user-scalable=no, so the WebView won't
// page-zoom and these multi-touch events are ours to interpret.
let pinchBaseDist = 0, pinchBaseFont = FONT_DEFAULT, pinchEl = null
const touchDist = (t) => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY)
function onPinchStart(e) {
  if (e.touches.length === 2) { pinchBaseDist = touchDist(e.touches); pinchBaseFont = currentFontSize }
}
function onPinchMove(e) {
  if (e.touches.length === 2 && pinchBaseDist > 0) {
    e.preventDefault()
    setFontFromUser(pinchFont(pinchBaseFont, pinchBaseDist, touchDist(e.touches)))
  }
}
function onPinchEnd(e) { if (e.touches.length < 2) pinchBaseDist = 0 }
function attachPinch(el) {
  detachPinch()
  pinchEl = el
  el.addEventListener("touchstart", onPinchStart, { passive: true })
  el.addEventListener("touchmove", onPinchMove, { passive: false })
  el.addEventListener("touchend", onPinchEnd, { passive: true })
  el.addEventListener("touchcancel", onPinchEnd, { passive: true })
}
function detachPinch() {
  if (!pinchEl) return
  pinchEl.removeEventListener("touchstart", onPinchStart)
  pinchEl.removeEventListener("touchmove", onPinchMove)
  pinchEl.removeEventListener("touchend", onPinchEnd)
  pinchEl.removeEventListener("touchcancel", onPinchEnd)
  pinchEl = null
}

window.cmInit = function (content, filename, lineWrap, fontSize) {
  const parent = document.getElementById("editor")
  if (!parent) return
  if (view) { view.destroy(); view = null }
  detachPinch()
  currentFontSize = clampFont(fontSize)
  const state = EditorState.create({
    doc: content || "",
    extensions: [
      lineNumbers(), highlightActiveLineGutter(), highlightActiveLine(),
      history(), foldGutter(), drawSelection(), rectangularSelection(),
      bracketMatching(), closeBrackets(), lintGutter(), indentOnInput(),
      highlightSelectionMatches(),
      wrapC.of(wrapExt(!!lineWrap)),
      fontC.of(fontExt(currentFontSize)),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      oneDark,
      langC.of(langFor(filename)),
      lspC.of([]),
      diffC.of([]),
      keymap.of([
        ...closeBracketsKeymap, ...completionKeymap, ...lintKeymap,
        ...defaultKeymap, ...searchKeymap, ...historyKeymap, indentWithTab,
        { key: "Mod-s", run: () => { try { bridge() && bridge().onSave() } catch (e) {} return true } },
        { key: "Mod-=", run: () => { setFontFromUser(stepFont(currentFontSize, 1)); return true } },
        { key: "Mod-+", run: () => { setFontFromUser(stepFont(currentFontSize, 1)); return true } },
        { key: "Shift-Mod-=", run: () => { setFontFromUser(stepFont(currentFontSize, 1)); return true } },
        { key: "Mod--", run: () => { setFontFromUser(stepFont(currentFontSize, -1)); return true } },
        { key: "Mod-0", run: () => { setFontFromUser(FONT_DEFAULT); return true } },
      ]),
      EditorView.updateListener.of((u) => {
        if (u.docChanged) { try { bridge() && bridge().onChange(u.state.doc.toString()) } catch (e) {} }
      }),
      EditorView.theme({ "&": { height: "100%" }, ".cm-scroller": { fontFamily: "monospace" } }),
    ],
  })
  view = new EditorView({ state, parent })
  attachPinch(parent)
  try { bridge() && bridge().onReady() } catch (e) {}
}
window.cmSetContent = function (content) {
  if (!view) return
  const cur = view.state.doc.toString()
  if (cur !== (content || "")) view.dispatch({ changes: { from: 0, to: view.state.doc.length, insert: content || "" } })
}
window.cmGetScrollTop = function () {
  if (!view) return 0
  return view.scrollDOM.scrollTop
}
window.cmSetScrollTop = function (px) {
  if (!view) return
  view.scrollDOM.scrollTop = px || 0
}
// 1-indexed line; endLine<=0 or absent → caret only. Reveals centered.
window.cmRevealLine = function (line, endLine) {
  if (!view) return
  const doc = view.state.doc
  const ln = Math.max(1, Math.min(line || 1, doc.lines))
  const from = doc.line(ln).from
  const sel = (endLine && endLine > ln)
    ? { anchor: from, head: doc.line(Math.min(endLine, doc.lines)).to }
    : { anchor: from }
  view.dispatch({ selection: sel, effects: EditorView.scrollIntoView(from, { y: "center" }) })
  view.focus()
}
window.cmGetContent = function () { return view ? view.state.doc.toString() : "" }
window.cmSetLineWrap = function (on) { if (view) view.dispatch({ effects: wrapC.reconfigure(wrapExt(!!on)) }) }
window.cmSetFontSize = function (px) { reconfigureFont(px) }
window.cmSetLanguage = function (filename) { if (view) view.dispatch({ effects: langC.reconfigure(langFor(filename)) }) }

// ---------------------------------------------------------------------------
// Walkthrough read-only diff region. Native passes the FULL file plus 1-indexed
// original-file ranges; this renderer initially clips to 20 context lines and
// expands in 20-line increments without a native/file round trip.
// ---------------------------------------------------------------------------

let diffRegion = null
let diffContextBefore = 20, diffContextAfter = 20
let diffUpButton = null, diffDownButton = null
let lastDiffPageAt = 0
let diffDisplayMeta = []

class DiffGutterMarker extends GutterMarker {
  constructor(mark, className) { super(); this.mark = mark; this.className = className }
  toDOM() {
    const el = document.createElement("span")
    el.textContent = this.mark
    el.className = this.className
    return el
  }
}
const addMarker = new DiffGutterMarker("+", "cm-diff-gutter-add")
const deleteMarker = new DiffGutterMarker("−", "cm-diff-gutter-delete")
const changeMarker = new DiffGutterMarker("±", "cm-diff-gutter-change")

function normalizedRanges(spec) {
  const count = String(spec.content || "").split("\n").length
  return (Array.isArray(spec.ranges) ? spec.ranges : []).map((r) => ({
    startLine: Math.max(1, Math.min(count, Number(r.startLine) || 1)),
    endLine: Math.max(1, Math.min(count, Number(r.endLine) || Number(r.startLine) || 1)),
    kind: r.kind === "add" || r.kind === "delete" || r.kind === "context" ? r.kind : "change",
    deletedLines: Array.isArray(r.deletedLines) ? r.deletedLines.map((line) => String(line)) : [],
  })).map((r) => ({ ...r, endLine: Math.max(r.startLine, r.endLine) }))
}

function diffKindForOriginalLine(line) {
  if (!diffRegion) return null
  for (const r of diffRegion.ranges) {
    if (r.kind !== "delete" && r.kind !== "context" && line >= r.startLine && line <= r.endLine) return r.kind
  }
  return null
}

function setExpandButton(which, visible, action) {
  const parent = document.getElementById("editor")
  if (!parent) return null
  let button = which === "up" ? diffUpButton : diffDownButton
  if (!visible) { if (button) button.style.display = "none"; return button }
  if (!button) {
    button = document.createElement("button")
    button.type = "button"
    button.className = "cm-diff-expand cm-diff-expand-" + which
    button.addEventListener("click", action)
    parent.appendChild(button)
    if (which === "up") diffUpButton = button; else diffDownButton = button
  }
  button.textContent = which === "up" ? "expand ↑ 20" : "expand ↓ 20"
  button.style.display = "block"
  return button
}

function renderDiffRegion() {
  if (!view || !diffRegion) return
  // Re-anchors/revisions replace the slice in-place. Capture before dispatch and restore after
  // layout so a live walkthrough_updated frame never yanks the reader back to the top.
  const preservedScrollTop = view.scrollDOM.scrollTop
  const allLines = String(diffRegion.content || "").split("\n")
  const firstChanged = diffRegion.ranges.length ? Math.min(...diffRegion.ranges.map((r) => r.startLine)) : 1
  const lastChanged = diffRegion.ranges.length ? Math.max(...diffRegion.ranges.map((r) => r.endLine)) : Math.min(1, allLines.length)
  const sliceStart = Math.max(1, firstChanged - diffContextBefore)
  const sliceEnd = Math.min(allLines.length, lastChanged + diffContextAfter)
  diffRegion.sliceStart = sliceStart
  const displayLines = []
  diffDisplayMeta = []
  for (let original = sliceStart; original <= sliceEnd; original++) {
    for (const range of diffRegion.ranges) {
      if (range.startLine !== original) continue
      for (const deleted of range.deletedLines) {
        displayLines.push(deleted)
        diffDisplayMeta.push({ originalLine: original, kind: "delete" })
      }
    }
    displayLines.push(allLines[original - 1])
    diffDisplayMeta.push({ originalLine: original, kind: diffKindForOriginalLine(original) })
  }
  const slice = displayLines.join("\n")
  view.dispatch({
    changes: { from: 0, to: view.state.doc.length, insert: slice },
    effects: [
      langC.reconfigure(langFor(diffRegion.language || diffRegion.path)),
      diffC.reconfigure([
        EditorState.readOnly.of(true),
        EditorView.editable.of(false),
        EditorView.decorations.compute([], (state) => {
          const decorations = []
          for (let n = 1; n <= state.doc.lines; n++) {
            const kind = diffDisplayMeta[n - 1] && diffDisplayMeta[n - 1].kind
            if (kind) decorations.push(Decoration.line({ class: "cm-diff-line cm-diff-" + kind }).range(state.doc.line(n).from))
          }
          return Decoration.set(decorations, true)
        }),
        gutter({
          class: "cm-diff-gutter",
          lineMarker(v, line) {
            const displayLine = v.state.doc.lineAt(line.from).number
            const kind = diffDisplayMeta[displayLine - 1] && diffDisplayMeta[displayLine - 1].kind
            return kind === "add" ? addMarker : kind === "delete" ? deleteMarker : kind === "change" ? changeMarker : null
          },
        }),
        EditorView.domEventHandlers({
          mousedown(event, v) {
            const pos = v.posAtCoords({ x: event.clientX, y: event.clientY })
            if (pos == null) return false
            const displayLine = v.state.doc.lineAt(pos).number
            const original = (diffDisplayMeta[displayLine - 1] || {}).originalLine || sliceStart
            try { bridge() && bridge().onDiffLineClick(original) } catch (e) {}
            return false
          },
          wheel(event) {
            if (Math.abs(event.deltaX) < 60 || Math.abs(event.deltaX) <= Math.abs(event.deltaY)) return false
            const now = Date.now()
            if (now - lastDiffPageAt < 450) return true
            lastDiffPageAt = now
            try { bridge() && bridge().onDiffPage(event.deltaX > 0 ? "next" : "previous") } catch (e) {}
            event.preventDefault()
            return true
          },
        }),
        EditorView.theme({
          "&": { height: "100%" },
          ".cm-content": { paddingTop: "30px", paddingBottom: "30px" },
          ".cm-diff-line.cm-diff-add": { backgroundColor: "rgba(46, 160, 67, .20)" },
          ".cm-diff-line.cm-diff-change": { backgroundColor: "rgba(210, 153, 34, .18)" },
          ".cm-diff-line.cm-diff-delete": { backgroundColor: "rgba(248, 81, 73, .20)" },
          ".cm-diff-gutter": { width: "20px", textAlign: "center", fontWeight: "700" },
          ".cm-diff-gutter-add": { color: "#56d364" },
          ".cm-diff-gutter-change": { color: "#e3b341" },
          ".cm-diff-gutter-delete": { color: "#ff7b72" },
        }),
      ]),
    ],
  })
  requestAnimationFrame(() => { if (view) view.scrollDOM.scrollTop = preservedScrollTop })
  setExpandButton("up", sliceStart > 1, () => {
    diffContextBefore += 20; renderDiffRegion()
    try { bridge() && bridge().onDiffExpand("up") } catch (e) {}
  })
  setExpandButton("down", sliceEnd < allLines.length, () => {
    diffContextAfter += 20; renderDiffRegion()
    try { bridge() && bridge().onDiffExpand("down") } catch (e) {}
  })
}

window.cmShowDiffRegion = function (spec) {
  if (!view || !spec) return
  diffRegion = { ...spec, ranges: normalizedRanges(spec) }
  diffContextBefore = 20
  diffContextAfter = 20
  renderDiffRegion()
}

// ---------------------------------------------------------------------------
// LSP (language-server) support — ported 1:1 from the web app's
// lsp-editor-extensions.ts (sans the debug logging + symbol-navigation panel).
// All of this is dormant until a native host calls window.cmLspConnect; on a
// plain Android WebView (no window.webkit.messageHandlers.lsp) it never runs.
// ---------------------------------------------------------------------------

// Characters that should open member/import completion.
const LSP_COMPLETION_TRIGGER_CHARS = new Set([".", ":", '"', "'", "`", "<", "/", "@", "#"])

// Find the live LSP plugin on a view by instance, not facet id — bundlers can
// dedupe poorly so LSPPlugin.get() returns null while the plugin is running,
// which breaks sync + completion. (Mirrors the web's getLSPPlugin.)
function getLSPPlugin(v) {
  const insts = (v && v.plugins) || []
  for (const p of insts) {
    if (p.value instanceof LSPPlugin) return p.value
  }
  return null
}

// Patch once so @codemirror/lsp-client internals (sync, completion, hover)
// resolve the plugin even when the facet lookup misses.
let lspPluginPatched = false
function patchLSPPluginGet() {
  if (lspPluginPatched) return
  lspPluginPatched = true
  const fallback = LSPPlugin.get.bind(LSPPlugin)
  LSPPlugin.get = (v) => getLSPPlugin(v) || fallback(v)
}

function lspAutocompletion() {
  return autocompletion({
    override: [serverCompletionSource],
    activateOnTyping: true,
    activateOnTypingDelay: 50,
    interactionDelay: 0,
  })
}

// Force completion after `.` etc. Sync first, then a brief delay so the server
// sees the latest didChange before textDocument/completion.
function completionOnTriggerChars() {
  return EditorView.updateListener.of((update) => {
    if (!update.docChanged) return
    const pos = update.state.selection.main.head
    const ch = update.state.sliceDoc(pos - 1, pos)
    if (!LSP_COMPLETION_TRIGGER_CHARS.has(ch)) return
    const plugin = getLSPPlugin(update.view)
    if (!plugin) return
    window.setTimeout(() => {
      if (getLSPPlugin(update.view) !== plugin) return
      plugin.client.sync()
      startCompletion(update.view)
    }, 80)
  })
}

// Bundled LSP editor extensions. Keep `client.plugin()` as a nested array —
// same shape as upstream tests; spreading a pre-flattened list can prevent the
// ViewPlugin facet from registering.
function lspEditorExtensions(client, fileUri, languageId) {
  return [
    client.plugin(fileUri, languageId),
    lspAutocompletion(),
    hoverTooltips(),
    signatureHelp(),
    completionOnTriggerChars(),
    keymap.of([
      ...formatKeymap,
      ...renameKeymap,
      ...jumpToDefinitionKeymap,
      ...findReferencesKeymap,
    ]),
  ]
}

// serverId -> { client, handlers } for every connected language server.
const lspClients = new Map()

// Open an LSP connection for the current editor. The native host pumps inbound
// JSON-RPC back in via cmLspMessage. No-op (stays a plain editor) when there is
// no native LSP bridge — e.g. on Android.
window.cmLspConnect = async function (serverId, rootUri, fileUri, languageId) {
  if (!view) return
  const lspBridge = window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.lsp
  if (!lspBridge) return // no native bridge → stay plain
  patchLSPPluginGet()
  if (lspClients.has(serverId)) return // already connected for this server

  const handlers = new Set()
  const transport = {
    send: (m) => lspBridge.postMessage(JSON.stringify({ serverId, message: m })),
    subscribe: (h) => handlers.add(h),
    unsubscribe: (h) => handlers.delete(h),
  }
  const client = new LSPClient({
    rootUri,
    extensions: [serverDiagnostics()],
    timeout: 15000,
  })
  lspClients.set(serverId, { client, handlers })

  client.connect(transport)
  try {
    await client.initializing
  } catch (e) {
    lspClients.delete(serverId)
    try { client.disconnect() } catch (e2) {}
    return
  }
  if (!view) return
  view.dispatch({ effects: lspC.reconfigure(lspEditorExtensions(client, fileUri, languageId)) })
}

// Deliver an inbound JSON-RPC message (string) from the native host to the
// LSPClient for the given server.
window.cmLspMessage = function (serverId, message) {
  const entry = lspClients.get(serverId)
  if (!entry || typeof message !== "string") return
  for (const h of entry.handlers) h(message)
}

// Tear down all LSP connections and revert the editor to plain mode.
window.cmLspDisconnect = function () {
  for (const entry of lspClients.values()) {
    try { entry.client.disconnect() } catch (e) {}
  }
  lspClients.clear()
  if (view) view.dispatch({ effects: lspC.reconfigure([]) })
}
