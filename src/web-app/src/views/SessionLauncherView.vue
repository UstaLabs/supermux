<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue"
import { useRouter } from "vue-router"
import { ArrowUp, ChevronLeft, FolderOpen, Loader2Icon } from "lucide-vue-next"
import { toast } from "vue-sonner"
import { api } from "@/api/client"
import { useWS } from "@/api/ws"
import { useSessions } from "@/stores/sessions"
import { usePendingFirstMessage } from "@/stores/pendingFirstMessage"
import { useLauncherDraft } from "@/stores/launcherDraft"
import { useUploads } from "@/stores/uploads"
import { useIsDesktop } from "@/composables/useIsDesktop"
import { useSortedSessions } from "@/composables/useSortedSessions"
import { formatWorkdir } from "@/lib/format-workdir"
import { chooseDefaultProject } from "@/lib/default-project"
import { orderProjectsByRecency, recentWorkdirs as toRecentWorkdirs } from "@/lib/recent-projects"
import MuxLogo from "@/components/MuxLogo.vue"
import ProjectPathPicker from "@/components/ProjectPathPicker.vue"
import LauncherAgentPicker from "@/components/LauncherAgentPicker.vue"
import LauncherModelPicker from "@/components/LauncherModelPicker.vue"
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
} from "@/components/ai-elements/prompt-input"
import PromptInputActionAddCamera from "@/components/ai-elements/prompt-input/PromptInputActionAddCamera.vue"
import SlashCommandMenu from "@/components/SlashCommandMenu.vue"
import LauncherComposeLock from "@/components/LauncherComposeLock.vue"
import LauncherDraftSync from "@/components/LauncherDraftSync.vue"
import { useLauncherCommands } from "@/composables/useLauncherCommands"
import type { PromptInputMessage } from "@/components/ai-elements/prompt-input"

const router = useRouter()
const ws = useWS()
const sessions = useSessions()
const pending = usePendingFirstMessage()
const uploads = useUploads()
const isDesktop = useIsDesktop()
const sortedSessions = useSortedSessions()

const workdir = ref("~")
const workdirTouched = ref(false)
// Flips once the user starts composing (typing / attaching / recording). After
// that we stop following the recency order so the project can't change under
// them while they compose — see chooseDefaultProject and the watcher below.
const composeStarted = ref(false)
const agent = ref<"claude" | "codex" | "cursor" | "opencode">("claude")
const model = ref("")
const LS_KEY = "cmux:launcher-prefs"

const repoInfo = ref<{ isGitRepo: boolean; eligible: boolean; repoRoot?: string; currentBranch?: string; branches?: { local: string[]; remote: string[] } } | null>(null)
const useWorktree = ref(true)
const baseBranch = ref("")

const launcherDraft = useLauncherDraft()
if (launcherDraft.state.workdir) {
  workdir.value = launcherDraft.state.workdir
  workdirTouched.value = true
}
useWorktree.value = launcherDraft.state.useWorktree
baseBranch.value = launcherDraft.state.baseBranch

// True once refreshRepoInfo has resolved at least once. Gates the "default to the
// repo's current branch" reset so a restored draft's baseBranch survives the first,
// restore-triggered call — later calls (the user picking a different project) still
// reset it, matching today's behavior for a fresh pick.
let repoInfoInitialized = false
async function refreshRepoInfo(p: string) {
  try {
    const validation = await api.validatePath(p)
    if (!validation.ok || !validation.path) { repoInfo.value = null; return }
    repoInfo.value = await api.getRepoInfo(validation.path)
    if (repoInfoInitialized) {
      baseBranch.value = repoInfo.value?.currentBranch ?? ""
    } else {
      repoInfoInitialized = true
      if (!baseBranch.value) baseBranch.value = repoInfo.value?.currentBranch ?? ""
    }
  } catch { repoInfo.value = null }
}
watch(workdir, (p) => { if (p?.trim()) void refreshRepoInfo(p) }, { immediate: true })

// Fetch origin (once per repo) when the worktree picker opens, so the branch
// list reflects what's been pushed since the last local fetch.
const worktreeFetching = ref(false)
const fetchedRepos = new Set<string>()
async function onWorktreeRefresh() {
  const root = repoInfo.value?.repoRoot
  const p = workdir.value?.trim()
  if (!root || !p || fetchedRepos.has(root)) return
  worktreeFetching.value = true
  try {
    const validation = await api.validatePath(p)
    if (validation.ok && validation.path) {
      const fresh = await api.getRepoInfo(validation.path, { fetch: true })
      repoInfo.value = fresh
      if (!baseBranch.value) baseBranch.value = fresh.currentBranch ?? ""
      fetchedRepos.add(root)
    }
  } catch { /* keep existing branches */ } finally {
    worktreeFetching.value = false
  }
}

interface LauncherPrefs {
  agent: "claude" | "codex" | "cursor" | "opencode"
  models: Partial<Record<"claude" | "codex" | "cursor" | "opencode", string>>
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
    const existing = loadPrefs() ?? { models: {} }
    const merged: LauncherPrefs = {
      agent: agent.value,
      models: { ...existing.models, [agent.value]: model.value },
    }
    localStorage.setItem(LS_KEY, JSON.stringify(merged))
  } catch {}
}

const AGENTS = ["claude", "codex", "cursor", "opencode"] as const
const prefs = loadPrefs()
if (prefs) {
  agent.value = AGENTS.includes(prefs.agent as typeof AGENTS[number]) ? prefs.agent : "claude"
  model.value = prefs.models[prefs.agent] ?? ""
}

watch([agent, model], () => {
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
const recentWorkdirs = computed(() => toRecentWorkdirs(sortedSessions.value))
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
    const result = await api.createSession({
      workdir: validation.path,
      agent: agent.value,
      model: model.value || undefined,
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
          :max-file-size="25 * 1024 * 1024"
          :global-drop="isDesktop"
          :initial-input="launcherDraft.state.text"
          @submit="onPromptSubmit"
        >
          <LauncherComposeLock @engaged="composeStarted = true" />
          <LauncherDraftSync />
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
            />
          </PromptInputBody>
          <PromptInputFooter>
            <PromptInputTools>
              <LauncherAgentPicker v-model:agent="agent" />
              <LauncherModelPicker v-model:model="model" :agent="agent" />
              <PromptInputActionMenu>
                <PromptInputActionMenuTrigger />
                <PromptInputActionMenuContent>
                  <PromptInputActionAddAttachments label="Files" />
                  <PromptInputActionAddCamera />
                </PromptInputActionMenuContent>
              </PromptInputActionMenu>
              <MicButton
                :disabled="ws.status !== 'connected' || hasPendingUploads || isRecording || submitting"
                @start="startRecording"
              />
            </PromptInputTools>
            <PromptInputTools class="ml-auto">
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
              <span v-else-if="submitting">Creating session…</span>
            </span>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
