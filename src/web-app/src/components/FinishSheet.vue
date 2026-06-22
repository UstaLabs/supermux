<script setup lang="ts">
import { ref, computed, watch } from "vue"
import { api, type FinishReadiness } from "@/api/client"
import { useFinishJob } from "@/stores/finishJob"
import { toast } from "vue-sonner"
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription } from "@/components/ui/sheet"
import {
  GitMerge,
  GitPullRequest,
  Trash2,
  Archive,
  LoaderCircle,
  ExternalLink,
  CircleCheck,
  CircleX,
  TriangleAlert,
  Send,
  Sparkles,
} from "@lucide/vue"

const props = defineProps<{ open: boolean; sessionId: string; branch?: string; base?: string }>()
const emit = defineEmits<{ (e: "update:open", v: boolean): void }>()

const finishJob = useFinishJob()
const job = computed(() => finishJob.bySession[props.sessionId])
const readiness = ref<FinishReadiness | null>(null)
const loadingReadiness = ref(false)
const confirmingDiscard = ref(false)
const commitMessage = ref("Session changes")
const verifyDraft = ref<{ content: string; source: string } | null>(null)
const verifySaving = ref(false)
const sending = ref(false)
const busy = ref(false)

const view = computed<"menu" | "running" | "outcome">(() => {
  const j = job.value
  if (j?.status === "running") return "running"
  if (j && j.status !== "running") return "outcome"
  return "menu"
})
const outcome = computed<any>(() => job.value?.outcome ?? null)
const oStatus = computed<string>(() => outcome.value?.status ?? "")

function setOpen(v: boolean) { emit("update:open", v) }

watch(() => props.open, async (o) => {
  if (!o) return
  confirmingDiscard.value = false
  verifyDraft.value = null
  if (job.value && job.value.status !== "running") finishJob.ack(props.sessionId)
  if (!job.value || job.value.status === "done") await loadReadiness()
})

async function loadReadiness() {
  loadingReadiness.value = true
  try {
    const r = await api.finishReadiness(props.sessionId)
    readiness.value = r && "error" in r ? null : (r as FinishReadiness)
  } catch { readiness.value = null } finally { loadingReadiness.value = false }
}

async function run(body: { action: "merge" | "pr" | "keep" | "discard"; skipVerify?: boolean; commitFirst?: boolean; commitMessage?: string }) {
  if (busy.value) return
  busy.value = true
  try {
    const r = await api.finish(props.sessionId, body)
    if (r && "error" in r) { toast.error((r as any).error); return }
    finishJob.set(props.sessionId, r as any) // optimistic; WS keeps it fresh
  } catch (e: any) { toast.error(e?.message ?? "Finish failed") } finally { busy.value = false }
}

function merge() { void run({ action: "merge" }) }
function openPr() { void run({ action: "pr" }) }
function keep() { void run({ action: "keep" }); setOpen(false) }
function confirmDiscard() { confirmingDiscard.value = true }
function doDiscard() { confirmingDiscard.value = false; void run({ action: "discard" }) }
function mergeAnyway() { void run({ action: "merge", skipVerify: true }) }
function commitAndContinue() { void run({ action: job.value?.action === "pr" ? "pr" : "merge", commitFirst: true, commitMessage: commitMessage.value }) }
function retryPr() { void run({ action: "pr" }) }

