<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue"
import { useRouter } from "vue-router"
import { DialogRoot, DialogPortal, DialogOverlay, DialogContent } from "reka-ui"
import { Button } from "@/components/ui/button"
import AgentLogo from "@/components/AgentLogo.vue"
import LauncherModelPicker from "@/components/LauncherModelPicker.vue"
import LauncherEffortPicker from "@/components/LauncherEffortPicker.vue"
import { api } from "@/api/client"
import { useSessions, type Session } from "@/stores/sessions"
import { usePendingFirstMessage } from "@/stores/pendingFirstMessage"
import {
  buildHandoffPrefill,
  CONTINUE_AGENTS,
  defaultContinueAgent,
  type ContinueAgent,
} from "@/lib/handoff-prefill"
import { toast } from "vue-sonner"

const props = defineProps<{
  open: boolean
  session: Session | null
}>()

const emit = defineEmits<{
  (e: "update:open", v: boolean): void
}>()

const router = useRouter()
const sessions = useSessions()
const pending = usePendingFirstMessage()

const agent = ref<ContinueAgent>("claude")
const model = ref("")
const reasoningLevel = ref("")
const message = ref("")
const submitting = ref(false)
// Skip the agent-change reset while we apply open-dialog defaults from the source session.
const seeding = ref(false)

watch(
  () => [props.open, props.session?.id] as const,
  async ([open]) => {
    if (!open || !props.session) return
    seeding.value = true
    const nextAgent = defaultContinueAgent(props.session.agent)
    agent.value = nextAgent
    // Prefill model/effort from the source only when staying on the same agent;
    // switching agent later clears these so we never send a cross-agent id.
    const sameAgent = props.session.agent === nextAgent
    model.value = sameAgent && props.session.model ? props.session.model : ""
    reasoningLevel.value =
      sameAgent && props.session.reasoningLevel ? props.session.reasoningLevel : ""
    message.value = buildHandoffPrefill({
      name: props.session.name,
      id: props.session.id,
    })
    await nextTick()
    seeding.value = false
  },
)

// User changed agent in the dialog: drop model so Default applies, and clear
// effort so LauncherEffortPicker can resolve the new agent/model default.
watch(agent, () => {
  if (seeding.value) return
  model.value = ""
  reasoningLevel.value = ""
})

const canStart = computed(() => {
  if (submitting.value) return false
  if (!props.session?.workdir?.trim()) return false
  return message.value.trim().length > 0
})

function close() {
  emit("update:open", false)
}

async function start() {
  const source = props.session
  if (!source || !canStart.value) return
  const text = message.value.trim()
  if (!text) return

  submitting.value = true
  try {
    // Same checkout as the source — never mint a new worktree. Pass the source
    // name + id so the broker can uniquify the display name and copy project/
    // worktree metadata (repo_root, session_branch) instead of naming the
    // session after the worktree directory basename (often a uuid).
    const result = await api.createSession({
      workdir: source.workdir,
      agent: agent.value,
      model: model.value || undefined,
      reasoningLevel: reasoningLevel.value || undefined,
      worktree: false,
      name: source.name,
      inheritFrom: source.id,
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
      repo_root: result.repo_root ?? source.repo_root,
      session_branch: result.session_branch ?? source.session_branch,
    })
    pending.set(result.id, { text })
    close()
    await router.push(`/s/${result.id}`)
  } catch (err: unknown) {
    toast.error(err instanceof Error ? err.message : "Failed to start conversation")
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <DialogRoot :open="props.open" @update:open="(v) => emit('update:open', v)">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 bg-black/50 z-50 data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0" />
      <DialogContent
        class="fixed top-1/2 left-1/2 z-50 -translate-x-1/2 -translate-y-1/2 w-[calc(100%-2rem)] max-w-md bg-popover text-popover-foreground rounded-xl p-5 ring-1 ring-foreground/10 outline-none data-open:animate-in data-closed:animate-out data-closed:fade-out-0 data-open:fade-in-0 data-closed:zoom-out-95 data-open:zoom-in-95"
        @pointer-down-outside="close"
        @escape-key-down="close"
      >
        <h3 class="font-semibold text-base">Continue in new conversation</h3>
        <p class="text-sm text-muted-foreground mt-1.5">
          Same worktree as
          <span class="font-medium text-foreground">{{ props.session?.name ?? "this session" }}</span>.
          The new agent is told to read this session first. Edit freely before start.
        </p>

        <div class="mt-4 space-y-3">
          <div>
            <p class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground mb-1.5">
              Agent
            </p>
            <div class="flex flex-wrap gap-1.5">
              <button
                v-for="a in CONTINUE_AGENTS"
                :key="a"
                type="button"
                class="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium border transition"
                :class="agent === a
                  ? 'border-primary bg-primary/10 text-foreground'
                  : 'border-border text-muted-foreground hover:bg-accent hover:text-foreground'"
                @click="agent = a"
              >
                <AgentLogo :agent="a" class="size-3.5 shrink-0 opacity-80" />
                <span class="capitalize">{{ a }}</span>
              </button>
            </div>
          </div>

          <div>
            <p class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground mb-1.5">
              Model &amp; thinking
            </p>
            <div class="flex flex-wrap items-center gap-1 rounded-lg border border-border bg-background px-1.5 py-1">
              <LauncherModelPicker v-model:model="model" :agent="agent" />
              <LauncherEffortPicker
                v-model:level="reasoningLevel"
                :agent="agent"
                :model="model"
              />
            </div>
          </div>

          <div>
            <label
              for="continue-handoff-message"
              class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground"
            >
              Handoff message
            </label>
            <textarea
              id="continue-handoff-message"
              v-model="message"
              rows="8"
              class="mt-1.5 w-full resize-y rounded-lg border border-border bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring min-h-[8rem]"
              placeholder="What should the new conversation pick up?"
            />
          </div>
        </div>

        <div class="flex gap-3 mt-5 justify-end">
          <Button variant="outline" size="sm" :disabled="submitting" @click="close">
            Cancel
          </Button>
          <Button size="sm" :disabled="!canStart" @click="start">
            {{ submitting ? "Starting…" : "Start" }}
          </Button>
        </div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>
