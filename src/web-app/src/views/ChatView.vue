<script setup lang="ts">
defineOptions({ name: "ChatView" })

import { computed, ref, provide, onMounted, onBeforeUnmount, nextTick, watch } from "vue"
import { useRouter } from "vue-router"
import { ChevronLeft, GitMerge } from "@lucide/vue"
import { AlertTriangleIcon, Loader2Icon, SendHorizonalIcon, SquareIcon } from "lucide-vue-next"
import { useMessages } from "@/stores/messages"
import { useWS } from "@/api/ws"
import { useSessions } from "@/stores/sessions"
import { useFinishJob } from "@/stores/finishJob"
import FinishSheet from "@/components/FinishSheet.vue"
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
import SessionLinks from "@/components/SessionLinks.vue"
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
import TerminalPanel from "@/components/TerminalPanel.vue"
import EditorPane from "@/components/editor/EditorPane.vue"
import SessionDisplayPanel from "@/components/SessionDisplayPanel.vue"
import TerminalPane from "@/components/TerminalPane.vue"
import AgentViewToggle from "@/components/AgentViewToggle.vue"
import PaneSwitcher from "@/components/PaneSwitcher.vue"

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

// "Working…" timer: counts continuously from workingSince — through tool
// transitions — until working becomes false (the agent goes idle or dead).
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

// Finish: opens the FinishSheet (durable job runs in the broker). The header
// button reflects the job state — running stage label, or a done/failed badge.
const finishSheetOpen = ref(false)
const finishJob = useFinishJob()
const fjob = computed(() => finishJob.bySession[props.id])
const finishRunning = computed(() => fjob.value?.status === "running")
const finishUnacked = computed(() => finishJob.isUnacked(props.id))
const finishBadge = computed(() => finishUnacked.value ? (fjob.value?.status === "failed" ? "failed" : "done") : null)
const finishLabel = computed(() => finishRunning.value ? (fjob.value?.stage || "Finishing…") : "Finish")
const layout = useLayout()
const panels = computed(() => layout.panelsFor(props.id))
const activeTab = computed({
  get: () => panels.value.activeTab,
  set: (tab) => { panels.value.activeTab = tab },
})
const isClaude = computed(() => session.value?.agent === "claude")
const mainView = computed<"chat" | "terminal">({
  get: () => (isClaude.value && !isArchived.value ? panels.value.mainView : "chat"),
  set: (v) => { if (isClaude.value) panels.value.mainView = v },
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
  <!-- Height tracks the VISUAL viewport (--vvh), not 100dvh, so the shell shrinks
       above the on-screen keyboard instead of letting the terminal / composer slide
       under it. Falls back to 100dvh before --vvh is set / on old browsers. -->
  <div class="flex flex-col bg-[var(--cmux-chat)] text-foreground" style="height: var(--vvh, 100dvh)">
    <header
      class="flex items-center gap-3 px-3 py-1.5 min-h-[3.5rem] border-b border-border sticky top-0 bg-[var(--cmux-header)]/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.5rem)"
    >
      <router-link v-if="!isDesktop" :to="isArchived ? '/archived' : '/'" class="text-muted-foreground hover:text-foreground transition -ml-1 p-1" aria-label="Back">
        <ChevronLeft class="size-5" />
      </router-link>
      <div class="min-w-0 flex-1 leading-tight">
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
      <SessionLinks v-if="!isArchived && session?.name" :session-name="session?.name ?? ''" />
      <button
        v-if="isArchived"
        class="shrink-0 px-3 py-1.5 text-xs font-medium rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition disabled:opacity-50"
        :disabled="resuming"
        @click="handleResume"
      >
        {{ resuming ? "…" : "Resume" }}
      </button>
      <PaneSwitcher
        v-if="isDesktop && !isArchived"
        :session-id="props.id"
        mode="header-cluster"
      />
      <button
        v-if="!isArchived && session?.session_branch"
        type="button"
        aria-label="Finish: sync, verify, and merge into the base branch"
        class="relative inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-[12px] font-medium bg-emerald-600 text-white hover:bg-emerald-500 transition-colors"
        @click="finishSheetOpen = true"
      >
        <Loader2Icon v-if="finishRunning" class="size-3.5 animate-spin" />
        <GitMerge v-else class="size-3.5" />
        <span :class="{ 'hidden sm:inline': !finishRunning }">{{ finishLabel }}</span>
        <span
          v-if="finishBadge"
          aria-hidden="true"
          class="absolute -top-0.5 -right-0.5 size-2 rounded-full ring-2 ring-[var(--cmux-header)]"
          :class="finishBadge === 'failed' ? 'bg-red-500' : 'bg-emerald-400'"
        />
      </button>
    </header>

    <div v-if="isArchived" class="px-4 py-2 text-xs text-muted-foreground bg-muted/30 border-b border-border text-center">
      Archived · Resume to continue
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
        <template v-if="mainView === 'chat'">
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
                v-if="!isArchived && liveState.state === 'dead'"
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
                v-else-if="!isArchived && agentState.isSending(props.id)"
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
                v-else-if="!isArchived && liveState.working"
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

        <div v-if="isClaude && !isArchived" class="flex justify-center px-3 pt-2 bg-[var(--cmux-chat)]">
          <AgentViewToggle :session-id="props.id" />
        </div>
        <div
          v-if="!isArchived"
          class="px-3 pt-3 bg-[var(--cmux-chat)]"
          :style="{ paddingBottom: isDesktop ? 'calc(env(safe-area-inset-bottom, 0px) + 0.5rem)' : '0.5rem' }"
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
              <VoiceRecorder v-if="isRecording" :session-id="props.id" @done="onRecordingDone" />
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
        </template>
        <template v-else>
          <!-- flex-1 + min-h-0 guarantees the pane gets a real height inside the
               flex column so xterm's FitAddon can measure it. -->
          <div class="flex-1 min-h-0 relative">
            <TerminalPane
              :key="`agent:${props.id}`"
              :session-name="props.id"
              terminal-id="agent"
              kind="agent"
              :active="mainView === 'terminal'"
              @exit="mainView = 'chat'"
            />
          </div>
          <div
            v-if="isClaude"
            class="flex justify-center px-3 py-2 bg-[var(--cmux-chat)] border-t border-border"
            :style="{ paddingBottom: isDesktop ? 'calc(env(safe-area-inset-bottom, 0px) + 0.5rem)' : '0.5rem' }"
          >
            <AgentViewToggle :session-id="props.id" />
          </div>
        </template>
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
            <TerminalPanel
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
    <PaneSwitcher
      v-if="!isDesktop && !isArchived"
      :session-id="props.id"
      mode="bottom-bar"
    />
    <ModelSwitcher :session-id="props.id" v-model:open="modelSwitcherOpen" />
    <EffortSwitcher :session-id="props.id" v-model:open="effortSwitcherOpen" />
    <FinishSheet v-model:open="finishSheetOpen" :session-id="props.id" :branch="session?.session_branch" />
    <KillConfirmDialog
      :open="killConfirmOpen"
      :session-name="displayName"
      @update:open="killConfirmOpen = $event"
      @confirm="() => { void api.killSession(props.id); killConfirmOpen = false }"
    />
  </div>
</template>
