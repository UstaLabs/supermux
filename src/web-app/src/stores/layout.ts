import { defineStore } from "pinia"
import { reactive, watch } from "vue"

const KEY = "cmux:layout"

export const SIDEBAR = { default: 320, min: 220, max: 560 }
export const SIDEBAR_RAIL = 56
export const CHAT_SPLIT = { default: 25, min: 20, max: 80 }
export const EDITOR_TERM_SPLIT = { default: 75, min: 20, max: 80 }
export const WORK_DISPLAY_SPLIT = { default: 55, min: 25, max: 75 }

export type ChatPanelTab = "chat" | "terminal" | "editor" | "display"

export interface ChatPanelState {
  chatOpen: boolean
  terminalOpen: boolean
  editorOpen: boolean
  displayOpen: boolean
  activeTab: ChatPanelTab
  mainView: "chat" | "terminal"
}

interface LayoutState {
  sidebarWidth: number
  chatSplitPct: number
  editorTermSplitPct: number
  workDisplaySplitPct: number
  sidebarCollapsed: boolean
  sidebarPage: "sessions" | "archived"
  groupByProject: boolean
  panels: Record<string, ChatPanelState>
}

function clamp(v: number, min: number, max: number): number {
  if (typeof v !== "number" || Number.isNaN(v)) return min
  return Math.min(max, Math.max(min, v))
}

function defaults(): LayoutState {
  return {
    sidebarWidth: SIDEBAR.default,
    chatSplitPct: CHAT_SPLIT.default,
    editorTermSplitPct: EDITOR_TERM_SPLIT.default,
    workDisplaySplitPct: WORK_DISPLAY_SPLIT.default,
    sidebarCollapsed: false,
    sidebarPage: "sessions",
    groupByProject: false,
    panels: {},
  }
}

function defaultPanelState(): ChatPanelState {
  return {
    chatOpen: true,
    terminalOpen: false,
    editorOpen: false,
    displayOpen: false,
    activeTab: "chat",
    mainView: "chat",
  }
}

function loadPanelState(raw: unknown): ChatPanelState {
  const base = defaultPanelState()
  if (!raw || typeof raw !== "object") return base
  const p = raw as Partial<ChatPanelState>
  const activeTab = p.activeTab === "terminal" || p.activeTab === "editor" || p.activeTab === "display"
    ? p.activeTab
    : base.activeTab
  const mainView = p.mainView === "terminal" ? "terminal" : "chat"
  return {
    chatOpen: typeof p.chatOpen === "boolean" ? p.chatOpen : base.chatOpen,
    terminalOpen: typeof p.terminalOpen === "boolean" ? p.terminalOpen : base.terminalOpen,
    editorOpen: typeof p.editorOpen === "boolean" ? p.editorOpen : base.editorOpen,
    displayOpen: typeof p.displayOpen === "boolean" ? p.displayOpen : base.displayOpen,
    activeTab,
    mainView,
  }
}

function loadPanels(raw: unknown): Record<string, ChatPanelState> {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) return {}
  const out: Record<string, ChatPanelState> = {}
  for (const [name, panel] of Object.entries(raw)) {
    out[name] = loadPanelState(panel)
  }
  return out
}

function load(): LayoutState {
  const base = defaults()
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return base
    const p = JSON.parse(raw)
    if (!p || typeof p !== "object") return base
    return {
      sidebarWidth: clamp(p.sidebarWidth, SIDEBAR.min, SIDEBAR.max),
      chatSplitPct: clamp(p.chatSplitPct, CHAT_SPLIT.min, CHAT_SPLIT.max),
      editorTermSplitPct: clamp(p.editorTermSplitPct, EDITOR_TERM_SPLIT.min, EDITOR_TERM_SPLIT.max),
      workDisplaySplitPct: typeof p.workDisplaySplitPct === "number"
        ? clamp(p.workDisplaySplitPct, WORK_DISPLAY_SPLIT.min, WORK_DISPLAY_SPLIT.max)
        : base.workDisplaySplitPct,
      sidebarCollapsed: typeof p.sidebarCollapsed === "boolean" ? p.sidebarCollapsed : base.sidebarCollapsed,
      sidebarPage: p.sidebarPage === "archived" ? "archived" : "sessions",
      groupByProject: typeof p.groupByProject === "boolean" ? p.groupByProject : base.groupByProject,
      panels: loadPanels(p.panels),
    }
  } catch {
    return base
  }
}

