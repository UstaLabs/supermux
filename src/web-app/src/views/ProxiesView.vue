<script setup lang="ts">
import { ref, onMounted } from "vue"
import { ArrowLeft, Plus, ExternalLink, Trash2, Network } from "lucide-vue-next"
import { useProxies, type Proxy as ProxyRow } from "@/stores/proxies"
import { displayUrl } from "@/lib/proxy-url"
import { api } from "@/api/client"
import { toast } from "vue-sonner"
import DeleteProxyDialog from "@/components/DeleteProxyDialog.vue"
import CreateProxyForm from "@/components/CreateProxyForm.vue"
import PublicProxyConfirmDialog from "@/components/PublicProxyConfirmDialog.vue"

const proxies = useProxies()

const showCreateForm = ref(false)
const deleteTarget = ref<string | null>(null)
const showDeleteConfirm = ref(false)
const publicTarget = ref<string | null>(null)
const showPublicConfirm = ref(false)
const toggling = ref<string | null>(null)

function requestDelete(domain: string) {
  deleteTarget.value = domain
  showDeleteConfirm.value = true
}

async function confirmDelete() {
  const domain = deleteTarget.value
  if (!domain) return
  showDeleteConfirm.value = false
  try {
    await api.removeProxy(domain)
    proxies.remove(domain)
    toast.success(`Proxy removed`)
  } catch (err: any) {
    toast.error(err?.message ?? "Failed to remove proxy")
  }
  deleteTarget.value = null
}

async function applyPublic(domain: string, isPublic: boolean) {
  const item = proxies.list.find((x) => x.domain === domain)
  const prev = item?.isPublic
  if (item) item.isPublic = isPublic
  toggling.value = domain
  try {
    const updated = await api.setProxyPublic(domain, isPublic)
    proxies.add(updated)
    toast.success(isPublic ? "Proxy is now public" : "Proxy is now private")
  } catch (err: any) {
    if (item && prev !== undefined) item.isPublic = prev
    toast.error(err?.message ?? "Failed to update proxy")
  } finally {
    toggling.value = null
  }
}

function onPublicToggle(p: ProxyRow, next: boolean) {
  if (next) {
    publicTarget.value = p.domain
    showPublicConfirm.value = true
    return
  }
  void applyPublic(p.domain, false)
}

async function confirmPublic() {
  const domain = publicTarget.value
  if (!domain) return
  showPublicConfirm.value = false
  await applyPublic(domain, true)
  publicTarget.value = null
}

async function refresh() {
  try {
    const list = await api.listProxies()
    proxies.replace(list.map((p: any) => ({ ...p, isPublic: !!p.isPublic })))
  } catch {}
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
          <ArrowLeft class="size-5" />
        </router-link>
        <h1 class="text-base font-semibold tracking-tight">Proxies</h1>
      </div>
    </header>

    <div v-if="proxies.list.length === 0" class="px-6 py-12 text-center text-muted-foreground">
      <div class="mx-auto size-14 rounded-2xl bg-card ring-1 ring-border flex items-center justify-center mb-4">
        <Network class="size-6 text-muted-foreground" />
      </div>
      <p class="text-sm font-medium text-foreground">No active proxies</p>
      <p class="text-xs mt-1 mb-4">Expose a local port from any session to the web.</p>
      <button class="text-xs text-primary" @click="showCreateForm = true">Create one</button>
    </div>

    <ul class="divide-y divide-border">
      <li v-for="p in proxies.list" :key="p.domain" class="flex items-center justify-between gap-3 px-4 py-3">
        <div class="flex items-center gap-3 min-w-0 flex-1">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Network class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0 flex-1">
            <div class="flex items-center gap-2 min-w-0">
              <a
                :href="p.url"
                target="_blank"
                rel="noopener noreferrer"
                class="font-medium text-primary hover:underline truncate flex items-center gap-1 min-w-0"
              >
                <span class="truncate">{{ displayUrl(p.url) }}</span>
                <ExternalLink class="size-3 shrink-0" />
              </a>
              <span
                v-if="p.isPublic"
                class="shrink-0 text-[10px] font-medium uppercase tracking-wide px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600 dark:text-amber-400"
              >Public</span>
            </div>
            <div class="text-[11px] text-muted-foreground truncate">
              {{ p.sessionName }} · port {{ p.port }}
            </div>
          </div>
        </div>
        <div class="flex items-center gap-2 shrink-0">
          <button
            type="button"
            role="switch"
            :aria-checked="!!p.isPublic"
            :disabled="toggling === p.domain"
            class="text-[11px] font-medium px-2 py-1 rounded-md border transition disabled:opacity-50"
            :class="p.isPublic
              ? 'border-amber-500/40 bg-amber-500/15 text-amber-600 dark:text-amber-400'
              : 'border-border bg-muted/40 text-muted-foreground hover:text-foreground'"
            @click="onPublicToggle(p, !p.isPublic)"
          >
            {{ p.isPublic ? "Public" : "Private" }}
          </button>
          <button
            class="p-2 rounded-md text-muted-foreground hover:text-red-400 hover:bg-red-500/10 transition"
            aria-label="Remove proxy"
            @click="requestDelete(p.domain)"
          >
            <Trash2 class="size-4" />
          </button>
        </div>
      </li>
    </ul>

    <!-- FAB -->
    <button
      class="fixed right-5 bottom-6 z-30 size-14 rounded-full bg-primary text-primary-foreground shadow-lg flex items-center justify-center active:scale-95 transition-transform"
      style="bottom: calc(env(safe-area-inset-bottom, 0px) + 1.5rem)"
      aria-label="New proxy"
      @click="showCreateForm = true"
    >
      <Plus class="size-6" />
    </button>
  </div>

  <DeleteProxyDialog
    :open="showDeleteConfirm"
    :domain="deleteTarget ?? ''"
    @update:open="showDeleteConfirm = $event"
    @confirm="confirmDelete"
  />

  <PublicProxyConfirmDialog
    :open="showPublicConfirm"
    :domain="publicTarget ?? ''"
    @update:open="(v) => { showPublicConfirm = v; if (!v) publicTarget = null }"
    @confirm="confirmPublic"
  />

  <CreateProxyForm
    :open="showCreateForm"
    @update:open="showCreateForm = $event"
    @created="refresh"
  />
</template>
