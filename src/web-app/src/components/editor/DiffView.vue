<script setup lang="ts">
import { ref, computed, watch } from "vue"
import { ChevronRight, ChevronDown, X, Plus, MessageSquare } from "@lucide/vue"
import type { RepoDiff } from "@/composables/useEditor"
import { api, type ReviewComment, type RepoRefs } from "@/api/client"
import { toast } from "vue-sonner"

const props = defineProps<{
  repos: RepoDiff[]
  comments?: ReviewComment[]
  sessionId: string
  base: string
  refs: RepoRefs[]
}>()

const emit = defineEmits<{
  close: []
  reload: []
  setBase: [base: string]
}>()

// ── Base selector ───────────────────────────────────────────────────────────
const baseMenuOpen = ref(false)
const baseSubmenu = ref<null | "commit" | "branch">(null)

// refs from the primary (first) repo drive the submenus
const primaryRefs = computed(() => props.refs[0] ?? { repo: "", branches: [], commits: [] })

const baseLabel = computed(() => {
  const b = props.base
  if (b === "head") return "Uncommitted"
  if (b.startsWith("commit:")) return b.slice(7, 14)
  if (b.startsWith("branch:")) return b.slice(7)
  return "Session start"
})

function chooseBase(spec: string) {
  baseMenuOpen.value = false
  baseSubmenu.value = null
  if (spec !== props.base) emit("setBase", spec)
}

const totalFiles = computed(() => props.repos.reduce((n, r) => n + r.files.length, 0))
const multiRepo = computed(() => props.repos.length > 1)

const expandedFiles = ref(new Set<string>())
const expandedRepos = ref(new Set<string>())

// Repos expanded by default; files collapsed by default.
watch(
  () => props.repos,
  (repos) => { expandedRepos.value = new Set(repos.map((r) => r.repo)) },
  { immediate: true },
)

function fileKey(repo: string, path: string): string {
  return `${repo} ${path}`
}

function toggleFile(key: string) {
  if (expandedFiles.value.has(key)) expandedFiles.value.delete(key)
  else expandedFiles.value.add(key)
}

function toggleRepo(repo: string) {
  if (expandedRepos.value.has(repo)) expandedRepos.value.delete(repo)
  else expandedRepos.value.add(repo)
}

function repoLabel(repo: string): string {
  return repo === "" ? "workdir" : repo
}

interface DiffLine {
  type: "add" | "del" | "ctx" | "hunk"
  content: string
  newLine?: number
}

function parseDiffLines(diff: string): DiffLine[] {
  const out: DiffLine[] = []
  let inHunk = false, newLn = 0
  for (const line of diff.split("\n")) {
    if (line.startsWith("@@")) {
      inHunk = true
      const m = line.match(/\+(\d+)/)              // new-side start
      newLn = m ? parseInt(m[1], 10) : 0
      out.push({ type: "hunk", content: line }); continue
    }
    if (!inHunk) continue
    if (line.startsWith("+")) out.push({ type: "add", content: line.slice(1), newLine: newLn++ })
    else if (line.startsWith("-")) out.push({ type: "del", content: line.slice(1) })
    else if (line.startsWith(" ")) out.push({ type: "ctx", content: line.slice(1), newLine: newLn++ })
  }
  return out
}

function diffStats(diff: string): { added: number; deleted: number } {
  let added = 0, deleted = 0
  for (const line of diff.split("\n")) {
    if (line.startsWith("+") && !line.startsWith("+++")) added++
    else if (line.startsWith("-") && !line.startsWith("---")) deleted++
  }
  return { added, deleted }
}

function statusColor(status: string): string {
  if (status === "added") return "text-emerald-400"
  if (status === "deleted") return "text-red-400"
  if (status === "renamed") return "text-blue-400"
  return "text-amber-400"
}

function statusLabel(status: string): string {
  if (status === "added") return "Added"
  if (status === "deleted") return "Deleted"
  if (status === "renamed") return "Renamed"
  return "Modified"
}

