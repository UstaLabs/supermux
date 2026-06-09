<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { Check, ChevronDown, Loader2Icon } from "lucide-vue-next"
import { api } from "@/api/client"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"

const props = defineProps<{
  model: string
  agent: string
}>()

const emit = defineEmits<{
  "update:model": [value: string]
}>()

const open = ref(false)
const models = ref<{ id: string; displayName: string }[]>([])
const loading = ref(false)

const display = computed(() => {
  if (!props.model) return "Default"
  const found = models.value.find((m) => m.id === props.model)
  return found?.displayName ?? props.model
})

async function fetchModels() {
  loading.value = true
  try {
    const res = await api.listModels(props.agent)
    models.value = res.models
  } catch {
    models.value = []
  } finally {
    loading.value = false
  }
}

function pick(id: string) {
  emit("update:model", id)
  open.value = false
}

watch(() => props.agent, () => {
  models.value = []
  fetchModels()
})

onMounted(fetchModels)
</script>

<template>
  <DropdownMenu v-model:open="open">
    <DropdownMenuTrigger as-child>
      <button
        type="button"
        class="inline-flex max-w-full items-center gap-1.5 rounded-full px-2 py-0.5 text-[11px] font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
        aria-label="Model"
      >
        <span class="truncate max-w-28">{{ display }}</span>
        <ChevronDown class="size-3 shrink-0 opacity-60" />
      </button>
    </DropdownMenuTrigger>

    <DropdownMenuContent align="start" class="w-56 max-h-72 overflow-y-auto p-1">
      <p class="px-2 pt-1 pb-0.5 text-[10px] font-medium uppercase tracking-wide text-muted-foreground">
        Model
      </p>

      <button
        type="button"
        class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
        @click="pick('')"
      >
        <span class="flex-1">Default</span>
        <Check v-if="!model" class="size-4 shrink-0 text-primary" />
      </button>

      <div v-if="loading" class="flex items-center justify-center gap-2 py-4 text-xs text-muted-foreground">
        <Loader2Icon class="size-3 animate-spin" />
        Loading models…
      </div>

      <template v-else>
        <div v-if="models.length === 0" class="px-2 py-3 text-xs text-muted-foreground text-center">
          No models found
        </div>

        <div v-if="models.length > 0" class="my-1 border-t border-border" />
        <button
          v-for="m in models"
          :key="m.id"
          type="button"
          class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-accent"
          @click="pick(m.id)"
        >
          <span class="flex-1 truncate">{{ m.displayName }}</span>
          <Check v-if="model === m.id" class="size-4 shrink-0 text-primary" />
        </button>
      </template>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
