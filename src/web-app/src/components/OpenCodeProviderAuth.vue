<script setup lang="ts">
import { ref, computed, onMounted } from "vue"
import { api } from "@/api/client"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Link, Check, RefreshCw } from "lucide-vue-next"
import { toast } from "vue-sonner"

type Method = { type: string; label: string; index: number }
type Provider = { id: string; configured: boolean; methods: Method[] }

// Anthropic + OpenAI are already covered by the claude/codex agents, so hide
// them here to avoid duplicate auth surfaces (per product decision).
const HIDDEN = new Set(["anthropic", "openai"])

const providers = ref<Provider[]>([])
const loading = ref(false)

const keyValues = ref<Record<string, string>>({})
const saving = ref<Record<string, boolean>>({})
const oauth = ref<Record<string, { method: number; url: string; code: string; submitting: boolean }>>({})

const visible = computed(() => {
  const list = providers.value.filter((p) => !HIDDEN.has(p.id))
  // The same OpenCode API key can be registered for Zen and Go. Zen isn't in
  // /provider/auth (it's the built-in free tier + a key from opencode.ai/auth),
  // so surface one explicit OpenCode key row and let the backend save both.
  if (!list.some((p) => p.id === "opencode")) {
    list.unshift({ id: "opencode", configured: false, methods: [{ type: "api", label: "OpenCode key (Zen + Go)", index: 0 }] })
  }
  return list
})

async function load() {
  loading.value = true
  try {
    providers.value = await api.getOpenCodeProviders()
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to load opencode providers")
  } finally {
    loading.value = false
  }
}

function prettyName(id: string) {
  return id.split(/[-_]/).map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(" ")
}
function oauthMethod(p: Provider) { return p.methods.find((m) => m.type === "oauth") }
function apiMethod(p: Provider) { return p.methods.find((m) => m.type === "api") }

async function saveKey(id: string) {
  const key = (keyValues.value[id] ?? "").trim()
  if (!key) return
  saving.value = { ...saving.value, [id]: true }
  try {
    await api.setOpenCodeKey(id, key)
    keyValues.value = { ...keyValues.value, [id]: "" }
    toast.success(`Connected ${prettyName(id)}`)
    await load()
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to save key")
  } finally {
    saving.value = { ...saving.value, [id]: false }
  }
}

async function startOAuth(id: string, method: number) {
  try {
    const { url } = await api.startOpenCodeOAuth(id, method)
    oauth.value = { ...oauth.value, [id]: { method, url, code: "", submitting: false } }
    window.open(url, "_blank", "noopener")
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to start login")
  }
}

async function finishOAuth(id: string) {
  const st = oauth.value[id]
  if (!st || !st.code.trim()) return
  oauth.value = { ...oauth.value, [id]: { ...st, submitting: true } }
  try {
    await api.finishOpenCodeOAuth(id, st.method, st.code.trim())
    const next = { ...oauth.value }; delete next[id]; oauth.value = next
    toast.success(`Connected ${prettyName(id)}`)
    await load()
  } catch (e: any) {
    oauth.value = { ...oauth.value, [id]: { ...st, submitting: false } }
    toast.error(e?.message ?? "Login failed")
  }
}

onMounted(load)
</script>

<template>
  <div class="space-y-2">
    <div class="flex items-center justify-between">
      <p class="text-[11px] font-medium uppercase tracking-wide text-muted-foreground">Connect a provider</p>
      <button class="text-muted-foreground hover:text-foreground" title="Refresh" @click="load">
        <RefreshCw class="size-3.5" :class="loading ? 'animate-spin' : ''" />
      </button>
    </div>
    <p class="text-[11px] text-muted-foreground">Free models work out of the box — connect a subscription for more.</p>

    <ul class="space-y-1.5">
      <li v-for="p in visible" :key="p.id" class="rounded-md border border-border p-2.5 space-y-2">
        <div class="flex items-center gap-1.5 text-sm font-medium">
          {{ prettyName(p.id) }}
          <Check v-if="p.configured" class="size-3.5 text-emerald-400" />
        </div>

        <div v-if="oauth[p.id]" class="space-y-1.5">
          <p class="text-[11px] text-muted-foreground">A browser tab opened — authorize, then paste the code:</p>
          <a :href="oauth[p.id]!.url" target="_blank" rel="noopener" class="inline-flex items-center gap-1 text-xs text-primary">
            <Link class="size-3" /> Reopen sign-in
          </a>
          <div class="flex gap-2">
            <Input v-model="oauth[p.id]!.code" placeholder="paste code" class="flex-1 text-xs font-mono" />
            <Button size="sm" :disabled="!oauth[p.id]!.code.trim() || oauth[p.id]!.submitting" @click="finishOAuth(p.id)">
              {{ oauth[p.id]!.submitting ? "..." : "Finish" }}
            </Button>
          </div>
        </div>

        <div v-else class="space-y-2">
          <Button v-if="oauthMethod(p)" variant="outline" size="sm" @click="startOAuth(p.id, oauthMethod(p)!.index)">
            <Link class="size-3.5 mr-1.5" /> Login via browser
          </Button>
          <div v-if="apiMethod(p)" class="space-y-1">
            <a v-if="p.id === 'opencode'" href="https://opencode.ai/auth" target="_blank" rel="noopener" class="inline-flex items-center gap-1 text-[11px] text-primary">
              <Link class="size-3" /> Get a key at opencode.ai/auth
            </a>
            <div class="flex gap-2">
              <Input v-model="keyValues[p.id]" :placeholder="apiMethod(p)!.label" type="password" class="flex-1 text-xs font-mono" />
              <Button size="sm" :disabled="!keyValues[p.id]?.trim() || saving[p.id]" @click="saveKey(p.id)">
                {{ saving[p.id] ? "..." : "Save" }}
              </Button>
            </div>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>
