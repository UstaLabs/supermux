<script setup lang="ts">
import { ref, shallowRef, onMounted, onUnmounted, onActivated, onDeactivated, computed, watch, inject, toRef, type Ref } from "vue"
import type { Extension } from "@codemirror/state"
import { useRouter } from "vue-router"
import { Search, GitCompareArrows, PanelLeftClose, PanelLeftOpen, RefreshCw, Settings2, Download, Loader2, Eye, Pencil } from "@lucide/vue"
import { useEditor } from "@/composables/useEditor"
import { useIsDesktop } from "@/composables/useIsDesktop"
import { useSessions } from "@/stores/sessions"
import { useWS } from "@/api/ws"
import { useLsp, type LspStatus } from "@/stores/lsp"
import { api } from "@/api/client"
import FileTree from "./FileTree.vue"
import EditorTabs from "./EditorTabs.vue"
import CodeEditor from "./CodeEditor.vue"
import DiffView from "./DiffView.vue"
import SymbolLocationsPanel from "./SymbolLocationsPanel.vue"
import MarkdownPreview from "./MarkdownPreview.vue"
import { uriToWorkdirPath, type SymbolLocation } from "@/lib/lsp-symbol-navigation"
import { isMarkdownPath } from "@/lib/markdown"
import { resolveTreeResize, TREE_COLLAPSE_AT, TREE_WIDTH } from "@/lib/editor-resize"
import { useEditorSettings } from "@/stores/editorSettings"

const props = defineProps<{
  sessionName: string
  active: boolean
}>()

const editor = useEditor(toRef(() => props.sessionName))
const isDesktop = useIsDesktop()
const router = useRouter()
const editorSettings = useEditorSettings()
const treeVisible = ref(true)
const searchQuery = ref("")
const searchResults = ref<Array<{ path: string; name: string; type: string; ignored?: boolean }>>([])

// ── Markdown preview ──────────────────────────────────────────────────────
// For .md files the header offers a Preview toggle that swaps the code editor
// for a rendered, read-only view. The flag is global but only takes effect for
// markdown tabs, so switching to a non-markdown file falls back to the editor.
const previewMode = ref(false)
const activeIsMarkdown = computed(() => {
  const tab = editor.activeTab.value
  return !!tab && isMarkdownPath(tab.path)
})
const showPreview = computed(() => previewMode.value && activeIsMarkdown.value)
const showPreviewToggle = computed(() => activeIsMarkdown.value && !editor.showDiff.value)

// ── Resizable / collapsible file tree (desktop layout) ──────────────────────
// The sidebar width is persisted; dragging the handle resizes it, and dragging
// it below the collapse threshold hides the tree (the header toggle restores it
// at its previous width). On narrow layouts the tree is a full-screen overlay,
// so resizing doesn't apply there.
const bodyEl = ref<HTMLElement | null>(null)
const resizing = ref(false)
let resizeStartX = 0
let resizeStartWidth = 0

function maxTreeWidth(): number {
  const w = bodyEl.value?.clientWidth ?? 0
  // Always leave room for the editor; never exceed the absolute max.
  return w > 0 ? Math.min(TREE_WIDTH.max, Math.max(TREE_WIDTH.min, w - 240)) : TREE_WIDTH.max
}

function onResizeStart(e: PointerEvent) {
  resizing.value = true
  resizeStartX = e.clientX
  resizeStartWidth = editorSettings.state.treeWidth
  ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
  e.preventDefault()
}

function onResizeMove(e: PointerEvent) {
  if (!resizing.value) return
  const desired = resizeStartWidth + (e.clientX - resizeStartX)
  const action = resolveTreeResize(desired, { min: TREE_WIDTH.min, max: maxTreeWidth(), collapseAt: TREE_COLLAPSE_AT })
  if (action.type === "collapse") {
    editorSettings.setTreeWidth(resizeStartWidth) // remember pre-drag width for re-open
    treeVisible.value = false
    endResize(e)
  } else {
    editorSettings.setTreeWidth(action.width)
  }
}

function endResize(e: PointerEvent) {
  resizing.value = false
  try { (e.target as HTMLElement).releasePointerCapture(e.pointerId) } catch { /* capture may already be gone */ }
}

let searchTimeout: NodeJS.Timeout | null = null

function onSearchInput() {
  if (searchTimeout) clearTimeout(searchTimeout)
  if (!searchQuery.value.trim()) { searchResults.value = []; return }
  searchTimeout = setTimeout(async () => {
    try {
      searchResults.value = await api.fsSearch(props.sessionName, searchQuery.value)
    } catch { searchResults.value = [] }
  }, 200)
}

