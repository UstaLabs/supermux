<script setup lang="ts">
import { ref, computed, onMounted } from "vue"
import { useRouter } from "vue-router"
import { ArrowLeft, Sparkle, Play } from "lucide-vue-next"
import Switch from "@/components/ui/switch/Switch.vue"
import { api } from "@/api/client"
import { toast } from "vue-sonner"

const router = useRouter()

const loading = ref(true)
const saving = ref(false)
const running = ref(false)
const enabled = ref(false)
const hour = ref(1)
const minute = ref(0)
const nextRun = ref<string | null>(null)

// <input type="time"> binds an "HH:MM" string.
const timeStr = computed({
  get: () => `${String(hour.value).padStart(2, "0")}:${String(minute.value).padStart(2, "0")}`,
  set: (v: string) => {
    const [h, m] = v.split(":").map((n) => parseInt(n, 10))
    if (Number.isFinite(h)) hour.value = h
    if (Number.isFinite(m)) minute.value = m
  },
})

const nextRunLabel = computed(() => {
  if (!enabled.value) return "Disabled"
  if (!nextRun.value) return "—"
  try {
    return new Date(nextRun.value).toLocaleString()
  } catch {
    return nextRun.value
  }
})

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push("/settings")
}

async function load() {
  loading.value = true
  try {
    const r = await api.getCuratorSettings()
    enabled.value = r.config.enabled
    hour.value = r.config.hour
    minute.value = r.config.minute
    nextRun.value = r.nextRun
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to load curator settings")
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    const r = await api.saveCuratorSettings({ enabled: enabled.value, hour: hour.value, minute: minute.value })
    nextRun.value = r.nextRun
    toast.success("Saved")
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to save")
  } finally {
    saving.value = false
  }
}

async function runNow() {
  running.value = true
  try {
    await api.runCuratorNow()
    toast.success("Curator run started")
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to start")
  } finally {
    running.value = false
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
      <h1 class="text-base font-semibold tracking-tight">Curator</h1>
    </header>

    <div v-if="loading" class="px-4 py-10 text-center text-sm text-muted-foreground">Loading…</div>

    <ul v-else class="divide-y divide-border">
      <li class="flex items-center justify-between gap-3 px-4 py-3.5">
        <label for="curator-enabled" class="flex items-center gap-3 min-w-0 cursor-pointer">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Sparkle class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0">
            <div class="font-medium">Nightly curator</div>
            <div class="text-[11px] text-muted-foreground">Curate ~/.mux daily, commit + push, and post a digest.</div>
          </div>
        </label>
        <Switch id="curator-enabled" v-model="enabled" />
      </li>

      <li class="flex items-center justify-between gap-3 px-4 py-3.5">
        <div class="min-w-0">
          <div class="font-medium">Run at</div>
          <div class="text-[11px] text-muted-foreground">Daily, host local time.</div>
        </div>
        <input
          v-model="timeStr"
          type="time"
          class="rounded-md bg-card border border-border px-3 py-1.5 text-sm tabular-nums focus:outline-none focus:ring-1 focus:ring-primary"
        />
      </li>

      <li class="flex items-center justify-between gap-3 px-4 py-3.5">
        <div class="min-w-0">
          <div class="font-medium">Next run</div>
          <div class="text-[11px] text-muted-foreground">The digest notifies all your devices.</div>
        </div>
        <div class="text-sm text-muted-foreground tabular-nums">{{ nextRunLabel }}</div>
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
      <button
        class="flex items-center gap-1.5 rounded-md border border-border px-4 py-2 text-sm disabled:opacity-50"
        :disabled="running"
        @click="runNow"
      >
        <Play class="size-4" />
        {{ running ? "Starting…" : "Run now" }}
      </button>
    </div>
  </div>
</template>
