import { ref, computed, toValue, type MaybeRefOrGetter } from "vue"
import { api, type ReviewComment } from "@/api/client"
import { useWS } from "@/api/ws"
import { toast } from "vue-sonner"

export interface EditorTab {
  path: string
  content: string
  savedContent: string
}

export interface DiffFile {
  path: string
  status: string
  diff: string
  binary?: boolean
  modeChange?: boolean
}

export interface RepoDiff {
  repo: string
  files: DiffFile[]
}

const MAX_TABS = 10

export function useEditor(sessionName: MaybeRefOrGetter<string>) {
  const sessionId = computed(() => toValue(sessionName))
  const tabs = ref<EditorTab[]>([])
  const activeTabPath = ref<string | null>(null)
  const loading = ref(false)
  const diffRepos = ref<RepoDiff[]>([])
  const diffComments = ref<ReviewComment[]>([])
  const showDiff = ref(false)
  const changedPaths = ref<Set<string>>(new Set())

  const ws = useWS()

  const activeTab = computed(() =>
    tabs.value.find((t) => t.path === activeTabPath.value) ?? null
  )

  const dirtyTabs = computed(() =>
    new Set(tabs.value.filter((t) => t.content !== t.savedContent).map((t) => t.path))
  )

  async function openFile(path: string) {
    const existing = tabs.value.find((t) => t.path === path)
    if (existing) {
      activeTabPath.value = path
      return
    }
    loading.value = true
    try {
      const content = await api.fsReadFile(sessionId.value, path)
      if (tabs.value.length >= MAX_TABS) {
        const cleanTab = tabs.value.find((t) => t.content === t.savedContent && t.path !== activeTabPath.value)
        if (cleanTab) {
          tabs.value = tabs.value.filter((t) => t.path !== cleanTab.path)
        } else {
          tabs.value.shift()
        }
      }
      tabs.value.push({ path, content, savedContent: content })
      activeTabPath.value = path
    } catch (err: any) {
      toast.error("Failed to open file", { description: err?.message ?? String(err) })
    } finally {
      loading.value = false
    }
  }

  function closeTab(path: string) {
    const idx = tabs.value.findIndex((t) => t.path === path)
    if (idx === -1) return
    tabs.value.splice(idx, 1)
    if (activeTabPath.value === path) {
      activeTabPath.value = tabs.value[Math.min(idx, tabs.value.length - 1)]?.path ?? null
    }
  }

  function updateContent(path: string, content: string) {
    const tab = tabs.value.find((t) => t.path === path)
    if (tab) tab.content = content
  }

  async function saveFile(path: string) {
    const tab = tabs.value.find((t) => t.path === path)
    if (!tab) return
    try {
      await api.fsWriteFile(sessionId.value, path, tab.content)
      tab.savedContent = tab.content
      toast.success(`Saved ${path.split("/").pop()}`)
    } catch (err: any) {
      toast.error("Save failed", { description: err?.message ?? String(err) })
    }
  }

  async function loadDiff() {
    try {
      const res = await api.fsDiff(sessionId.value)
      diffRepos.value = res.repos
      diffComments.value = res.comments
      showDiff.value = true
    } catch (err: any) {
      toast.error("Failed to load diff", { description: err?.message ?? String(err) })
    }
  }

  async function reloadDiff() {
    try {
      const res = await api.fsDiff(sessionId.value)
      diffRepos.value = res.repos
      diffComments.value = res.comments
    } catch (err: any) {
      toast.error("Failed to reload diff", { description: err?.message ?? String(err) })
    }
  }

  function handleFsChanged(paths: string[]) {
    for (const p of paths) changedPaths.value.add(p)
  }

  function startWatchingFor(id: string) {
    ws.send({ type: "editor_open", session: id })
    ws.onFsChanged(id, handleFsChanged)
  }

  function stopWatchingFor(id: string) {
    ws.send({ type: "editor_close", session: id })
    ws.offFsChanged(id)
  }

  function startWatching() {
    startWatchingFor(sessionId.value)
  }

  function stopWatching() {
    stopWatchingFor(sessionId.value)
  }

  async function reloadFile(path: string) {
    const tab = tabs.value.find((t) => t.path === path)
    if (!tab) return
    try {
      const content = await api.fsReadFile(sessionId.value, path)
      tab.content = content
      tab.savedContent = content
      changedPaths.value.delete(path)
      changedPaths.value.delete(path.replace(/^\//, ""))
    } catch (err: any) {
      toast.error("Reload failed", { description: err?.message ?? String(err) })
    }
  }

  return {
    tabs, activeTabPath, activeTab, dirtyTabs, loading,
    diffRepos, diffComments, showDiff, changedPaths,
    openFile, closeTab, updateContent, saveFile,
    loadDiff, reloadDiff, startWatching, stopWatching, reloadFile,
  }
}
