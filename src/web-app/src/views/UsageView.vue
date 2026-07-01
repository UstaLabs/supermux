<script setup lang="ts">
import { ref, onMounted, computed } from "vue"
import { ChevronLeft, RefreshCw } from "@lucide/vue"
import { api } from "@/api/client"
import { codexResetNote } from "@/lib/codex-reset"

interface UsageWindow { used: number; resetsAt: string | number | null }
interface ClaudeExtraUsage { enabled: boolean; monthlyLimit: number; usedCredits: number; currency: string }
interface ClaudeUsage { fiveHour: UsageWindow; sevenDay: UsageWindow; sevenDaySonnet: UsageWindow; extraUsage: ClaudeExtraUsage | null }
interface CodexUsage { plan: string; primaryWindow: UsageWindow; secondaryWindow: UsageWindow; credits: { hasCredits: boolean; balance: string } | null; limitReached: boolean; resetCredits: number }
interface CursorUsage { totalPercentUsed: number; totalSpendCents: number; includedCents: number; limitCents: number; billingCycleStart: string; billingCycleEnd: string }
interface OpenCodeUsage { sessions: number; messages: number; totalCostUsd: number; inputTokens: number; outputTokens: number; cacheReadTokens: number; cacheWriteTokens: number }
interface UsageResponse { claude: ClaudeUsage | null; codex: CodexUsage | null; cursor: CursorUsage | null; opencode: OpenCodeUsage | null; errors: Record<string, string> }

const data = ref<UsageResponse | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

async function refresh() {
  loading.value = true
  error.value = null
  try {
    data.value = await api.getUsage()
  } catch (e: any) {
    error.value = e?.message ?? String(e)
  } finally {
    loading.value = false
  }
}

onMounted(refresh)

function barColor(pct: number): string {
  if (pct >= 85) return "bg-red-500"
  if (pct >= 60) return "bg-yellow-500"
  return "bg-emerald-500"
}

function clamp(v: number): number {
  return Math.max(0, Math.min(100, v))
}

function formatTokens(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + "M"
  if (n >= 1_000) return (n / 1_000).toFixed(1) + "K"
  return String(n)
}

function formatReset(resetsAt: string | number | null, kind: "claude" | "codex" | "cursor"): string {
  if (resetsAt == null) return ""
  let ms: number
  if (kind === "claude") {
    ms = new Date(resetsAt as string).getTime()
  } else if (kind === "codex") {
    ms = Number(resetsAt) * 1000
  } else {
    ms = Number(resetsAt)
  }
  if (isNaN(ms)) return ""
  const diff = ms - Date.now()
  if (diff <= 0) return "resets soon"
  if (diff < 24 * 3600_000) {
    const h = Math.floor(diff / 3600_000)
    const m = Math.floor((diff % 3600_000) / 60_000)
    return h > 0 ? `resets in ${h}h ${m}m` : `resets in ${m}m`
  }
  const d = new Date(ms)
  return `resets ${d.toLocaleDateString("en-US", { month: "short", day: "numeric" })}`
}

const claude = computed(() => data.value?.claude ?? null)
const codex = computed(() => data.value?.codex ?? null)
const cursor = computed(() => data.value?.cursor ?? null)
const opencode = computed(() => data.value?.opencode ?? null)
const errors = computed(() => data.value?.errors ?? {})

const confirmingReset = ref(false)
const redeeming = ref(false)
const resetNote = ref<string | null>(null)

