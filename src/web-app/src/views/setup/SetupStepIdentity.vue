<script setup lang="ts">
import { ref, onMounted } from "vue"
import { api } from "@/api/client"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { toast } from "vue-sonner"

const loading = ref(true)
const saving = ref(false)
const paName = ref("")
const soulText = ref("")

async function load() {
  loading.value = true
  try {
    const [config, soul] = await Promise.all([
      api.getAppConfig(),
      api.getSoul(),
    ])
    paName.value = config.paName ?? ""
    soulText.value = soul ?? ""
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to load identity settings")
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  try {
    await Promise.all([
      api.saveAppConfig({ paName: paName.value }),
      api.saveSoul(soulText.value),
    ])
    toast.success("Saved")
  } catch (e: any) {
    toast.error(e?.message ?? "Failed to save")
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="flex flex-col flex-1 px-4 py-6 gap-4">
    <div class="mb-1">
      <h2 class="text-lg font-semibold tracking-tight">Identity</h2>
      <p class="text-sm text-muted-foreground mt-0.5">Give your PA a name and a personality. Both are optional — you can skip this step.</p>
    </div>

    <div v-if="loading" class="py-8 text-center text-sm text-muted-foreground">Loading…</div>

    <div v-else class="space-y-4">
      <div>
        <label class="text-xs text-muted-foreground font-medium mb-1 block">PA name</label>
        <Input v-model="paName" placeholder="e.g. Claude, Codex, Aria" />
      </div>

      <div>
        <label class="text-xs text-muted-foreground font-medium mb-1 block">soul.md</label>
        <p class="text-[11px] text-muted-foreground mb-1.5">
          Personality, instructions, and persistent context that is prepended to every session.
        </p>
        <Textarea
          v-model="soulText"
          placeholder="You are a helpful assistant…"
          class="min-h-40 font-mono text-xs"
        />
      </div>

      <Button class="w-full" :disabled="saving" @click="save">
        <span
          v-if="saving"
          class="size-4 border-2 border-primary-foreground/30 border-t-primary-foreground rounded-full animate-spin mr-2"
        />
        {{ saving ? "Saving…" : "Save" }}
      </Button>
    </div>
  </div>
</template>
