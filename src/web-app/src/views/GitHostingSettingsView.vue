<script setup lang="ts">
import { onMounted, ref, computed } from "vue"
import { useRouter } from "vue-router"
import { ArrowLeft, GitBranch, ChevronDown } from "lucide-vue-next"
import { useForges } from "@/stores/forges"
import ForgeIcon from "@/components/ForgeIcon.vue"
import { Input } from "@/components/ui/input"
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet"

type Kind = "github" | "gitlab"
const KINDS: Kind[] = ["github", "gitlab"]

const router = useRouter()
const forges = useForges()

const sheetOpen = ref(false)
const addKind = ref<Kind>("github")
const token = ref("")
const baseUrl = ref("")
const transport = ref<"https" | "ssh">("https")
const showAdvanced = ref(false)
const submitting = ref(false)

const hasConnections = computed(() => forges.connections.length > 0)
const cli = computed(() => forges.cliStatus)

/** Offer CLI import only when that CLI is authed AND its account isn't already connected. */
function canImport(kind: Kind): boolean {
  const c = cli.value?.[kind]
  if (!c?.available) return false
  const login = c.login?.toLowerCase()
  if (!login) return true
  return !forges.connections.some((x) => x.kind === kind && x.account.login.toLowerCase() === login)
}

onMounted(() => forges.loadConnections())

function goBack() { if (window.history.length > 1) router.back(); else router.push("/settings") }

function openSheet(kind?: Kind) {
  if (kind) addKind.value = kind
  token.value = ""; baseUrl.value = ""; transport.value = "https"; showAdvanced.value = false
  forges.error = null
  sheetOpen.value = true
}