async function useReset() {
  redeeming.value = true
  resetNote.value = null
  try {
    const res = await api.redeemCodexReset()
    if (res.codex && data.value) data.value.codex = res.codex as CodexUsage
    else await refresh()
    resetNote.value = codexResetNote(res.code, res.windowsReset)
  } catch (e: any) {
    resetNote.value = e?.message ?? "Reset failed"
  } finally {
    redeeming.value = false
    confirmingReset.value = false
  }
}
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
        <h1 class="text-base font-semibold tracking-tight">Usage</h1>
      </div>
      <button
        @click="refresh"
        :disabled="loading"
        class="text-muted-foreground hover:text-foreground transition p-1"
        aria-label="Refresh"
      >
        <RefreshCw class="size-5" :class="{ 'animate-spin': loading }" />
      </button>
    </header>

    <div class="max-w-lg mx-auto px-4 py-4 space-y-4">
      <!-- Global error -->
      <div v-if="error" class="text-sm text-red-400 text-center py-4">{{ error }}</div>

      <!-- Loading skeleton -->
      <div v-if="loading && !data" class="space-y-4">
        <div v-for="i in 3" :key="i" class="rounded-xl border border-border bg-card p-4 animate-pulse">
          <div class="h-4 bg-muted rounded w-24 mb-3" />
          <div class="h-2 bg-muted rounded-full mb-2" />
          <div class="h-3 bg-muted rounded w-16" />
        </div>
      </div>

      <template v-if="data">
        <!-- Claude card -->
        <div class="rounded-xl border border-border bg-card p-4" :class="{ 'opacity-50': !claude }">
          <div class="flex items-center justify-between mb-3">
            <div>
              <h2 class="font-semibold text-sm">Claude</h2>
              <p class="text-xs text-muted-foreground">Pro plan</p>
            </div>
          </div>
          <template v-if="claude">
            <!-- 5-hour window -->
            <div class="mb-3">
              <div class="flex items-center justify-between text-xs mb-1">
                <span class="text-muted-foreground">5-hour window</span>
                <span>{{ Math.round(claude.fiveHour.used) }}% used</span>
              </div>
              <div class="h-2 rounded-full bg-muted overflow-hidden">
                <div :class="barColor(claude.fiveHour.used)" class="h-full rounded-full transition-all" :style="{ width: clamp(claude.fiveHour.used) + '%' }" />
              </div>
              <p class="text-[11px] text-muted-foreground mt-1">{{ formatReset(claude.fiveHour.resetsAt, 'claude') }}</p>
            </div>
            <!-- 7-day window -->
            <div class="mb-3">
              <div class="flex items-center justify-between text-xs mb-1">
                <span class="text-muted-foreground">7-day window</span>
                <span>{{ Math.round(claude.sevenDay.used) }}% used</span>
              </div>
              <div class="h-2 rounded-full bg-muted overflow-hidden">
                <div :class="barColor(claude.sevenDay.used)" class="h-full rounded-full transition-all" :style="{ width: clamp(claude.sevenDay.used) + '%' }" />
              </div>
              <p class="text-[11px] text-muted-foreground mt-1">{{ formatReset(claude.sevenDay.resetsAt, 'claude') }}</p>
            </div>
            <!-- 7-day Sonnet -->
            <div class="mb-3">
              <div class="flex items-center justify-between text-xs mb-1">
                <span class="text-muted-foreground">7-day Sonnet</span>
                <span>{{ Math.round(claude.sevenDaySonnet.used) }}% used</span>
              </div>
              <div class="h-2 rounded-full bg-muted overflow-hidden">
                <div :class="barColor(claude.sevenDaySonnet.used)" class="h-full rounded-full transition-all" :style="{ width: clamp(claude.sevenDaySonnet.used) + '%' }" />
              </div>
              <p class="text-[11px] text-muted-foreground mt-1">{{ formatReset(claude.sevenDaySonnet.resetsAt, 'claude') }}</p>
            </div>
            <!-- Extra usage -->
            <div v-if="claude.extraUsage && claude.extraUsage.enabled" class="pt-2 border-t border-border">
              <div class="flex items-center justify-between text-xs">
                <span class="text-muted-foreground">Extra usage</span>
                <span>${{ claude.extraUsage.usedCredits.toFixed(2) }} / ${{ claude.extraUsage.monthlyLimit.toFixed(2) }}</span>
              </div>
            </div>
          </template>
          <p v-else class="text-xs text-muted-foreground">{{ errors.claude || 'Not available' }}</p>
        </div>

        <!-- Codex card -->
        <div class="rounded-xl border border-border bg-card p-4" :class="{ 'opacity-50': !codex }">
          <div class="flex items-center justify-between mb-3">
            <div>
              <h2 class="font-semibold text-sm">Codex</h2>
              <p class="text-xs text-muted-foreground">{{ codex?.plan ?? 'unknown' }}</p>
            </div>
            <span v-if="codex?.limitReached" class="text-[10px] font-medium text-red-400 bg-red-500/10 px-2 py-0.5 rounded-full">limit reached</span>
          </div>
          <template v-if="codex">
            <!-- Primary window (5h) -->
            <div class="mb-3">
              <div class="flex items-center justify-between text-xs mb-1">
                <span class="text-muted-foreground">5-hour window</span>
                <span>{{ Math.round(codex.primaryWindow.used) }}% used</span>
              </div>
              <div class="h-2 rounded-full bg-muted overflow-hidden">
                <div :class="barColor(codex.primaryWindow.used)" class="h-full rounded-full transition-all" :style="{ width: clamp(codex.primaryWindow.used) + '%' }" />
              </div>
              <p class="text-[11px] text-muted-foreground mt-1">{{ formatReset(codex.primaryWindow.resetsAt, 'codex') }}</p>
            </div>
            <!-- Secondary window (7d) -->
            <div class="mb-3">
              <div class="flex items-center justify-between text-xs mb-1">
                <span class="text-muted-foreground">7-day window</span>
                <span>{{ Math.round(codex.secondaryWindow.used) }}% used</span>
              </div>
              <div class="h-2 rounded-full bg-muted overflow-hidden">
                <div :class="barColor(codex.secondaryWindow.used)" class="h-full rounded-full transition-all" :style="{ width: clamp(codex.secondaryWindow.used) + '%' }" />
              </div>
              <p class="text-[11px] text-muted-foreground mt-1">{{ formatReset(codex.secondaryWindow.resetsAt, 'codex') }}</p>
            </div>
            <!-- Credits -->
            <div v-if="codex.credits && codex.credits.hasCredits" class="pt-2 border-t border-border">
              <div class="flex items-center justify-between text-xs">
                <span class="text-muted-foreground">Credits balance</span>
                <span>${{ codex.credits.balance }}</span>
              </div>
            </div>
            <!-- Banked rate-limit resets -->
            <div class="pt-2 mt-2 border-t border-border">
              <div class="flex items-center justify-between text-xs">
                <span class="text-muted-foreground">🎟️ Resets banked</span>
                <span>{{ codex.resetCredits }}</span>
              </div>
              <div v-if="codex.resetCredits > 0" class="mt-2">
                <button
                  v-if="!confirmingReset"
                  @click="confirmingReset = true"
                  :disabled="redeeming"
                  class="text-xs px-3 py-1.5 rounded-lg border border-border hover:bg-muted transition disabled:opacity-50"
                >Use a reset</button>
                <div v-else class="flex items-center gap-2">
                  <button
                    @click="useReset"
                    :disabled="redeeming"
                    class="text-xs px-3 py-1.5 rounded-lg bg-emerald-600 text-white hover:bg-emerald-500 transition disabled:opacity-50 flex items-center gap-1.5"
                  >
                    <RefreshCw v-if="redeeming" class="size-3.5 animate-spin" />
                    Confirm · spends 1 of {{ codex.resetCredits }}
                  </button>
                  <button
                    @click="confirmingReset = false"
                    :disabled="redeeming"
                    class="text-xs px-3 py-1.5 rounded-lg border border-border hover:bg-muted transition disabled:opacity-50"
                  >Cancel</button>
                </div>
              </div>
              <p v-if="resetNote" class="text-[11px] text-muted-foreground mt-2">{{ resetNote }}</p>
            </div>
          </template>
          <p v-else class="text-xs text-muted-foreground">{{ errors.codex || 'Not available' }}</p>
        </div>

        <!-- Cursor card -->
        <div class="rounded-xl border border-border bg-card p-4" :class="{ 'opacity-50': !cursor }">
          <div class="flex items-center justify-between mb-3">
            <div>
              <h2 class="font-semibold text-sm">Cursor</h2>
              <p class="text-xs text-muted-foreground">Billing cycle</p>
            </div>
          </div>
          <template v-if="cursor">
            <div class="mb-3">
              <div class="flex items-center justify-between text-xs mb-1">
                <span class="text-muted-foreground">Usage</span>
                <span>{{ Math.round(cursor.totalPercentUsed) }}% used</span>
              </div>
              <div class="h-2 rounded-full bg-muted overflow-hidden">
                <div :class="barColor(cursor.totalPercentUsed)" class="h-full rounded-full transition-all" :style="{ width: clamp(cursor.totalPercentUsed) + '%' }" />
              </div>
              <p class="text-[11px] text-muted-foreground mt-1">{{ formatReset(cursor.billingCycleEnd, 'cursor') }}</p>
            </div>
            <div class="pt-2 border-t border-border">
              <div class="flex items-center justify-between text-xs">
                <span class="text-muted-foreground">Spend</span>
                <span>${{ (cursor.totalSpendCents / 100).toFixed(2) }} / ${{ (cursor.includedCents / 100).toFixed(2) }} included</span>
              </div>
            </div>
          </template>
          <p v-else class="text-xs text-muted-foreground">{{ errors.cursor || 'Not available' }}</p>
        </div>

        <!-- opencode card — no subscription quota, so we show cumulative local token/cost stats -->
        <div class="rounded-xl border border-border bg-card p-4" :class="{ 'opacity-50': !opencode }">
          <div class="flex items-center justify-between mb-3">
            <div>
              <h2 class="font-semibold text-sm">opencode</h2>
              <p class="text-xs text-muted-foreground">Local usage · all time</p>
            </div>
            <span v-if="opencode" class="text-sm font-semibold">${{ opencode.totalCostUsd.toFixed(2) }}</span>
          </div>
          <template v-if="opencode">
            <div class="grid grid-cols-2 gap-x-4 gap-y-2 text-xs">
              <div class="flex items-center justify-between">
                <span class="text-muted-foreground">Input</span>
                <span>{{ formatTokens(opencode.inputTokens) }}</span>
              </div>
              <div class="flex items-center justify-between">
                <span class="text-muted-foreground">Output</span>
                <span>{{ formatTokens(opencode.outputTokens) }}</span>
              </div>
              <div class="flex items-center justify-between">
                <span class="text-muted-foreground">Cache read</span>
                <span>{{ formatTokens(opencode.cacheReadTokens) }}</span>
              </div>
              <div class="flex items-center justify-between">
                <span class="text-muted-foreground">Cache write</span>
                <span>{{ formatTokens(opencode.cacheWriteTokens) }}</span>
              </div>
            </div>
            <div class="flex items-center justify-between text-[11px] text-muted-foreground pt-2 mt-2 border-t border-border">
              <span>{{ opencode.sessions }} sessions · {{ opencode.messages }} messages</span>
            </div>
          </template>
          <p v-else class="text-xs text-muted-foreground">{{ errors.opencode || 'Not available' }}</p>
        </div>
      </template>
    </div>
  </div>
</template>
