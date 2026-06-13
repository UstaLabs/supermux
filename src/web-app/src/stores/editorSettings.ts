import { defineStore } from "pinia"
import { reactive, watch } from "vue"
import { clampTreeWidth, TREE_WIDTH } from "@/lib/editor-resize"

const KEY = "cmux:editor-settings"

export const FONT_SIZE = { default: 13, min: 10, max: 24 }

export interface EditorSettings {
  // Wrap long lines onto the next visual line instead of scrolling horizontally.
  lineWrap: boolean
  // Editor font size in pixels.
  fontSize: number
  // File-tree sidebar width in pixels (desktop layout).
  treeWidth: number
}

function clampFontSize(v: unknown): number {
  if (typeof v !== "number" || Number.isNaN(v)) return FONT_SIZE.default
  return Math.min(FONT_SIZE.max, Math.max(FONT_SIZE.min, Math.round(v)))
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
      fontSize: clampFontSize(p.fontSize),
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
  function setFontSize(value: number) { state.fontSize = clampFontSize(value) }
  function setTreeWidth(value: number) { state.treeWidth = clampTreeWidth(value) }

  return { state, setLineWrap, toggleLineWrap, setFontSize, setTreeWidth }
})