/** Host from the optional self-hosted base URL (empty for SaaS). */
const tokenHost = computed(() => baseUrl.value.trim().replace(/^https?:\/\//, "").replace(/\/.*$/, ""))

/** Encode params with %20 for spaces, matching GitHub/GitLab template-URL docs. */
function enc(p: Record<string, string>): string {
  return new URLSearchParams(p).toString().replace(/\+/g, "%20")
}

const TOKEN_NAME = "supermux"
const TOKEN_DESC = "Clone, create & push repos from supermux"

/** Token-creation link, pre-filled with the name + exact scopes supermux needs. */
const tokenDocsUrl = computed(() => {
  const host = tokenHost.value
  if (addKind.value === "github") {
    // Self-hosted GHES: classic tokens prefill on all versions (fine-grained template URLs need ≥3.19).
    if (host && host !== "github.com")
      return `https://${host}/settings/tokens/new?${enc({ description: TOKEN_DESC, scopes: "repo,read:org" })}`
    // github.com: fine-grained template URL — one query param per permission (GA Aug 2025).
    return `https://github.com/settings/personal-access-tokens/new?${enc({ name: TOKEN_NAME, description: TOKEN_DESC, contents: "write", administration: "write" })}`
  }
  // GitLab (SaaS or self-hosted ≥14.1): name + scopes prefill.
  const base = host && host !== "gitlab.com" ? `https://${host}` : "https://gitlab.com"
  return `${base}/-/user_settings/personal_access_tokens?${enc({ name: TOKEN_NAME, scopes: "api", description: TOKEN_DESC })}`
})

const scopesHint = computed(() => {
  if (addKind.value === "github")
    return tokenHost.value && tokenHost.value !== "github.com" ? "repo, read:org" : "Contents + Administration (read & write)"
  return "api"
})

async function submit() {
  if (!token.value.trim()) return
  submitting.value = true
  try {
    await forges.connect({ kind: addKind.value, token: token.value.trim(), host: baseUrl.value.trim() || undefined, source: "pat", transport: transport.value })
    sheetOpen.value = false
  } catch { /* surfaced via forges.error */ } finally { submitting.value = false }
}
async function importCli(kind: Kind) {
  submitting.value = true
  try { await forges.importFromCli(kind, transport.value); sheetOpen.value = false } catch { /* surfaced */ } finally { submitting.value = false }
}
async function disconnect(id: string) {
  if (!confirm("Disconnect this account?")) return
  submitting.value = true
  try { await forges.disconnect(id) } finally { submitting.value = false }
}
</script>

<template>
  <div class="min-h-screen bg-background text-foreground">
    <header
      class="flex items-center gap-2 px-3 py-3 border-b border-border sticky top-0 bg-background/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)"
    >
      <button type="button" class="cmux-icon-button" aria-label="Back" @click="goBack"><ArrowLeft class="size-5" /></button>
      <h1 class="text-base font-semibold tracking-tight">Git hosting</h1>
    </header>

    <div class="mx-auto w-full max-w-2xl">
      <p v-if="forges.error" class="text-sm text-destructive px-4 pt-4">{{ forges.error }}</p>

      <!-- ── Empty state (first-run) ───────────────────────────── -->
      <div v-if="!hasConnections" class="flex flex-col items-center text-center px-6 pt-10 pb-20">
        <div class="size-14 rounded-2xl bg-muted grid place-items-center mb-4"><GitBranch class="size-7 text-muted-foreground" /></div>
        <h2 class="text-xl font-semibold tracking-tight mb-1.5">Connect a Git host</h2>
        <p class="text-sm text-muted-foreground max-w-xs leading-relaxed mb-6">
          Bring your GitHub &amp; GitLab repos into supermux — clone, create, and launch sessions on them without leaving the app.
        </p>

        <template v-for="k in KINDS" :key="k">
          <button
            v-if="canImport(k)"
            type="button" :disabled="submitting" @click="importCli(k)"
            class="w-full flex items-center gap-3 text-left rounded-xl border p-3.5 mb-3 disabled:opacity-60 transition"
            style="border-color: color-mix(in oklab, var(--primary) 45%, var(--border)); background: color-mix(in oklab, var(--primary) 8%, var(--card))"
          >
            <ForgeIcon :kind="k" class="size-6 shrink-0" />
            <span class="flex-1 min-w-0">
              <span class="block text-sm font-semibold">Import from your {{ k === 'github' ? 'gh' : 'glab' }} CLI</span>
              <span class="block text-xs text-muted-foreground truncate">@{{ cli?.[k]?.login }} · already signed in</span>
            </span>
            <span class="rounded-lg bg-primary text-primary-foreground text-xs font-medium px-3 py-1.5">Import</span>
          </button>
        </template>

        <div class="flex items-center gap-2.5 text-muted-foreground text-xs my-3 w-full"><span class="h-px bg-border flex-1"></span>or connect manually<span class="h-px bg-border flex-1"></span></div>
        <div class="flex gap-2.5 w-full">
          <button
            v-for="k in KINDS" :key="k" type="button" @click="openSheet(k)"
            class="flex-1 inline-flex items-center justify-center gap-2 rounded-lg border border-border py-2.5 text-sm capitalize hover:bg-muted/50 transition"
          ><ForgeIcon :kind="k" class="size-4" /> {{ k }}</button>
        </div>
        <p class="text-xs text-muted-foreground max-w-xs mt-4">Uses a personal access token or your CLI login. Read-only unless you create or push.</p>
      </div>

      <!-- ── Accounts list (≥1 connection) ─────────────────────── -->
      <div v-else class="px-4 py-5">
        <p class="text-xs font-semibold uppercase tracking-wide text-muted-foreground mb-2.5">Accounts</p>
        <div v-for="c in forges.connections" :key="c.id" class="flex items-center gap-3 rounded-xl border border-border bg-card p-3 mb-2">
          <ForgeIcon :kind="c.kind" class="size-5 shrink-0" />
          <div class="min-w-0 flex-1">
            <div class="text-sm font-semibold flex items-center gap-2 truncate">
              @{{ c.account.login }}
              <span v-if="c.status === 'needs_reconnect'" class="text-[10px] rounded-full px-1.5 py-px border" style="color: var(--cmux-warning); border-color: color-mix(in oklab, var(--cmux-warning) 45%, transparent)">reconnect</span>
              <span v-else class="size-1.5 rounded-full" style="background:#3fb950"></span>
            </div>
            <div class="text-xs text-muted-foreground truncate">{{ c.host }} · {{ c.transport.toUpperCase() }}<span v-if="c.source === 'cli'"> · via CLI</span></div>
          </div>
          <button v-if="c.status === 'needs_reconnect'" type="button" class="text-xs text-primary" @click="openSheet(c.kind)">Reconnect</button>
          <button type="button" class="text-xs text-muted-foreground hover:text-foreground" @click="disconnect(c.id)">Disconnect</button>
        </div>
        <button type="button" @click="openSheet()" class="w-full flex items-center justify-center gap-2 rounded-xl border border-dashed border-border text-primary py-3 text-sm font-medium hover:bg-muted/30 transition">＋ Add account</button>
      </div>
    </div>

    <!-- ── Add-account sheet ─────────────────────────────────── -->
    <Sheet v-model:open="sheetOpen">
      <SheetContent side="bottom" class="max-w-[440px] mx-auto rounded-t-2xl px-5 pt-3 pb-[calc(env(safe-area-inset-bottom,0px)+1.25rem)]">
        <SheetTitle class="text-base">Add a Git account</SheetTitle>
        <div class="flex flex-col gap-3">
          <div class="inline-flex bg-secondary rounded-lg p-0.5 gap-0.5">
            <button
              v-for="k in KINDS" :key="k" type="button" @click="addKind = k"
              class="flex-1 inline-flex items-center justify-center gap-2 rounded-md py-2 text-xs font-medium capitalize"
              :class="addKind === k ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground'"
            ><ForgeIcon :kind="k" class="size-3.5" /> {{ k }}</button>
          </div>

          <button
            v-if="canImport(addKind)" type="button" :disabled="submitting" @click="importCli(addKind)"
            class="rounded-lg bg-primary text-primary-foreground text-sm py-2.5 font-medium disabled:opacity-60"
          >Import token from {{ addKind === 'github' ? 'gh' : 'glab' }} CLI<span v-if="cli?.[addKind]?.login"> (@{{ cli?.[addKind]?.login }})</span></button>
          <div v-if="canImport(addKind)" class="flex items-center gap-2.5 text-muted-foreground text-xs"><span class="h-px bg-border flex-1"></span>or paste a token<span class="h-px bg-border flex-1"></span></div>

          <div>
            <Input v-model="token" type="password" :placeholder="addKind === 'github' ? 'github_pat_…' : 'glpat-…'" class="font-mono" />
            <p class="text-xs text-muted-foreground mt-1.5"><a :href="tokenDocsUrl" target="_blank" rel="noreferrer" class="text-primary">Create a pre-filled token ↗</a> · needs {{ scopesHint }}</p>
          </div>

          <div class="border-t border-border pt-3">
            <button type="button" class="w-full flex items-center justify-between text-xs text-muted-foreground" @click="showAdvanced = !showAdvanced">
              Self-hosted &amp; transport
              <ChevronDown class="size-4 transition-transform" :class="showAdvanced ? 'rotate-180' : ''" />
            </button>
            <div v-if="showAdvanced" class="flex flex-col gap-2.5 mt-3">
              <Input v-model="baseUrl" type="text" placeholder="API base URL (self-hosted) — e.g. github.acme.com/api/v3" class="font-mono" />
              <div class="flex gap-2">
                <button v-for="t in (['https', 'ssh'] as const)" :key="t" type="button" @click="transport = t"
                  class="flex-1 rounded-md border py-2 text-xs uppercase" :class="transport === t ? 'border-primary text-primary' : 'border-border text-muted-foreground'">{{ t }}</button>
              </div>
              <p v-if="transport === 'ssh' && addKind === 'gitlab'" class="text-xs" style="color: var(--cmux-warning)">SSH for GitLab is experimental</p>
            </div>
          </div>

          <button type="button" @click="submit" :disabled="submitting || !token.trim()" class="rounded-lg bg-primary text-primary-foreground text-sm py-2.5 font-medium capitalize disabled:opacity-60">Connect {{ addKind }}</button>
        </div>
      </SheetContent>
    </Sheet>
  </div>
</template>
