<script setup lang="ts">
import { computed, ref, watch } from "vue"
import { GitBranch, Check, Plus, Loader2Icon } from "lucide-vue-next"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Input } from "@/components/ui/input"
import { useGitRemote, sessionsSharingCheckout } from "@/stores/gitRemote"
import { useSessions } from "@/stores/sessions"
import type { GitLocalBranch } from "@/api/client"
import { toast } from "vue-sonner"

const props = defineProps<{ sessionId: string }>()

const git = useGitRemote()
const sessionsStore = useSessions()

const open = ref(false)
const query = ref("")
const invalidMsg = ref<string | null>(null)
const confirmTarget = ref<{ name: string; create: boolean } | null>(null)

const branches = computed(() => git.branchesBySession[props.sessionId])
const busy = computed(() => git.busyBySession[props.sessionId] ?? null)
const self = computed(() => sessionsStore.list.find((s) => s.id === props.sessionId))

const pinned = computed(() => self.value?.session_branch ?? null)
const showList = computed(() => !pinned.value && !!branches.value?.inPlace && !!branches.value?.repoRoot)

const q = computed(() => query.value.trim().toLowerCase())
const filteredLocal = computed(() =>
  (branches.value?.local ?? []).filter((b) => b.name.toLowerCase().includes(q.value)))
const filteredRemote = computed(() =>
  (branches.value?.remote ?? []).filter((n) => n.toLowerCase().includes(q.value)))
const exactMatch = computed(() => {
  const t = query.value.trim()
  return !!t && ((branches.value?.local ?? []).some((b) => b.name === t) || (branches.value?.remote ?? []).includes(t))
})
const showCreate = computed(() => showList.value && !!query.value.trim() && !exactMatch.value)

const sharing = computed(() =>
  sessionsSharingCheckout(sessionsStore.list, props.sessionId, branches.value?.repoRoot ?? null))

function isCurrent(name: string) { return branches.value?.current === name }
function takenElsewhere(b: GitLocalBranch) {
  return !!b.checkedOutAt && b.checkedOutAt !== branches.value?.repoRoot
}

watch(open, (o) => {
  if (!o) return
  query.value = ""; invalidMsg.value = null; confirmTarget.value = null
  void git.loadBranches(props.sessionId)
})

function pick(name: string, create = false) {
  invalidMsg.value = null
  if (sharing.value.length) { confirmTarget.value = { name, create }; return }
  void doSwitch(name, create)
}

async function doSwitch(name: string, create: boolean) {
  confirmTarget.value = null
  const r = await git.switchBranch(props.sessionId, name, create)
  if (!r) return
  if (r.status === "switched") { toast.success(`Switched to ${r.branch}`); open.value = false; return }
  if (r.status === "invalid_name") { invalidMsg.value = r.message; return }
  open.value = false // refusals render in BranchSyncStatus's result card
}

const rowBtn = "flex w-full items-center gap-2 rounded-md px-2 py-2 text-left text-[13px] font-mono hover:bg-accent disabled:opacity-40 disabled:pointer-events-none"
</script>

<template>
  <Popover v-model:open="open">
    <PopoverTrigger as-child>
      <slot />
    </PopoverTrigger>
    <PopoverContent align="start" class="w-80 max-w-[calc(100vw-1.5rem)] p-2 overflow-y-auto">
      <!-- Pinned worktree session: no list -->
      <p v-if="pinned" class="px-1 py-0.5 text-[12px] text-muted-foreground">
        Pinned to <code class="font-mono">{{ pinned }}</code> (worktree session) — branch switching applies to in-place sessions only.
      </p>

      <!-- Branch picker -->
      <template v-else-if="showList">
        <Input
          v-model="query" placeholder="Filter branches or type a new name…"
          autocapitalize="off" autocorrect="off" spellcheck="false"
          class="h-8 text-[13px]"
          @keydown.enter.prevent="showCreate && pick(query.trim(), true)"
        />
        <p v-if="invalidMsg" class="mt-1 px-1 text-[11px] text-destructive">{{ invalidMsg }}</p>

        <!-- Shared-checkout confirm (inline) -->
        <div v-if="confirmTarget" class="mt-2 rounded-md border border-border bg-card p-2.5">
          <p class="text-[12px]">
            <span class="font-mono">{{ sharing.map((s) => s.name).join(", ") }}</span>
            {{ sharing.length === 1 ? "is" : "are" }} also working in this checkout — switching moves them to
            <code class="font-mono">{{ confirmTarget.name }}</code> too. Switch anyway?
          </p>
          <div class="mt-2 flex justify-end gap-2">
            <button type="button" class="text-[12px] px-2.5 py-1 rounded-md border border-border hover:bg-accent text-muted-foreground" @click="confirmTarget = null">Cancel</button>
            <button type="button" class="text-[12px] px-2.5 py-1 rounded-md bg-primary text-primary-foreground hover:bg-primary/90" :disabled="!!busy" @click="doSwitch(confirmTarget.name, confirmTarget.create)">Switch anyway</button>
          </div>
        </div>

        <div class="mt-1.5 space-y-2">
          <button v-if="showCreate" type="button" :class="rowBtn" :disabled="!!busy" @click="pick(query.trim(), true)">
            <Plus class="size-4 shrink-0 opacity-80" />
            <span class="flex-1 truncate">Create branch '{{ query.trim() }}'</span>
            <Loader2Icon v-if="busy === 'switch'" class="size-3.5 animate-spin" />
          </button>

          <div v-if="filteredLocal.length">
            <p class="px-2 pb-0.5 text-[10px] uppercase tracking-wide text-muted-foreground">Local</p>
            <button
              v-for="b in filteredLocal" :key="b.name" type="button" :class="rowBtn"
              :disabled="!!busy || isCurrent(b.name) || takenElsewhere(b)"
              @click="pick(b.name)"
            >
              <Check v-if="isCurrent(b.name)" class="size-4 shrink-0" />
              <GitBranch v-else class="size-4 shrink-0 opacity-50" />
              <span class="flex-1 truncate">{{ b.name }}</span>
              <span v-if="takenElsewhere(b)" class="text-[10px] text-muted-foreground truncate max-w-[45%]">in {{ b.checkedOutAt }}</span>
            </button>
          </div>

          <div v-if="filteredRemote.length">
            <p class="px-2 pb-0.5 text-[10px] uppercase tracking-wide text-muted-foreground">Remote</p>
            <button v-for="n in filteredRemote" :key="n" type="button" :class="rowBtn" :disabled="!!busy" @click="pick(n)">
              <GitBranch class="size-4 shrink-0 opacity-50" />
              <span class="flex-1 truncate">{{ n }}</span>
            </button>
          </div>

          <p v-if="!filteredLocal.length && !filteredRemote.length && !showCreate" class="px-2 text-[12px] text-muted-foreground">No branches match.</p>
        </div>
      </template>

      <p v-else class="px-1 py-0.5 text-[12px] text-muted-foreground">Loading branches…</p>
    </PopoverContent>
  </Popover>
</template>