// ── Inline comment state ────────────────────────────────────────────────────

// Key: `${repo}||${path}||${newLine}` — tracks which line has the composer open
const composerFor = ref<string | null>(null)
const draft = ref("")
const submitting = ref(false)
const wrap = ref(true)

function composerKey(repo: string, path: string, newLine: number): string {
  return `${repo}||${path}||${newLine}`
}

function openComposer(repo: string, path: string, newLine: number) {
  const key = composerKey(repo, path, newLine)
  if (composerFor.value === key) {
    composerFor.value = null
    draft.value = ""
  } else {
    composerFor.value = key
    draft.value = ""
  }
}

function cancelComposer() {
  composerFor.value = null
  draft.value = ""
}

function commentsFor(repo: string, path: string, newLine: number): ReviewComment[] {
  return (props.comments ?? []).filter(
    (c) => c.repo === repo && c.path === path && (c.currentLine ?? c.anchorLine) === newLine && !(c as any).parentId,
  )
}

async function addComment(repo: string, path: string, line: DiffLine, hunkHeader: string) {
  if (!draft.value.trim() || line.newLine == null) return
  submitting.value = true
  try {
    await api.reviewAddComment(props.sessionId, {
      repo,
      path,
      side: "RIGHT",
      anchorLine: line.newLine,
      anchorContext: line.content,
      body: draft.value.trim(),
      diffHunkHeader: hunkHeader,
    })
    draft.value = ""
    composerFor.value = null
    emit("reload")
  } catch (err: any) {
    toast.error("Failed to add comment", { description: err?.message ?? String(err) })
  } finally {
    submitting.value = false
  }
}

async function resolveComment(c: ReviewComment) {
  try {
    await api.reviewUpdateComment(props.sessionId, c.id, { status: "resolved", resolvedBy: "user" })
    emit("reload")
  } catch (err: any) {
    toast.error("Failed to resolve comment", { description: err?.message ?? String(err) })
  }
}

const openCount = computed(() => (props.comments ?? []).filter((c) => c.status === "open").length)

async function submitReview() {
  if (openCount.value === 0) return
  submitting.value = true
  try {
    const r = await api.reviewSubmit(props.sessionId)
    toast.success(`Sent ${r.delivered} comment${r.delivered !== 1 ? "s" : ""} to the agent`)
    emit("reload")
  } catch (err: any) {
    toast.error("Failed to submit review", { description: err?.message ?? String(err) })
  } finally {
    submitting.value = false
  }
}

// Track hovered line for the + gutter affordance
const hoveredLineKey = ref<string | null>(null)

function lineHoverKey(repo: string, path: string, newLine: number | undefined): string | null {
  if (newLine == null) return null
  return `${repo}||${path}||${newLine}`
}
</script>

