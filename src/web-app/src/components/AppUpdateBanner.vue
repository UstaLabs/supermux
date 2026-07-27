<script setup lang="ts">
import { onMounted, ref } from "vue"
import { useRouter } from "vue-router"
import { Download, X } from "lucide-vue-next"
import { api } from "@/api/client"
import { useAuth } from "@/stores/auth"

const router = useRouter()
const auth = useAuth()
const latest = ref<string | null>(null)
const dismissed = ref(false)
const KEY = "supermux_web_update_dismissed"

onMounted(async () => {
  if (!auth.paired) return
  try {
    const s = await api.getUpdateStatus()
    if (s.updateAvailable && s.latest) {
      const d = localStorage.getItem(KEY)
      if (d !== s.latest) latest.value = s.latest
    }
  } catch {
    // non-critical
  }
})

function dismiss() {
  if (latest.value) localStorage.setItem(KEY, latest.value)
  dismissed.value = true
}

function openPage() {
  router.push("/settings/updates")
}
</script>

<template>
  <div
    v-if="latest && !dismissed"
    class="flex items-center gap-2 px-3 py-2 bg-primary/15 text-foreground text-sm border-b border-border"
  >
    <Download class="size-4 shrink-0 text-primary" />
    <button class="flex-1 text-left min-w-0" @click="openPage">
      Update available: v{{ latest }}
    </button>
    <button
      class="rounded-md bg-foreground text-background px-2.5 py-1 text-xs font-medium"
      @click="openPage"
    >
      Update
    </button>
    <button class="p-1 text-muted-foreground hover:text-foreground" aria-label="Dismiss" @click="dismiss">
      <X class="size-4" />
    </button>
  </div>
</template>
