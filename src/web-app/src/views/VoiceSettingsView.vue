<script setup lang="ts">
import { ref, computed, onMounted } from "vue"
import { useRouter } from "vue-router"
import { ArrowLeft, Bot, Cpu } from "lucide-vue-next"
import { api } from "@/api/client"
import { toast } from "vue-sonner"

const router = useRouter()

// Curated voice-cleanup engines (the direct-API adapter layer). Mirrors ENGINES in
// src/core/agent-api/index.ts, minus the gated Claude adapter (ban-risk opt-in) and
// the internal cursor-cli fallback. `family` is the AgentKind used to list models
// for that engine via GET /models?agent=<family>.
const ENGINES = [
  { id: "codex", label: "Codex", family: "codex" },
  { id: "opencode-zen", label: "OpenCode Zen", family: "opencode" },
  { id: "opencode-go", label: "OpenCode Go", family: "opencode" },
  { id: "cursor", label: "Cursor", family: "cursor" },
] as const
const DEFAULT_ENGINE = "codex"

const loading = ref(true)
const saving = ref(false)
const engine = ref<string>(DEFAULT_ENGINE)
const model = ref("")
const models = ref<{ id: string; displayName: string }[]>([])

const familyFor = (eng: string) => ENGINES.find((e) => e.id === eng)?.family ?? "codex"
const engineLabel = computed(() => ENGINES.find((e) => e.id === engine.value)?.label ?? engine.value)

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push("/settings")
}

async function loadModelsForEngine(eng: string) {
  try {
    const { models: ms } = await api.listModels(familyFor(eng))
    models.value = ms
  } catch {
    models.value = [] // engine still usable; falls back to its own default model
  }
}

async function load() {
  loading.value = true
  try {
    const cfg = await api.getAppConfig()
    engine.value = (typeof cfg.voiceCleanupEngine === "string" && cfg.voiceCleanupEngine) || DEFAULT_ENGINE
    model.value = cfg.voiceCleanupModel ?? ""
    await loadModelsForEngine(engine.value)
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to load voice settings")
  } finally {
    loading.value = false
  }
}

// Switching engine drops the now-irrelevant model (a codex model id is meaningless
// to cursor) and reloads the new engine's model list.
async function onEngineChange() {
  model.value = ""
  await loadModelsForEngine(engine.value)
}

async function save() {
  saving.value = true
  try {
    // model.value === "" sends "", which the broker treats as "reset to the
    // engine's default" (see sanitizeAppConfigPatch).
    await api.saveAppConfig({ voiceCleanupEngine: engine.value, voiceCleanupModel: model.value })
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
        <label for="voice-cleanup-engine" class="flex items-center gap-3 min-w-0">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Bot class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0">
            <div class="font-medium">Cleanup engine</div>
            <div class="text-[11px] text-muted-foreground">Direct-API agent that cleans up voice transcripts.</div>
          </div>
        </label>
        <select
          id="voice-cleanup-engine"
          v-model="engine"
          class="rounded-md bg-card border border-border px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-primary shrink-0 max-w-[180px]"
          @change="onEngineChange"
        >
          <option v-for="e in ENGINES" :key="e.id" :value="e.id">{{ e.label }}</option>
        </select>
      </li>

      <li class="flex items-center justify-between gap-3 px-4 py-3.5">
        <label for="voice-cleanup-model" class="flex items-center gap-3 min-w-0">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Cpu class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0">
            <div class="font-medium">Cleanup model</div>
            <div class="text-[11px] text-muted-foreground">Model for {{ engineLabel }}. Default uses the engine's own.</div>
          </div>
        </label>
        <select
          id="voice-cleanup-model"
          v-model="model"
          class="rounded-md bg-card border border-border px-3 py-1.5 text-sm focus:outline-none focus:ring-1 focus:ring-primary shrink-0 max-w-[180px]"
        >
          <option value="">Default</option>
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
