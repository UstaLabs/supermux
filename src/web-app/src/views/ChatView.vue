<script setup lang="ts">
defineOptions({ name: "ChatView" })

import { computed, ref, provide, onMounted, onBeforeUnmount, nextTick, watch } from "vue"
import { useRouter } from "vue-router"
import { ChevronLeft, TerminalSquare, FileCode2, MessageSquare, Monitor, GitMerge } from "@lucide/vue"
import { AlertTriangleIcon, Loader2Icon, SendHorizonalIcon, SquareIcon } from "lucide-vue-next"
import { useMessages } from "@/stores/messages"
import { useWS } from "@/api/ws"
import { useSessions } from "@/stores/sessions"
import { useFinishProgress } from "@/stores/finishProgress"
import { useLayout, CHAT_SPLIT, EDITOR_TERM_SPLIT, WORK_DISPLAY_SPLIT } from "@/stores/layout"
import { useIsDesktop } from "@/composables/useIsDesktop"
import { usePanelResize } from "@/composables/usePanelResize"
import { useUploads } from "@/stores/uploads"
import { useDisplays } from "@/stores/displays"
import { useActivity } from "@/stores/activity"
import { useAgentState } from "@/stores/agentState"
import { formatDuration } from "@/lib/format-duration"
import { formatWorkdir } from "@/lib/format-workdir"
import { toWorkdirRelativePath } from "@/lib/workdir-display"
import { toast } from "vue-sonner"
import AgentLogo from "@/components/AgentLogo.vue"
import BranchSyncStatus from "@/components/BranchSyncStatus.vue"
import ModelSwitcher from "@/components/ModelSwitcher.vue"
import EffortSwitcher from "@/components/EffortSwitcher.vue"
import ModelPill from "@/components/ModelPill.vue"
import EffortPill from "@/components/EffortPill.vue"
import SlashCommandMenu from "@/components/SlashCommandMenu.vue"
import KillConfirmDialog from "@/components/KillConfirmDialog.vue"
import { useCommandsStore, type SlashCommand } from "@/stores/commands"
import { usePendingFirstMessage } from "@/stores/pendingFirstMessage"
import { useComposerSubmit } from "@/composables/useComposerSubmit"
import { useRenameRequest } from "@/composables/useRenameRequest"
import { api } from "@/api/client"
import TerminalPane from "@/components/TerminalPane.vue"
import EditorPane from "@/components/editor/EditorPane.vue"
import SessionDisplayPanel from "@/components/SessionDisplayPanel.vue"

import { Conversation, ConversationContent } from "@/components/ai-elements/conversation"
import { Message, MessageContent } from "@/components/ai-elements/message"
import MessageText from "@/components/MessageText.vue"
import AttachmentList from "@/components/attachments/AttachmentList.vue"
import {
  PromptInput,
  PromptInputBody,
  PromptInputHeader,
  PromptInputTextarea,
  PromptInputFooter,
  PromptInputTools,
  PromptInputSubmit,
  PromptInputActionMenu,
  PromptInputActionMenuTrigger,
  PromptInputActionMenuContent,
  PromptInputActionAddAttachments,
  PromptInputAttachments,
} from "@/components/ai-elements/prompt-input"
import PromptInputActionAddCamera from "@/components/ai-elements/prompt-input/PromptInputActionAddCamera.vue"
import PromptInputDraftSync from "@/components/PromptInputDraftSync.vue"
import type { PromptInputMessage } from "@/components/ai-elements/prompt-input"
import { Suggestion } from "@/components/ai-elements/suggestion"
import Tool from "@/components/ai-elements/tool/Tool.vue"
import MicButton from "@/components/voice/MicButton.vue"
import VoiceRecorder from "@/components/voice/VoiceRecorder.vue"

const props = defineProps<{ id: string }>()
const router = useRouter()
const messages = useMessages()
const pendingFirstMessage = usePendingFirstMessage()
const { send, submit: submitComposer, retryLast } = useComposerSubmit(() => props.id)
const ws = useWS()
const sessions = useSessions()
const displays = useDisplays()
const activity = useActivity()
const agentState = useAgentState()
const isDesktop = useIsDesktop()

// Live agent-state indicator (Sending… / Working… / nothing)
const liveState = computed(() => agentState.get(props.id))

// "Working…" timer: counts continuously from when the agent entered the working
// state (thinking/running), THROUGH tool transitions, until it goes idle/sending.
const now = ref(Date.now())
let _tick: ReturnType<typeof setInterval> | undefined
onMounted(() => { _tick = setInterval(() => { now.value = Date.now() }, 1000) })
onBeforeUnmount(() => { if (_tick) clearInterval(_tick) })
const workingElapsed = computed(() => {
  const ws = liveState.value.workingSince
  return ws ? Math.max(0, Math.floor((now.value - ws) / 1000)) : 0
})

const loading = ref(true)
const modelSwitcherOpen = ref(false)
const effortSwitcherOpen = ref(false)

// Slash commands
const commandsStore = useCommandsStore()
const { requestRename } = useRenameRequest()
const sessionCommands = computed(() => commandsStore.commandsFor(props.id))
const killConfirmOpen = ref(false)

