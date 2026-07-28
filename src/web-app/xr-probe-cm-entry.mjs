// Temporary diagnostic bundle for /xr-probe.html (xr-3): real CodeMirror 6
// instances built from the SAME installed packages as the live app, so the
// probe measures exactly what the app renders. Safe to delete after the
// XREAL/DeX font investigation.
import { EditorView, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, rectangularSelection } from "@codemirror/view"
import { EditorState } from "@codemirror/state"
import { history } from "@codemirror/commands"
import { foldGutter, bracketMatching, indentOnInput, syntaxHighlighting, defaultHighlightStyle } from "@codemirror/language"
import { closeBrackets } from "@codemirror/autocomplete"
import { lintGutter } from "@codemirror/lint"
import { highlightSelectionMatches } from "@codemirror/search"
import { oneDark } from "@codemirror/theme-one-dark"

const DOC = [
  "node_modules",
  "bun.lock",
  "*.log",
  "dist/",
  ".env",
  ".DS_Store",
  "*.pid",
  ".worktrees/",
  "",
  "# Web PWA build output — sample doc for glyph measurement",
].join("\n")

const APP_FONT = '"JetBrains Mono", "Fira Code", "Cascadia Code", monospace'
const PROBE_FONT = "ui-monospace, Menlo, Consolas, monospace"

function baseExtensions(font, wrap) {
  return [
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
    wrap ? EditorView.lineWrapping : [],
    EditorView.theme({ "&": { fontSize: "13px" } }),
    syntaxHighlighting(defaultHighlightStyle, { fallback: true }),
    oneDark,
    EditorView.theme({
      "&": { height: "100%", background: "#1f2430" },
      ".cm-scroller": { fontFamily: font },
    }),
  ]
}

const VARIANTS = {
  // 1: faithful replica of the app's editor (minus LSP/lang/keymaps)
  replica: () => baseExtensions(APP_FONT, true),
  // 2: replica but with the font stack the static probe used (which measured clean)
  probefont: () => baseExtensions(PROBE_FONT, true),
  // 3: replica with line wrapping OFF
  nowrap: () => baseExtensions(APP_FONT, false),
  // 4: bare minimum CodeMirror — just a 13px theme
  bare: () => [EditorView.theme({ "&": { fontSize: "13px" } })],
}

window.__xrCM = {
  create(parent, variant) {
    const mkExt = VARIANTS[variant] || VARIANTS.bare
    return new EditorView({
      state: EditorState.create({ doc: DOC, extensions: mkExt() }),
      parent,
    })
  },
  measure(parent) {
    const content = parent.querySelector(".cm-content")
    const line = parent.querySelector(".cm-line")
    const gutter = parent.querySelector(".cm-gutterElement")
    const out = {}
    if (content) {
      const cs = getComputedStyle(content)
      out.fontPx = parseFloat(cs.fontSize)
      out.family = String(cs.fontFamily).slice(0, 60)
    }
    if (line) {
      const r = line.getBoundingClientRect()
      out.lineH = Math.round(r.height * 100) / 100
      const range = document.createRange()
      range.selectNodeContents(line)
      const tr = range.getBoundingClientRect()
      out.textH = Math.round(tr.height * 100) / 100
      out.textW = Math.round(tr.width * 100) / 100
    }
    if (gutter) {
      out.gutterFontPx = parseFloat(getComputedStyle(gutter).fontSize)
    }
    return out
  },
}
