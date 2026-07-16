<script setup lang="ts">
import { ref, onMounted } from "vue"
import { useRouter } from "vue-router"
import { ArrowLeft, Plus, Bot, Loader2Icon } from "lucide-vue-next"
import { toast } from "vue-sonner"
import { api } from "@/api/client"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import LauncherAgentPicker from "@/components/LauncherAgentPicker.vue"
import LauncherModelPicker from "@/components/LauncherModelPicker.vue"
import PACard from "@/components/PACard.vue"
import KillConfirmDialog from "@/components/KillConfirmDialog.vue"

interface PA {
  id: string
  name: string
  workdir: string
  mute: boolean
  connected: boolean
  agent?: string
  model?: string
  role?: "personal_assistant" | "worker"
  isDefault?: boolean
  status?: string
}

const router = useRouter()

const pas = ref<PA[]>([])
const loading = ref(false)
const creating = ref(false)
const showCreate = ref(false)

const newName = ref("")
const newAgent = ref<"claude" | "codex" | "cursor" | "opencode" | "grok">("claude")
const newModel = ref("")
const newFocus = ref("")

const killTarget = ref<{ id: string; name: string } | null>(null)
const showKillConfirm = ref(false)

async function refresh() {
  loading.value = true
  try {
    const result = await api.listPAs()
    pas.value = result.pas ?? []
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to load personal assistants")
  } finally {
    loading.value = false
  }
}

async function create() {
  if (!newName.value.trim()) return
  creating.value = true
  try {
    const result = await api.createPA({
      name: newName.value.trim(),
      agent: newAgent.value,
      model: newModel.value || undefined,
      focusText: newFocus.value.trim() || undefined,
    })
    toast.success(`Created ${result.name}`)
    newName.value = ""
    newModel.value = ""
    newFocus.value = ""
    showCreate.value = false
    await refresh()
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to create PA")
  } finally {
    creating.value = false
  }
}

function onSwitch(id: string) {
  router.push(`/s/${id}`)
}

function onKill(id: string, name: string) {
  killTarget.value = { id, name }
  showKillConfirm.value = true
}

async function confirmKill() {
  const target = killTarget.value
  if (!target) return
  showKillConfirm.value = false
  try {
    await api.killSession(target.id)
    toast.success(`${target.name} killed`)
    await refresh()
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to kill PA")
  } finally {
    killTarget.value = null
  }
}

onMounted(refresh)
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <header
      class="flex items-center justify-between px-3 py-3 border-b border-border sticky top-0 bg-background/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)"
    >
      <div class="flex items-center gap-2">
        <button class="text-muted-foreground hover:text-foreground transition -ml-1 p-1" aria-label="Back" @click="$router.back()">
          <ArrowLeft class="size-5" />
        </button>
        <h1 class="text-base font-semibold tracking-tight">Personal Assistants</h1>
      </div>
      <Dialog v-model:open="showCreate">
        <DialogTrigger as-child>
          <Button size="sm" class="gap-1.5">
            <Plus class="size-4" />
            Create PA
          </Button>
        </DialogTrigger>
        <DialogContent class="sm:max-w-md">
          <DialogHeader><DialogTitle>Create personal assistant</DialogTitle></DialogHeader>
          <div class="space-y-4">
            <div class="space-y-1.5">
              <label class="text-xs font-medium text-muted-foreground">Name</label>
              <Input v-model="newName" placeholder="e.g. coder, researcher" autofocus />
            </div>
            <div class="space-y-1.5">
              <label class="text-xs font-medium text-muted-foreground">Agent</label>
              <div class="flex items-center gap-2">
                <LauncherAgentPicker v-model:agent="newAgent" />
              </div>
            </div>
            <div class="space-y-1.5">
              <label class="text-xs font-medium text-muted-foreground">Model</label>
              <div class="flex items-center gap-2">
                <LauncherModelPicker v-model:model="newModel" :agent="newAgent" />
              </div>
            </div>
            <div class="space-y-1.5">
              <label class="text-xs font-medium text-muted-foreground">Focus (optional)</label>
              <Textarea v-model="newFocus" placeholder="What should this PA focus on?" class="min-h-[5rem]" />
            </div>
            <Button class="w-full" :disabled="!newName.trim() || creating" @click="create">
              <Loader2Icon v-if="creating" class="size-4 animate-spin" />
              <span v-else>Create</span>
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </header>

    <div v-if="loading" class="flex items-center justify-center gap-2 py-12 text-sm text-muted-foreground">
      <Loader2Icon class="size-4 animate-spin" />
      Loading assistants…
    </div>

    <div v-else-if="pas.length === 0" class="px-6 py-12 text-center text-muted-foreground">
      <div class="mx-auto size-14 rounded-2xl bg-card ring-1 ring-border flex items-center justify-center mb-4">
        <Bot class="size-6 text-muted-foreground" />
      </div>
      <p class="text-sm font-medium text-foreground">No personal assistants yet</p>
      <p class="text-xs mt-1">Create one to get started.</p>
    </div>

    <div v-else class="p-4 grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
      <PACard
        v-for="pa in pas"
        :key="pa.id"
        :id="pa.id"
        :name="pa.name"
        :agent="pa.agent"
        :model="pa.model"
        :workdir="pa.workdir"
        :connected="pa.connected"
        :is-default="pa.isDefault"
        @switch="onSwitch"
        @kill="onKill"
      />
    </div>

    <KillConfirmDialog
      :open="showKillConfirm"
      :session-name="killTarget?.name ?? ''"
      @update:open="showKillConfirm = $event"
      @confirm="confirmKill"
    />
  </div>
</template>
