<script setup lang="ts">
import { ref } from "vue"
import { useRouter } from "vue-router"
import { ArrowLeft, Server } from "lucide-vue-next"
import { api } from "@/api/client"
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
    </ul>

    <RestartBrokerDialog v-model:open="showDialog" @confirm="confirmRestart" />
  </div>
</template>
