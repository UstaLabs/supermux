<script setup lang="ts">
import { onMounted, onUnmounted, ref } from "vue"
import { useRouter } from "vue-router"
import {
  ArrowLeft,
  Server,
  Download,
  RefreshCw,
  Copy,
  Check,
  CircleCheck,
  Loader2,
  AlertTriangle,
  ExternalLink,
} from "lucide-vue-next"
import { api } from "@/api/client"
import type { UpdateStatusDTO } from "@/api/client"
import { toast } from "vue-sonner"
import RestartBrokerDialog from "@/components/RestartBrokerDialog.vue"

const router = useRouter()

const restarting = ref(false)
const showDialog = ref(false)

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push("/settings")
}

async function confirmRestart() {
  showDialog.value = false
  restarting.value = true
  try {
    await api.restartBroker()
    // The broker will go down — the WS disconnect will naturally show
    // the reconnecting state. No further UI action needed.
  } catch (e: any) {
    restarting.value = false
    toast.error(e?.message ?? "Failed to restart broker")
  }
}

// ── Updates ─────────────────────────────────────────────────────────────────
type UpdateState = UpdateStatusDTO["state"]

const status = ref<UpdateStatusDTO | null>(null)
const statusError = ref<string | null>(null)
// Live phase while a self-update runs (binary mode). null = not running.
// "restarting" is the special phase where the broker is unreachable mid-restart.
const phase = ref<UpdateState | "restarting" | null>(null)
const copied = ref(false)
const DOCKER_CMD = "docker compose pull && docker compose up -d"

let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollGen = 0

onMounted(loadStatus)
onUnmounted(() => {
  pollGen++
  if (pollTimer) clearTimeout(pollTimer)
})

async function loadStatus() {
  try {
    const s = await api.getUpdateStatus()
    status.value = s
    statusError.value = null
    // Resume a run already in flight (started via CLI or another tab).
    if (s.mode === "binary" && phase.value === null &&
        (s.state === "checking" || s.state === "downloading" || s.state === "swapping")) {
      startPolling(s.current)
    }
  } catch (e: any) {
    statusError.value = e?.message ?? "Failed to load update status"
  }
}

