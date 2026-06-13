<script setup lang="ts">
import { onMounted, ref } from "vue"
import { useRouter } from "vue-router"
import { ArrowLeft, Trash2, RotateCw } from "lucide-vue-next"
import { useForges } from "@/stores/forges"
import ForgeIcon from "@/components/ForgeIcon.vue"

const router = useRouter()
const forges = useForges()

const addKind = ref<"github" | "gitlab">("github")
const token = ref("")
const baseUrl = ref("")
const transport = ref<"https" | "ssh">("https")
const submitting = ref(false)

onMounted(() => { forges.loadConnections(); forges.loadCloned() })

function goBack() { if (window.history.length > 1) router.back(); else router.push("/settings") }

async function submit() {
  if (!token.value.trim()) return
  submitting.value = true
  try { await forges.connect({ kind: addKind.value, token: token.value.trim(), host: baseUrl.value.trim() || undefined, source: "pat", transport: transport.value }); token.value = ""; baseUrl.value = "" }
  catch { /* surfaced via forges.error */ } finally { submitting.value = false }
}
async function importCli(kind: "github" | "gitlab") {
  submitting.value = true
  try { await forges.importFromCli(kind, transport.value) } catch { /* surfaced */ } finally { submitting.value = false }
}
async function disconnect(id: string) { if (!confirm("Disconnect this account?")) return; submitting.value = true; try { await forges.disconnect(id) } finally { submitting.value = false } }
async function del(path: string) { if (!confirm("Delete this cloned repo from disk?")) return; submitting.value = true; try { await forges.removeCloned(path) } finally { submitting.value = false } }
async function pull(path: string) { submitting.value = true; try { await forges.pullCloned(path) } catch { /* surfaced via forges.error */ } finally { submitting.value = false } }

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`
  const u = ["KB", "MB", "GB"]; let v = n / 1024, i = 0
  while (v >= 1024 && i < u.length - 1) { v /= 1024; i++ }
  return `${v.toFixed(v < 10 ? 1 : 0)} ${u[i]}`
}
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <header class="flex items-center gap-2 px-3 py-3 border-b border-border sticky top-0 bg-background/95 backdrop-blur z-10" style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)">
      <button type="button" class="cmux-icon-button" aria-label="Back" @click="goBack"><ArrowLeft class="size-5" /></button>
      <h1 class="text-base font-semibold tracking-tight">Git hosting</h1>
    </header>

    <div class="mx-auto w-full max-w-2xl px-4 py-6 flex flex-col gap-5">
      <p v-if="forges.error" class="text-sm text-destructive">{{ forges.error }}</p>

      <section class="rounded-xl border border-border bg-card p-4">
        <h2 class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-3">Connected accounts</h2>
        <p v-if="!forges.connections.length" class="text-sm text-muted-foreground">No accounts connected yet.</p>
        <ul v-else class="flex flex-col divide-y divide-border">
          <li v-for="c in forges.connections" :key="c.id" class="flex items-center gap-3 py-2.5">
            <ForgeIcon :kind="c.kind" class="size-5 shrink-0" />
            <div class="min-w-0 flex-1">
              <div class="text-sm font-medium truncate">{{ c.label }}<span v-if="c.status === 'needs_reconnect'" class="ml-1 text-xs" style="color:var(--cmux-warning)">· reconnect</span></div>
              <div class="text-xs text-muted-foreground truncate">{{ c.transport.toUpperCase() }}<span v-if="c.source === 'cli'"> · via CLI</span><span v-if="c.ssh"> · key {{ c.ssh.registered ? 'registered' : 'add manually' }}</span></div>
            </div>
            <button type="button" class="text-xs text-muted-foreground hover:text-foreground" @click="disconnect(c.id)">Disconnect</button>
          </li>
        </ul>
      </section>

      <section class="rounded-xl border border-border bg-card p-4 flex flex-col gap-3">
        <h2 class="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Add a connection</h2>
        <div class="flex gap-2">
          <button v-for="k in (['github', 'gitlab'] as const)" :key="k" type="button"
            class="flex-1 inline-flex items-center justify-center gap-2 rounded-lg border px-3 py-2 text-sm capitalize"
            :class="addKind === k ? 'border-primary text-primary' : 'border-border text-muted-foreground'" @click="addKind = k">
            <ForgeIcon :kind="k" class="size-4" /> {{ k }}
          </button>
        </div>
        <button v-if="forges.cliStatus?.[addKind]?.available" type="button"
          class="rounded-lg bg-primary text-primary-foreground text-sm py-2 disabled:opacity-60" :disabled="submitting" @click="importCli(addKind)">
          Import token from {{ addKind === 'github' ? 'gh' : 'glab' }} CLI<span v-if="forges.cliStatus?.[addKind]?.login"> (@{{ forges.cliStatus?.[addKind]?.login }})</span>
        </button>
        <input v-model="token" type="password" placeholder="Personal access token" class="rounded-lg border border-border bg-input px-3 py-2 text-sm font-mono" />
        <input v-model="baseUrl" type="text" placeholder="Self-hosted base URL (optional)" class="rounded-lg border border-border bg-input px-3 py-2 text-sm font-mono" />
        <div class="flex gap-2 items-center text-sm">
          <span class="text-muted-foreground">Transport</span>
          <button v-for="t in (['https', 'ssh'] as const)" :key="t" type="button"
            class="rounded-md border px-2.5 py-1 text-xs uppercase" :class="transport === t ? 'border-primary text-primary' : 'border-border text-muted-foreground'" @click="transport = t">{{ t }}</button>
          <span v-if="transport === 'ssh' && addKind === 'gitlab'" class="text-xs" style="color:var(--cmux-warning)">experimental</span>
        </div>
        <button type="button" class="rounded-lg bg-primary text-primary-foreground text-sm py-2 disabled:opacity-60 capitalize" :disabled="submitting || !token.trim()" @click="submit">Connect {{ addKind }}</button>
      </section>

      <section class="rounded-xl border border-border bg-card p-4">
        <h2 class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-3">Cloned repositories</h2>
        <p v-if="!forges.clonedRepos.length" class="text-sm text-muted-foreground">Nothing cloned yet.</p>
        <ul v-else class="flex flex-col divide-y divide-border">
          <li v-for="r in forges.clonedRepos" :key="r.path" class="flex items-center gap-3 py-2.5">
            <div class="min-w-0 flex-1">
              <div class="text-sm font-medium truncate">{{ r.name }}</div>
              <div class="text-xs text-muted-foreground font-mono truncate">{{ r.path }} · {{ fmtBytes(r.sizeBytes) }}</div>
            </div>
            <button type="button" class="cmux-icon-button" aria-label="Pull" @click="pull(r.path)"><RotateCw class="size-4" /></button>
            <button type="button" class="cmux-icon-button text-destructive" aria-label="Delete" @click="del(r.path)"><Trash2 class="size-4" /></button>
          </li>
        </ul>
      </section>
    </div>
  </div>
</template>
