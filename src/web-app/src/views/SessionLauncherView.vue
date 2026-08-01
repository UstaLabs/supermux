<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue"
import { useRouter, useRoute } from "vue-router"
import { ArrowUp, ChevronLeft, FolderOpen, Loader2Icon } from "lucide-vue-next"
import { toast } from "vue-sonner"
import { api } from "@/api/client"
import { useWS } from "@/api/ws"
import { useSessions } from "@/stores/sessions"
import { usePendingFirstMessage } from "@/stores/pendingFirstMessage"
import { useLauncherDraft } from "@/stores/launcherDraft"
import { useUploads } from "@/stores/uploads"
import { useUploader } from "@/composables/useUploader"
import { useIsDesktop } from "@/composables/useIsDesktop"
import { useSessionsByRecency } from "@/composables/useSortedSessions"
import { formatWorkdir } from "@/lib/format-workdir"
import { chooseDefaultProject } from "@/lib/default-project"
import { orderProjectsByRecency, recentWorkdirs as toRecentWorkdirs } from "@/lib/recent-projects"
import MuxLogo from "@/components/MuxLogo.vue"
import ProjectPathPicker from "@/components/ProjectPathPicker.vue"
import LauncherAgentPicker from "@/components/LauncherAgentPicker.vue"
import LauncherModelPicker from "@/components/LauncherModelPicker.vue"
import LauncherEffortPicker from "@/components/LauncherEffortPicker.vue"
import LauncherWorktreePicker from "@/components/LauncherWorktreePicker.vue"
import MicButton from "@/components/voice/MicButton.vue"
import VoiceRecorder from "@/components/voice/VoiceRecorder.vue"
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
  PromptInputSaveDraft,
} from "@/components/ai-elements/prompt-input"
import PromptInputActionAddCamera from "@/components/ai-elements/prompt-input/PromptInputActionAddCamera.vue"
import PromptInputActionAddRecordVideo from "@/components/ai-elements/prompt-input/PromptInputActionAddRecordVideo.vue"
import SlashCommandMenu from "@/components/SlashCommandMenu.vue"
import LauncherComposeLock from "@/components/LauncherComposeLock.vue"
import LauncherDraftSync from "@/components/LauncherDraftSync.vue"
import LauncherDraftAttachments from "@/components/LauncherDraftAttachments.vue"
import { useLauncherCommands } from "@/composables/useLauncherCommands"
import type { AttachmentFile, PromptInputMessage } from "@/components/ai-elements/prompt-input"

const router = useRouter()
const route = useRoute()
const ws = useWS()
const sessions = useSessions()
const pending = usePendingFirstMessage()
const uploads = useUploads()
const uploader = useUploader()
const isDesktop = useIsDesktop()
// Launcher defaults to the most-recently-active project (message recency).
// Session list order is separate and must not reshuffle on new messages.
const sessionsByRecency = useSessionsByRecency()

const workdir = ref("~")
const workdirTouched = ref(false)
// Flips once the user starts composing (typing / attaching / recording). After
// that we stop following the recency order so the project can't change under
// them while they compose — see chooseDefaultProject and the watcher below.
const composeStarted = ref(false)
const agent = ref<"claude" | "codex" | "cursor" | "opencode" | "grok">("claude")
const model = ref("")
const reasoningLevel = ref("")
const LS_KEY = "cmux:launcher-prefs"

const repoInfo = ref<{ isGitRepo: boolean; eligible: boolean; repoRoot?: string; currentBranch?: string; branches?: { local: string[]; remote: string[] } } | null>(null)
const useWorktree = ref(true)
const baseBranch = ref("")

// When the launcher is opened from a draft (`/new?draft=<id>`), this holds the
// draft's session id so onPromptSubmit can discard it before spawning the real
// session. Null for a normal new-session launch.
const activeDraftId = ref<string | null>(null)
// Attachments from a reopened draft — handed to <LauncherDraftAttachments>
// (inside PromptInput) so they rehydrate after the composer mounts.
const draftAttachments = ref<Array<{ file_id: string; name?: string; mime?: string; size?: number }>>([])

