<script setup lang="ts">
import { computed, onMounted, ref } from "vue"
import { useRouter } from "vue-router"
import {
  ArrowLeft, WrapText, Type, Minus, Plus, Languages, Download, Loader2, Trash2, PlusCircle,
} from "lucide-vue-next"
import Switch from "@/components/ui/switch/Switch.vue"
import Input from "@/components/ui/input/Input.vue"
import { useEditorSettings, FONT_SIZE } from "@/stores/editorSettings"
import { api } from "@/api/client"
import { toast } from "vue-sonner"
import { beginLspInstall, endLspInstall, tickLspInstall } from "@/lib/lsp-install-toast"

const router = useRouter()
const settings = useEditorSettings()

type LspRow = {
  id: string
  label: string
  extensions: string[]
  enabled: boolean
  state: "ready" | "missing" | "prereq-missing"
  installLabel: string | null
  installable: boolean
  requires: string | null
  custom: boolean
  command?: string | null
}

const lspLoading = ref(true)
const lspSaving = ref(false)
const lspInstalling = ref<string | null>(null)
const lspRemoving = ref<string | null>(null)
const lspServers = ref<LspRow[]>([])
const showAddForm = ref(false)
const addSaving = ref(false)

const newId = ref("")
const newLabel = ref("")
const newCommand = ref("")
const newArgs = ref("")
const newExtensions = ref("")
const newLanguageId = ref("")
const newInstallCmd = ref("")

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push("/settings")
}

const lineWrap = computed({
  get: () => settings.state.lineWrap,
  set: (v: boolean) => settings.setLineWrap(v),
})

const fontSize = computed(() => settings.state.fontSize)
function bumpFontSize(delta: number) {
  settings.setFontSize(settings.state.fontSize + delta)
}

function slugId(label: string): string {
  return label
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 48) || "server"
}

function stateLabel(row: LspRow): string {
  if (row.state === "ready") return row.custom && row.command ? `Ready (${row.command})` : "Ready"
  if (row.state === "prereq-missing") return `Needs ${row.requires ?? "toolchain"}`
  return row.custom ? "Binary not found on broker" : "Not installed"
}

function extSummary(exts: string[]): string {
  const uniq = [...new Set(exts)].slice(0, 6)
  const tail = exts.length > uniq.length ? "…" : ""
  return uniq.join(", ") + tail
}

function resetAddForm() {
  newId.value = ""
  newLabel.value = ""
  newCommand.value = ""
  newArgs.value = ""
  newExtensions.value = ""
  newLanguageId.value = ""
  newInstallCmd.value = ""
}

async function loadLsp() {
  lspLoading.value = true
  try {
    const r = await api.getEditorSettings()
    lspServers.value = r.lsp.servers as LspRow[]
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to load language server settings")
  } finally {
    lspLoading.value = false
  }
}

async function setLspEnabled(row: LspRow, enabled: boolean) {
  const prev = row.enabled
  row.enabled = enabled
  lspSaving.value = true
  try {
    const r = await api.saveEditorSettings({ lsp: { servers: { [row.id]: { enabled } } } })
    lspServers.value = r.lsp.servers as LspRow[]
    toast.success("Saved")
  } catch (e: any) {
    row.enabled = prev
    toast.error(e?.message ?? "Failed to save")
  } finally {
    lspSaving.value = false
  }
}

async function installLsp(row: LspRow) {
  if (lspInstalling.value) return
  lspInstalling.value = row.id
  const toastId = beginLspInstall(row.label, row.id)
  tickLspInstall(toastId, row.label, row.installLabel ?? "Running on broker…")
  try {
    const r = await api.installEditorLsp(row.id)
    const last = r.lines.at(-1)
    endLspInstall(toastId, row.label, r.ok, last)
    await loadLsp()
  } catch (e: any) {
    endLspInstall(toastId, row.label, false, e?.message ?? "Install failed")
  } finally {
    lspInstalling.value = null
  }
}

async function removeCustom(row: LspRow) {
  lspRemoving.value = row.id
  try {
    const r = await api.removeCustomEditorLsp(row.id)
    if (!r.ok) {
      toast.error(r.error ?? "Remove failed")
      return
    }
    lspServers.value = (r.lsp?.servers ?? []) as LspRow[]
    toast.success("Removed")
  } catch (e: any) {
    toast.error(e?.message ?? "Remove failed")
  } finally {
    lspRemoving.value = null
  }
}

function onNewLabelInput() {
  if (!newId.value.trim() && newLabel.value.trim()) {
    newId.value = slugId(newLabel.value)
  }
}

