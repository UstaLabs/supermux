<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { ArrowUp, ArrowDown, RefreshCw, UploadCloud, GitBranch, Loader2Icon } from "lucide-vue-next"
import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import BranchPickerPopover from "@/components/BranchPickerPopover.vue"
import { useGitRemote, type GitActionResult } from "@/stores/gitRemote"
import { api, type GitPullResult, type GitSwitchResult } from "@/api/client"
import { toast } from "vue-sonner"

const props = defineProps<{ sessionId: string; workdir?: string; workdirLabel?: string }>()

const git = useGitRemote()
const syncOpen = ref(false)
const sending = ref(false)

const status = computed(() => git.statusBySession[props.sessionId])
const busy = computed(() => git.busyBySession[props.sessionId] ?? null)
const result = computed(() => git.resultBySession[props.sessionId] ?? null)

const eligible = computed(() => !!status.value?.isRepo)
const published = computed(() => !!status.value?.upstream)
const ahead = computed(() => status.value?.ahead ?? 0)
const behind = computed(() => status.value?.behind ?? 0)

const label = computed(() =>
  status.value?.branch ?? (status.value?.detachedSha ? `detached @ ${status.value.detachedSha}` : ""))
const showState = computed(() => !!status.value?.hasRemote && !!status.value?.branch)

const stateLabel = computed(() => {
  if (!published.value) return "not published"
  if (ahead.value && behind.value) return `↑${ahead.value} ↓${behind.value}`
  if (ahead.value) return `↑${ahead.value}`
  if (behind.value) return `↓${behind.value}`
  return "✓"
})

const cardTitle = computed(() => {
  switch (result.value?.status) {
    case "conflict": return "Pull conflicts"
    case "dirty": return "Uncommitted changes"
    case "clobber": return "Uncommitted changes in the way"
    case "checked_out_elsewhere": return "Branch in use"
    case "merge_in_progress": return "Merge in progress"
    case "rejected_non_ff": return "Push rejected"
    case "auth_failed": return "Authentication failed"
    default: return "Git error"
  }
})

function loadStatus() { void git.loadStatus(props.sessionId) }
onMounted(loadStatus)
watch(() => props.sessionId, loadStatus)
watch(() => props.workdir, loadStatus)

async function act(op: "publish" | "push" | "pull" | "fetch") {
  syncOpen.value = false
  const r = await git.run(props.sessionId, op)
  if (!r) return
  if (op === "fetch") {
    const fetchResult = r as { ok: boolean; error?: string }
    if (fetchResult.ok) void git.loadBranches(props.sessionId)
    else toast.error(fetchResult.error ?? "Couldn't reach origin")
    return
  }
  const res = r as GitActionResult
  if (res.status === "pushed") toast.success("Pushed to origin")
  else if (res.status === "clean") toast.success("Pulled from origin")
  else if (res.status === "up_to_date") toast.info("Already up to date")
  // Non-success results render in the card (the store captured them).
}

type Sendable = Extract<GitPullResult | GitSwitchResult,
  { status: "conflict" | "dirty" | "clobber" | "merge_in_progress" }>
const sendable = computed(() =>
  result.value?.status === "conflict" || result.value?.status === "dirty"
  || result.value?.status === "clobber" || result.value?.status === "merge_in_progress")

function gitIssueMessage(r: Sendable): string {
  if (r.status === "conflict")
    return `Pulling from the remote hit merge conflicts in:\n${r.files.map((f) => `- ${f}`).join("\n")}\n\nThe worktree is in a conflicted merge state — please resolve the conflicts and commit, then I'll Pull again.`
  if (r.status === "dirty")
    return `Pull is blocked by uncommitted changes in: ${r.files.join(", ")}. Please commit or stash them, then I'll Pull again.`
  if (r.status === "clobber")
    return `Switching to \`${r.branch}\` is blocked — uncommitted changes to these files would be overwritten:\n${r.files.map((f) => `- ${f}`).join("\n")}\n\nPlease commit or stash them, then I'll switch again.`
  return "A merge is in progress in this checkout. Please resolve and commit it (or abort it), then I'll switch branches."
}

async function sendToAgent() {
  const r = result.value
  if (!r || sending.value || !sendable.value) return
  sending.value = true
  try {
    await api.sendMessage(props.sessionId, gitIssueMessage(r as Sendable))
    toast.success("Sent to the agent")
    git.dismiss(props.sessionId)
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to send to agent")
  } finally { sending.value = false }
}

async function pullFromCard() {
  const r = await git.run(props.sessionId, "pull")
  if (!r) return
  const res = r as GitActionResult
  if (res.status === "clean") toast.success("Pulled from origin")
  else if (res.status === "up_to_date") toast.info("Already up to date")
}

const segBtn = "inline-flex items-center gap-1 rounded px-1 py-0.5 transition-colors hover:bg-accent hover:text-foreground"
const itemClass =
  "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent disabled:opacity-40 disabled:pointer-events-none"
</script>

