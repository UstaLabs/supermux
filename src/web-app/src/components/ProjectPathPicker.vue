<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue"
import { Check, ChevronDown, CornerDownLeft, FolderOpen, Search } from "lucide-vue-next"
import { DropdownMenu, DropdownMenuContent, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Input } from "@/components/ui/input"
import { buildProjectOptions, type ProjectPath } from "@/lib/project-options"
import { formatWorkdir } from "@/lib/format-workdir"
import { useForges } from "@/stores/forges"
import { useForgeOmnibox } from "@/composables/useForgeOmnibox"
import { buildOmniboxOptions, type OmniOption, type OmniCloud } from "@/lib/forge-omnibox"
import ForgeIcon from "@/components/ForgeIcon.vue"

const props = withDefaults(defineProps<{
  modelValue: string
  projects: ProjectPath[]
  homeDir?: string | null
  placeholder?: string
  variant?: "inline" | "compact" | "heading"
}>(), {
  placeholder: "Select project",
  variant: "compact",
})

const emit = defineEmits<{
  (e: "update:modelValue", value: string): void
}>()

const open = ref(false)
const draft = ref("")
const activeIndex = ref(0)
const inputRef = ref<InstanceType<typeof Input> | null>(null)
const listEl = ref<HTMLElement | null>(null)

const forges = useForges()
const omni = useForgeOmnibox(draft)

const projectOptions = computed(() => buildProjectOptions(props.projects, props.homeDir))
const normalizedDraft = computed(() => draft.value.trim())

const selectedLabel = computed(() => {
  const value = props.modelValue.trim()
  if (!value) return props.placeholder
  const match = projectOptions.value.find((project) => project.path === value)
  return match?.label ?? formatWorkdir(value, props.homeDir ?? undefined)
})

// Short, recognizable name for the "heading" variant (e.g. "project-api", "Home").
const shortLabel = computed(() => {
  const value = props.modelValue.trim()
  const home = props.homeDir ?? undefined
  if (!value || value === "~" || (home && value === home)) return "Home"
  const base = value.replace(/\/+$/, "").split("/").filter(Boolean).pop()
  return base || "Home"
})

const triggerClass = computed(() =>
  props.variant === "heading"
    ? "group mx-auto inline-flex max-w-full items-center gap-1 rounded-lg px-1.5 py-0.5 text-2xl font-medium text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring md:text-3xl"
    : "group flex w-full min-w-0 items-center gap-2 rounded-xl border border-border/70 bg-card/35 px-3 py-2.5 text-left transition-colors hover:bg-card/70 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
)

const filteredProjects = computed(() => {
  const query = normalizedDraft.value.toLowerCase()
  if (!query) return projectOptions.value
  return projectOptions.value.filter((project) =>
    project.label.toLowerCase().includes(query) || project.path.toLowerCase().includes(query),
  )
})

const exactDraftMatch = computed(() =>
  !!normalizedDraft.value && projectOptions.value.some((project) => project.path === normalizedDraft.value),
)

const showTypedPathOption = computed(() => !!normalizedDraft.value && !exactDraftMatch.value)
const optionCount = computed(() => filteredProjects.value.length + (showTypedPathOption.value ? 1 : 0))

watch(open, async (isOpen) => {
  if (!isOpen) return
  draft.value = props.modelValue
  activeIndex.value = 0
  void forges.loadConnections()
  await nextTick()
  const input = inputRef.value?.$el ?? inputRef.value
  input?.focus?.()
  input?.select?.()
})

watch([filteredProjects, showTypedPathOption], () => {
  if (activeIndex.value >= optionCount.value) activeIndex.value = Math.max(0, optionCount.value - 1)
})

watch(activeIndex, (index) => {
  void nextTick(() => listEl.value?.querySelector(`[data-idx="${index}"]`)?.scrollIntoView({ block: "nearest" }))
})