async function submitAdd() {
  const id = newId.value.trim() || slugId(newLabel.value)
  if (!id || !newLabel.value.trim() || !newCommand.value.trim() || !newExtensions.value.trim()) {
    toast.error("Fill in name, command, and extensions")
    return
  }
  addSaving.value = true
  try {
    const r = await api.addCustomEditorLsp({
      id,
      label: newLabel.value.trim(),
      command: newCommand.value.trim(),
      args: newArgs.value.trim() || undefined,
      extensions: newExtensions.value.trim(),
      languageId: newLanguageId.value.trim() || undefined,
      installCmd: newInstallCmd.value.trim() || undefined,
    })
    if (!r.ok) {
      toast.error(r.error ?? "Could not add server")
      return
    }
    lspServers.value = (r.lsp?.servers ?? []) as LspRow[]
    showAddForm.value = false
    resetAddForm()
    toast.success("Language server added")
  } catch (e: any) {
    toast.error(e?.message ?? "Could not add server")
  } finally {
    addSaving.value = false
  }
}

onMounted(() => { void loadLsp() })
</script>

<template>
  <div class="min-h-screen bg-background text-foreground pb-8">
    <header
      class="flex items-center gap-2 px-3 py-3 border-b border-border sticky top-0 bg-background/95 backdrop-blur z-10"
      style="padding-top: calc(env(safe-area-inset-top, 0px) + 0.75rem)"
    >
      <button class="text-muted-foreground hover:text-foreground transition -ml-1 p-1" aria-label="Back" @click="goBack">
        <ArrowLeft class="size-5" />
      </button>
      <h1 class="text-base font-semibold tracking-tight">Editor Settings</h1>
    </header>

    <p class="px-4 pt-3 text-[11px] text-muted-foreground uppercase tracking-wide font-medium">Appearance</p>
    <ul class="divide-y divide-border">
      <li class="flex items-center justify-between gap-3 px-4 py-3.5">
        <label for="setting-line-wrap" class="flex items-center gap-3 min-w-0 cursor-pointer">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <WrapText class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0">
            <div class="font-medium">Wrap long lines</div>
            <div class="text-[11px] text-muted-foreground">
              Fold long lines instead of scrolling sideways.
            </div>
          </div>
        </label>
        <Switch id="setting-line-wrap" v-model="lineWrap" />
      </li>

      <li class="flex items-center justify-between gap-3 px-4 py-3.5">
        <div class="flex items-center gap-3 min-w-0">
          <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0">
            <Type class="size-4 text-muted-foreground" />
          </div>
          <div class="min-w-0">
            <div class="font-medium">Font size</div>
            <div class="text-[11px] text-muted-foreground">
              Text size in the code editor (this device only).
            </div>
          </div>
        </div>
        <div class="flex items-center gap-1 shrink-0">
          <button
            class="cmux-icon-button size-8 disabled:opacity-40"
            :disabled="fontSize <= FONT_SIZE.min"
            aria-label="Decrease font size"
            @click="bumpFontSize(-1)"
          >
            <Minus class="size-4" />
          </button>
          <span class="w-12 text-center text-sm tabular-nums">{{ fontSize }}px</span>
          <button
            class="cmux-icon-button size-8 disabled:opacity-40"
            :disabled="fontSize >= FONT_SIZE.max"
            aria-label="Increase font size"
            @click="bumpFontSize(1)"
          >
            <Plus class="size-4" />
          </button>
        </div>
      </li>
    </ul>

    <p class="px-4 pt-5 pb-1 text-[11px] text-muted-foreground uppercase tracking-wide font-medium">Language servers</p>
    <p class="px-4 pb-2 text-[11px] text-muted-foreground">
      Runs on the broker host. Add any LSP binary installed there (e.g. <code class="text-[10px]">zls</code>, <code class="text-[10px]">clangd</code>).
    </p>

    <div v-if="lspLoading" class="px-4 py-6 text-sm text-muted-foreground flex items-center gap-2">
      <Loader2 class="size-4 animate-spin" />
      Loading…
    </div>

    <template v-else>
      <ul class="divide-y divide-border border-t border-border">
        <li v-for="row in lspServers" :key="row.id" class="px-4 py-3.5">
          <div class="flex items-start justify-between gap-3">
            <div class="flex items-start gap-3 min-w-0 flex-1">
              <div class="size-9 rounded-lg bg-card ring-1 ring-border flex items-center justify-center shrink-0 mt-0.5">
                <Languages class="size-4 text-muted-foreground" />
              </div>
              <div class="min-w-0 flex-1">
                <div class="font-medium flex items-center gap-2">
                  {{ row.label }}
                  <span v-if="row.custom" class="text-[10px] font-normal text-muted-foreground uppercase tracking-wide">Custom</span>
                </div>
                <div class="text-[11px] text-muted-foreground truncate">{{ extSummary(row.extensions) }}</div>
                <div
                  class="text-[11px] mt-0.5"
                  :class="row.state === 'ready' ? 'text-emerald-600 dark:text-emerald-400' : 'text-amber-600 dark:text-amber-400'"
                >
                  {{ stateLabel(row) }}
                </div>
              </div>
            </div>
            <div class="flex items-center gap-1 shrink-0">
              <button
                v-if="row.custom"
                class="cmux-icon-button size-8 text-muted-foreground hover:text-destructive"
                :disabled="lspRemoving === row.id"
                aria-label="Remove custom server"
                @click="removeCustom(row)"
              >
                <Loader2 v-if="lspRemoving === row.id" class="size-4 animate-spin" />
                <Trash2 v-else class="size-4" />
              </button>
              <Switch
                :id="`lsp-${row.id}`"
                :model-value="row.enabled"
                :disabled="lspSaving"
                @update:model-value="(v: boolean) => setLspEnabled(row, v)"
              />
            </div>
          </div>
          <button
            v-if="row.enabled && row.state !== 'ready' && row.installable"
            class="mt-2 ml-12 text-xs text-primary hover:underline inline-flex items-center gap-1 disabled:opacity-50"
            :disabled="lspInstalling === row.id"
            @click="installLsp(row)"
          >
            <Loader2 v-if="lspInstalling === row.id" class="size-3 animate-spin" />
            <Download v-else class="size-3" />
            {{ row.installLabel ?? "Install" }}
          </button>
        </li>
      </ul>

      <div class="px-4 pt-4">
        <button
          v-if="!showAddForm"
          class="w-full flex items-center justify-center gap-2 py-2.5 rounded-lg border border-dashed border-border text-sm text-muted-foreground hover:text-foreground hover:border-foreground/30 transition"
          @click="showAddForm = true"
        >
          <PlusCircle class="size-4" />
          Add language server
        </button>

        <form v-else class="rounded-lg border border-border bg-card/50 p-4 space-y-3" @submit.prevent="submitAdd">
          <p class="text-sm font-medium">Add language server</p>
          <div>
            <label class="text-[11px] text-muted-foreground">Display name</label>
            <Input v-model="newLabel" class="mt-1" placeholder="Zig" @input="onNewLabelInput" />
          </div>
          <div>
            <label class="text-[11px] text-muted-foreground">Server id</label>
            <Input v-model="newId" class="mt-1 font-mono text-sm" placeholder="zig" />
          </div>
          <div>
            <label class="text-[11px] text-muted-foreground">Command on broker</label>
            <Input v-model="newCommand" class="mt-1 font-mono text-sm" placeholder="zls" />
          </div>
          <div>
            <label class="text-[11px] text-muted-foreground">Args (optional)</label>
            <Input v-model="newArgs" class="mt-1 font-mono text-sm" placeholder="--stdio" />
          </div>
          <div>
            <label class="text-[11px] text-muted-foreground">Extensions</label>
            <Input v-model="newExtensions" class="mt-1 font-mono text-sm" placeholder=".zig, .zon" />
          </div>
          <div>
            <label class="text-[11px] text-muted-foreground">Language id (optional)</label>
            <Input v-model="newLanguageId" class="mt-1 font-mono text-sm" placeholder="zig" />
          </div>
          <div>
            <label class="text-[11px] text-muted-foreground">Install command (optional)</label>
            <Input
              v-model="newInstallCmd"
              class="mt-1 font-mono text-sm"
              placeholder="apt install -y clangd"
            />
            <p class="text-[10px] text-muted-foreground mt-1">
              Runs as the broker user — do not use sudo.
            </p>
          </div>
          <div class="flex gap-2 pt-1">
            <button
              type="submit"
              class="flex-1 py-2 rounded-lg bg-primary text-primary-foreground text-sm font-medium disabled:opacity-50"
              :disabled="addSaving"
            >
              <Loader2 v-if="addSaving" class="size-4 animate-spin inline mr-1" />
              Save
            </button>
            <button
              type="button"
              class="px-4 py-2 rounded-lg border border-border text-sm"
              :disabled="addSaving"
              @click="showAddForm = false; resetAddForm()"
            >
              Cancel
            </button>
          </div>
        </form>
      </div>
    </template>
  </div>
</template>