const launcherDraft = useLauncherDraft()
if (launcherDraft.state.workdir) {
  workdir.value = launcherDraft.state.workdir
  workdirTouched.value = true
}
useWorktree.value = launcherDraft.state.useWorktree
baseBranch.value = launcherDraft.state.baseBranch

// Prefill the launcher from a reopened draft (`/new?draft=<id>`). This MUST run
// synchronously in setup (not onMounted): the child PromptInput reads
// `:initial-input="launcherDraft.state.text"` once during ITS setup, which fires
// before the parent's onMounted — so setText must land here to be picked up.
// Runs after the launcherDraft-based workdir restore above so the draft's values
// win. The draft session is expected to already be in the store from the list
// view; a hard navigation before the store hydrates makes prefill a best-effort
// no-op.
{
  const draftId = route.query.draft
  if (typeof draftId === "string") {
    const s = sessions.list.find((x) => x.id === draftId)
    if (s) {
      activeDraftId.value = draftId
      workdir.value = s.workdir
      workdirTouched.value = true
      if (s.agent) agent.value = s.agent as typeof agent.value
      if (s.model) model.value = s.model
      if (s.reasoningLevel) reasoningLevel.value = s.reasoningLevel
      // Restore composer text + durable attachment refs. Attachments were
      // uploaded when the draft was saved, so file_ids are real server ids
      // that <LauncherDraftAttachments> rehydrates into the composer.
      launcherDraft.setText(s.draftPayload?.text ?? "")
      const atts = s.draftPayload?.attachments
      if (Array.isArray(atts) && atts.length) {
        draftAttachments.value = atts
          .filter((a): a is { file_id: string; name?: string; mime?: string; size?: number } =>
            !!a && typeof (a as { file_id?: unknown }).file_id === "string")
          .map((a) => ({
            file_id: a.file_id,
            name: a.name,
            mime: a.mime,
            size: typeof (a as { size?: unknown }).size === "number" ? (a as { size: number }).size : undefined,
          }))
      }
    }
  }
}

// True once refreshRepoInfo has resolved at least once. Gates the "default to the
// repo's current branch" reset so a restored draft's baseBranch survives the first,
// restore-triggered call — later calls (the user picking a different project) still
// reset it, matching today's behavior for a fresh pick.
let repoInfoInitialized = false
// Monotonic generation so a slow getRepoInfo for a previous workdir can't overwrite
// a newer pick (recency hydration / rapid project switches race without this).
let repoInfoSeq = 0
async function refreshRepoInfo(p: string) {
  const seq = ++repoInfoSeq
  try {
    const validation = await api.validatePath(p)
    if (seq !== repoInfoSeq) return
    if (!validation.ok || !validation.path) { repoInfo.value = null; return }
    const info = await api.getRepoInfo(validation.path)
    if (seq !== repoInfoSeq) return
    repoInfo.value = info
    if (repoInfoInitialized) {
      baseBranch.value = info?.currentBranch ?? ""
    } else {
      repoInfoInitialized = true
      if (!baseBranch.value) baseBranch.value = info?.currentBranch ?? ""
    }
  } catch {
    if (seq !== repoInfoSeq) return
    repoInfo.value = null
  }
}
watch(workdir, (p) => { if (p?.trim()) void refreshRepoInfo(p) }, { immediate: true })

// Re-list local + remote-tracking branches whenever the worktree picker opens.
// Network `git fetch` is once per repo (fetchedRepos) so reopening stays cheap,
// but we always re-read refs so newly created local branches show up.
const worktreeFetching = ref(false)
const fetchedRepos = new Set<string>()
async function onWorktreeRefresh() {
  const p = workdir.value?.trim()
  if (!p) return
  const root = repoInfo.value?.repoRoot
  const shouldFetch = !!root && !fetchedRepos.has(root)
  const seq = ++repoInfoSeq
  worktreeFetching.value = shouldFetch
  try {
    const validation = await api.validatePath(p)
    if (seq !== repoInfoSeq) return
    if (validation.ok && validation.path) {
      const fresh = await api.getRepoInfo(validation.path, { fetch: shouldFetch })
      if (seq !== repoInfoSeq) return
      repoInfo.value = fresh
      if (!baseBranch.value) baseBranch.value = fresh.currentBranch ?? ""
      if (shouldFetch && fresh.repoRoot) fetchedRepos.add(fresh.repoRoot)
    }
  } catch { /* keep existing branches */ } finally {
    if (seq === repoInfoSeq) worktreeFetching.value = false
  }
}