function selectPath(path: string) {
  emit("update:modelValue", path)
  open.value = false
}

function selectTypedPath() {
  const path = normalizedDraft.value
  if (path) selectPath(path)
}

function projectIndexFor(optionIndex: number): number {
  return optionIndex - (showTypedPathOption.value ? 1 : 0)
}

function selectActive() {
  if (showTypedPathOption.value && activeIndex.value === 0) {
    selectTypedPath()
    return
  }
  const project = filteredProjects.value[projectIndexFor(activeIndex.value)]
  if (project) selectPath(project.path)
}

function moveActive(delta: number) {
  const count = optionCount.value
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

const cloudByConnection = computed(() => {
  const groups: { connection: { id: string; host: string; account: { login: string }; kind: "github" | "gitlab" }; repos: OmniCloud[] }[] = []
  const opts = buildOmniboxOptions({ query: draft.value, localProjects: [], cloudRepos: omni.cloudRepos.value, connections: forges.connections })
  for (const o of opts) {
    if (o.kind !== "cloud") continue
    const conn = forges.connections.find((c) => c.id === o.connectionId)
    if (!conn) continue
    let g = groups.find((x) => x.connection.id === conn.id)
    if (!g) { g = { connection: conn, repos: [] }; groups.push(g) }
    g.repos.push(o)
  }
  return groups
})

const createOptions = computed(() =>
  buildOmniboxOptions({ query: draft.value, localProjects: projectOptions.value, cloudRepos: omni.cloudRepos.value, connections: forges.connections })
    .filter((o): o is Extract<OmniOption, { kind: "create" }> => o.kind === "create"),
)

async function handleResolve(opt: OmniOption) {
  if (opt.kind !== "local" && omni.resolving.value) return
  if (opt.kind === "local") { selectPath(opt.path); return }
  try { const path = await omni.resolve(opt); emit("update:modelValue", path); open.value = false } catch { /* keep open; error surfaced elsewhere */ }
}

defineExpose({
  focus: () => { open.value = true },
})
</script>

<template>
  <DropdownMenu v-model:open="open">
    <DropdownMenuTrigger as-child>
      <button type="button" :class="triggerClass" aria-label="Select project path">
        <template v-if="props.variant === 'heading'">
          <span class="truncate">{{ shortLabel }}</span>
          <ChevronDown
            class="size-5 shrink-0 opacity-60 transition-transform group-hover:opacity-100"
            :class="open ? 'rotate-180' : ''"
          />
        </template>
        <template v-else>
          <span
            class="min-w-0 flex-1 truncate font-mono text-sm"
            :class="props.modelValue.trim() ? 'text-foreground' : 'text-muted-foreground'"
          >
            {{ selectedLabel }}
          </span>
          <ChevronDown
            class="size-4 shrink-0 text-muted-foreground transition-transform group-hover:text-foreground"
            :class="open ? 'rotate-180' : ''"
          />
        </template>
      </button>
    </DropdownMenuTrigger>

    <DropdownMenuContent
      :align="props.variant === 'heading' ? 'center' : 'start'"
      class="w-[min(34rem,calc(100vw-2rem))] p-2"
    >
      <div class="relative">
        <Search class="pointer-events-none absolute left-2.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          ref="inputRef"
          v-model="draft"
          placeholder="Type a path or search projects"
          class="h-9 pl-8 font-mono text-sm"
          @keydown="onInputKeydown"
        />
      </div>

      <div ref="listEl" class="mt-2 max-h-72 overflow-y-auto" role="listbox" aria-label="Project paths">
        <button
          v-if="showTypedPathOption"
          type="button"
          :data-idx="0"
          role="option"
          :aria-selected="activeIndex === 0"
          class="flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left hover:bg-accent"
          :class="activeIndex === 0 ? 'bg-accent' : ''"
          @mousemove="activeIndex = 0"
          @mousedown.prevent
          @click="selectTypedPath"
        >
          <CornerDownLeft class="size-4 shrink-0 text-muted-foreground" />
          <span class="min-w-0 flex-1">
            <span class="block truncate text-sm">Use this path</span>
            <span class="block truncate font-mono text-xs text-muted-foreground">{{ normalizedDraft }}</span>
          </span>
        </button>

        <div v-if="filteredProjects.length > 0" class="mt-1">
          <p class="px-2 py-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
            Projects
          </p>
          <button
            v-for="(project, i) in filteredProjects"
            :key="project.path"
            type="button"
            :data-idx="i + (showTypedPathOption ? 1 : 0)"
            role="option"
            :aria-selected="activeIndex === i + (showTypedPathOption ? 1 : 0)"
            class="flex w-full items-start gap-2 rounded-lg px-2 py-2 text-left hover:bg-accent"
            :class="activeIndex === i + (showTypedPathOption ? 1 : 0) ? 'bg-accent' : ''"
            @mousemove="activeIndex = i + (showTypedPathOption ? 1 : 0)"
            @mousedown.prevent
            @click="selectPath(project.path)"
          >
            <FolderOpen class="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            <span class="min-w-0 flex-1">
              <span class="block truncate text-sm font-medium">{{ project.label }}</span>
              <span class="block truncate font-mono text-xs text-muted-foreground">{{ project.path }}</span>
            </span>
            <Check v-if="project.path === props.modelValue" class="mt-0.5 size-4 shrink-0 text-primary" />
          </button>
        </div>

        <div v-else-if="!showTypedPathOption && cloudByConnection.length === 0 && createOptions.length === 0" class="px-3 py-8 text-center text-sm text-muted-foreground">
          Type an existing project path, or create a session from a known project.
        </div>

        <!-- Cloud repos grouped by connection -->
        <template v-for="g in cloudByConnection" :key="g.connection.id">
          <div class="mt-1">
            <p class="flex items-center gap-1.5 px-2 py-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
              <ForgeIcon :kind="g.connection.kind" class="size-3.5" />
              {{ g.connection.host }} · @{{ g.connection.account.login }}
            </p>
            <button
              v-for="o in g.repos"
              :key="o.repo.fullName"
              type="button"
              role="option"
              :disabled="omni.resolving.value"
              class="flex w-full items-start gap-2 rounded-lg px-2 py-2 text-left hover:bg-accent disabled:opacity-60"
              @mousedown.prevent
              @click="handleResolve(o)"
            >
              <FolderOpen class="mt-0.5 size-4 shrink-0 text-muted-foreground" />
              <span class="min-w-0 flex-1">
                <span class="block truncate text-sm font-medium">{{ o.repo.name }}</span>
                <span class="block truncate font-mono text-xs text-muted-foreground">{{ o.repo.fullName }}</span>
              </span>
              <span class="shrink-0 text-xs text-muted-foreground">↓ clone</span>
            </button>
          </div>
        </template>

        <!-- Create options -->
        <div v-if="createOptions.length > 0" class="mt-1">
          <p class="px-2 py-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
            Create
          </p>
          <button
            v-for="o in createOptions"
            :key="o.createTarget"
            type="button"
            role="option"
            :disabled="omni.resolving.value"
            class="flex w-full items-center gap-2 rounded-lg px-2 py-2 text-left hover:bg-accent disabled:opacity-60"
            @mousedown.prevent
            @click="handleResolve(o)"
          >
            <CornerDownLeft class="size-4 shrink-0 text-muted-foreground" />
            <span class="block truncate text-sm">{{ o.label }}</span>
          </button>
        </div>

        <!-- Resolving indicator -->
        <div v-if="omni.searching.value || omni.resolving.value" class="px-3 py-2 text-center text-xs text-muted-foreground">
          {{ omni.resolving.value ? "Cloning / creating…" : "Searching…" }}
        </div>
      </div>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
