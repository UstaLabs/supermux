<script setup lang="ts">
import { ref, onMounted, computed } from "vue"
import { ChevronLeft, Copy, Check, Plus, Smartphone } from "@lucide/vue"
import { api } from "@/api/client"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { useNotifications } from "@/composables/useNotifications"

const notifications = useNotifications()

interface Device { name: string; created_at: string; last_seen_at: string | null }

const devices = ref<Device[]>([])
const adding = ref(false)
const newName = ref("")
const newUrl = ref<string | null>(null)
const copiedAt = ref(0)
const copied = computed(() => copiedAt.value > 0 && Date.now() - copiedAt.value < 1500)

async function refresh() { devices.value = await api.listDevices() }

async function add() {
  if (!newName.value.trim()) return
  const result = await api.addDevice(newName.value.trim())
  newUrl.value = result.url
  newName.value = ""
  await refresh()
}

async function copyUrl() {
  if (!newUrl.value) return
  try {
    await navigator.clipboard.writeText(newUrl.value)
    copiedAt.value = Date.now()
    setTimeout(() => {}, 1600)
  } catch {}
}

async function revoke(name: string) {
  if (!confirm(`Revoke ${name}? Active web sessions on that device will disconnect immediately.`)) return
  await api.revokeDevice(name)
  await refresh()
}

function rel(ts: string | null): string {
  if (!ts) return "never"
  const d = Date.now() - new Date(ts).getTime()
  if (d < 60_000) return "just now"
  if (d < 3600_000) return `${Math.floor(d / 60_000)}m ago`
  if (d < 86_400_000) return `${Math.floor(d / 3_600_000)}h ago`
  return `${Math.floor(d / 86_400_000)}d ago`
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
        <router-link to="/" class="text-muted-foreground hover:text-foreground transition -ml-1 p-1" aria-label="Back">
          <ChevronLeft class="size-5" />
        </router-link>
        <h1 class="text-base font-semibold tracking-tight">Devices</h1>
      </div>
      <Dialog v-model:open="adding">
        <DialogTrigger as-child>
          <Button size="sm" class="gap-1.5">
            <Plus class="size-4" />
            Add device
          </Button>
        </DialogTrigger>
        <DialogContent class="sm:max-w-md">
          <DialogHeader><DialogTitle>Add device</DialogTitle></DialogHeader>
          <div v-if="!newUrl" class="space-y-3">
            <p class="text-xs text-muted-foreground">Give the new device a name. You'll get a one-time URL to open on that device.</p>
            <Input v-model="newName" placeholder="e.g. laptop, ipad, kitchen" autofocus />
            <Button class="w-full" :disabled="!newName.trim()" @click="add">Mint token</Button>
          </div>
          <div v-else class="space-y-3">
            <p class="text-sm text-muted-foreground">Open this URL on the new device:</p>
            <div class="relative">
              <code class="block break-all text-[11px] p-2.5 pr-10 bg-card border border-border rounded-md font-mono leading-relaxed">{{ newUrl }}</code>
              <button
                type="button"
                @click="copyUrl"
                class="absolute top-1.5 right-1.5 p-1.5 rounded-md bg-card hover:bg-muted text-muted-foreground transition"
                :title="copied ? 'Copied' : 'Copy'"
              >
                <Check v-if="copied" class="size-4 text-emerald-400" />
                <Copy v-else class="size-4" />
              </button>
            </div>
            <p class="text-xs text-muted-foreground">
              Treat this URL like a password — anyone who opens it gets access until you revoke the device.
            </p>
            <Button class="w-full" variant="secondary" @click="newUrl = null; adding = false; refresh()">Done</Button>
          </div>
        </DialogContent>
      </Dialog>
    </header>

    <div
      v-if="notifications.status.value === 'denied'"
      class="mx-4 mt-3 px-3 py-2 text-xs text-muted-foreground border border-border rounded-md bg-card/40"
    >
      Notifications were blocked by your browser. Enable them in browser settings to receive pushes.
    </div>

    <div v-if="devices.length === 0" class="px-6 py-12 text-center text-muted-foreground">
      <div class="mx-auto size-14 rounded-2xl bg-card ring-1 ring-border flex items-center justify-center mb-4">
        <Smartphone class="size-6 text-muted-foreground" />
      </div>
      <p class="text-sm font-medium text-foreground">No devices paired yet</p>
      <p class="text-xs mt-1">Run <code class="text-primary font-mono">bun run pair &lt;name&gt;</code> on your broker host.</p>
    </div>

    <ul class="divide-y divide-border">
      <li v-for="d in devices" :key="d.name" class="flex items-center justify-between gap-3 px-4 py-3">
        <div class="flex items-center gap-3 min-w-0">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Smartphone class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0">
            <div class="font-medium truncate">{{ d.name }}</div>
            <div class="text-[11px] text-muted-foreground truncate">paired {{ d.created_at.slice(0, 10) }} · last seen {{ rel(d.last_seen_at) }}</div>
          </div>
        </div>
        <Button variant="ghost" size="sm" class="text-red-400 hover:text-red-300 hover:bg-red-500/10" @click="revoke(d.name)">
          Revoke
        </Button>
      </li>
    </ul>
  </div>
</template>
