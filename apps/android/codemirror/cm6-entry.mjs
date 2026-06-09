// CodeMirror 6 bundle for the Android WebView editor — mirrors the web's
// CodeEditor.vue setup (minus LSP), with a curated language set so there are
// no dynamic imports (which can't load from a file:// WebView origin).
import { EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, rectangularSelection } from "@codemirror/view"
import { EditorState, Compartment } from "@codemirror/state"
import { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands"
import { syntaxHighlighting, defaultHighlightStyle, foldGutter, bracketMatching, indentOnInput, StreamLanguage } from "@codemirror/language"
import { closeBrackets, closeBracketsKeymap, completionKeymap } from "@codemirror/autocomplete"
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
import { shell } from "@codemirror/legacy-modes/mode/shell"
import { kotlin } from "@codemirror/legacy-modes/mode/clike"

function langFor(filename) {
  const ext = (String(filename || "").split(".").pop() || "").toLowerCase()
  switch (ext) {
    case "js": case "mjs": case "cjs": return javascript()
    case "jsx": return javascript({ jsx: true })
    case "ts": return javascript({ typescript: true })
    case "tsx": return javascript({ jsx: true, typescript: true })
    case "py": return python()
    case "java": return java()
    case "c": case "h": case "cc": case "cpp": case "hpp": case "cxx": case "hxx": return cpp()
    case "rs": return rust()
    case "go": return go()
    case "php": return php()
    case "sql": return sql()
    case "json": return json()
    case "md": case "markdown": return markdown()
    case "html": case "htm": case "vue": return html()
    case "css": case "scss": case "sass": case "less": return css()
    case "xml": case "svg": return xml()
    case "yaml": case "yml": return yaml()
    case "kt": case "kts": return StreamLanguage.define(kotlin)
    case "sh": case "bash": case "zsh": return StreamLanguage.define(shell)
    default: return []
  }
}

let view = null
const wrapC = new Compartment()
const fontC = new Compartment()
const langC = new Compartment()
const bridge = () => (typeof window !== "undefined" ? window.AndroidEditor : null)
const wrapExt = (on) => (on ? EditorView.lineWrapping : [])
const fontExt = (px) => EditorView.theme({ "&": { fontSize: (px || 13) + "px" } })

window.cmInit = function (content, filename, lineWrap, fontSize) {
  const parent = document.getElementById("editor")
  if (!parent) return
  if (view) { view.destroy(); view = null }
  const state = EditorState.create({
    doc: content || "",
    extensions: [
      lineNumbers(), highlightActiveLineGutter(), highlightActiveLine(),
      history(), foldGutter(), drawSelection(), rectangularSelection(),
      bracketMatching(), closeBrackets(), lintGutter(), indentOnInput(),
      highlightSelectionMatches(),
      wrapC.of(wrapExt(!!lineWrap)),
      fontC.of(fontExt(fontSize)),
      syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
      oneDark,
      langC.of(langFor(filename)),
      keymap.of([
        ...closeBracketsKeymap, ...completionKeymap, ...lintKeymap,
        ...defaultKeymap, ...searchKeymap, ...historyKeymap, indentWithTab,
        { key: "Mod-s", run: () => { try { bridge() && bridge().onSave() } catch (e) {} return true } },
      ]),
      EditorView.updateListener.of((u) => {
        if (u.docChanged) { try { bridge() && bridge().onChange(u.state.doc.toString()) } catch (e) {} }
      }),
      EditorView.theme({ "&": { height: "100%" }, ".cm-scroller": { fontFamily: "monospace" } }),
    ],
  })
  view = new EditorView({ state, parent })
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
window.cmGetContent = function () { return view ? view.state.doc.toString() : "" }
window.cmSetLineWrap = function (on) { if (view) view.dispatch({ effects: wrapC.reconfigure(wrapExt(!!on)) }) }
window.cmSetFontSize = function (px) { if (view) view.dispatch({ effects: fontC.reconfigure(fontExt(px)) }) }
window.cmSetLanguage = function (filename) { if (view) view.dispatch({ effects: langC.reconfigure(langFor(filename)) }) }
