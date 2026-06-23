<script setup lang="ts">
import { ref, onMounted, onUnmounted } from "vue"
import { Check, Link, Key, X, Copy, Settings, ChevronDown } from "lucide-vue-next"
import { api, type InstallJob } from "@/api/client"
import AgentLogo from "@/components/AgentLogo.vue"
import OpenCodeProviderAuth from "@/components/OpenCodeProviderAuth.vue"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { toast } from "vue-sonner"

withDefaults(defineProps<{
  title?: string
  subtitle?: string
}>(), {
  title: "Agents",
  subtitle: "Manage CLI authorization and API key fallback for each agent.",
})

const emit = defineEmits<{
  (e: "update:canProceed", v: boolean): void
}>()

interface AgentStatus {
  kind: string
  installed: boolean
  authed: boolean
}

interface LoginState {
  phase: string
  url?: string
  code?: string
  error?: string
  needsCode?: boolean
}

const statuses = ref<AgentStatus[]>([])
const loading = ref(true)

const pasteValues = ref<Record<string, string>>({})
const pasteSaving = ref<Record<string, boolean>>({})

const loginStates = ref<Record<string, LoginState>>({})
const pollIntervals = ref<Record<string, ReturnType<typeof setInterval>>>({})

// Each agent row has a gear that expands its re-config panel (an accordion). Agents
// that aren't authed yet start open so setup is obvious; configured ones collapse
// behind the gear but stay re-authorizable. `expanded[kind]` overrides the default
// once the user toggles it.
const expanded = ref<Record<string, boolean>>({})
function isConfigOpen(s: AgentStatus): boolean {
  return expanded.value[s.kind] ?? !s.authed
}
function toggleConfig(s: AgentStatus) {
  expanded.value = { ...expanded.value, [s.kind]: !isConfigOpen(s) }
}
function statusLabel(s: AgentStatus): string {
  if (s.authed) return "Authenticated"
  if (!s.installed) return "Not installed"
  // opencode's free `opencode/*` tier runs with no credentials — usable without auth.
  if (s.kind === "opencode") return "Ready · free tier"
  return "Installed, not authenticated"
}

const fieldByKind: Record<string, string> = {
  claude: "claudeOauthToken",
  codex: "codexApiKey",
  cursor: "cursorApiKey",
}

const helpByKind: Record<string, string> = {
  claude: "Run `claude setup-token` on a machine with a browser, paste the token here.",
  codex: "Paste your OpenAI API key.",
  cursor: "Paste your Cursor API key.",
}

const loginSupportedKinds = ["claude", "codex", "cursor"]

async function refresh() {
  try {
    const result: AgentStatus[] = await api.getAgentStatuses()
    statuses.value = result
    // opencode's free tier is usable without auth, so it also satisfies "can proceed".
    emit("update:canProceed", result.some((s) => s.authed || (s.kind === "opencode" && s.installed)))
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to load agent statuses")
  } finally {
    loading.value = false
  }
}

async function saveKey(kind: string) {
  const val = (pasteValues.value[kind] ?? "").trim()
  if (!val) return
  pasteSaving.value = { ...pasteSaving.value, [kind]: true }
  try {
    const field = fieldByKind[kind]
    await api.saveAppConfig({ [field]: val })
    pasteValues.value = { ...pasteValues.value, [kind]: "" }
    toast.success("Saved")
    await refresh()
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to save")
  } finally {
    pasteSaving.value = { ...pasteSaving.value, [kind]: false }
  }
}

function stopPoll(kind: string) {
  const id = pollIntervals.value[kind]
  if (id !== undefined) {
    clearInterval(id)
    const next = { ...pollIntervals.value }
    delete next[kind]
    pollIntervals.value = next
  }
}