interface LauncherPrefs {
  agent: "claude" | "codex" | "cursor" | "opencode" | "grok"
  models: Partial<Record<"claude" | "codex" | "cursor" | "opencode" | "grok", string>>
  reasoningLevels?: Partial<Record<"claude" | "codex" | "cursor" | "opencode" | "grok", string>>
}

function loadPrefs(): LauncherPrefs | null {
  try {
    const raw = localStorage.getItem(LS_KEY)
    if (!raw) return null
    return JSON.parse(raw) as LauncherPrefs
  } catch {
    return null
  }
}

function savePrefs() {
  try {
    const existing = loadPrefs() ?? { models: {}, reasoningLevels: {} }
    const merged: LauncherPrefs = {
      agent: agent.value,
      models: { ...existing.models, [agent.value]: model.value },
      reasoningLevels: { ...existing.reasoningLevels, [agent.value]: reasoningLevel.value },
    }
    localStorage.setItem(LS_KEY, JSON.stringify(merged))
  } catch {}
}

const AGENTS = ["claude", "codex", "cursor", "opencode", "grok"] as const
const prefs = loadPrefs()
if (prefs) {
  agent.value = AGENTS.includes(prefs.agent as typeof AGENTS[number]) ? prefs.agent : "claude"
  model.value = prefs.models[prefs.agent] ?? ""
  reasoningLevel.value = prefs.reasoningLevels?.[agent.value] ?? ""
}

// Restore the sticky thinking level for whichever agent is now selected (and
// clear it for agents that store none), so the picker resolves against the
// right agent and a submit never carries a stale level across an agent switch.
watch(agent, (a) => {
  reasoningLevel.value = loadPrefs()?.reasoningLevels?.[a] ?? ""
})

watch([agent, model, reasoningLevel], () => {
  savePrefs()
})

watch([workdir, workdirTouched, useWorktree, baseBranch], () => {
  if (workdirTouched.value) {
    launcherDraft.setWorkdir(workdir.value)
    launcherDraft.setWorktree(useWorktree.value)
    launcherDraft.setBaseBranch(baseBranch.value)
  }
})

const submitting = ref(false)
const projects = ref<{ path: string }[]>([])
const isRecording = ref(false)
const pageEl = ref<HTMLElement | null>(null)

// Distinct project paths from the user's sessions, most-recently-active first
// (recency comes from each session's latest message timestamp), then the
// picker list of recently-used projects ahead of any other known projects.
const recentWorkdirs = computed(() => toRecentWorkdirs(sessionsByRecency.value))
const orderedProjects = computed(() => orderProjectsByRecency(recentWorkdirs.value, projects.value))
const { commands: launcherCommands, loading: launcherCommandsLoading } = useLauncherCommands(agent, workdir)

// Follow the most-recently-used project as session/message data hydrates, but
// stop once the user engages (picks a path or starts composing) so a late
// recency reshuffle can't swap the project out from under them.
watch(recentWorkdirs, (recent) => {
  workdir.value = chooseDefaultProject({
    current: workdir.value,
    recent,
    picked: workdirTouched.value,
    composing: composeStarted.value,
  })
}, { immediate: true })

function onPickWorkdir(path: string) {
  workdirTouched.value = true
  workdir.value = path
}

// Focus the prompt as soon as the page opens so the user can just start typing.
// (On installed iOS PWAs WebKit may withhold the on-screen keyboard until a tap
// because focus() runs outside the navigation gesture — the field is still
// focused, so the next keystroke/tap lands in it.)
onMounted(async () => {
  await nextTick()
  if (isRecording.value) return
  pageEl.value?.querySelector<HTMLTextAreaElement>('textarea[name="message"]')?.focus()
})

