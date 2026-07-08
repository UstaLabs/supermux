import { defineStore } from "pinia"
import { reactive, watch } from "vue"
import { clampTreeWidth, TREE_WIDTH } from "@/lib/editor-resize"
import { FONT_SIZE, clampFont } from "@/lib/editor-font-zoom"

const KEY = "cmux:editor-settings"

export { FONT_SIZE }

export interface EditorSettings {
  // Wrap long lines onto the next visual line instead of scrolling horizontally.
  lineWrap: boolean
  // Editor font size in pixels.
  fontSize: number
  // File-tree sidebar width in pixels (desktop layout).
  treeWidth: number
}

function defaults(): EditorSettings {
  return { lineWrap: true, fontSize: FONT_SIZE.default, treeWidth: TREE_WIDTH.default }
}

function load(): EditorSettings {
  const base = defaults()
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return base
    const p = JSON.parse(raw)
    if (!p || typeof p !== "object") return base
    return {
      lineWrap: typeof p.lineWrap === "boolean" ? p.lineWrap : base.lineWrap,
      fontSize: clampFont(p.fontSize),
      treeWidth: clampTreeWidth(p.treeWidth),
    }
  } catch {
    return base
  }
}

export const useEditorSettings = defineStore("editorSettings", () => {
  const state = reactive<EditorSettings>(load())

  watch(state, () => {
    try { localStorage.setItem(KEY, JSON.stringify(state)) } catch {}
  }, { deep: true })

  function setLineWrap(value: boolean) { state.lineWrap = value }
  function toggleLineWrap() { state.lineWrap = !state.lineWrap }
  function setFontSize(value: number) { state.fontSize = clampFont(value) }
  function setTreeWidth(value: number) { state.treeWidth = clampTreeWidth(value) }

  return { state, setLineWrap, toggleLineWrap, setFontSize, setTreeWidth }
})