function issueMessage(o: any): string {
  if (o?.status === "sync_conflict") return `The Finish step merged the base branch in and hit conflicts in:\n${(o.files || []).map((f: string) => `- ${f}`).join("\n")}\n\nThe worktree is in a conflicted merge state — please resolve the conflicts and commit, then I'll run Finish again.`
  if (o?.status === "tests_failed") return `The Finish step ran the tests (\`${o.command}\`) and they failed:\n\n\`\`\`\n${o.output}\n\`\`\`\n\nPlease fix them so the branch is green, then I'll run Finish again.`
  if (o?.status === "dirty_overlap") return `The base checkout has unsaved changes in: ${(o.files || []).join(", ")} — the same files my work touches. Please commit or stash them so Finish can fast-forward.`
  if (o?.status === "push_rejected") return `Pushing the branch for a PR was rejected because the remote has diverged: ${o.message}. Please reconcile (pull/rebase) and I'll run Finish again.`
  return `Finish reported: ${o?.message ?? o?.status}`
}
async function letAgentFix() {
  const o = outcome.value
  if (!o || sending.value) return
  sending.value = true
  try { await api.sendMessage(props.sessionId, issueMessage(o)); toast.success("Sent to the agent"); dismiss() }
  catch (e: any) { toast.error(e?.message ?? "Failed to send") } finally { sending.value = false }
}
async function generateVerify() { try { verifyDraft.value = await api.verifySuggest(props.sessionId) } catch (e: any) { toast.error(e?.message ?? "Failed to suggest") } }
async function saveVerify() {
  if (!verifyDraft.value || verifySaving.value) return
  verifySaving.value = true
  try {
    const r: any = await api.verifySave(props.sessionId, verifyDraft.value.content)
    if (!r?.ok) { toast.error(r?.reason ?? "Failed to save"); return }
    toast.success("Saved .mux/verify.sh"); verifyDraft.value = null; void run({ action: "merge" })
  } catch (e: any) { toast.error(e?.message ?? "Failed to save") } finally { verifySaving.value = false }
}
function dismiss() { finishJob.clear(props.sessionId); setOpen(false) }
</script>