// Open a file and, on mobile, collapse the full-screen tree overlay — otherwise
// it sits on top of the editor and hides the file you just opened.
async function revealFile(path: string, line?: number, endLine?: number) {
  await editor.openFile(path)
  if (line !== undefined) {
    revealPosition.value = {
      path,
      line: line - 1,
      character: 0,
      endLine: endLine !== undefined ? endLine - 1 : undefined,
      nonce: ++revealNonce,
    }
  }
  if (!isDesktop.value) treeVisible.value = false
}

function openSearchResult(path: string) {
  revealFile(path)
  searchQuery.value = ""
  searchResults.value = []
}

function onTreeFileOpen(path: string) {
  revealFile(path)
}

const fileChangedBanner = computed(() => {
  if (!editor.activeTab.value) return false
  const path = editor.activeTab.value.path
  return editor.changedPaths.value.has(path) || editor.changedPaths.value.has(path.replace(/^\//, ""))
})

// ── Language server (LSP) wiring ──────────────────────────────────────────
// For the active file we resolve the recommended server: if it's installed we
// hand CodeEditor the support extension; if not, we surface an install banner.
const sessions = useSessions()
const wsStore = useWS()
const lsp = useLsp()
const workdir = computed(() => sessions.byId(props.sessionName)?.workdir ?? "")
const lspExtension = shallowRef<Extension[]>([])
const lspActive = computed(() => lspExtension.value.length > 0)
/** Wait for refreshLsp to finish before mounting CodeMirror (avoids plain-then-lsp double mount). */
const lspSettled = ref(true)
const lspBanner = ref<LspStatus | null>(null)
const lspInstalling = ref(false)
const symbolLocations = ref<{ title: string; locations: SymbolLocation[] } | null>(null)
const revealPosition = ref<{ path: string; line: number; character: number; endLine?: number; nonce: number } | null>(null)
let revealNonce = 0
let lspToken = 0

async function navigateToSymbol(location: SymbolLocation) {
  const path = uriToWorkdirPath(location.uri, workdir.value)
  if (path == null) return
  symbolLocations.value = null
  await editor.openFile(path)
  revealPosition.value = {
    path,
    line: location.range.start.line,
    character: location.range.start.character,
    nonce: ++revealNonce,
  }
}

function showSymbolLocations(title: string, locations: SymbolLocation[]) {
  const visible = locations.filter((location) => uriToWorkdirPath(location.uri, workdir.value) != null)
  symbolLocations.value = visible.length > 0 ? { title, locations: visible } : null
}

async function refreshLsp() {
  const tab = editor.activeTab.value
  const token = ++lspToken
  lspSettled.value = false
  lspExtension.value = []
  lspBanner.value = null
  if (!tab || !workdir.value) {
    lspSettled.value = true
    return
  }
  const { extension, status } = await lsp.editorExtension(props.sessionName, workdir.value, tab.path, {
    clearLocations: () => { symbolLocations.value = null },
    navigate: (location) => { void navigateToSymbol(location) },
    showLocations: showSymbolLocations,
  })
  if (token !== lspToken) return // switched files mid-flight
  lspExtension.value = extension
  lspBanner.value = status.supported && status.state && status.state !== "ready" ? status : null
  lspSettled.value = true
}

function retryLsp() {
  void refreshLsp()
}

async function installLspServer() {
  const status = lspBanner.value
  if (!status?.serverId || lspInstalling.value) return
  lspInstalling.value = true
  const label = status.label ?? status.serverId
  const ok = await lsp.install(status.serverId, label)
  lspInstalling.value = false
  if (ok) void refreshLsp()
}

watch(
  () => [editor.activeTab.value?.path, workdir.value, wsStore.status] as const,
  () => {
    symbolLocations.value = null
    void refreshLsp()
  },
  { immediate: true },
)

watch(() => props.sessionName, () => {
  lspToken++
  lspInstalling.value = false
  lspExtension.value = []
  lspBanner.value = null
  lspSettled.value = false
  void refreshLsp()
})

const editorOpenFileFn = inject<Ref<((path: string, line?: number, endLine?: number) => void) | null>>("editorOpenFile", ref(null))

onMounted(() => {
  if (editorOpenFileFn) editorOpenFileFn.value = (path, line, endLine) => { void revealFile(path, line, endLine) }
})

onActivated(() => {
  editor.startWatching()
})

onDeactivated(() => {
  editor.stopWatching()
})

onUnmounted(() => {
  if (editorOpenFileFn) editorOpenFileFn.value = null
  editor.stopWatching()
})
</script>

<template>
  <div class="flex flex-col h-full bg-[var(--cmux-workspace)] text-foreground">
    <!-- Header -->
    <div class="flex items-center gap-1.5 px-2.5 py-1.5 border-b border-border min-h-[40px] bg-[var(--cmux-header)]">
      <button
        class="cmux-icon-button size-7"
        @click="treeVisible = !treeVisible"
      >
        <PanelLeftClose v-if="treeVisible" class="size-3.5" />
        <PanelLeftOpen v-else class="size-3.5" />
      </button>

      <!-- Search -->
      <div class="relative flex-1 max-w-[200px]">
        <Search class="absolute left-2 top-1/2 -translate-y-1/2 size-3 text-muted-foreground" />
        <input
          v-model="searchQuery"
          type="text"
          placeholder="Search files…"
          class="w-full pl-7 pr-2 py-1.5 text-[12px] bg-[var(--input)] rounded-md border border-border focus:border-primary/50 focus:outline-none text-foreground placeholder:text-muted-foreground"
          @input="onSearchInput"
        />
        <div
          v-if="searchResults.length > 0"
          class="absolute top-full left-0 right-0 mt-1 bg-popover border border-border rounded-md shadow-lg z-20 max-h-[200px] overflow-y-auto"
        >
          <button
            v-for="r in searchResults"
            :key="r.path"
            class="w-full px-3 py-1.5 text-[12px] text-left hover:bg-foreground/5 text-muted-foreground hover:text-foreground truncate"
            :class="{ 'opacity-50': r.ignored }"
            @click="openSearchResult(r.path)"
          >
            {{ r.path }}
          </button>
        </div>
      </div>

      <!-- Markdown preview toggle (markdown files only) -->
      <button
        v-if="showPreviewToggle"
        class="cmux-icon-button size-7 ml-auto"
        :class="{ 'text-primary': showPreview }"
        :title="previewMode ? 'Edit' : 'Preview'"
        @click="previewMode = !previewMode"
      >
        <Pencil v-if="previewMode" class="size-3.5" />
        <Eye v-else class="size-3.5" />
      </button>

      <button
        class="cmux-icon-button size-7"
        :class="{ 'ml-auto': !showPreviewToggle }"
        title="View Changes"
        @click="editor.loadDiff()"
      >
        <GitCompareArrows class="size-3.5" />
      </button>

      <button
        class="cmux-icon-button size-7"
        title="Editor Settings"
        @click="router.push('/settings/editor')"
      >
        <Settings2 class="size-3.5" />
      </button>

      <span
        v-if="lspActive && editor.activeTab"
        class="hidden sm:inline text-[10px] text-muted-foreground whitespace-nowrap shrink-0"
        title="Type . for members, or use a completion shortcut"
      >
        ⌥Space · ⌘⇧Space
      </span>
    </div>

    <!-- Tabs -->
    <EditorTabs
      v-if="editor.tabs.value.length > 0 && !editor.showDiff.value"
      :tabs="editor.tabs.value"
      :active-tab-path="editor.activeTabPath.value"
      :dirty-tabs="editor.dirtyTabs.value"
      @select="editor.activeTabPath.value = $event"
      @close="editor.closeTab($event)"
    />

    <!-- Body -->
    <div ref="bodyEl" class="flex-1 flex overflow-hidden relative">
      <!-- File tree sidebar (desktop: resizable sidebar, mobile: full-width overlay) -->
      <div
        v-if="treeVisible && !editor.showDiff.value"
        :class="isDesktop
          ? 'shrink-0 border-r border-border bg-[var(--cmux-session-list)] overflow-hidden'
          : 'absolute inset-0 z-10 bg-[var(--cmux-session-list)] overflow-hidden'"
        :style="isDesktop ? { width: `${editorSettings.state.treeWidth}px` } : undefined"
      >
        <FileTree :session-name="props.sessionName" @open-file="onTreeFileOpen($event)" />
      </div>

      <!-- Resize handle (desktop): drag to resize, drag fully shut to collapse. -->
      <div
        v-if="isDesktop && treeVisible && !editor.showDiff.value"
        class="w-1.5 shrink-0 -ml-px z-10 cursor-col-resize touch-none transition-colors hover:bg-primary/30"
        :class="resizing ? 'bg-primary/40' : ''"
        title="Drag to resize · drag shut to collapse"
        @pointerdown="onResizeStart"
        @pointermove="onResizeMove"
        @pointerup="endResize"
        @pointercancel="endResize"
      />

      <!-- Editor / Diff / Empty state -->
      <div class="flex-1 overflow-hidden relative">
        <DiffView
          v-if="editor.showDiff.value"
          :repos="editor.diffRepos.value"
          :comments="editor.diffComments.value"
          :session-id="props.sessionName"
          :base="editor.diffBase.value"
          :refs="editor.diffRefs.value"
          @close="editor.showDiff.value = false"
          @reload="editor.reloadDiff()"
          @set-base="editor.setDiffBase($event)"
        />

        <template v-else-if="editor.activeTab.value">
          <div
            v-if="fileChangedBanner"
            class="flex items-center gap-2 px-3 py-1.5 bg-[color-mix(in_oklab,var(--cmux-warning)_12%,var(--cmux-header))] border-b border-[color-mix(in_oklab,var(--cmux-warning)_28%,var(--border))] text-[12px] text-foreground"
          >
            <span>File changed on disk</span>
            <button
              class="flex items-center gap-1 px-2 py-0.5 rounded-md border border-border bg-[var(--cmux-header)] hover:bg-accent transition-colors"
              @click="editor.reloadFile(editor.activeTab.value!.path)"
            >
              <RefreshCw class="size-3" />
              Reload
            </button>
          </div>

          <!-- Rendered markdown preview (read-only) — otherwise the code editor -->
          <MarkdownPreview
            v-if="showPreview"
            class="flex-1 min-h-0 h-full"
            :content="editor.activeTab.value.content"
            @open-file="revealFile"
          />

          <template v-else>
          <div
            v-if="lspBanner"
            class="flex items-center gap-2 px-3 py-1.5 bg-[color-mix(in_oklab,var(--cmux-accent,#6366f1)_12%,var(--cmux-header))] border-b border-[color-mix(in_oklab,var(--cmux-accent,#6366f1)_28%,var(--border))] text-[12px] text-foreground"
          >
            <template v-if="lspInstalling">
              <Loader2 class="size-3 animate-spin shrink-0" />
              <span class="truncate">Installing {{ lspBanner.label }}…</span>
            </template>
            <template v-else-if="lspBanner.state === 'prereq-missing'">
              <span class="truncate">{{ lspBanner.label }} code intelligence needs <code class="font-mono">{{ lspBanner.requires }}</code> installed first.</span>
            </template>
            <template v-else-if="lspBanner.state === 'unavailable'">
              <span class="truncate">Code intelligence unavailable{{ lspBanner.error ? `: ${lspBanner.error}` : "" }}.</span>
              <button
                class="flex items-center gap-1 px-2 py-0.5 rounded-md border border-border bg-[var(--cmux-header)] hover:bg-accent transition-colors shrink-0"
                @click="retryLsp"
              >
                Retry
              </button>
            </template>
            <template v-else>
              <span class="truncate">Enable code completion &amp; errors for {{ lspBanner.label }}?</span>
              <button
                class="flex items-center gap-1 px-2 py-0.5 rounded-md border border-border bg-[var(--cmux-header)] hover:bg-accent transition-colors shrink-0"
                :title="lspBanner.installLabel ?? ''"
                @click="installLspServer"
              >
                <Download class="size-3" />
                Install
              </button>
            </template>
          </div>

          <div
            v-if="!lspSettled"
            class="flex flex-1 items-center justify-center text-[12px] text-muted-foreground"
          >
            Connecting language service…
          </div>
          <CodeEditor
            v-else
            class="flex-1 min-h-0 h-full"
            :content="editor.activeTab.value.content"
            :path="editor.activeTab.value.path"
            :active="props.active"
            :lsp-extension="lspExtension"
            :reveal-position="revealPosition"
            @update="editor.updateContent(editor.activeTab.value!.path, $event)"
            @save="editor.saveFile(editor.activeTab.value!.path)"
          />
          </template>
        </template>

        <div v-else class="flex items-center justify-center h-full text-muted-foreground text-[13px] bg-[var(--cmux-code)]">
          Open a file from the tree or search
        </div>

        <SymbolLocationsPanel
          v-if="symbolLocations"
          :title="symbolLocations.title"
          :locations="symbolLocations.locations"
          :workdir="workdir"
          @select="navigateToSymbol"
          @close="symbolLocations = null"
        />
      </div>
    </div>
  </div>
</template>
