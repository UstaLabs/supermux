<script setup lang="ts">
import { ref, onMounted, computed } from "vue"
import { Copy, Check } from "lucide-vue-next"
import { api } from "@/api/client"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import Switch from "@/components/ui/switch/Switch.vue"
import { toast } from "vue-sonner"

const loading = ref(true)
const savingTelegram = ref(false)
const savingExposure = ref(false)

const telegramBotToken = ref("")
const telegramConfigured = ref(false)

const isPublic = ref(false)
const webPublicUrl = ref("")

const pairingUrl = ref<string | null>(null)
const copiedAt = ref(0)
const copied = computed(() => copiedAt.value > 0 && Date.now() - copiedAt.value < 1500)
const generatingPairing = ref(false)

// Exposure snippets
interface ExposureData {
  exposureMode: string
  publicUrl: string
  snippets: {
    caddy: string
    nginx: string
    cloudflared: string
  }
}
const exposureData = ref<ExposureData | null>(null)
const snippetCopiedKey = ref<string | null>(null)

// Reachability test
const testing = ref(false)
const reachabilityResult = ref<{ reachable: boolean; status?: number; error?: string } | null>(null)

async function load() {
  loading.value = true
  try {
    const config = await api.getAppConfig()
    telegramConfigured.value = !!config.telegramConfigured
    isPublic.value = config.exposureMode === "public"
    webPublicUrl.value = config.webPublicUrl ?? ""
    if (isPublic.value) {
      await loadExposure()
    }
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to load settings")
  } finally {
    loading.value = false
  }
}

async function loadExposure() {
  try {
    exposureData.value = await api.getExposure()
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to load exposure info")
  }
}

async function saveTelegram() {
  if (!telegramBotToken.value.trim()) return
  savingTelegram.value = true
  try {
    await api.saveAppConfig({ telegramBotToken: telegramBotToken.value.trim() })
    telegramConfigured.value = true
    telegramBotToken.value = ""
    toast.success("Telegram bot token saved")
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to save Telegram token")
  } finally {
    savingTelegram.value = false
  }
}

async function saveExposure() {
  savingExposure.value = true
  try {
    await api.saveAppConfig({
      exposureMode: isPublic.value ? "public" : "local",
      webPublicUrl: isPublic.value ? webPublicUrl.value : undefined,
    })
    toast.success("Saved")
    if (isPublic.value) {
      await loadExposure()
    } else {
      exposureData.value = null
    }
    reachabilityResult.value = null
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to save exposure settings")
  } finally {
    savingExposure.value = false
  }
}

async function generatePairing() {
  generatingPairing.value = true
  try {
    const result = await api.addDevice("device")
    pairingUrl.value = result.url
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to generate pairing link")
  } finally {
    generatingPairing.value = false
  }
}

async function copyPairingUrl() {
  if (!pairingUrl.value) return
  try {
    await navigator.clipboard.writeText(pairingUrl.value)
    copiedAt.value = Date.now()
  } catch {}
}

async function copySnippet(key: string, text: string) {
  try {
    await navigator.clipboard.writeText(text)
    snippetCopiedKey.value = key
    setTimeout(() => { if (snippetCopiedKey.value === key) snippetCopiedKey.value = null }, 1500)
  } catch {}
}