function onControlCommand(cmd: SlashCommand) {
  switch (cmd.action?.kind) {
    case "spawn":  void router.push("/new"); break
    case "model":  modelSwitcherOpen.value = true; break
    case "rename": if (session.value?.name) requestRename(session.value.name); break
    case "mute":   void api.toggleMute(props.id, !!cmd.action.muted); break
    case "stop":   void interrupt(); break
    case "kill":   killConfirmOpen.value = true; break
  }
}

function retryStalled() { void retryLast() }

// Soft-interrupt the running agent (Stop button / /stop). The broker flips the
// live status to idle and broadcasts it, so the Working… indicator clears.
async function interrupt() {
  try {
    await api.interrupt(props.id)
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to stop the agent")
  }
}

// Finish: sync base → verify → integrate (worktree-backed sessions only).
const finishing = ref(false)
type FinishResult = Awaited<ReturnType<typeof api.finish>>
const finishResult = ref<FinishResult | null>(null)
const finishSending = ref(false)
const finishProgress = useFinishProgress()
const finishStage = computed(() => finishProgress.stageBySession[props.id])

const verifyDraft = ref<{ content: string; source: string } | null>(null)
const verifySaving = ref(false)
const commitMessage = ref("Session changes")

async function generateVerify() {
  try { verifyDraft.value = await api.verifySuggest(props.id) }
  catch (e: any) { toast.error(e?.message ?? "Failed to suggest a verify script") }
}
async function saveVerify() {
  if (!verifyDraft.value || verifySaving.value) return
  verifySaving.value = true
  try {
    const r = await api.verifySave(props.id, verifyDraft.value.content)
    if (!r.ok) { toast.error(r.reason ?? "Failed to save"); return }
    toast.success("Saved .mux/verify.sh — click Finish to run it")
    verifyDraft.value = null
    finishResult.value = null
  } catch (e: any) { toast.error(e?.message ?? "Failed to save") }
  finally { verifySaving.value = false }
}

async function finish(opts?: { skipVerify?: boolean; commitFirst?: boolean; commitMessage?: string }) {
  if (finishing.value) return
  finishing.value = true
  try {
    const r = await api.finish(props.id, opts)
    if (r.status === "integrated") { toast.success(`Merged into ${r.base}${r.verified ? ` (verified: ${r.verified})` : ""}`); finishResult.value = null }
    else if (r.status === "nothing_to_do") { toast.info("Nothing to finish — no new commits"); finishResult.value = null }
    else if (r.status === "non_ff") { toast.message("Base moved — re-syncing, retrying…"); void finish(opts) }
    else { finishResult.value = r } // sync_conflict / tests_failed / dirty_overlap / error → show the card
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : "Finish failed")
  } finally { finishing.value = false; finishProgress.clear(props.id) }
}

function finishIssueMessage(r: FinishResult): string {
  if (r.status === "sync_conflict")
    return `The Finish step merged the base branch in and hit conflicts in:\n${r.files.map((f) => `- ${f}`).join("\n")}\n\nThe worktree is in a conflicted merge state — please resolve the conflicts and commit, then I'll run Finish again.`
  if (r.status === "tests_failed")
    return `The Finish step ran the tests (\`${r.command}\`) and they failed:\n\n\`\`\`\n${r.output}\n\`\`\`\n\nPlease fix them so the branch is green, then I'll run Finish again.`
  if (r.status === "dirty_overlap")
    return `The base checkout has unsaved changes in: ${r.files.join(", ")} — the same files my work touches. Please commit or stash them so Finish can fast-forward.`
  return `Finish failed: ${(r as { message?: string }).message ?? r.status}`
}

async function sendFinishToAgent() {
  const r = finishResult.value
  if (!r || finishSending.value) return
  finishSending.value = true
  try {
    await api.sendMessage(props.id, finishIssueMessage(r))
    toast.success("Sent to the agent")
    finishResult.value = null
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to send to agent")
  } finally { finishSending.value = false }
}
function mergeAnyway() { finishResult.value = null; verifyDraft.value = null; void finish({ skipVerify: true }) }
function commitAndFinish() { finishResult.value = null; void finish({ commitFirst: true, commitMessage: commitMessage.value }) }
const layout = useLayout()
const panels = computed(() => layout.panelsFor(props.id))
const activeTab = computed({
  get: () => panels.value.activeTab,
  set: (tab) => { panels.value.activeTab = tab },
})
type PendingOpenFile = { path: string; line?: number; endLine?: number }
const editorOpenFile = ref<((path: string, line?: number, endLine?: number) => void) | null>(null)
provide("editorOpenFile", editorOpenFile)
// Path-click can fire before EditorPane has mounted and registered editorOpenFile.
// Stash the requested path and flush it the moment the open-file fn appears.
const pendingOpenPath = ref<PendingOpenFile | null>(null)
watch(editorOpenFile, (fn) => {
  const pending = pendingOpenPath.value
  if (fn && pending) {
    fn(pending.path, pending.line, pending.endLine)
    pendingOpenPath.value = null
  }
})