<template>
  <div class="h-full overflow-y-auto bg-[var(--cmux-workspace)] text-foreground flex flex-col">
    <!-- Header -->
    <div class="flex items-center justify-between px-3 py-2 border-b border-border sticky top-0 bg-[var(--cmux-header)]/95 backdrop-blur z-10">
      <span class="text-[13px] font-medium">{{ totalFiles }} changed file{{ totalFiles !== 1 ? 's' : '' }}</span>
      <div class="flex items-center gap-1">
        <div class="relative">
          <button
            class="text-[11px] px-2 py-1 rounded-md hover:bg-accent transition-colors text-muted-foreground flex items-center gap-1"
            title="Change diff base"
            @click="baseMenuOpen = !baseMenuOpen; baseSubmenu = null"
          >
            <span class="text-foreground/80">Base:</span> {{ baseLabel }}
            <ChevronDown class="size-3" />
          </button>
          <div
            v-if="baseMenuOpen"
            class="absolute right-0 mt-1 w-52 rounded-md border border-border bg-[var(--cmux-header)] shadow-lg z-20 py-1 text-[12px]"
          >
            <button class="block w-full text-left px-3 py-1.5 hover:bg-accent" @click="chooseBase('session-start')">Session start</button>
            <button class="block w-full text-left px-3 py-1.5 hover:bg-accent" @click="chooseBase('head')">Uncommitted (HEAD)</button>
            <button class="w-full text-left px-3 py-1.5 hover:bg-accent flex items-center justify-between" @click="baseSubmenu = baseSubmenu === 'commit' ? null : 'commit'">
              Previous commit… <ChevronRight class="size-3" />
            </button>
            <div v-if="baseSubmenu === 'commit'" class="max-h-56 overflow-y-auto border-t border-border/50">
              <button
                v-for="c in primaryRefs.commits"
                :key="c.sha"
                class="block w-full text-left px-3 py-1.5 hover:bg-accent truncate"
                @click="chooseBase('commit:' + c.sha)"
              >
                <span class="font-mono text-[11px] text-muted-foreground">{{ c.sha }}</span> {{ c.subject }}
              </button>
              <div v-if="primaryRefs.commits.length === 0" class="px-3 py-1.5 text-muted-foreground italic">No commits</div>
            </div>
            <button class="w-full text-left px-3 py-1.5 hover:bg-accent flex items-center justify-between" @click="baseSubmenu = baseSubmenu === 'branch' ? null : 'branch'">
              Another branch… <ChevronRight class="size-3" />
            </button>
            <div v-if="baseSubmenu === 'branch'" class="max-h-56 overflow-y-auto border-t border-border/50">
              <button
                v-for="b in primaryRefs.branches"
                :key="b"
                class="block w-full text-left px-3 py-1.5 hover:bg-accent font-mono text-[11px] truncate"
                @click="chooseBase('branch:' + b)"
              >{{ b }}</button>
              <div v-if="primaryRefs.branches.length === 0" class="px-3 py-1.5 text-muted-foreground italic">No branches</div>
            </div>
          </div>
        </div>
        <button
          class="text-[11px] px-2 py-1 rounded-md hover:bg-accent transition-colors"
          :class="wrap ? 'text-primary' : 'text-muted-foreground'"
          title="Toggle line wrap"
          @click="wrap = !wrap"
        >Wrap</button>
        <button class="cmux-icon-button size-7" @click="emit('close')">
          <X class="size-4 text-muted-foreground" />
        </button>
      </div>
    </div>

    <!-- Per-repo groups -->
    <div class="flex-1">
      <template v-for="repo in repos" :key="repo.repo">
        <!-- Repo header (only when more than one repo) -->
        <button
          v-if="multiRepo"
          class="flex items-center gap-2 w-full px-3 py-2 text-left bg-[var(--cmux-session-list)] hover:bg-card/70 transition-colors border-b border-border"
          @click="toggleRepo(repo.repo)"
        >
          <component :is="expandedRepos.has(repo.repo) ? ChevronDown : ChevronRight" class="size-4 shrink-0 text-muted-foreground" />
          <span class="text-[12px] font-mono font-medium truncate flex-1">{{ repoLabel(repo.repo) }}</span>
          <span class="text-[11px] text-muted-foreground shrink-0">{{ repo.files.length }} file{{ repo.files.length !== 1 ? 's' : '' }}</span>
        </button>

        <!-- Files in this repo -->
        <template v-if="!multiRepo || expandedRepos.has(repo.repo)">
          <div
            v-for="f in repo.files"
            :key="fileKey(repo.repo, f.path)"
            class="border-b border-border"
            :class="{ 'pl-3': multiRepo }"
          >
            <!-- File header -->
            <button
              class="flex items-center gap-2 w-full px-3 py-2.5 text-left hover:bg-card/70 transition-colors"
              @click="toggleFile(fileKey(repo.repo, f.path))"
            >
              <component :is="expandedFiles.has(fileKey(repo.repo, f.path)) ? ChevronDown : ChevronRight" class="size-4 shrink-0 text-muted-foreground" />
              <span class="text-[13px] truncate flex-1 font-mono">{{ f.path }}</span>
              <span v-if="f.binary" class="text-[11px] text-muted-foreground shrink-0">Binary</span>
              <span v-else-if="f.modeChange" class="text-[11px] text-muted-foreground shrink-0">Mode</span>
              <span :class="statusColor(f.status)" class="text-[11px] font-medium shrink-0">{{ statusLabel(f.status) }}</span>
              <template v-if="!f.binary">
                <span v-if="diffStats(f.diff).added" class="text-[11px] text-emerald-400 shrink-0">+{{ diffStats(f.diff).added }}</span>
                <span v-if="diffStats(f.diff).deleted" class="text-[11px] text-red-400 shrink-0">-{{ diffStats(f.diff).deleted }}</span>
              </template>
            </button>

            <!-- Diff content (expanded) -->
            <div v-if="expandedFiles.has(fileKey(repo.repo, f.path))" class="overflow-x-auto border-t border-border/50 bg-[var(--cmux-code)]">
              <div v-if="f.binary" class="px-3 py-3 text-[12px] text-muted-foreground italic">
                Binary file — no text diff
              </div>
              <div v-else-if="f.modeChange && parseDiffLines(f.diff).length === 0" class="px-3 py-3 text-[12px] text-muted-foreground italic">
                File mode changed
              </div>
              <table v-else class="w-full text-[12px] font-mono leading-[1.6]">
                <template v-for="(line, i) in parseDiffLines(f.diff)" :key="i">
                  <!-- Main diff row -->
                  <tr
                    :class="{
                      'bg-emerald-500/10': line.type === 'add',
                      'bg-red-500/10': line.type === 'del',
                      'bg-blue-500/5': line.type === 'hunk',
                      'group': line.type === 'add' || line.type === 'ctx',
                    }"
                    @mouseenter="hoveredLineKey = lineHoverKey(repo.repo, f.path, line.newLine)"
                    @mouseleave="hoveredLineKey = null"
                  >
                    <!-- Gutter cell: shows +/- sigil for del, and a hover + button for add/ctx -->
                    <td class="px-2 select-none w-5 text-center relative" :class="{
                      'text-emerald-500': line.type === 'add',
                      'text-red-500': line.type === 'del',
                      'text-blue-400': line.type === 'hunk',
                      'text-muted-foreground/40': line.type === 'ctx',
                    }">
                      <template v-if="line.type === 'del'">-</template>
                      <template v-else-if="line.type === 'hunk'">@@</template>
                      <template v-else-if="line.type === 'add' || line.type === 'ctx'">
                        <!-- Always-visible (tappable on mobile) + comment affordance -->
                        <button
                          v-if="line.newLine != null"
                          class="absolute inset-0 flex items-center justify-center text-muted-foreground/60 opacity-60 transition-opacity hover:text-primary group-hover:opacity-100"
                          :class="{ 'opacity-100 text-primary': composerFor === composerKey(repo.repo, f.path, line.newLine!) }"
                          title="Add comment"
                          @click.stop="openComposer(repo.repo, f.path, line.newLine!)"
                        >
                          <Plus class="size-3" />
                        </button>
                        <span v-else>{{ line.type === 'add' ? '+' : '' }}</span>
                      </template>
                    </td>
                    <td class="px-2" :class="[wrap ? 'whitespace-pre-wrap break-all' : 'whitespace-pre', {
                      'text-emerald-300': line.type === 'add',
                      'text-red-300': line.type === 'del',
                      'text-blue-300 text-[11px] italic': line.type === 'hunk',
                      'text-foreground/70': line.type === 'ctx',
                    }]">{{ line.content }}</td>
                  </tr>

                  <!-- Inline comment composer row (opens below the clicked line) -->
                  <tr
                    v-if="(line.type === 'add' || line.type === 'ctx') && line.newLine != null && composerFor === composerKey(repo.repo, f.path, line.newLine!)"
                    class="bg-[var(--cmux-header)]"
                  >
                    <td colspan="2" class="px-3 py-2">
                      <div class="flex flex-col gap-2">
                        <textarea
                          v-model="draft"
                          class="w-full text-[12px] font-sans bg-[var(--input)] border border-border rounded-md px-2 py-1.5 text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-primary/50 resize-none"
                          rows="3"
                          placeholder="Leave a comment…"
                          autofocus
                          @keydown.escape="cancelComposer"
                        />
                        <div class="flex gap-2 justify-end">
                          <button
                            class="px-2.5 py-1 text-[11px] rounded-md border border-border hover:bg-accent transition-colors text-muted-foreground"
                            @click="cancelComposer"
                          >
                            Cancel
                          </button>
                          <button
                            class="px-2.5 py-1 text-[11px] rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-50"
                            :disabled="!draft.trim() || submitting"
                            @click="addComment(repo.repo, f.path, line, parseDiffLines(f.diff).slice(0, i).filter(l => l.type === 'hunk').pop()?.content ?? '')"
                          >
                            Add comment
                          </button>
                        </div>
                      </div>
                    </td>
                  </tr>

                  <!-- Existing comment thread rows beneath this line -->
                  <template v-if="(line.type === 'add' || line.type === 'ctx') && line.newLine != null">
                    <tr
                      v-for="c in commentsFor(repo.repo, f.path, line.newLine!)"
                      :key="c.id"
                      class="bg-[var(--cmux-header)] border-t border-border/30"
                    >
                      <td colspan="2" class="px-3 py-2">
                        <div class="flex flex-col gap-1">
                          <div class="flex items-center gap-2">
                            <MessageSquare class="size-3 shrink-0 text-muted-foreground" />
                            <span class="text-[11px] text-muted-foreground font-sans">{{ c.author }}</span>
                            <span
                              v-if="c.outdated"
                              class="text-[10px] px-1 py-0.5 rounded bg-amber-500/20 text-amber-400 font-sans"
                            >outdated</span>
                            <span
                              v-else-if="c.status === 'submitted'"
                              class="text-[10px] px-1 py-0.5 rounded bg-blue-500/20 text-blue-400 font-sans"
                            >submitted</span>
                            <span
                              v-else-if="c.status === 'resolved'"
                              class="text-[10px] px-1 py-0.5 rounded bg-emerald-500/20 text-emerald-400 font-sans"
                            >resolved</span>
                            <button
                              v-if="c.status === 'open'"
                              class="ml-auto text-[10px] px-1.5 py-0.5 rounded border border-border hover:bg-accent transition-colors text-muted-foreground font-sans"
                              @click="resolveComment(c)"
                            >
                              Resolve
                            </button>
                          </div>
                          <p class="text-[12px] font-sans text-foreground/80 whitespace-pre-wrap">{{ c.body }}</p>
                        </div>
                      </td>
                    </tr>
                  </template>
                </template>
              </table>
            </div>
          </div>
        </template>
      </template>

      <!-- Empty state -->
      <div v-if="totalFiles === 0" class="flex items-center justify-center h-32 text-muted-foreground text-[13px]">
        No changes found
      </div>
    </div>

    <!-- Sticky submit bar -->
    <div
      v-if="(comments ?? []).length > 0 || openCount > 0"
      class="sticky bottom-0 flex items-center justify-between px-3 py-2 border-t border-border bg-[var(--cmux-header)]/95 backdrop-blur z-10"
    >
      <span class="text-[12px] text-muted-foreground font-sans">
        {{ openCount }} open comment{{ openCount !== 1 ? 's' : '' }}
      </span>
      <button
        class="px-3 py-1.5 text-[12px] rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors disabled:opacity-40 font-sans"
        :disabled="openCount === 0 || submitting"
        @click="submitReview"
      >
        Submit review
      </button>
    </div>
  </div>
</template>
