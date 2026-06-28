<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue"
import { Check, ChevronDown, Folder, Search } from "lucide-vue-next"
import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import type { ArchivedProject } from "@/lib/archived-projects"

const props = defineProps<{
  modelValue: string | null
  projects: ArchivedProject[]
}>()

const emit = defineEmits<{
  (e: "update:modelValue", value: string | null): void
}>()

const open = ref(false)
const draft = ref("")
const activeIndex = ref(0)
const inputRef = ref<InstanceType<typeof Input> | null>(null)
const listEl = ref<HTMLElement | null>(null)

interface FilterOption {
  key: string | null
  label: string
  count: number
}

const totalCount = computed(() => props.projects.reduce((n, p) => n + p.count, 0))

const filteredProjects = computed(() => {
  const q = draft.value.trim().toLowerCase()
  if (!q) return props.projects
  return props.projects.filter(
    (p) => p.label.toLowerCase().includes(q) || p.key.toLowerCase().includes(q),
  )
})

const options = computed<FilterOption[]>(() => [
  { key: null, label: "All projects", count: totalCount.value },
  ...filteredProjects.value,
])

const selectedLabel = computed(() => {
  if (!props.modelValue) return "All projects"
  return props.projects.find((p) => p.key === props.modelValue)?.label ?? "All projects"
})

watch(open, async (isOpen) => {
  if (!isOpen) return
  draft.value = ""
  activeIndex.value = 0
  await nextTick()
  const el = inputRef.value?.$el ?? inputRef.value
  el?.focus?.()
})

watch(filteredProjects, () => {
  if (activeIndex.value >= options.value.length) {
    activeIndex.value = Math.max(0, options.value.length - 1)
  }
})

watch(activeIndex, (i) => {
  void nextTick(() => listEl.value?.querySelector(`[data-idx="${i}"]`)?.scrollIntoView({ block: "nearest" }))
})

function select(key: string | null) {
  emit("update:modelValue", key)
  open.value = false
}

function selectActive() {
  const opt = options.value[activeIndex.value]
  if (opt) select(opt.key)
}

function moveActive(delta: number) {
  const count = options.value.length
  if (count === 0) return
  activeIndex.value = (activeIndex.value + delta + count) % count
}

function onInputKeydown(e: KeyboardEvent) {
  if (e.key === "ArrowDown") {
    e.preventDefault()
    moveActive(1)
  } else if (e.key === "ArrowUp") {
    e.preventDefault()
    moveActive(-1)
  } else if (e.key === "Enter") {
    e.preventDefault()
    selectActive()
  } else if (e.key === "Escape") {
    e.preventDefault()
    open.value = false
  }
}
</script>

<template>
  <DropdownMenu v-model:open="open">
    <DropdownMenuTrigger as-child>
      <button
        type="button"
        class="group flex w-full min-w-0 items-center gap-2 rounded-lg border border-border/70 bg-card/35 px-3 py-2 text-left transition-colors hover:bg-card/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        aria-label="Filter by project"
      >
        <Folder class="size-4 shrink-0 text-muted-foreground" />
        <span class="min-w-0 flex-1 truncate text-sm" :class="modelValue ? 'text-foreground' : 'text-muted-foreground'">
          {{ selectedLabel }}
        </span>
        <ChevronDown
          class="size-4 shrink-0 text-muted-foreground transition-transform group-hover:text-foreground"
          :class="open ? 'rotate-180' : ''"
        />
      </button>
    </DropdownMenuTrigger>

    <DropdownMenuContent align="start" class="w-[min(28rem,calc(100vw-2rem))] p-2">
      <div class="relative">
        <Search class="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          ref="inputRef"
          v-model="draft"
          placeholder="Search projects"
          class="h-9 pl-8 text-sm"
          @keydown="onInputKeydown"
        />
      </div>

      <div ref="listEl" class="mt-2 max-h-72 overflow-y-auto" role="listbox" aria-label="Projects">
        <button
          v-for="(opt, i) in options"
          :key="opt.key ?? '__all__'"
          type="button"
          :data-idx="i"
          role="option"
          :aria-selected="activeIndex === i"
          class="flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left hover:bg-accent"
          :class="activeIndex === i ? 'bg-accent' : ''"
          @mousemove="activeIndex = i"
          @mousedown.prevent
          @click="select(opt.key)"
        >
          <Folder v-if="opt.key" class="size-4 shrink-0 text-muted-foreground" />
          <span v-else class="size-4 shrink-0" />
          <span class="min-w-0 flex-1 truncate text-sm" :class="opt.key ? '' : 'font-medium'">{{ opt.label }}</span>
          <span class="shrink-0 text-xs tabular-nums text-muted-foreground">{{ opt.count }}</span>
          <Check v-if="opt.key === modelValue" class="size-4 shrink-0 text-primary" />
        </button>
      </div>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