async function startLogin(kind: string) {
  try {
    await api.startAgentLogin(kind)
    loginStates.value = { ...loginStates.value, [kind]: { phase: "awaiting_user" } }
    const intervalId = setInterval(async () => {
      try {
        const state: LoginState = await api.getAgentLogin(kind)
        loginStates.value = { ...loginStates.value, [kind]: state }
        if (state.phase === "success") {
          stopPoll(kind)
          await refresh()
        } else if (state.phase === "failed") {
          stopPoll(kind)
          toast.error(state.error ?? `${kind} login failed`)
        }
      } catch (e: any) {
        stopPoll(kind)
        toast.error(e?.message ?? "Login poll error")
      }
    }, 1500)
    pollIntervals.value = { ...pollIntervals.value, [kind]: intervalId }
  } catch (e: any) {
    toast.error(e?.message ?? `Failed to start ${kind} login`)
  }
}

async function cancelLogin(kind: string) {
  stopPoll(kind)
  try {
    await api.cancelAgentLogin(kind)
  } catch {}
  const next = { ...loginStates.value }
  delete next[kind]
  loginStates.value = next
}

const codeValues = ref<Record<string, string>>({})
const codeSubmitting = ref<Record<string, boolean>>({})

async function submitCode(kind: string) {
  const code = (codeValues.value[kind] ?? "").trim()
  if (!code) return
  codeSubmitting.value = { ...codeSubmitting.value, [kind]: true }
  try {
    await api.sendAgentLoginCode(kind, code)
    toast.success("Code submitted - finishing sign-in...")
    codeValues.value = { ...codeValues.value, [kind]: "" }
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to submit code")
  } finally {
    codeSubmitting.value = { ...codeSubmitting.value, [kind]: false }
  }
}

async function copyToClipboard(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.success("Copied")
  } catch {}
}

// ── agent install ────────────────────────────────────────────────────────────
// The broker shells out to the agent's official installer (non-interactively).
// We kick it off, then poll progress on a separate timer from the login poller.
const installStates = ref<Record<string, InstallJob>>({})
const installPolls = ref<Record<string, ReturnType<typeof setInterval>>>({})

function stopInstallPoll(kind: string) {
  const id = installPolls.value[kind]
  if (id !== undefined) {
    clearInterval(id)
    const next = { ...installPolls.value }
    delete next[kind]
    installPolls.value = next
  }
}

async function installAgent(kind: string) {
  stopInstallPoll(kind)
  installStates.value = { ...installStates.value, [kind]: { state: "running", log: "", exitCode: null } }
  try {
    const job = await api.startAgentInstall(kind)
    installStates.value = { ...installStates.value, [kind]: job }
    const intervalId = setInterval(async () => {
      try {
        const j = await api.getAgentInstall(kind)
        installStates.value = { ...installStates.value, [kind]: j }
        if (j.state !== "running") {
          stopInstallPoll(kind)
          if (j.state === "done") {
            toast.success(`${kind} installed`)
            const next = { ...installStates.value }
            delete next[kind]
            installStates.value = next
            await refresh() // re-detect → row flips to installed, normal UI appears
          } else {
            toast.error(`${kind} install failed`)
          }
        }
      } catch (e: any) {
        stopInstallPoll(kind)
        toast.error(e?.message ?? "Install poll error")
      }
    }, 1000)
    installPolls.value = { ...installPolls.value, [kind]: intervalId }
  } catch (e: any) {
    installStates.value = { ...installStates.value, [kind]: { state: "failed", log: e?.message ?? String(e), exitCode: null } }
    toast.error(e?.message ?? `Failed to start ${kind} install`)
  }
}

onMounted(refresh)

onUnmounted(() => {
  for (const kind of Object.keys(pollIntervals.value)) {
    stopPoll(kind)
  }
  for (const kind of Object.keys(installPolls.value)) {
    stopInstallPoll(kind)
  }
})
</script>

