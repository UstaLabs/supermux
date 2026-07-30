<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue"
import { useRouter } from "vue-router"
import {
  ArrowLeft,
  Download,
  RefreshCw,
  CircleCheck,
  Loader2,
  AlertTriangle,
  ExternalLink,
} from "lucide-vue-next"
import { api } from "@/api/client"
import type { UpdateStatusDTO } from "@/api/client"
import { toast } from "vue-sonner"

const router = useRouter()
const status = ref<UpdateStatusDTO | null>(null)
const statusError = ref<string | null>(null)
const phase = ref<UpdateStatusDTO["state"] | "restarting" | null>(null)
const rechecking = ref(false)
const buildId = typeof __APP_BUILD_ID__ !== "undefined" ? __APP_BUILD_ID__ : "unknown"

let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollGen = 0

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push("/settings")
}

function applyStatus(s: UpdateStatusDTO) {
  status.value = s
  statusError.value = null
  if (s.mode === "binary" && phase.value === null &&
      (s.state === "checking" || s.state === "downloading" || s.state === "swapping")) {
    startPolling(s.current)
  }
}

/** Initial open: read cached checker status (no network re-poll). */
async function loadStatus() {
  try {
    applyStatus(await api.getUpdateStatus())
  } catch (e: any) {
    statusError.value = e?.message ?? "Failed to check for updates"
  }
}

/** Recheck: force the broker to poll versions.json now. */
async function recheck() {
  if (rechecking.value || phase.value) return
  rechecking.value = true
  try {
    applyStatus(await api.checkUpdate())
  } catch (e: any) {
    statusError.value = e?.message ?? "Failed to check for updates"
  } finally {
    rechecking.value = false
  }
}

onMounted(loadStatus)
onUnmounted(() => {
  pollGen++
  if (pollTimer) clearTimeout(pollTimer)
})

async function startUpdate() {
  if (!status.value) return
  const baseline = status.value.current
  phase.value = "checking"
  try {
    const res = await api.runUpdate()
    if (!("started" in res) || !res.started) {
      phase.value = null
      toast.error("error" in res ? res.error : "Could not start update")
      return
    }
    startPolling(baseline)
  } catch (e: any) {
    phase.value = null
    toast.error(e?.message ?? "Failed to start update")
  }
}

function startPolling(baseline: string) {
  if (pollTimer) clearTimeout(pollTimer)
  const gen = ++pollGen
  const tick = async () => {
    if (gen !== pollGen) return
    let s: UpdateStatusDTO
    try {
      s = await api.getUpdateStatus()
    } catch {
      if (gen !== pollGen) return
      phase.value = "restarting"
      pollTimer = setTimeout(tick, 2000)
      return
    }
    if (gen !== pollGen) return
    status.value = s
    if (s.current !== baseline) {
      phase.value = null
      pollTimer = null
      toast.success(`Updated to v${s.current}`)
      return
    }
    if (s.state === "failed") {
      phase.value = "failed"
      pollTimer = null
      toast.error(s.lastError ?? "Update failed")
      return
    }
    if (s.state === "restart-required") {
      phase.value = "restart-required"
      pollTimer = null
      toast("Update staged — restart the broker to finish")
      return
    }
    phase.value = s.state
    pollTimer = setTimeout(tick, 2000)
  }
  pollTimer = setTimeout(tick, 2000)
}

function phaseLabel(p: string): string {
  switch (p) {
    case "checking": return "Checking…"
    case "downloading": return "Downloading…"
    case "swapping": return "Swapping…"
    case "restarting": return "Restarting…"
    case "restart-required": return "Restart required"
    case "failed": return "Failed"
    default: return "Working…"
  }
}
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
      <h1 class="text-base font-semibold tracking-tight">Check for updates</h1>
      <button
        class="ml-auto text-sm text-muted-foreground hover:text-foreground px-2 py-1 disabled:opacity-50 inline-flex items-center gap-1.5"
        :disabled="!!phase || rechecking"
        @click="recheck"
      >
        <Loader2 v-if="rechecking" class="size-3.5 animate-spin" />
        Recheck
      </button>
    </header>

    <ul class="divide-y divide-border">
      <li class="flex items-start justify-between gap-3 px-4 py-3.5">
        <div class="flex items-start gap-3 min-w-0">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Download class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0 space-y-1">
            <div class="font-medium">Release</div>
            <div v-if="!status && statusError" class="text-[11px] text-destructive">{{ statusError }}</div>
            <div v-else-if="!status" class="text-[11px] text-muted-foreground">Checking…</div>
            <template v-else>
              <div class="text-[11px] text-muted-foreground">
                supermux {{ status.current }}
                <span class="opacity-70">· web {{ buildId }}</span>
              </div>

              <div v-if="phase" class="text-[11px] flex items-center gap-1.5"
                :class="phase === 'failed' ? 'text-destructive' : 'text-muted-foreground'">
                <Loader2 v-if="phase !== 'failed' && phase !== 'restart-required'" class="size-3 animate-spin" />
                <AlertTriangle v-else-if="phase === 'failed'" class="size-3" />
                <span>{{ phaseLabel(phase) }}</span>
              </div>

              <div v-else-if="status.disabled" class="text-[11px] text-muted-foreground">
                Update checks disabled (MUX_UPDATE_CHECK=0).
              </div>

              <template v-else-if="status.mode === 'binary'">
                <div v-if="status.updateAvailable" class="text-[11px] text-foreground">
                  Update available:
                  <a
                    v-if="status.notesUrl"
                    :href="status.notesUrl"
                    target="_blank"
                    rel="noopener noreferrer"
                    class="font-medium underline decoration-dotted inline-flex items-center gap-0.5"
                  >v{{ status.latest }}<ExternalLink class="size-3" /></a>
                  <span v-else class="font-medium">v{{ status.latest }}</span>
                </div>
                <div v-else class="text-[11px] text-muted-foreground flex items-center gap-1.5">
                  <CircleCheck class="size-3 text-emerald-500" />
                  <span>You're up to date</span>
                </div>
              </template>

              <div v-else-if="status.mode === 'source'" class="text-[11px] text-muted-foreground">
                Source install — update with git pull &amp;&amp; restart.
              </div>
              <div v-else-if="status.mode === 'docker'" class="text-[11px] text-muted-foreground">
                Docker install — docker compose pull &amp;&amp; docker compose up -d
              </div>
            </template>
          </div>
        </div>

        <button
          v-if="status && !phase && !status.disabled && status.mode === 'binary' && (status.updateAvailable || status.state === 'failed')"
          class="shrink-0 rounded-md bg-foreground text-background px-3 py-1.5 text-sm font-medium inline-flex items-center gap-1.5"
          @click="startUpdate"
        >
          <RefreshCw v-if="status.state === 'failed'" class="size-3.5" />
          {{ status.state === "failed" ? "Retry" : "Update" }}
        </button>
      </li>
    </ul>
  </div>
</template>