const hasPendingUploads = computed(() =>
  Object.values(uploads.byId).some((s) => s.status === "uploading" || s.status === "failed"),
)

const canSubmit = computed(() => {
  if (submitting.value || ws.status !== "connected") return false
  if (!workdir.value.trim()) return false
  return true
})

const workdirLabel = computed(() => formatWorkdir(workdir.value, sessions.homeDir))

async function loadProjects() {
  try {
    const result = await api.listProjects()
    projects.value = result.projects ?? []
  } catch {
    projects.value = []
  }
}

watch(() => sessions.homeDir, loadProjects, { immediate: true })

function startRecording() {
  composeStarted.value = true
  isRecording.value = true
}

function onRecordingDone() {
  isRecording.value = false
}

function onPromptError(err: { code: string; message: string }) {
  if (err.code === "max_file_size") {
    toast.error("File too large", { description: "Attachments must be 500 MB or smaller." })
    return
  }
  if (err.code === "max_files") {
    toast.error("Too many files", { description: err.message })
    return
  }
  if (err.code === "accept") {
    toast.error("Unsupported file", { description: err.message })
    return
  }
  toast.error(err.message)
}

async function onPromptSubmit(payload: PromptInputMessage) {
  const text = payload?.text?.trim() ?? ""
  const hasFiles = (payload?.files?.length ?? 0) > 0
  if (!text && !hasFiles) {
    toast.error("Enter a message or attach a file")
    return
  }

  const w = workdir.value.trim()
  if (!w) {
    toast.error("Select a project path")
    return
  }

  submitting.value = true
  try {
    const validation = await api.validatePath(w)
    if (!validation.ok || !validation.path) {
      toast.error(validation.error ?? "Invalid working directory")
      return
    }
    // Starting a reopened draft: the backend hard-deletes a draft on send and
    // spawns a brand-new session under a different id, so we can't send to the
    // draft directly. Instead discard the draft first (freeing its name), then
    // fall through to the normal createSession + pending + navigate flow.
    if (activeDraftId.value) {
      try { await api.killSession(activeDraftId.value) } catch { /* draft may already be gone; proceed */ }
      activeDraftId.value = null
    }
    const result = await api.createSession({
      workdir: validation.path,
      agent: agent.value,
      model: model.value || undefined,
      reasoningLevel: reasoningLevel.value || undefined,
      worktree: repoInfo.value?.eligible ? useWorktree.value : false,
      baseBranch: repoInfo.value?.eligible && useWorktree.value ? (baseBranch.value || undefined) : undefined,
    })
    sessions.add({
      id: result.id,
      name: result.name,
      workdir: result.workdir,
      mute: false,
      connected: true,
      agent: result.agent,
      model: result.model,
      reasoningLevel: result.reasoningLevel,
    })
    launcherDraft.clear()
    pending.set(result.id, payload)
    await router.push(`/s/${result.id}`)
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : String(err)
    toast.error(msg || "Failed to create session")
  } finally {
    submitting.value = false
  }
}