/** Relative time, no date lib: "<1m ago" / "Nm ago" / "Nh ago" / "Nd ago". */
function relativeTime(ts: number): string {
  const secs = Math.max(0, Math.floor((Date.now() - ts) / 1000))
  if (secs < 60) return "<1m ago"
  const mins = Math.floor(secs / 60)
  if (mins < 60) return `${mins}m ago`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.floor(hours / 24)}d ago`
}

function phaseLabel(p: UpdateState | "restarting"): string {
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

async function startUpdate() {
  if (!status.value) return
  const baseline = status.value.current
  phase.value = "checking"
  try {
    const res = await api.runUpdate()
    if (!("started" in res) || !res.started) {
      // 400/409 bodies normally throw in request(); this guards a 2xx non-start.
      const msg = "error" in res ? res.error : "Could not start update"
      phase.value = null
      toast.error(msg)
      return
    }
    startPolling(baseline)
  } catch (e: any) {
    phase.value = null
    toast.error(e?.message ?? "Failed to start update")
  }
}

// Poll status every 2s. While the broker answers we mirror its state; the moment
// a fetch throws (systemd has killed the process to restart it) we flip to
// "restarting" and keep polling the SAME endpoint — when it answers again we
// compare `current` against the pre-update baseline to confirm the new version.
function startPolling(baseline: string) {
  if (pollTimer) clearTimeout(pollTimer)
  const gen = ++pollGen
  const tick = async () => {
    if (gen !== pollGen) return                 // bail if unmounted/superseded
    let s: UpdateStatusDTO
    try {
      s = await api.getUpdateStatus()
    } catch {
      // Broker unreachable — expected during the systemd restart. Keep waiting.
      if (gen !== pollGen) return
      phase.value = "restarting"
      pollTimer = setTimeout(tick, 2000)
      return
    }
    if (gen !== pollGen) return                 // bail after the await too
    status.value = s
    statusError.value = null

    if (s.current !== baseline) {
      // New version is live (binary swapped + broker came back up).
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
      // Non-systemd install: staged on disk, broker stays up. Tell the user.
      phase.value = "restart-required"
      pollTimer = null
      toast("Update staged — restart the broker to finish")
      return
    }
    // Still checking / downloading / swapping → mirror and keep polling.
    phase.value = s.state
    pollTimer = setTimeout(tick, 2000)
  }
  // First poll after a short beat so the broker has claimed the slot.
  pollTimer = setTimeout(tick, 2000)
}

async function copyDockerCmd() {
  try {
    await navigator.clipboard.writeText(DOCKER_CMD)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    toast.error("Couldn't copy to clipboard")
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
      <h1 class="text-base font-semibold tracking-tight">System</h1>
    </header>

    <ul class="divide-y divide-border">
      <li class="flex items-center justify-between gap-3 px-4 py-3.5">
        <div class="flex items-center gap-3 min-w-0">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Server class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0">
            <div class="font-medium">Restart broker</div>
            <div class="text-[11px] text-muted-foreground">Active sessions will reconnect automatically.</div>
          </div>
        </div>
        <button
          class="rounded-md bg-destructive text-destructive-foreground px-3 py-1.5 text-sm font-medium disabled:opacity-50"
          :disabled="restarting"
          @click="showDialog = true"
        >
          {{ restarting ? "Restarting…" : "Restart" }}
        </button>
      </li>

      <!-- Updates -->
      <li class="flex items-start justify-between gap-3 px-4 py-3.5">
        <div class="flex items-start gap-3 min-w-0">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Download class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0 space-y-1">
            <div class="font-medium">Updates</div>

            <!-- Couldn't load status at all -->
            <div v-if="!status && statusError" class="text-[11px] text-destructive">
              {{ statusError }}
            </div>
            <div v-else-if="!status" class="text-[11px] text-muted-foreground">Loading…</div>

            <template v-else>
              <!-- Version row, always shown -->
              <div class="text-[11px] text-muted-foreground">
                supermux {{ status.current }}
                <span class="opacity-70">· {{ status.commit }}</span>
              </div>

              <!-- A self-update is running (binary mode) -->
              <div v-if="phase" class="text-[11px] flex items-center gap-1.5"
                :class="phase === 'failed' ? 'text-destructive' : 'text-muted-foreground'">
                <Loader2 v-if="phase !== 'failed' && phase !== 'restart-required'" class="size-3 animate-spin" />
                <AlertTriangle v-else-if="phase === 'failed'" class="size-3" />
                <span>{{ phaseLabel(phase) }}</span>
              </div>

              <!-- Checks disabled -->
              <div v-else-if="status.disabled" class="text-[11px] text-muted-foreground">
                Update checks disabled (MUX_UPDATE_CHECK=0).
              </div>

              <!-- Binary mode -->
              <template v-else-if="status.mode === 'binary'">
                <div v-if="status.state === 'failed'" class="text-[11px] text-destructive flex items-center gap-1.5">
                  <AlertTriangle class="size-3 shrink-0" />
                  <span>{{ status.lastError ?? "Last update failed" }}</span>
                </div>
                <div v-else-if="status.state === 'restart-required'" class="text-[11px] text-muted-foreground">
                  Update staged — restart the broker to finish.
                </div>
                <div v-else-if="status.updateAvailable" class="text-[11px] text-foreground">
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
                <div v-else class="text-[11px] text-muted-foreground flex items-center gap-1">
                  <CircleCheck class="size-3 text-emerald-500" />
                  <span>
                    Up to date<template v-if="status.lastChecked"> · checked {{ relativeTime(status.lastChecked) }}</template>
                  </span>
                </div>
              </template>

              <!-- Source mode -->
              <div v-else-if="status.mode === 'source'" class="text-[11px] text-muted-foreground">
                Source install — update with git (git pull &amp;&amp; restart).
              </div>

              <!-- Docker mode -->
              <div v-else-if="status.mode === 'docker'" class="space-y-1.5">
                <div class="text-[11px] text-muted-foreground">Docker install — update with:</div>
                <div class="flex items-center gap-1.5">
                  <code class="text-[11px] font-mono bg-card ring-1 ring-border rounded px-1.5 py-1 select-all break-all">{{ DOCKER_CMD }}</code>
                  <button
                    class="shrink-0 rounded-md ring-1 ring-border p-1.5 text-muted-foreground hover:text-foreground"
                    :aria-label="copied ? 'Copied' : 'Copy command'"
                    @click="copyDockerCmd"
                  >
                    <Check v-if="copied" class="size-3.5 text-emerald-500" />
                    <Copy v-else class="size-3.5" />
                  </button>
                </div>
              </div>
            </template>
          </div>
        </div>

        <!-- Action: Update / Retry (binary mode only, not while running) -->
        <button
          v-if="status && !phase && !status.disabled && status.mode === 'binary' && (status.updateAvailable || status.state === 'failed')"
          class="shrink-0 rounded-md bg-foreground text-background px-3 py-1.5 text-sm font-medium disabled:opacity-50 inline-flex items-center gap-1.5"
          @click="startUpdate"
        >
          <RefreshCw v-if="status.state === 'failed'" class="size-3.5" />
          {{ status.state === "failed" ? "Retry" : "Update" }}
        </button>
        <button
          v-else-if="status && phase && phase !== 'failed' && phase !== 'restart-required'"
          class="shrink-0 rounded-md bg-foreground/60 text-background px-3 py-1.5 text-sm font-medium inline-flex items-center gap-1.5"
          disabled
        >
          <Loader2 class="size-3.5 animate-spin" />
          {{ phaseLabel(phase) }}
        </button>
      </li>
    </ul>

    <RestartBrokerDialog v-model:open="showDialog" @confirm="confirmRestart" />
  </div>
</template>
