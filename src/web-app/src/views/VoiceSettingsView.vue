<script setup lang="ts">
import { ref, onMounted } from "vue"
import { useRouter } from "vue-router"
import { ArrowLeft, Mic } from "lucide-vue-next"
import { api } from "@/api/client"
import { toast } from "vue-sonner"

const router = useRouter()

const loading = ref(true)
const saving = ref(false)
const model = ref("")
const models = ref<{ id: string; displayName: string }[]>([])

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push("/settings")
}

async function load() {
  loading.value = true
  try {
    const [cfg, { models: ms }] = await Promise.all([
      api.getAppConfig(),
      api.listModels("claude"),
    ])
    model.value = cfg.voiceCleanupModel ?? ""
    models.value = ms
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to load voice settings")
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await api.saveAppConfig({ voiceCleanupModel: model.value || undefined })
    toast.success("Saved")
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to save")
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <header
      class="flex items-center gap-2 px-3 py-3 border-b border-border sticky top-0 bg-background/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)"
    >
      <button class="text-muted-foreground hover:text-foreground transition -ml-1 p-1" aria-label="Back" @click="goBack">
        <ArrowLeft class="size-5" />
      </button>
      <h1 class="text-base font-semibold tracking-tight">Voice dictation</h1>
    </header>

    <div v-if="loading" class="px-4 py-10 text-center text-sm text-muted-foreground">Loading…</div>

    <ul v-else class="divide-y divide-border">
      <li class="flex items-center justify-between gap-3 px-4 py-3.5">
        <label for="voice-cleanup-model" class="flex items-center gap-3 min-w-0">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Mic class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0">
            <div class="font-medium">Cleanup model</div>
            <div class="text-[11px] text-muted-foreground">Claude model used to clean up voice transcripts.</div>
          </div>
        </label>
        <select
          id="voice-cleanup-model"
          v-model="model"
          class="rounded-md bg-card border border-border px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-primary shrink-0 max-w-[180px]"
        >
          <option value="">Default (Haiku)</option>
          <option v-for="m in models" :key="m.id" :value="m.id">{{ m.displayName }}</option>
        </select>
      </li>
    </ul>

    <div v-if="!loading" class="flex items-center gap-2 px-4 py-4">
      <button
        class="flex-1 rounded-md bg-primary text-primary-foreground px-4 py-2 text-sm font-medium disabled:opacity-50"
        :disabled="saving"
        @click="save"
      >
        {{ saving ? "Saving…" : "Save" }}
      </button>
    </div>
  </div>
</template>