<template>
  <!-- Not a git repo: fall back to the workdir label, as before. -->
  <div v-if="!eligible && props.workdirLabel" class="text-[11px] text-muted-foreground truncate font-mono">
    {{ props.workdirLabel }}
  </div>

  <!-- Split pill: branch name opens the branch picker; sync state opens the sync menu. -->
  <div v-else-if="eligible" class="inline-flex max-w-full items-center -ml-1 text-[11px] font-mono text-muted-foreground">
    <BranchPickerPopover :session-id="props.sessionId">
      <button type="button" :class="segBtn" class="max-w-full" aria-label="Switch branch">
        <GitBranch class="size-3 shrink-0 opacity-70" />
        <span class="truncate max-w-40">{{ label }}</span>
        <Loader2Icon v-if="busy && !showState" class="size-3 shrink-0 animate-spin" />
      </button>
    </BranchPickerPopover>

    <DropdownMenu v-if="showState" v-model:open="syncOpen">
      <DropdownMenuTrigger as-child>
        <button type="button" :class="segBtn" class="shrink-0" aria-label="Branch sync">
          <span class="opacity-80">· {{ stateLabel }}</span>
          <Loader2Icon v-if="busy" class="size-3 shrink-0 animate-spin" />
        </button>
      </DropdownMenuTrigger>

      <DropdownMenuContent align="start" class="w-56 p-1">
        <p v-if="props.workdir" class="px-2 pt-1 pb-1 text-[10px] font-mono uppercase tracking-wide text-muted-foreground truncate">
          {{ props.workdir }}
        </p>

        <button v-if="!published" type="button" :class="itemClass" :disabled="!!busy" @click="act('publish')">
          <UploadCloud class="size-4 shrink-0 opacity-80" />
          <span class="flex-1">Publish branch</span>
          <Loader2Icon v-if="busy === 'publish'" class="size-3.5 animate-spin" />
        </button>

        <template v-else>
          <button type="button" :class="itemClass" :disabled="!!busy || ahead === 0" @click="act('push')">
            <ArrowUp class="size-4 shrink-0 opacity-80" />
            <span class="flex-1">Push</span>
            <span v-if="ahead" class="text-[11px] text-muted-foreground">{{ ahead }}</span>
            <Loader2Icon v-if="busy === 'push'" class="size-3.5 animate-spin" />
          </button>
          <button type="button" :class="itemClass" :disabled="!!busy || behind === 0" @click="act('pull')">
            <ArrowDown class="size-4 shrink-0 opacity-80" />
            <span class="flex-1">Pull</span>
            <span v-if="behind" class="text-[11px] text-muted-foreground">{{ behind }}</span>
            <Loader2Icon v-if="busy === 'pull'" class="size-3.5 animate-spin" />
          </button>
        </template>

        <div class="my-1 border-t border-border" />
        <button type="button" :class="itemClass" :disabled="!!busy" @click="act('fetch')">
          <RefreshCw class="size-4 shrink-0 opacity-80" />
          <span class="flex-1">Fetch</span>
          <Loader2Icon v-if="busy === 'fetch'" class="size-3.5 animate-spin" />
        </button>
      </DropdownMenuContent>
    </DropdownMenu>
  </div>

  <!-- Actionable result card (conflict / dirty / clobber / elsewhere / merge / rejected / auth / error). -->
  <div
    v-if="result"
    class="fixed inset-x-3 bottom-40 z-50 mx-auto max-w-lg rounded-xl border border-border bg-card shadow-xl"
  >
    <div class="flex items-center gap-2 px-4 py-2.5 border-b border-border">
      <span class="text-[13px] font-semibold">{{ cardTitle }}</span>
      <button class="ml-auto text-muted-foreground hover:text-foreground text-lg leading-none" aria-label="Dismiss" @click="git.dismiss(props.sessionId)">&times;</button>
    </div>
    <div class="px-4 py-3 max-h-60 overflow-y-auto text-[12px]">
      <ul v-if="result.status === 'conflict' || result.status === 'dirty' || result.status === 'clobber'" class="font-mono space-y-0.5">
        <li v-for="f in result.files" :key="f" class="truncate text-foreground/80">{{ f }}</li>
      </ul>
      <p v-else-if="result.status === 'checked_out_elsewhere'" class="text-foreground/80">
        That branch is checked out in another worktree:
        <span class="block mt-1 font-mono text-foreground/60 break-all">{{ result.path }}</span>
      </p>
      <p v-else-if="result.status === 'merge_in_progress'" class="text-foreground/80">
        A merge is in progress in this checkout. Resolve and commit it (or abort it) before switching.
      </p>
      <p v-else-if="result.status === 'rejected_non_ff'" class="text-foreground/80">
        <code>origin</code> has commits this branch doesn't. Pull first, then push.
      </p>
      <p v-else-if="result.status === 'auth_failed'" class="text-foreground/80">
        Couldn't authenticate to origin. Set up a credential helper, an SSH key, or run <code>gh auth login</code>.
        <span class="block mt-1 whitespace-pre-wrap break-all font-mono text-foreground/60">{{ result.message }}</span>
      </p>
      <p v-else class="text-foreground/80 whitespace-pre-wrap break-all font-mono">{{ (result as { message?: string }).message }}</p>
    </div>
    <div class="flex items-center justify-end gap-2 px-4 py-2.5 border-t border-border">
      <button type="button" class="text-[12px] px-2.5 py-1 rounded-md border border-border hover:bg-accent text-muted-foreground" @click="git.dismiss(props.sessionId)">Dismiss</button>
      <button
        v-if="result.status === 'rejected_non_ff'"
        type="button"
        class="text-[12px] px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
        :disabled="!!busy"
        @click="pullFromCard"
      >Pull</button>
      <button
        v-if="sendable"
        type="button"
        class="text-[12px] px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
        :disabled="sending"
        @click="sendToAgent"
      >Send to agent</button>
    </div>
  </div>
</template>