// Keep panes mounted once opened so toggling off hides (not destroys) them —
// the terminal websocket/PTY and editor state survive a hide/show cycle.
const terminalEverOpened = ref(panels.value.terminalOpen)
const editorEverOpened = ref(panels.value.editorOpen)
const displayEverOpened = ref(panels.value.displayOpen)
watch(() => panels.value.terminalOpen, (v) => { if (v) terminalEverOpened.value = true })
watch(() => panels.value.editorOpen, (v) => { if (v) editorEverOpened.value = true })
watch(() => panels.value.displayOpen, (v) => { if (v) displayEverOpened.value = true })

// `session` (and its inputs) MUST be declared before `sessionStream` and the
// watch below. The watch evaluates its getter during setup, which reads
// `session.value`; declaring `session` later puts it in the temporal dead zone
// → "Cannot access 'session' before initialization" crashes ChatView on mount.
const activeSession = computed(() => sessions.list.find((s) => s.id === props.id))
const archivedSession = computed(() => sessions.archivedSessions.find((s) => s.id === props.id))
const session = computed(() => activeSession.value ?? archivedSession.value)

const sessionStream = computed(() => {
  const name = session.value?.name
  return name ? displays.runningForSession(name) : undefined
})
watch(
  () => !!sessionStream.value,
  (hasStream, had) => {
    if (hasStream && !had) {
      panels.value.displayOpen = true
      if (!isDesktop.value) activeTab.value = "display"
    }
  },
)

const rightVisible = computed(() => panels.value.terminalOpen || panels.value.editorOpen || panels.value.displayOpen)
const workVisible = computed(() => panels.value.terminalOpen || panels.value.editorOpen)
const rightMounted = computed(() => terminalEverOpened.value || editorEverOpened.value || displayEverOpened.value)
const workMounted = computed(() => terminalEverOpened.value || editorEverOpened.value)

const contentRowRef = ref<HTMLElement | null>(null)
const workColRef = ref<HTMLElement | null>(null)
const rightAreaRef = ref<HTMLElement | null>(null)

const chatStyle = computed(() => {
  if (!isDesktop.value) return undefined
  if (rightVisible.value && panels.value.chatOpen) return `flex: 0 0 ${layout.state.chatSplitPct}%`
  return "flex: 1 1 0"
})
const editorStyle = computed(() => {
  if (!isDesktop.value) return undefined
  if (panels.value.terminalOpen) return `flex: 0 0 ${layout.state.editorTermSplitPct}%`
  return "flex: 1 1 0"
})
const workColStyle = computed(() => {
  if (!isDesktop.value) return undefined
  if (workVisible.value && panels.value.displayOpen) {
    return `flex: 0 0 ${layout.state.workDisplaySplitPct}%`
  }
  return "flex: 1 1 0"
})
const displayColStyle = computed(() => {
  if (!isDesktop.value) return undefined
  if (workVisible.value && panels.value.displayOpen) return "flex: 1 1 0"
  return "flex: 1 1 0"
})

const chatResize = usePanelResize({
  orientation: "horizontal",
  unit: "pct",
  min: CHAT_SPLIT.min,
  max: CHAT_SPLIT.max,
  get: () => layout.state.chatSplitPct,
  set: (v) => { layout.state.chatSplitPct = v },
  containerEl: () => contentRowRef.value,
  onReset: () => layout.resetChatSplit(),
})
const editorTermResize = usePanelResize({
  orientation: "vertical",
  unit: "pct",
  min: EDITOR_TERM_SPLIT.min,
  max: EDITOR_TERM_SPLIT.max,
  get: () => layout.state.editorTermSplitPct,
  set: (v) => { layout.state.editorTermSplitPct = v },
  containerEl: () => workColRef.value,
  onReset: () => layout.resetEditorTermSplit(),
})
const workDisplayResize = usePanelResize({
  orientation: "horizontal",
  unit: "pct",
  min: WORK_DISPLAY_SPLIT.min,
  max: WORK_DISPLAY_SPLIT.max,
  get: () => layout.state.workDisplaySplitPct,
  set: (v) => { layout.state.workDisplaySplitPct = v },
  containerEl: () => rightAreaRef.value,
  onReset: () => layout.resetWorkDisplaySplit(),
})