// Persist the current composer content as a DRAFT (no agent spawned) instead of
// starting a session. Mirrors onPromptSubmit's workdir validation, but POSTs
// userStatus:"draft" with the composer text + attachment metadata, then returns
// to the list. `payload` is the current composer content emitted by
// PromptInputSaveDraft (same PromptInputMessage shape @submit receives).
async function saveAsDraft(payload: PromptInputMessage) {
  const text = payload?.text?.trim() ?? ""
  const hasFiles = (payload?.files?.length ?? 0) > 0
  if (!text && !hasFiles) {
    toast.error("Enter a message or attach a file")
    return
  }

  const w = workdir.value.trim()
  if (!w) {
    toast.error("Select a project path")
    return
  }

  submitting.value = true
  try {
    const validation = await api.validatePath(w)
    if (!validation.ok || !validation.path) {
      toast.error(validation.error ?? "Invalid working directory")
      return
    }
    // Upload composer files so draft_payload holds durable server file_ids
    // (not ephemeral local composer ids). Reuses already-uploaded results when
    // re-saving a restored draft.
    const files = (payload?.files ?? []) as AttachmentFile[]
    const attachments: Array<{ file_id: string; name?: string; mime?: string; size?: number }> = []
    for (const f of files) {
      const kindHint = (f.file as { _cmuxKind?: "photo" | "document" | "voice" | "audio" | "video" | "video_note" } | undefined)?._cmuxKind
      const current = uploads.get(f.id)
      if (current?.status === "uploaded") {
        attachments.push({
          file_id: current.result.file_id,
          name: current.result.name ?? f.filename,
          mime: current.result.mime ?? f.mediaType,
          size: current.result.size,
        })
        continue
      }
      if (!f.file) {
        toast.error("Attachment missing", { description: f.filename ?? "file" })
        return
      }
      uploads.start(f.id)
      const fileName = f.file.name || f.filename || "file"
      const uploadingToastId = toast.loading(`Uploading ${fileName}…`)
      try {
        // Session id is metadata on the attachment row; the draft row is
        // created after uploads complete. Use a stable provisional label.
        const result = await uploader.upload(
          activeDraftId.value ?? "draft",
          f.file,
          kindHint,
          (sent, total) => { uploads.setProgress(f.id, total > 0 ? sent / total : 0) },
        )
        toast.dismiss(uploadingToastId)
        uploads.succeed(f.id, result)
        attachments.push({
          file_id: result.file_id,
          name: result.name,
          mime: result.mime,
          size: result.size,
        })
      } catch (err: unknown) {
        toast.dismiss(uploadingToastId)
        const msg = err instanceof Error ? err.message : String(err)
        uploads.fail(f.id, msg)
        toast.error("Upload failed", { description: `${fileName}: ${msg}` })
        return
      }
    }
    const draftPayload = { text: payload?.text ?? "", attachments }
    // Re-saving a reopened draft replaces it: there's no update endpoint, so
    // discard the original before creating the replacement (mirrors
    // onPromptSubmit) — otherwise a second draft is created alongside it.
    if (activeDraftId.value) {
      try { await api.killSession(activeDraftId.value) } catch { /* already gone; proceed */ }
      activeDraftId.value = null
    }
    const result = await api.createSession({
      workdir: validation.path,
      agent: agent.value,
      model: model.value || undefined,
      reasoningLevel: reasoningLevel.value || undefined,
      userStatus: "draft",
      draftPayload,
    })
    sessions.add({
      id: result.id,
      name: result.name,
      workdir: result.workdir,
      mute: false,
      connected: false,
      agent: result.agent,
      userStatus: "draft",
      draftPayload,
    })
    launcherDraft.clear()
    uploads.clearAll()
    await router.push("/")
  } catch (err: unknown) {
    const msg = err instanceof Error ? err.message : String(err)
    toast.error(msg || "Failed to save draft")
  } finally {
    submitting.value = false
  }
}

function goBack() {
  if (isDesktop.value) return
  void router.push("/")
}
</script>