export const useLayout = defineStore("layout", () => {
  const state = reactive<LayoutState>(load())

  // Blank-screen guard: chat can only be hidden while a session's side pane is open.
  // `immediate` also repairs any stale all-closed state restored from storage.
  watch(
    () => state.panels,
    (panels) => {
      for (const panel of Object.values(panels)) {
        if (!panel.editorOpen && !panel.terminalOpen && !panel.displayOpen) panel.chatOpen = true
        if (panel.activeTab === "terminal" && !panel.terminalOpen) panel.activeTab = "chat"
        if (panel.activeTab === "editor" && !panel.editorOpen) panel.activeTab = "chat"
        if (panel.activeTab === "display" && !panel.displayOpen) panel.activeTab = "chat"
      }
    },
    { deep: true, immediate: true, flush: "sync" },
  )

  watch(state, () => {
    try { localStorage.setItem(KEY, JSON.stringify(state)) } catch {}
  }, { deep: true })

  function panelsFor(sessionId: string): ChatPanelState {
    state.panels[sessionId] ??= defaultPanelState()
    return state.panels[sessionId]!
  }

  function toggleTerminal(sessionId: string) { panelsFor(sessionId).terminalOpen = !panelsFor(sessionId).terminalOpen }
  function toggleEditor(sessionId: string) { panelsFor(sessionId).editorOpen = !panelsFor(sessionId).editorOpen }
  function toggleDisplay(sessionId: string) { panelsFor(sessionId).displayOpen = !panelsFor(sessionId).displayOpen }
  function toggleChat(sessionId: string) {
    const panel = panelsFor(sessionId)
    // No-op when nothing else is open — never leave a blank screen.
    if (!panel.editorOpen && !panel.terminalOpen && !panel.displayOpen) return
    panel.chatOpen = !panel.chatOpen
  }

  function selectTab(sessionId: string, tab: ChatPanelTab) {
    const panel = panelsFor(sessionId)
    // Open the pane BEFORE setting activeTab so the synchronous blank-screen
    // guard (watch, flush: "sync") doesn't bounce activeTab back to "chat".
    if (tab === "terminal") panel.terminalOpen = true
    else if (tab === "editor") panel.editorOpen = true
    else if (tab === "display") panel.displayOpen = true
    panel.activeTab = tab
  }

  function setMainView(sessionId: string, view: "chat" | "terminal") {
    panelsFor(sessionId).mainView = view
  }

  function resetSidebar() { state.sidebarWidth = SIDEBAR.default }
  function resetChatSplit() { state.chatSplitPct = CHAT_SPLIT.default }
  function resetEditorTermSplit() { state.editorTermSplitPct = EDITOR_TERM_SPLIT.default }
  function resetWorkDisplaySplit() { state.workDisplaySplitPct = WORK_DISPLAY_SPLIT.default }
  function toggleSidebarCollapsed() { state.sidebarCollapsed = !state.sidebarCollapsed }
  function expandSidebar() { state.sidebarCollapsed = false }
  function showArchivedPage() { state.sidebarPage = "archived" }
  function showSessionsPage() { state.sidebarPage = "sessions" }
  function toggleGroupByProject() { state.groupByProject = !state.groupByProject }

  return { state, panelsFor, selectTab, setMainView, toggleTerminal, toggleEditor, toggleDisplay, toggleChat, resetSidebar, resetChatSplit, resetEditorTermSplit, resetWorkDisplaySplit, toggleSidebarCollapsed, expandSidebar, showArchivedPage, showSessionsPage, toggleGroupByProject }
})