function handleOpenFile(path: string, line?: number, endLine?: number) {
  const wd = session.value?.workdir
  const rel = wd ? toWorkdirRelativePath(path, wd, sessions.homeDir) : path.replace(/^\.\//, "")
  if (rel === null) {
    toast.error("File is outside this session's project")
    return
  }
  panels.value.editorOpen = true
  activeTab.value = "editor"
  if (editorOpenFile.value) {
    editorOpenFile.value(rel, line, endLine)
    pendingOpenPath.value = null
  } else {
    pendingOpenPath.value = { path: rel, line, endLine }
  }
}

const entries = computed(() => messages.bySession[props.id] ?? [])

type ToolRow = { type: "tool"; ts: number; key: string; toolName: string; summary?: string; input?: string; output?: string; status: "running" | "done" | "error"; truncated?: boolean }
type Row =
  | { type: "message"; ts: number; key: string; entry: (typeof entries.value)[number] }
  | ToolRow

const rows = computed<Row[]>(() => {
  const acts = activity.bySession[props.id] ?? []
  const resultByCall = new Map<string, (typeof acts)[number]>()
  for (const e of acts) if (e.kind === "tool_result" && e.callId) resultByCall.set(e.callId, e)

  const out: Row[] = []
  for (const e of entries.value) {
    out.push({ type: "message", ts: new Date(e.ts).getTime(), key: `m:${e.id}`, entry: e })
  }
  for (const e of acts) {
    const ts = new Date(e.ts).getTime()
    const key = e.seq !== undefined ? `a:${e.seq}` : `a:${e.ts}:${e.kind}:${e.tool ?? ""}:${e.title}`
    // "thinking" activity entries are intentionally dropped: thinking is shown
    // only as a live indicator at the bottom, never as a persistent history pill.
    if (e.kind === "tool") {
      const res = e.callId ? resultByCall.get(e.callId) : undefined
      const status: "running" | "done" | "error" = res ? (res.title === "error" ? "error" : "done") : "running"
      const prefix = `${e.tool ?? ""}: `
      const summary = e.tool && e.title.startsWith(prefix) ? e.title.slice(prefix.length) : e.title
      out.push({
        type: "tool", ts, key,
        toolName: e.tool ?? "tool",
        summary: summary || undefined,
        input: e.detail || undefined,
        output: res?.detail || undefined,
        status,
        truncated: e.truncated || res?.truncated || undefined,
      })
    }
    // tool_result rows are absorbed into their tool card — skip
  }
  const rank = (t: string) => (t === "message" ? 1 : 0)
  return out.sort((a, b) => a.ts - b.ts || rank(a.type) - rank(b.type))
})

// Group consecutive activity rows into a tight cluster so they don't inherit
// the message-level gap. Messages stay as their own blocks.
type Block =
  | { kind: "message"; key: string; entry: (typeof entries.value)[number] }
  | { kind: "activity"; key: string; items: ToolRow[] }

const blocks = computed<Block[]>(() => {
  const result: Block[] = []
  let cluster: ToolRow[] = []
  const flush = () => {
    if (cluster.length) { result.push({ kind: "activity", key: `act:${cluster[0]!.key}`, items: cluster }); cluster = [] }
  }
  for (const r of rows.value) {
    if (r.type === "message") { flush(); result.push({ kind: "message", key: r.key, entry: r.entry }) }
    else cluster.push(r)
  }
  flush()
  return result
})

const isArchived = computed(() => !sessions.list.some((s) => s.id === props.id))
const displayName = computed(() => session.value?.name ?? "Unknown session")
const workdirLabel = computed(() => {
  const wd = session.value?.workdir
  if (!wd) return ""
  return formatWorkdir(wd, sessions.homeDir)
})

const resuming = ref(false)

async function handleResume() {
  resuming.value = true
  try {
    await sessions.resumeSession(props.id)
    layout.showSessionsPage()
    toast.success("Session resumed")
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to resume session")
  } finally {
    resuming.value = false
  }
}

const starterPrompts = [
  "What's the current state?",
  "Run the tests",
  "Show me the diff",
  "Summarize recent work",
]

const sentAt = computed(() => messages.getSentAt(props.id))

watch(entries, (curr) => {
  const last = curr[curr.length - 1]
  if (!last) return
  if (last.direction === "outbound" && new Date(last.ts).getTime() >= sentAt.value) {
    messages.clearSent(props.id)
  }
}, { deep: true })

const uploads = useUploads()

const isRecording = ref(false)

function startRecording() {
  isRecording.value = true
}

function onRecordingDone() {
  isRecording.value = false
}

const hasPendingUploads = computed(() =>
  Object.values(uploads.byId).some((s) => s.status === "uploading" || s.status === "failed")
)

async function onPromptSubmit(payload: PromptInputMessage) {
  await submitComposer(payload)
}

async function flushPendingFirstMessage() {
  const payload = pendingFirstMessage.consume(props.id)
  if (!payload) return
  try {
    await submitComposer(payload)
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : "Failed to send message")
  }
}

async function loadMessages() {
  loading.value = true
  try {
    if (!sessions.list.some((s) => s.id === props.id) && !sessions.archivedSessions.some((s) => s.id === props.id)) {
      if (!sessions.archivedLoaded) await sessions.fetchArchived()
    }
    const archived = !sessions.list.some((s) => s.id === props.id)
    if (archived || !messages.bySession[props.id]?.length) {
      const log = await api.getSessionMessages(props.id)
      messages.replace(props.id, Array.isArray(log) ? log : [])
    }
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to load messages")
  } finally {
    await nextTick()
    loading.value = false
  }
}

watch(() => props.id, () => { void loadMessages(); void flushPendingFirstMessage() }, { immediate: true })
</script>