<template>
  <Sheet :open="props.open" @update:open="setOpen">
    <SheetContent
      side="bottom"
      :show-close-button="view !== 'running'"
      class="max-h-[85vh] overflow-y-auto gap-0 p-0 pb-[calc(env(safe-area-inset-bottom,0px)+1rem)]"
    >
      <!-- ===================== MENU ===================== -->
      <template v-if="view === 'menu'">
        <SheetHeader class="px-4 pt-4 pb-2">
          <SheetTitle>Finish · {{ branch ?? readiness?.branch }}</SheetTitle>
          <SheetDescription v-if="readiness">
            {{ readiness.branch }} → {{ readiness.base }}
          </SheetDescription>
        </SheetHeader>

        <div class="px-4 pb-4 flex flex-col gap-3">
          <!-- Summary -->
          <div v-if="loadingReadiness" class="flex items-center gap-2 text-[12px] text-muted-foreground">
            <LoaderCircle class="size-3.5 animate-spin" />
            <span>Checking branch…</span>
          </div>
          <div
            v-else-if="readiness"
            class="rounded-lg border border-border bg-card px-3 py-2.5 text-[12px] text-foreground/80 flex flex-wrap items-center gap-x-3 gap-y-1"
          >
            <span class="font-medium text-foreground">
              ↑{{ readiness.ahead }}<template v-if="readiness.behind > 0"> · ↓{{ readiness.behind }}</template>
            </span>
            <span>{{ readiness.filesChanged }} files · +{{ readiness.insertions }}/−{{ readiness.deletions }}</span>
            <span
              v-if="readiness.conflictPreflight === 'will_conflict'"
              class="inline-flex items-center gap-1 text-amber-400"
            >
              <TriangleAlert class="size-3" /> may conflict
            </span>
            <span
              v-else-if="readiness.conflictPreflight === 'clean'"
              class="inline-flex items-center gap-1 text-emerald-400"
            >✓ no conflict</span>
            <span
              v-if="readiness.dirtyFiles.length > 0"
              class="inline-flex items-center gap-1 text-amber-400"
            >
              <TriangleAlert class="size-3" /> {{ readiness.dirtyFiles.length }} uncommitted
            </span>
          </div>

          <!-- Nothing to land: only Keep + Discard -->
          <template v-if="readiness?.nothingToLand">
            <p class="text-[12px] text-muted-foreground">No new commits to land</p>
            <button
              type="button"
              :disabled="busy"
              class="flex items-center justify-center gap-2 rounded-lg border border-border bg-card px-3 py-3 text-[13px] font-medium text-foreground hover:bg-accent disabled:opacity-60 transition-colors"
              @click="keep"
            >
              <LoaderCircle v-if="busy" class="size-4 animate-spin" /><Archive v-else class="size-4" />
              Keep
            </button>
            <button
              type="button"
              :disabled="busy"
              class="flex items-center justify-center gap-2 rounded-lg border border-destructive/40 px-3 py-3 text-[13px] font-medium text-destructive hover:bg-destructive/10 disabled:opacity-60 transition-colors"
              @click="confirmDiscard"
            >
              <Trash2 class="size-4" /> Discard
            </button>
          </template>

          <!-- Normal: four actions -->
          <template v-else>
            <button
              type="button"
              :disabled="busy"
              :class="[
                'flex items-center justify-center gap-2 rounded-lg px-3 py-3 text-[13px] font-medium disabled:opacity-60 transition-colors',
                readiness?.recommended === 'merge'
                  ? 'bg-emerald-600 text-white hover:bg-emerald-500'
                  : 'border border-border bg-card text-foreground hover:bg-accent',
              ]"
              @click="merge"
            >
              <LoaderCircle v-if="busy" class="size-4 animate-spin" /><GitMerge v-else class="size-4" />
              Merge locally
            </button>

            <button
              type="button"
              :disabled="busy || (!!readiness && !readiness.hasRemote)"
              :class="[
                'flex items-center justify-center gap-2 rounded-lg px-3 py-3 text-[13px] font-medium disabled:opacity-60 disabled:cursor-not-allowed transition-colors',
                readiness?.recommended === 'pr'
                  ? 'bg-emerald-600 text-white hover:bg-emerald-500'
                  : 'border border-border bg-card text-foreground hover:bg-accent',
              ]"
              @click="openPr"
            >
              <LoaderCircle v-if="busy" class="size-4 animate-spin" /><GitPullRequest v-else class="size-4" />
              <span v-if="readiness && readiness.hasRemote && !readiness.ghAvailable">Push &amp; open PR</span>
              <span v-else>Open PR</span>
              <span v-if="readiness && !readiness.hasRemote" class="text-[11px] font-normal opacity-70">no remote</span>
            </button>

            <button
              type="button"
              :disabled="busy"
              class="flex items-center justify-center gap-2 rounded-lg border border-border bg-card px-3 py-3 text-[13px] font-medium text-foreground hover:bg-accent disabled:opacity-60 transition-colors"
              @click="keep"
            >
              <Archive class="size-4" /> Keep
            </button>

            <button
              type="button"
              :disabled="busy"
              class="flex items-center justify-center gap-2 rounded-lg border border-destructive/40 px-3 py-3 text-[13px] font-medium text-destructive hover:bg-destructive/10 disabled:opacity-60 transition-colors"
              @click="confirmDiscard"
            >
              <Trash2 class="size-4" /> Discard
            </button>
          </template>

          <!-- Discard confirm row -->
          <div
            v-if="confirmingDiscard"
            class="rounded-lg border border-destructive/40 bg-destructive/5 px-3 py-2.5 flex flex-col gap-2"
          >
            <span class="text-[12px] text-foreground">Discard all work on this branch?</span>
            <div class="flex items-center gap-2">
              <button
                type="button"
                class="text-[12px] px-2.5 py-1 rounded-md bg-destructive/10 text-destructive hover:bg-destructive/20 transition-colors"
                @click="doDiscard"
              >Discard</button>
              <button
                type="button"
                class="text-[12px] px-2.5 py-1 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
                @click="confirmingDiscard = false"
              >Cancel</button>
            </div>
          </div>
        </div>
      </template>

      <!-- ===================== RUNNING ===================== -->
      <template v-else-if="view === 'running'">
        <SheetHeader class="sr-only">
          <SheetTitle>Finishing</SheetTitle>
        </SheetHeader>
        <div class="px-4 py-8 flex flex-col items-center gap-3 text-center">
          <LoaderCircle class="size-6 animate-spin text-foreground/70" />
          <span class="text-[13px] font-medium text-foreground">{{ job?.stage ?? "Finishing…" }}</span>
          <span class="text-[12px] text-muted-foreground">You can close this — I'll notify you when it's done.</span>
        </div>
      </template>

      <!-- ===================== OUTCOME ===================== -->
      <template v-else>
        <SheetHeader class="px-4 pt-4 pb-2">
          <SheetTitle>Finish</SheetTitle>
        </SheetHeader>
        <div class="px-4 pb-4 flex flex-col gap-3 text-[12px]">
          <!-- integrated -->
          <template v-if="oStatus === 'integrated'">
            <div class="flex items-center gap-2 text-[13px] font-medium text-emerald-400">
              <CircleCheck class="size-4" /> Merged into {{ outcome.base }}
            </div>
            <button
              type="button"
              class="self-end text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
              @click="setOpen(false)"
            >Done</button>
          </template>

          <!-- pr_opened -->
          <template v-else-if="oStatus === 'pr_opened'">
            <div class="flex items-center gap-2 text-[13px] font-medium text-foreground">
              <GitPullRequest class="size-4" /> Pull request opened
            </div>
            <div class="flex items-center justify-end gap-2">
              <a
                v-if="outcome.prUrl"
                :href="outcome.prUrl"
                target="_blank"
                rel="noopener noreferrer"
                class="inline-flex items-center gap-1 text-[12px] px-3 py-1.5 rounded-md border border-border text-foreground hover:bg-accent transition-colors"
              ><ExternalLink class="size-3.5" /> View PR</a>
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
                @click="dismiss"
              >Dismiss</button>
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
                @click="setOpen(false)"
              >Done</button>
            </div>
          </template>

          <!-- branch_published -->
          <template v-else-if="oStatus === 'branch_published'">
            <div class="flex items-center gap-2 text-[13px] font-medium text-foreground">
              <GitPullRequest class="size-4" /> Branch pushed
            </div>
            <p v-if="outcome.prError" class="text-muted-foreground">{{ outcome.prError }}</p>
            <div class="flex items-center justify-end gap-2">
              <a
                v-if="outcome.compareUrl"
                :href="outcome.compareUrl"
                target="_blank"
                rel="noopener noreferrer"
                class="inline-flex items-center gap-1 text-[12px] px-3 py-1.5 rounded-md border border-border text-foreground hover:bg-accent transition-colors"
              ><ExternalLink class="size-3.5" /> Open a PR</a>
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
                @click="dismiss"
              >Dismiss</button>
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
                @click="setOpen(false)"
              >Done</button>
            </div>
          </template>

          <!-- tests_failed -->
          <template v-else-if="oStatus === 'tests_failed'">
            <div class="flex items-center gap-2 text-[13px] font-medium text-destructive">
              <CircleX class="size-4" /> Tests failed
            </div>
            <pre class="whitespace-pre-wrap break-all font-mono text-foreground/80 max-h-60 overflow-y-auto rounded-md border border-border bg-card px-2.5 py-2">{{ outcome.output }}</pre>
            <div class="flex flex-wrap items-center justify-end gap-2">
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
                @click="dismiss"
              >Dismiss</button>
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md border border-amber-500/40 text-amber-400 hover:bg-amber-500/10 transition-colors"
                @click="mergeAnyway"
              >Merge anyway</button>
              <button
                type="button"
                :disabled="sending"
                class="inline-flex items-center gap-1 text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors"
                @click="letAgentFix"
              ><Send class="size-3.5" /> Let the agent fix it</button>
            </div>
          </template>

          <!-- sync_conflict / dirty_overlap -->
          <template v-else-if="oStatus === 'sync_conflict' || oStatus === 'dirty_overlap'">
            <div class="flex items-center gap-2 text-[13px] font-medium text-foreground">
              <TriangleAlert class="size-4 text-amber-400" />
              {{ oStatus === 'sync_conflict' ? 'Merge conflicts' : 'Base has unsaved changes' }}
            </div>
            <ul class="font-mono space-y-0.5 max-h-40 overflow-y-auto">
              <li v-for="f in (outcome.files || [])" :key="f" class="truncate text-foreground/80">{{ f }}</li>
            </ul>
            <div class="flex items-center justify-end gap-2">
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
                @click="dismiss"
              >Dismiss</button>
              <button
                type="button"
                :disabled="sending"
                class="inline-flex items-center gap-1 text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors"
                @click="letAgentFix"
              ><Send class="size-3.5" /> Let the agent fix it</button>
            </div>
          </template>

          <!-- uncommitted -->
          <template v-else-if="oStatus === 'uncommitted'">
            <p class="text-foreground/80">These changes aren't committed yet</p>
            <ul class="font-mono space-y-0.5 max-h-40 overflow-y-auto">
              <li v-for="f in (outcome.files || [])" :key="f" class="truncate text-foreground/80">{{ f }}</li>
            </ul>
            <input
              v-model="commitMessage"
              placeholder="Commit message"
              class="w-full text-[12px] bg-[var(--input)] border border-border rounded-md px-2 py-1.5 text-foreground focus:outline-none focus:border-primary/50"
            />
            <div class="flex items-center justify-end gap-2">
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
                @click="dismiss"
              >Dismiss</button>
              <button
                type="button"
                :disabled="busy"
                class="text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors"
                @click="commitAndContinue"
              >Commit &amp; {{ job?.action === 'pr' ? 'open PR' : 'merge' }}</button>
            </div>
          </template>

          <!-- no_verify -->
          <template v-else-if="oStatus === 'no_verify'">
            <p v-if="!verifyDraft" class="text-foreground/80">No <code>.mux/verify.sh</code> configured</p>
            <div v-else class="flex flex-col gap-1">
              <span class="text-[10px] uppercase tracking-wide text-muted-foreground">Draft · {{ verifyDraft.source }}</span>
              <textarea
                v-model="verifyDraft.content"
                rows="6"
                class="w-full font-mono text-[12px] bg-[var(--input)] border border-border rounded-md px-2 py-1.5 text-foreground focus:outline-none focus:border-primary/50"
              />
            </div>
            <div class="flex flex-wrap items-center justify-end gap-2">
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
                @click="dismiss"
              >Dismiss</button>
              <template v-if="!verifyDraft">
                <button
                  type="button"
                  class="inline-flex items-center gap-1 text-[12px] px-3 py-1.5 rounded-md border border-amber-500/40 text-amber-400 hover:bg-amber-500/10 transition-colors"
                  @click="mergeAnyway"
                >Merge without verifying</button>
                <button
                  type="button"
                  class="inline-flex items-center gap-1 text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
                  @click="generateVerify"
                ><Sparkles class="size-3.5" /> Generate verify</button>
              </template>
              <button
                v-else
                type="button"
                :disabled="verifySaving"
                class="text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors"
                @click="saveVerify"
              >Save</button>
            </div>
          </template>

          <!-- push_auth_failed / push_rejected -->
          <template v-else-if="oStatus === 'push_auth_failed' || oStatus === 'push_rejected'">
            <div class="flex items-center gap-2 text-[13px] font-medium text-destructive">
              <CircleX class="size-4" /> Push failed
            </div>
            <p class="text-foreground/80">{{ outcome.message }}</p>
            <div class="flex items-center justify-end gap-2">
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
                @click="dismiss"
              >Dismiss</button>
              <button
                type="button"
                :disabled="busy"
                class="text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors"
                @click="retryPr"
              >Retry</button>
            </div>
          </template>

          <!-- nothing_to_do -->
          <template v-else-if="oStatus === 'nothing_to_do'">
            <p class="text-foreground/80">Nothing to land — no new commits.</p>
            <button
              type="button"
              class="self-end text-[12px] px-3 py-1.5 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
              @click="dismiss"
            >Dismiss</button>
          </template>

          <!-- kept / discarded -->
          <template v-else-if="oStatus === 'kept' || oStatus === 'discarded'">
            <div class="flex items-center gap-2 text-[13px] font-medium text-foreground">
              <CircleCheck class="size-4 text-emerald-400" />
              {{ oStatus === 'kept' ? 'Branch kept' : 'Work discarded' }}
            </div>
            <button
              type="button"
              class="self-end text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 transition-colors"
              @click="setOpen(false)"
            >Done</button>
          </template>

          <!-- non_ff -->
          <template v-else-if="oStatus === 'non_ff'">
            <div class="flex items-center gap-2 text-[13px] font-medium text-foreground">
              <TriangleAlert class="size-4 text-amber-400" /> Base branch moved
            </div>
            <p class="text-foreground/80">The base branch moved while finishing. Re-sync and merge again.</p>
            <div class="flex items-center justify-end gap-2">
              <button
                type="button"
                class="text-[12px] px-3 py-1.5 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
                @click="dismiss"
              >Dismiss</button>
              <button
                type="button"
                :disabled="busy"
                class="text-[12px] px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 transition-colors"
                @click="merge"
              >Merge again</button>
            </div>
          </template>

          <!-- error (default) -->
          <template v-else>
            <div class="flex items-center gap-2 text-[13px] font-medium text-destructive">
              <CircleX class="size-4" /> Finish failed
            </div>
            <p class="text-foreground/80">{{ outcome?.message ?? oStatus }}</p>
            <button
              type="button"
              class="self-end text-[12px] px-3 py-1.5 rounded-md border border-border text-muted-foreground hover:bg-accent transition-colors"
              @click="dismiss"
            >Dismiss</button>
          </template>
        </div>
      </template>
    </SheetContent>
  </Sheet>
</template>