<template>
  <div class="flex flex-col flex-1 px-4 py-6 gap-4">
    <div class="mb-1">
      <h2 class="text-lg font-semibold tracking-tight">{{ title }}</h2>
      <p class="text-sm text-muted-foreground mt-0.5">{{ subtitle }}</p>
    </div>

    <div v-if="loading" class="py-8 text-center text-sm text-muted-foreground">Loading...</div>

    <ul v-else class="divide-y divide-border rounded-lg border border-border overflow-hidden">
      <li v-for="s in statuses" :key="s.kind" class="bg-card">
        <div class="flex items-center justify-between gap-3 px-4 py-3.5">
          <div class="flex items-center gap-3 min-w-0">
            <div
              class="size-9 rounded-lg ring-1 ring-border flex items-center justify-center shrink-0"
              :class="s.authed ? 'bg-emerald-500/10' : 'bg-card'"
            >
              <Check v-if="s.authed" class="size-4 text-emerald-400" />
              <AgentLogo v-else :agent="s.kind" class="size-5" />
            </div>
            <div class="min-w-0">
              <div class="font-medium capitalize">{{ s.kind }}</div>
              <div class="text-[11px] text-muted-foreground">{{ statusLabel(s) }}</div>
            </div>
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <span v-if="s.authed" class="text-[11px] text-emerald-400 font-medium">Ready</span>
            <Button
              v-if="!s.installed"
              size="sm"
              variant="outline"
              :disabled="installStates[s.kind]?.state === 'running'"
              @click="installAgent(s.kind)"
            >
              {{ installStates[s.kind]?.state === 'running' ? 'Installing…' : 'Install' }}
            </Button>
            <button
              type="button"
              class="p-1.5 rounded-md text-muted-foreground hover:text-foreground hover:bg-muted transition flex items-center gap-1"
              :class="isConfigOpen(s) ? 'text-foreground bg-muted' : ''"
              :aria-expanded="isConfigOpen(s)"
              :title="isConfigOpen(s) ? 'Hide settings' : 'Configure'"
              @click="toggleConfig(s)"
            >
              <Settings class="size-4" />
              <ChevronDown class="size-3 transition-transform" :class="isConfigOpen(s) ? 'rotate-180' : ''" />
            </button>
          </div>
        </div>

        <!-- Install progress / failure (broker is running the official installer) -->
        <div v-if="installStates[s.kind]" class="px-4 pb-4 border-t border-border pt-3 space-y-2">
          <div v-if="installStates[s.kind]!.state === 'running'" class="flex items-center gap-2 text-xs text-muted-foreground">
            <span class="size-3.5 border-2 border-muted-foreground/30 border-t-muted-foreground rounded-full animate-spin shrink-0" />
            Installing {{ s.kind }} on the broker…
          </div>
          <div v-else-if="installStates[s.kind]!.state === 'failed'" class="space-y-1.5">
            <p class="text-xs text-red-400">Install failed.</p>
            <Button size="sm" variant="outline" @click="installAgent(s.kind)">Retry</Button>
          </div>
          <pre
            v-if="installStates[s.kind]!.log"
            class="text-[10px] leading-snug p-2 bg-background border border-border rounded-md font-mono max-h-32 overflow-auto whitespace-pre-wrap"
          >{{ installStates[s.kind]!.log.slice(-2000) }}</pre>
        </div>

        <!-- opencode is multi-provider: surface the provider connect UI (OAuth + key).
             Gated on `installed` so a not-installed opencode never triggers a control-
             server spawn (the 45s hang behind the 502/500). -->
        <div v-if="s.kind === 'opencode' && isConfigOpen(s) && s.installed" class="px-4 pb-4 border-t border-border pt-3">
          <OpenCodeProviderAuth />
        </div>

        <div v-if="isConfigOpen(s) && loginStates[s.kind]" class="px-4 pb-4 space-y-3">
          <div v-if="loginStates[s.kind]!.phase === 'awaiting_user'" class="space-y-2">
            <div v-if="!loginStates[s.kind]!.url" class="flex items-center gap-2 text-xs text-muted-foreground">
              <span class="size-3.5 border-2 border-muted-foreground/30 border-t-muted-foreground rounded-full animate-spin shrink-0" />
              Generating sign-in link - this can take a few seconds...
            </div>
            <template v-else>
              <p class="text-xs text-muted-foreground">Open this link to authorize.</p>
              <div class="flex items-center gap-2">
                <a
                  :href="loginStates[s.kind]!.url"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="inline-flex items-center gap-1.5 rounded-md bg-primary text-primary-foreground text-xs font-medium px-3 py-2 hover:opacity-90 transition"
                >
                  <Link class="size-3.5" />
                  Open sign-in page
                </a>
                <button
                  type="button"
                  class="p-2 rounded-md bg-card hover:bg-muted text-muted-foreground transition"
                  title="Copy link"
                  @click="copyToClipboard(loginStates[s.kind]!.url!)"
                >
                  <Copy class="size-3.5" />
                </button>
              </div>
              <div v-if="loginStates[s.kind]!.code" class="flex items-center gap-2">
                <span class="text-xs text-muted-foreground">Enter code:</span>
                <code class="text-base font-mono font-bold tracking-widest text-foreground">{{ loginStates[s.kind]!.code }}</code>
              </div>
              <p class="text-[11px] text-muted-foreground animate-pulse">Waiting for authorization...</p>
              <div v-if="loginStates[s.kind]!.needsCode" class="space-y-1.5 pt-1">
                <p class="text-[11px] text-muted-foreground">After authorizing, paste the code from the browser here:</p>
                <div class="flex gap-2">
                  <Input v-model="codeValues[s.kind]" placeholder="paste code" class="flex-1 text-xs font-mono" />
                  <Button size="sm" :disabled="!codeValues[s.kind]?.trim() || codeSubmitting[s.kind]" @click="submitCode(s.kind)">
                    {{ codeSubmitting[s.kind] ? "..." : "Submit" }}
                  </Button>
                </div>
              </div>
            </template>
            <Button variant="outline" size="sm" @click="cancelLogin(s.kind)">
              <X class="size-3.5 mr-1.5" />
              Cancel
            </Button>
          </div>

          <div v-else-if="loginStates[s.kind]!.phase === 'success'" class="text-xs text-emerald-400 py-1">
            Authorized successfully.
          </div>

          <div v-else-if="loginStates[s.kind]!.phase === 'failed'" class="text-xs text-red-400 py-1">
            Login failed: {{ loginStates[s.kind]!.error ?? "unknown error" }}
          </div>
        </div>

        <div v-if="isConfigOpen(s) && !loginStates[s.kind] && s.kind !== 'opencode'" class="px-4 pb-4 space-y-3">
          <div class="space-y-1.5">
            <div class="flex items-center gap-1.5 text-xs text-muted-foreground font-medium">
              <Key class="size-3" />
              Paste {{ s.kind === 'claude' ? 'OAuth token' : 'API key' }}
            </div>
            <template v-if="s.kind === 'claude'">
              <p class="text-[11px] text-muted-foreground">On a machine with a browser, run this and paste the token it prints:</p>
              <div class="relative">
                <code class="block text-[11px] p-2.5 pr-9 bg-background border border-border rounded-md font-mono">claude setup-token</code>
                <button
                  type="button"
                  class="absolute top-1.5 right-1.5 p-1.5 rounded-md bg-card hover:bg-muted text-muted-foreground transition"
                  @click="copyToClipboard('claude setup-token')"
                >
                  <Copy class="size-3.5" />
                </button>
              </div>
            </template>
            <p v-else class="text-[11px] text-muted-foreground">{{ helpByKind[s.kind] }}</p>
            <div class="flex gap-2">
              <Input
                v-model="pasteValues[s.kind]"
                :placeholder="s.kind === 'claude' ? 'oauth_token_...' : 'sk-...'"
                class="flex-1 text-xs font-mono"
                type="password"
              />
              <Button
                size="sm"
                :disabled="!pasteValues[s.kind]?.trim() || pasteSaving[s.kind]"
                @click="saveKey(s.kind)"
              >
                {{ pasteSaving[s.kind] ? "Saving..." : "Save" }}
              </Button>
            </div>
          </div>

          <div v-if="s.installed && loginSupportedKinds.includes(s.kind)" class="border-t border-border pt-3">
            <div class="flex items-center gap-1.5 text-xs text-muted-foreground font-medium mb-1.5">
              <Link class="size-3" />
              Authorize via link
            </div>
            <p v-if="s.kind === 'codex'" class="text-[11px] text-muted-foreground">
              Requires "Allow device code login" enabled in ChatGPT -> Settings -> Security.
            </p>
            <Button variant="outline" size="sm" @click="startLogin(s.kind)">
              Start authorization
            </Button>
          </div>
        </div>
      </li>
    </ul>
  </div>
</template>