<template>
  <div class="h-dvh flex flex-col bg-[var(--cmux-chat)] text-foreground">
    <header
      class="flex items-center gap-3 px-3 py-2 min-h-[3.5rem] border-b border-border sticky top-0 bg-[var(--cmux-header)]/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.5rem)"
    >
      <router-link v-if="!isDesktop" :to="isArchived ? '/archived' : '/'" class="text-muted-foreground hover:text-foreground transition -ml-1 p-1" aria-label="Back">
        <ChevronLeft class="size-5" />
      </router-link>
      <div class="min-w-0 flex-1">
        <div class="flex items-center gap-2">
          <AgentLogo
            v-if="session?.agent"
            :agent="session.agent"
            class="size-4 shrink-0 text-muted-foreground"
          />
          <span class="font-semibold truncate leading-tight">{{ displayName }}</span>
        </div>
        <BranchSyncStatus
          v-if="!isArchived"
          :session-id="props.id"
          :workdir="session?.workdir"
          :workdir-label="workdirLabel"
        />
        <div v-else-if="workdirLabel" class="text-[11px] text-muted-foreground truncate font-mono">
          {{ workdirLabel }}
        </div>
      </div>
      <button
        v-if="isArchived"
        class="shrink-0 px-3 py-1.5 text-xs font-medium rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition disabled:opacity-50"
        :disabled="resuming"
        @click="handleResume"
      >
        {{ resuming ? "…" : "Resume" }}
      </button>
      <button
        v-if="isDesktop && !isArchived"
        class="cmux-icon-button disabled:opacity-40 disabled:cursor-not-allowed"
        :class="{ 'cmux-icon-button-active': panels.chatOpen }"
        :disabled="!panels.editorOpen && !panels.terminalOpen && !panels.displayOpen"
        aria-label="Toggle chat"
        @click="layout.toggleChat(props.id)"
      >
        <MessageSquare class="size-4" />
      </button>
      <button
        v-if="!isArchived"
        class="cmux-icon-button"
        :class="{ 'cmux-icon-button-active': panels.terminalOpen }"
        aria-label="Toggle terminal"
        @click="layout.toggleTerminal(props.id); if (panels.terminalOpen) activeTab = 'terminal'"
      >
        <TerminalSquare class="size-4" />
      </button>
      <button
        v-if="!isArchived"
        class="cmux-icon-button"
        :class="{ 'cmux-icon-button-active': panels.editorOpen }"
        aria-label="Toggle editor"
        @click="layout.toggleEditor(props.id); if (panels.editorOpen) activeTab = 'editor'"
      >
        <FileCode2 class="size-4" />
      </button>
      <button
        v-if="!isArchived"
        class="cmux-icon-button"
        :class="{ 'cmux-icon-button-active': panels.displayOpen }"
        aria-label="Toggle display"
        @click="layout.toggleDisplay(props.id); if (panels.displayOpen) activeTab = 'display'"
      >
        <Monitor class="size-4" />
      </button>
      <button
        v-if="!isArchived && session?.session_branch"
        type="button"
        :disabled="finishing"
        aria-label="Finish: sync, verify, and merge into the base branch"
        class="inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-[12px] font-medium bg-emerald-600 text-white hover:bg-emerald-500 disabled:opacity-80 disabled:cursor-not-allowed transition-colors"
        @click="finish()"
      >
        <Loader2Icon v-if="finishing" class="size-3.5 animate-spin" />
        <GitMerge v-else class="size-3.5" />
        <span :class="{ 'hidden sm:inline': !finishing }">{{ finishing ? (finishStage || 'Finishing…') : 'Finish' }}</span>
      </button>
    </header>

    <!-- Finish failure result -->
    <div
      v-if="finishResult"
      class="fixed inset-x-3 bottom-24 z-50 mx-auto max-w-lg rounded-xl border border-border bg-card shadow-xl"
    >
      <div class="flex items-center gap-2 px-4 py-2.5 border-b border-border">
        <span class="text-[13px] font-semibold">
          {{ finishResult.status === 'sync_conflict' ? 'Merge conflicts'
            : finishResult.status === 'tests_failed' ? 'Tests failed'
            : finishResult.status === 'dirty_overlap' ? 'Base has unsaved changes'
            : finishResult.status === 'no_verify' ? 'No verify configured'
            : finishResult.status === 'uncommitted' ? 'Uncommitted changes'
            : 'Finish failed' }}
        </span>
        <button
          class="ml-auto text-muted-foreground hover:text-foreground text-lg leading-none"
          aria-label="Dismiss"
          @click="finishResult = null; verifyDraft = null"
        >&times;</button>
      </div>
      <div class="px-4 py-3 max-h-60 overflow-y-auto text-[12px]">
        <ul v-if="finishResult.status === 'sync_conflict' || finishResult.status === 'dirty_overlap'" class="font-mono space-y-0.5">
          <li v-for="f in finishResult.files" :key="f" class="truncate text-foreground/80">{{ f }}</li>
        </ul>
        <pre v-else-if="finishResult.status === 'tests_failed'" class="whitespace-pre-wrap break-all font-mono text-foreground/80">{{ finishResult.output }}</pre>
        <template v-else-if="finishResult.status === 'no_verify'">
          <p v-if="!verifyDraft" class="text-foreground/80">
            This repo has no <code>.mux/verify.sh</code>, so there's nothing to run as a check.
          </p>
          <div v-else class="flex flex-col gap-1">
            <span class="text-[10px] uppercase tracking-wide text-muted-foreground">Draft · {{ verifyDraft.source }}</span>
            <textarea v-model="verifyDraft.content" rows="6"
              class="w-full font-mono text-[12px] bg-[var(--input)] border border-border rounded-md px-2 py-1.5 text-foreground focus:outline-none focus:border-primary/50" />
          </div>
        </template>
        <template v-else-if="finishResult.status === 'uncommitted'">
          <p class="text-foreground/80 mb-2">These changes aren't committed yet — Finish merges commits, so commit them first:</p>
          <ul class="font-mono space-y-0.5 mb-2">
            <li v-for="f in finishResult.files" :key="f" class="truncate text-foreground/80">{{ f }}</li>
          </ul>
          <input v-model="commitMessage" placeholder="Commit message"
            class="w-full text-[12px] bg-[var(--input)] border border-border rounded-md px-2 py-1.5 text-foreground focus:outline-none focus:border-primary/50" />
        </template>
        <p v-else class="text-foreground/80">{{ (finishResult as { message?: string }).message }}</p>
      </div>
      <div class="flex items-center justify-end gap-2 px-4 py-2.5 border-t border-border">
        <button
          class="text-[12px] px-2.5 py-1 rounded-md border border-border hover:bg-accent text-muted-foreground"
          @click="finishResult = null; verifyDraft = null"
        >Dismiss</button>
        <template v-if="finishResult.status === 'no_verify'">
          <button v-if="!verifyDraft" class="text-[12px] px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90" @click="generateVerify">Generate verify</button>
          <button v-else class="text-[12px] px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50" :disabled="verifySaving" @click="saveVerify">Save</button>
          <button class="text-[12px] px-2.5 py-1 rounded-md border border-amber-500/40 hover:bg-amber-500/10 text-amber-400" @click="mergeAnyway">Merge without verifying</button>
        </template>
        <button
          v-if="finishResult.status === 'tests_failed'"
          class="text-[12px] px-2.5 py-1 rounded-md border border-amber-500/40 hover:bg-amber-500/10 text-amber-400"
          @click="mergeAnyway"
        >Merge anyway</button>
        <button
          v-if="finishResult.status === 'uncommitted'"
          class="text-[12px] px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          :disabled="finishing"
          @click="commitAndFinish"
        >Commit &amp; finish</button>
        <button
          v-if="finishResult.status !== 'error' && finishResult.status !== 'no_verify' && finishResult.status !== 'uncommitted'"
          class="text-[12px] px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          :disabled="finishSending"
          @click="sendFinishToAgent"
        >Send to agent</button>
      </div>
    </div>

    <div v-if="isArchived" class="px-4 py-2 text-xs text-muted-foreground bg-muted/30 border-b border-border text-center">
      Archived · Resume to continue
    </div>

    <!-- Mobile tab bar (below header, above content) -->
    <div
      v-if="!isDesktop && (panels.terminalOpen || panels.editorOpen || panels.displayOpen)"
      class="flex border-b border-border bg-[var(--cmux-header)]"
    >
      <button
        class="flex-1 py-2 text-xs font-medium transition border-b-2"
        :class="activeTab === 'chat' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground'"
        @click="activeTab = 'chat'"
      >Chat</button>
      <button
        v-if="panels.displayOpen"
        class="flex-1 py-2 text-xs font-medium transition border-b-2"
        :class="activeTab === 'display' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground'"
        @click="activeTab = 'display'"
      >Display</button>
      <button
        v-if="panels.editorOpen"
        class="flex-1 py-2 text-xs font-medium transition border-b-2"
        :class="activeTab === 'editor' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground'"
        @click="activeTab = 'editor'"
      >Editor</button>
      <button
        v-if="panels.terminalOpen"
        class="flex-1 py-2 text-xs font-medium transition border-b-2"
        :class="activeTab === 'terminal' ? 'border-primary text-primary' : 'border-transparent text-muted-foreground'"
        @click="activeTab = 'terminal'"
      >Terminal</button>
    </div>

    <!-- Content area: split on desktop, tabbed on mobile -->
    <div ref="contentRowRef" class="flex-1 flex overflow-hidden" :class="isDesktop && rightVisible ? 'flex-row' : 'flex-col'">
      <!-- Chat panel -->
      <div
        class="flex flex-col min-w-0 overflow-hidden"
        :class="{
          'flex-1': !isDesktop,
          'hidden': (!isDesktop && rightVisible && activeTab !== 'chat') || (isDesktop && !panels.chatOpen),
        }"
        :style="chatStyle"
      >
        <!-- Skeletons live outside Conversation so the first real message render is the
             engine's initial (instant) scroll, not an animated skeleton→messages resize. -->
        <div v-if="loading" class="flex-1 overflow-hidden px-3 py-3 space-y-3">
          <div
            v-for="i in 3"
            :key="`s-${i}`"
            class="h-16 rounded-xl bg-card animate-pulse"
          />
        </div>

        <!-- Keyed per session: each chat gets a fresh stick-to-bottom engine. Both initial
             and resize are instant so opening a chat lands at the bottom without animation
             and content settling (images/blocks loading) doesn't re-scroll repeatedly. -->
        <Conversation v-else :key="props.id" class="flex-1" initial="instant" resize="instant">
          <ConversationContent class="px-3 py-3 gap-3 md:px-4 md:py-4 md:gap-3.5">
            <template v-if="entries.length === 0">
              <div class="py-10 text-center text-muted-foreground space-y-4">
                <p class="text-sm">No messages yet in <span class="text-foreground font-medium">{{ displayName }}</span>.</p>
                <div v-if="!isArchived" class="flex flex-wrap gap-2 justify-center px-4">
                  <Suggestion
                    v-for="s in starterPrompts"
                    :key="s"
                    :suggestion="s"
                    @click="send(s)"
                  />
                </div>
              </div>
            </template>

            <template v-else>
              <template v-for="block in blocks" :key="block.key">
                <Message
                  v-if="block.kind === 'message'"
                  :from="block.entry.direction === 'outbound' ? 'user' : 'assistant'"
                >
                  <MessageContent>
                    <MessageText v-if="block.entry.text" :content="block.entry.text" @open-file="handleOpenFile" />
                    <AttachmentList :attachments="block.entry.attachments" />
                  </MessageContent>
                </Message>
                <div v-else class="flex flex-col gap-1.5">
                  <template v-for="row in block.items" :key="row.key">
                    <Tool
                      :tool-name="row.toolName"
                      :summary="row.summary"
                      :input="row.input"
                      :output="row.output"
                      :status="row.status"
                      :truncated="row.truncated"
                    />
                  </template>
                </div>
              </template>
              <!-- Live status: "Sending…" until the agent's real start signal,
                   then "Working…" until idle. Nothing when idle. -->
              <div
                v-if="!isArchived && liveState.phase === 'stalled'"
                class="flex items-center gap-1.5 px-1 py-0.5 text-xs italic text-muted-foreground/70 ml-2"
              >
                <AlertTriangleIcon class="size-3.5 shrink-0 text-amber-500" />
                No response yet
                <button
                  type="button"
                  class="ml-1 inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-medium not-italic text-muted-foreground hover:text-primary hover:bg-primary/10 active:scale-95 transition"
                  aria-label="Retry sending"
                  @click="retryStalled"
                >
                  Retry
                </button>
                <button
                  type="button"
                  class="inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-medium not-italic text-muted-foreground hover:text-destructive hover:bg-destructive/10 active:scale-95 transition"
                  aria-label="Stop the agent"
                  @click="interrupt"
                >
                  <SquareIcon class="size-3 shrink-0" />
                  Stop
                </button>
              </div>
              <div
                v-else-if="!isArchived && liveState.phase === 'sending'"
                class="flex items-center gap-1.5 px-1 py-0.5 text-xs italic text-muted-foreground/70 ml-2"
              >
                <SendHorizonalIcon class="size-3.5 shrink-0 animate-pulse" />
                Sending…
                <button
                  type="button"
                  class="ml-1 inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-medium not-italic text-muted-foreground hover:text-destructive hover:bg-destructive/10 active:scale-95 transition"
                  aria-label="Stop the agent"
                  @click="interrupt"
                >
                  <SquareIcon class="size-3 shrink-0" />
                  Stop
                </button>
              </div>
              <div
                v-else-if="!isArchived && (liveState.phase === 'thinking' || liveState.phase === 'running')"
                class="flex items-center gap-1.5 px-1 py-0.5 text-xs text-muted-foreground ml-2"
              >
                <Loader2Icon class="size-3.5 shrink-0 animate-spin text-primary" />
                Working…
                <span class="opacity-60">{{ formatDuration(workingElapsed) }}</span>
                <button
                  type="button"
                  class="ml-1 inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground hover:text-destructive hover:bg-destructive/10 active:scale-95 transition"
                  aria-label="Stop the agent"
                  @click="interrupt"
                >
                  <SquareIcon class="size-3 shrink-0" />
                  Stop
                </button>
              </div>
            </template>
          </ConversationContent>
        </Conversation>

        <div
          v-if="!isArchived"
          class="px-3 pt-3 bg-[var(--cmux-chat)]"
          style="padding-bottom: calc(env(safe-area-inset-bottom, 0px) + 0.5rem)"
        >
          <PromptInput
            class="relative"
            :max-files="10"
            :max-file-size="25 * 1024 * 1024"
            :global-drop="true"
            @submit="onPromptSubmit"
          >
            <SlashCommandMenu :commands="sessionCommands" :loading="!commandsStore.isResolved(props.id)" @control="onControlCommand" />
            <PromptInputDraftSync :session-id="props.id" />
            <PromptInputHeader>
              <PromptInputAttachments />
            </PromptInputHeader>
            <PromptInputBody>
              <VoiceRecorder v-if="isRecording" @done="onRecordingDone" />
              <PromptInputTextarea v-else placeholder="Message…" />
            </PromptInputBody>
            <PromptInputFooter>
              <PromptInputTools>
                <ModelPill
                  :session-id="props.id"
                  :disabled="ws.status !== 'connected'"
                  @click="modelSwitcherOpen = true"
                />
                <EffortPill
                  :session-id="props.id"
                  :disabled="ws.status !== 'connected'"
                  @click="effortSwitcherOpen = true"
                />
                <PromptInputActionMenu>
                  <PromptInputActionMenuTrigger />
                  <PromptInputActionMenuContent>
                    <PromptInputActionAddAttachments label="Files" />
                    <PromptInputActionAddCamera />
                  </PromptInputActionMenuContent>
                </PromptInputActionMenu>
                <MicButton
                  :disabled="ws.status !== 'connected' || hasPendingUploads || isRecording"
                  @start="startRecording"
                />
              </PromptInputTools>
              <PromptInputTools class="ml-auto">
                <PromptInputSubmit
                  :disabled="ws.status !== 'connected' || hasPendingUploads || isRecording"
                  class="rounded-full size-8 bg-primary text-primary-foreground hover:bg-primary/90 disabled:bg-muted disabled:text-muted-foreground disabled:opacity-100"
                />
              </PromptInputTools>
            </PromptInputFooter>
          </PromptInput>
        </div>
      </div>

      <!-- Chat ↔ right-column resize handle (desktop only) -->
      <div
        v-if="isDesktop && rightVisible && panels.chatOpen"
        class="relative w-px bg-border shrink-0"
      >
        <div
          class="absolute inset-y-0 -left-1 -right-1 z-10 cursor-col-resize hover:bg-primary/25 transition-colors"
          @pointerdown="chatResize.onPointerDown"
          @dblclick="chatResize.onDblClick"
        />
      </div>

      <!-- Right area: editor/terminal stack + display side-by-side on desktop -->
      <div
        v-if="!isArchived && rightMounted"
        v-show="rightVisible"
        ref="rightAreaRef"
        class="min-w-0 overflow-hidden flex flex-1 flex-row"
        :class="{ 'flex-col': !isDesktop, 'hidden': !isDesktop && activeTab === 'chat' }"
      >
        <!-- Work column: editor + terminal stacked -->
        <div
          v-if="workMounted"
          v-show="workVisible && (isDesktop || activeTab !== 'display')"
          ref="workColRef"
          class="min-w-0 overflow-hidden flex flex-col min-h-0 flex-1"
          :style="isDesktop ? workColStyle : undefined"
        >
          <!-- Editor pane -->
          <div
            v-if="editorEverOpened"
            v-show="panels.editorOpen"
            class="overflow-hidden flex-1 min-h-0"
            :class="{ 'hidden': !isDesktop && activeTab !== 'editor' }"
            :style="editorStyle"
          >
            <EditorPane
              :session-name="props.id"
              :active="(isDesktop && panels.editorOpen) || (!isDesktop && activeTab === 'editor')"
            />
          </div>

          <!-- Editor ↔ terminal resize handle -->
          <div
            v-if="isDesktop && panels.editorOpen && panels.terminalOpen"
            class="relative h-px bg-border shrink-0"
          >
            <div
              class="absolute inset-x-0 -top-1 -bottom-1 z-10 cursor-row-resize hover:bg-foreground/20 transition-colors"
              @pointerdown="editorTermResize.onPointerDown"
              @dblclick="editorTermResize.onDblClick"
            />
          </div>

          <!-- Terminal pane -->
          <div
            v-if="terminalEverOpened"
            v-show="panels.terminalOpen"
            class="overflow-hidden flex-1 min-h-0"
            :class="{ 'hidden': !isDesktop && activeTab !== 'terminal' }"
          >
            <TerminalPane
              :session-name="props.id"
              :active="(isDesktop && panels.terminalOpen) || (!isDesktop && activeTab === 'terminal')"
            />
          </div>
        </div>

        <!-- Work ↔ display resize handle (desktop only) -->
        <div
          v-if="isDesktop && workVisible && panels.displayOpen"
          class="relative w-px bg-border shrink-0"
        >
          <div
            class="absolute inset-y-0 -left-1 -right-1 z-10 cursor-col-resize hover:bg-primary/25 transition-colors"
            @pointerdown="workDisplayResize.onPointerDown"
            @dblclick="workDisplayResize.onDblClick"
          />
        </div>

        <!-- Display pane: full-height column on the right -->
        <div
          v-if="displayEverOpened"
          v-show="panels.displayOpen"
          class="overflow-hidden flex flex-col min-h-0 min-w-0 overscroll-none"
          :class="{ 'hidden': !isDesktop && activeTab !== 'display', 'flex-1': !isDesktop || !workVisible }"
          :style="isDesktop && workVisible ? displayColStyle : undefined"
        >
          <SessionDisplayPanel :session-name="session?.name ?? ''" />
        </div>
      </div>
    </div>
    <ModelSwitcher :session-id="props.id" v-model:open="modelSwitcherOpen" />
    <EffortSwitcher :session-id="props.id" v-model:open="effortSwitcherOpen" />
    <KillConfirmDialog
      :open="killConfirmOpen"
      :session-name="displayName"
      @update:open="killConfirmOpen = $event"
      @confirm="() => { void api.killSession(props.id); killConfirmOpen = false }"
    />
  </div>
</template>
