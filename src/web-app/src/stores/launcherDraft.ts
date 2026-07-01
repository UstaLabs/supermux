import { defineStore } from "pinia"
import { reactive, watch } from "vue"

const KEY = "cmux:launcher-draft"

// In-progress New Session launcher state: the project pick (only once the user
// has explicitly engaged — null means "nothing in flight, follow the recency
// default"), worktree settings, and typed message text. Persists across
// navigation and app relaunch; cleared the moment a session is actually
// created (see SessionLauncherView.onPromptSubmit). Sibling of editorSettings.ts
// (same localStorage-backed reactive-store shape) — NOT the same store as the
// sticky agent/model prefs (cmux:launcher-prefs), which persist forever.
export interface LauncherDraft {
  workdir: string | null
  useWorktree: boolean
  baseBranch: string
  text: string
}

function defaults(): LauncherDraft {
  return { workdir: null, useWorktree: true, baseBranch: "", text: "" }
}

function load(): LauncherDraft {
  const base = defaults()
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return base
    const p = JSON.parse(raw)
    if (!p || typeof p !== "object") return base
    return {
      workdir: typeof p.workdir === "string" ? p.workdir : base.workdir,
      useWorktree: typeof p.useWorktree === "boolean" ? p.useWorktree : base.useWorktree,
      baseBranch: typeof p.baseBranch === "string" ? p.baseBranch : base.baseBranch,
      text: typeof p.text === "string" ? p.text : base.text,
    }
  } catch {
    return base
  }
}

export const useLauncherDraft = defineStore("launcherDraft", () => {
  const state = reactive<LauncherDraft>(load())

  watch(state, () => {
    try { localStorage.setItem(KEY, JSON.stringify(state)) } catch {}
  }, { deep: true })

  function setWorkdir(value: string) { state.workdir = value }
  function setWorktree(value: boolean) { state.useWorktree = value }
  function setBaseBranch(value: string) { state.baseBranch = value }
  function setText(value: string) { state.text = value }
  function clear() {
    state.workdir = null
    state.useWorktree = true
    state.baseBranch = ""
    state.text = ""
  }

  return { state, setWorkdir, setWorktree, setBaseBranch, setText, clear }
})