async function testReachability() {
  testing.value = true
  reachabilityResult.value = null
  try {
    const result = await api.validateExposure()
    reachabilityResult.value = result
    if (result.reachable) {
      toast.success(`Reachable (HTTP ${result.status ?? "?"})`)
    } else {
      toast.error(result.error ?? "Not reachable")
    }
  } catch (e: any) {
    toast.error(e?.message ?? "Reachability check failed")
  } finally {
    testing.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="flex flex-col flex-1 px-4 py-6 gap-6">
    <div class="mb-1">
      <h2 class="text-lg font-semibold tracking-tight">Connectivity</h2>
      <p class="text-sm text-muted-foreground mt-0.5">Optional: set up Telegram notifications, public exposure, and add devices.</p>
    </div>

    <div v-if="loading" class="py-8 text-center text-sm text-muted-foreground">Loading…</div>

    <template v-else>
      <!-- Telegram section -->
      <div class="space-y-3">
        <div class="flex items-center justify-between">
          <h3 class="text-sm font-medium">Telegram</h3>
          <span v-if="telegramConfigured" class="text-[11px] text-emerald-400 font-medium">✅ Configured</span>
        </div>
        <p class="text-[11px] text-muted-foreground">
          Create a bot via @BotFather and paste the token here to receive notifications and send commands via Telegram.
        </p>
        <div class="flex gap-2">
          <Input
            v-model="telegramBotToken"
            placeholder="1234567890:ABCdef..."
            class="flex-1 font-mono text-xs"
            type="password"
          />
          <Button
            size="sm"
            :disabled="!telegramBotToken.trim() || savingTelegram"
            @click="saveTelegram"
          >
            {{ savingTelegram ? "Saving…" : "Save" }}
          </Button>
        </div>
        <p class="text-[11px] text-muted-foreground">Saved tokens activate on the next broker restart.</p>
      </div>

      <!-- Separator -->
      <div class="border-t border-border" />

      <!-- Exposure section -->
      <div class="space-y-3">
        <h3 class="text-sm font-medium">Exposure mode</h3>
        <div class="flex items-center justify-between gap-3">
          <div class="min-w-0">
            <div class="text-sm">Public (HTTPS)</div>
            <div class="text-[11px] text-muted-foreground">Make the web app reachable outside your local network.</div>
          </div>
          <Switch v-model:checked="isPublic" />
        </div>
        <div v-if="isPublic" class="space-y-1.5">
          <label class="text-xs text-muted-foreground font-medium block">HTTPS base URL</label>
          <Input v-model="webPublicUrl" placeholder="https://mux.example.com" />
        </div>
        <Button size="sm" variant="outline" :disabled="savingExposure" @click="saveExposure">
          {{ savingExposure ? "Saving…" : "Save exposure settings" }}
        </Button>

        <!-- Snippets — shown when public mode is active and data loaded -->
        <template v-if="isPublic && exposureData">
          <div class="space-y-3 pt-1">
            <p class="text-[11px] text-muted-foreground">Choose one of the following to expose the app publicly:</p>

            <!-- Caddy -->
            <div class="space-y-1">
              <div class="flex items-center justify-between">
                <span class="text-xs font-medium text-muted-foreground">Caddy</span>
                <button
                  type="button"
                  class="flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition px-1.5 py-0.5 rounded-md hover:bg-muted"
                  @click="copySnippet('caddy', exposureData!.snippets.caddy)"
                >
                  <Check v-if="snippetCopiedKey === 'caddy'" class="size-3 text-emerald-400" />
                  <Copy v-else class="size-3" />
                  {{ snippetCopiedKey === 'caddy' ? 'Copied' : 'Copy' }}
                </button>
              </div>
              <pre class="text-[11px] p-2.5 bg-card border border-border rounded-md font-mono leading-relaxed overflow-x-auto whitespace-pre-wrap break-all">{{ exposureData.snippets.caddy }}</pre>
            </div>

            <!-- nginx -->
            <div class="space-y-1">
              <div class="flex items-center justify-between">
                <span class="text-xs font-medium text-muted-foreground">nginx</span>
                <button
                  type="button"
                  class="flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition px-1.5 py-0.5 rounded-md hover:bg-muted"
                  @click="copySnippet('nginx', exposureData!.snippets.nginx)"
                >
                  <Check v-if="snippetCopiedKey === 'nginx'" class="size-3 text-emerald-400" />
                  <Copy v-else class="size-3" />
                  {{ snippetCopiedKey === 'nginx' ? 'Copied' : 'Copy' }}
                </button>
              </div>
              <pre class="text-[11px] p-2.5 bg-card border border-border rounded-md font-mono leading-relaxed overflow-x-auto whitespace-pre-wrap break-all">{{ exposureData.snippets.nginx }}</pre>
            </div>

            <!-- Cloudflare Tunnel -->
            <div class="space-y-1">
              <div class="flex items-center justify-between">
                <span class="text-xs font-medium text-muted-foreground">Cloudflare Tunnel</span>
                <button
                  type="button"
                  class="flex items-center gap-1 text-[11px] text-muted-foreground hover:text-foreground transition px-1.5 py-0.5 rounded-md hover:bg-muted"
                  @click="copySnippet('cloudflared', exposureData!.snippets.cloudflared)"
                >
                  <Check v-if="snippetCopiedKey === 'cloudflared'" class="size-3 text-emerald-400" />
                  <Copy v-else class="size-3" />
                  {{ snippetCopiedKey === 'cloudflared' ? 'Copied' : 'Copy' }}
                </button>
              </div>
              <pre class="text-[11px] p-2.5 bg-card border border-border rounded-md font-mono leading-relaxed overflow-x-auto whitespace-pre-wrap break-all">{{ exposureData.snippets.cloudflared }}</pre>
            </div>

            <!-- Reachability test -->
            <div class="flex items-center gap-3 pt-1">
              <Button size="sm" variant="outline" :disabled="testing" @click="testReachability">
                {{ testing ? "Testing…" : "Test reachability" }}
              </Button>
              <span
                v-if="reachabilityResult !== null"
                class="text-[11px]"
                :class="reachabilityResult.reachable ? 'text-emerald-400' : 'text-destructive'"
              >
                <template v-if="reachabilityResult.reachable">
                  ✅ Reachable (HTTP {{ reachabilityResult.status ?? "?" }})
                </template>
                <template v-else>
                  ❌ {{ reachabilityResult.error ?? "Not reachable" }}
                </template>
              </span>
            </div>
          </div>
        </template>
      </div>

      <!-- Separator -->
      <div class="border-t border-border" />

      <!-- Pairing section -->
      <div class="space-y-3">
        <h3 class="text-sm font-medium">Add another device</h3>
        <p class="text-[11px] text-muted-foreground">
          Generate a one-time pairing link to open supermux on another browser / device.
        </p>
        <Button
          v-if="!pairingUrl"
          variant="outline"
          size="sm"
          :disabled="generatingPairing"
          @click="generatePairing"
        >
          {{ generatingPairing ? "Generating…" : "Generate pairing link" }}
        </Button>
        <div v-else class="space-y-2">
          <p class="text-xs text-muted-foreground">Open this URL on the other device:</p>
          <div class="relative">
            <code class="block break-all text-[11px] p-2.5 pr-10 bg-card border border-border rounded-md font-mono leading-relaxed">
              {{ pairingUrl }}
            </code>
            <button
              type="button"
              class="absolute top-1.5 right-1.5 p-1.5 rounded-md bg-card hover:bg-muted text-muted-foreground transition"
              :title="copied ? 'Copied' : 'Copy'"
              @click="copyPairingUrl"
            >
              <Check v-if="copied" class="size-4 text-emerald-400" />
              <Copy v-else class="size-4" />
            </button>
          </div>
          <Button variant="secondary" size="sm" @click="pairingUrl = null">Done</Button>
        </div>
      </div>
    </template>
  </div>
</template>
