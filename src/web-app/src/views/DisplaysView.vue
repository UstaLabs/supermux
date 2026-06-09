<script setup lang="ts">
import { ref, onMounted } from "vue"
import { ArrowLeft, Plus, Trash2, Monitor, X } from "lucide-vue-next"
import { useDisplays } from "@/stores/displays"
import { api } from "@/api/client"
import { toast } from "vue-sonner"
import DisplayPane from "@/components/DisplayPane.vue"

const displays = useDisplays()
const opened = ref<{ id: string; provider: string } | null>(null)
const starting = ref(false)

async function refresh() {
  try { displays.replace(await api.listDisplays()) } catch {}
}

async function startNew() {
  starting.value = true
  try {
    const info = await api.startDisplay({})
    toast.success("Display started")
    opened.value = { id: info.id, provider: info.provider }
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to start display")
  } finally {
    starting.value = false
  }
}

async function stop(id: string) {
  try { await api.stopDisplay(id); displays.remove(id); if (opened.value?.id === id) opened.value = null }
  catch (err: any) { toast.error(err?.message ?? "Failed to stop") }
}

onMounted(refresh)
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <header class="flex items-center justify-between px-3 py-3 border-b border-border sticky top-0 bg-background/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)">
      <div class="flex items-center gap-2">
        <router-link to="/" class="text-muted-foreground hover:text-foreground transition -ml-1 p-1" aria-label="Back">
          <ArrowLeft class="size-5" />
        </router-link>
        <h1 class="text-base font-semibold tracking-tight">Displays</h1>
      </div>
    </header>

    <div v-if="displays.list.length === 0" class="px-6 py-12 text-center text-muted-foreground">
      <div class="mx-auto size-14 rounded-2xl bg-card ring-1 ring-border flex items-center justify-center mb-4">
        <Monitor class="size-6 text-muted-foreground" />
      </div>
      <p class="text-sm font-medium text-foreground">No active displays</p>
      <p class="text-xs mt-1 mb-4">Start a virtual display, then run an emulator, browser, or app into it.</p>
      <button class="text-xs text-primary" :disabled="starting" @click="startNew">Start one</button>
    </div>

    <ul class="divide-y divide-border">
      <li v-for="d in displays.list" :key="d.id" class="flex items-center justify-between gap-3 px-4 py-3">
        <button class="flex items-center gap-3 min-w-0 text-left" @click="opened = { id: d.id, provider: d.provider }">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Monitor class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0">
            <div class="font-medium truncate">{{ d.display }} · {{ d.provider }}</div>
            <div class="text-[11px] text-muted-foreground truncate">{{ d.sessionName }} · {{ d.status }}</div>
          </div>
        </button>
        <button class="p-2 rounded-md text-muted-foreground hover:text-red-400 hover:bg-red-500/10 transition" aria-label="Stop display" @click="stop(d.id)">
          <Trash2 class="size-4" />
        </button>
      </li>
    </ul>

    <button class="fixed right-5 bottom-6 z-30 size-14 rounded-full bg-primary text-primary-foreground shadow-lg flex items-center justify-center active:scale-95 transition-transform"
      style="bottom: calc(env(safe-area-inset-bottom, 0px) + 1.5rem)" aria-label="New display" :disabled="starting" @click="startNew">
      <Plus class="size-6" />
    </button>

    <div v-if="opened" class="fixed inset-0 z-40 bg-black overscroll-none">
      <button class="absolute top-3 right-3 z-50 p-2 rounded-md bg-card/90 ring-1 ring-border" aria-label="Close" @click="opened = null">
        <X class="size-5" />
      </button>
      <DisplayPane :stream-id="opened.id" :provider="opened.provider" />
    </div>
  </div>
</template>