<template>
  <div class="flex h-full min-h-0 flex-col bg-[var(--cmux-workspace)] text-foreground">
    <header
      v-if="!isDesktop"
      class="flex items-center gap-2 px-3 py-3 border-b border-border bg-[var(--cmux-header)] shrink-0"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.5rem)"
    >
      <button type="button" class="cmux-icon-button" aria-label="Back" @click="goBack">
        <ChevronLeft class="size-5" />
      </button>
      <h1 class="text-base font-semibold">New session</h1>
    </header>

    <div class="flex-1 overflow-y-auto flex flex-col">
      <div
        ref="pageEl"
        class="mx-auto w-full max-w-2xl flex-1 flex flex-col justify-center px-4 py-8 md:py-12 gap-7 min-h-0"
      >
        <div class="flex flex-col items-center gap-4 text-center">
          <MuxLogo class="size-9 text-foreground/85" />
          <div class="flex max-w-full flex-col items-center gap-0.5">
            <h2 class="text-3xl font-semibold tracking-tight text-foreground md:text-4xl">
              Let's build
            </h2>
            <ProjectPathPicker
              :model-value="workdir"
              variant="heading"
              :projects="orderedProjects"
              :home-dir="sessions.homeDir"
              @update:model-value="onPickWorkdir"
            />
          </div>
          <LauncherWorktreePicker
            v-if="repoInfo?.eligible"
            v-model:use-worktree="useWorktree"
            v-model:base-branch="baseBranch"
            :branches="repoInfo.branches"
            :current-branch="repoInfo.currentBranch"
            :loading="worktreeFetching"
            @refresh="onWorktreeRefresh"
          />
        </div>

        <PromptInput
          class="relative"
          group-class="rounded-2xl border-border/70 bg-card dark:bg-card shadow-lg shadow-black/[0.04] dark:shadow-black/30"
          :max-files="10"
          :max-file-size="500 * 1024 * 1024"
          :global-drop="isDesktop"
          :initial-input="launcherDraft.state.text"
          @submit="onPromptSubmit"
          @error="onPromptError"
        >
          <LauncherComposeLock @engaged="composeStarted = true" />
          <LauncherDraftSync />
          <LauncherDraftAttachments :attachments="draftAttachments" />
          <SlashCommandMenu
            :commands="launcherCommands"
            :loading="launcherCommandsLoading"
          />
          <PromptInputHeader>
            <PromptInputAttachments />
          </PromptInputHeader>
          <PromptInputBody>
            <VoiceRecorder v-if="isRecording" @done="onRecordingDone" />
            <PromptInputTextarea
              v-else
              placeholder="What should the agent do?"
              class="min-h-[7rem] md:min-h-[8.5rem] text-[15px]"
              :enter-sends="isDesktop"
            />
          </PromptInputBody>
          <PromptInputFooter>
            <PromptInputTools>
              <LauncherAgentPicker v-model:agent="agent" />
              <LauncherModelPicker v-model:model="model" :agent="agent" />
              <LauncherEffortPicker v-model:level="reasoningLevel" :agent="agent" :model="model" />
              <PromptInputActionMenu>
                <PromptInputActionMenuTrigger />
                <PromptInputActionMenuContent>
                  <PromptInputActionAddAttachments label="Files" />
                  <PromptInputActionAddCamera />
                  <PromptInputActionAddRecordVideo />
                </PromptInputActionMenuContent>
              </PromptInputActionMenu>
              <MicButton
                :disabled="ws.status !== 'connected' || hasPendingUploads || isRecording || submitting"
                @start="startRecording"
              />
            </PromptInputTools>
            <PromptInputTools class="ml-auto">
              <PromptInputSaveDraft
                :disabled="!canSubmit || hasPendingUploads || isRecording || submitting"
                @save-draft="saveAsDraft"
              />
              <PromptInputSubmit
                :disabled="!canSubmit || hasPendingUploads || isRecording"
                class="rounded-full size-8 bg-primary text-primary-foreground hover:bg-primary/90 disabled:bg-muted disabled:text-muted-foreground disabled:opacity-100"
              >
                <ArrowUp class="size-4" />
              </PromptInputSubmit>
            </PromptInputTools>
          </PromptInputFooter>
        </PromptInput>

        <div class="flex items-center justify-center gap-2 px-1 text-xs text-muted-foreground">
          <span class="inline-flex min-w-0 items-center gap-1.5 font-mono">
            <FolderOpen class="size-3.5 shrink-0 opacity-70" />
            <span class="truncate">{{ workdirLabel }}</span>
          </span>
          <template v-if="ws.status !== 'connected' || submitting">
            <span class="opacity-40">·</span>
            <span class="inline-flex items-center gap-1.5">
              <Loader2Icon v-if="submitting" class="size-3 animate-spin" />
              <span v-if="ws.status !== 'connected'">Connecting…</span>
              <span v-else-if="submitting">Saving…</span>
            </span>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
