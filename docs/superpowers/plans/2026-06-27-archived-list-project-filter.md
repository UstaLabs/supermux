# Archived List — Filter & Show Projects Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a clearer per-row project label and a searchable "All projects" filter dropdown to the archived sessions list.

**Architecture:** A "project" is a session's `repo_root ?? workdir` (matching the active list). The backend starts sending `repo_root` for archived sessions; a new pure, unit-tested `lib/archived-projects.ts` derives the distinct projects + labels + filtering; a small `ArchivedProjectFilter.vue` dropdown drives a `selectedProjectKey` in `ArchivedListView.vue`, which also renders the project label on each row. Filtering is client-side.

**Tech Stack:** TypeScript, Vue 3 (`<script setup>`), Pinia, Tailwind, reka-ui DropdownMenu primitives, `bun:test`.

**Spec:** `docs/superpowers/specs/2026-06-27-archived-list-project-filter-design.md`

---

## File Structure

- **Modify** `src/channels/web/index.ts` — add `repo_root?` to `ArchivedSessionSnapshot`.
- **Modify** `src/main.ts` — include `repo_root` in the `listArchivedSessions` map.
- **Modify** `src/web-app/src/stores/sessions.ts` — add `repo_root?` to `ArchivedSession`.
- **Create** `src/web-app/src/lib/archived-projects.ts` — pure helpers (`projectLabel`, `archivedProjects`, `filterByProject`).
- **Create** `src/web-app/src/lib/archived-projects.test.ts` — `bun:test` unit tests.
- **Create** `src/web-app/src/components/ArchivedProjectFilter.vue` — searchable project filter dropdown.
- **Modify** `src/web-app/src/views/ArchivedListView.vue` — state, filter control, per-row project label (both compact + mobile variants).

---

## Task 1: Backend — expose `repo_root` for archived sessions

**Files:**
- Modify: `src/channels/web/index.ts:96-103` (the `ArchivedSessionSnapshot` interface)
- Modify: `src/main.ts:1391-1399` (the `listArchivedSessions` map)

`repo_root` is already persisted in the `sessions` DB table and surfaced for active sessions; the archived snapshot just omits it. This task adds the passthrough. (No DB migration; no new endpoint. This is wiring — verified by typecheck, not a unit test, matching the existing snapshot mappings which have none.)

- [ ] **Step 1: Add `repo_root` to the snapshot type**

In `src/channels/web/index.ts`, the interface currently reads:

```ts
export interface ArchivedSessionSnapshot {
  id: string
  name: string
  workdir: string
  agent: AgentKind
  model?: string
  killed_at?: string
}
```

Add `repo_root?`:

```ts
export interface ArchivedSessionSnapshot {
  id: string
  name: string
  workdir: string
  agent: AgentKind
  model?: string
  killed_at?: string
  repo_root?: string
}
```

- [ ] **Step 2: Send `repo_root` from the map**

In `src/main.ts`, the map currently reads:

```ts
    listArchivedSessions: () =>
      registry.sessions.listArchived().map((s) => ({
        id: s.id,
        name: s.name,
        workdir: s.workdir,
        agent: s.agent,
        model: s.model,
        killed_at: s.killed_at,
      })),
```

Add `repo_root`:

```ts
    listArchivedSessions: () =>
      registry.sessions.listArchived().map((s) => ({
        id: s.id,
        name: s.name,
        workdir: s.workdir,
        agent: s.agent,
        model: s.model,
        killed_at: s.killed_at,
        repo_root: s.repo_root,
      })),
```

- [ ] **Step 3: Typecheck**

Run: `bun run typecheck`
Expected: PASS (no errors). `s.repo_root` exists on the `SessionRecord` returned by `listArchived()` (`src/core/session-manager/types.ts`).

- [ ] **Step 4: Commit**

```bash
git add src/channels/web/index.ts src/main.ts
git commit -m "feat(web): send repo_root for archived sessions"
```

---

## Task 2: Frontend — add `repo_root` to the `ArchivedSession` type

**Files:**
- Modify: `src/web-app/src/stores/sessions.ts:22-29` (the `ArchivedSession` interface)

- [ ] **Step 1: Add the field**

The interface currently reads:

```ts
export interface ArchivedSession {
  id: string
  name: string
  workdir: string
  agent: string
  model?: string
  killed_at?: string
}
```

Add `repo_root?`:

```ts
export interface ArchivedSession {
  id: string
  name: string
  workdir: string
  agent: string
  model?: string
  killed_at?: string
  repo_root?: string
}
```

- [ ] **Step 2: Typecheck**

Run: `cd src/web-app && bunx vue-tsc --noEmit`
Expected: PASS (no errors).

- [ ] **Step 3: Commit**

```bash
git add src/web-app/src/stores/sessions.ts
git commit -m "feat(web): add repo_root to ArchivedSession type"
```

---

## Task 3: Pure helper — `lib/archived-projects.ts` (TDD)

**Files:**
- Create: `src/web-app/src/lib/archived-projects.ts`
- Test: `src/web-app/src/lib/archived-projects.test.ts`

This module owns all project derivation/label/filter logic. It defines its own minimal input interface (`ArchivedLike`) instead of importing the store type — same decoupling pattern as `recent-projects.ts` (`RecencySession`). Identity uses `normalizeWorkdirKey` (the same key `workdirDisplay` uses, so archived projects line up with the active list).

- [ ] **Step 1: Write the failing tests**

Create `src/web-app/src/lib/archived-projects.test.ts`:

```ts
import { expect, test } from "bun:test"
import { archivedProjects, filterByProject, projectLabel, type ArchivedLike } from "./archived-projects"

const HOME = "/home/ahmet"

function s(workdir: string, opts: Partial<ArchivedLike> = {}): ArchivedLike {
  return { workdir, ...opts }
}

// --- projectLabel ---

test("projectLabel shows parent/leaf with ellipsis for nested home paths", () => {
  expect(projectLabel("/home/ahmet/projects/kurbanhane", HOME)).toBe("…/projects/kurbanhane")
})

test("projectLabel shows ~/leaf when the project sits directly in home", () => {
  expect(projectLabel("/home/ahmet/foo", HOME)).toBe("~/foo")
})

test("projectLabel shows ~ for the home directory itself", () => {
  expect(projectLabel("/home/ahmet", HOME)).toBe("~")
})

test("projectLabel keeps parent/leaf for a shallow non-home path", () => {
  expect(projectLabel("/srv/acme", HOME)).toBe("srv/acme")
})

test("projectLabel adds ellipsis for a deep non-home path", () => {
  expect(projectLabel("/srv/www/acme", HOME)).toBe("…/www/acme")
})

test("projectLabel returns a single-segment path unchanged", () => {
  expect(projectLabel("/acme", HOME)).toBe("/acme")
})

// --- archivedProjects ---

test("archivedProjects dedupes by project and counts sessions", () => {
  const result = archivedProjects([
    s("/home/ahmet/projects/foo", { killed_at: "2026-06-01T00:00:00Z" }),
    s("/home/ahmet/projects/foo", { killed_at: "2026-06-02T00:00:00Z" }),
  ], HOME)
  expect(result).toEqual([{ key: "/home/ahmet/projects/foo", label: "…/projects/foo", count: 2 }])
})

test("archivedProjects groups a worktree session under its repo_root", () => {
  const result = archivedProjects([
    s("/home/ahmet/.mux/worktrees/x/abc", { repo_root: "/home/ahmet/projects/foo", killed_at: "2026-06-01T00:00:00Z" }),
    s("/home/ahmet/projects/foo", { killed_at: "2026-06-02T00:00:00Z" }),
  ], HOME)
  expect(result).toEqual([{ key: "/home/ahmet/projects/foo", label: "…/projects/foo", count: 2 }])
})

test("archivedProjects orders most-recently-archived first", () => {
  const result = archivedProjects([
    s("/home/ahmet/projects/old", { killed_at: "2026-06-01T00:00:00Z" }),
    s("/home/ahmet/projects/new", { killed_at: "2026-06-10T00:00:00Z" }),
  ], HOME)
  expect(result.map((p) => p.label)).toEqual(["…/projects/new", "…/projects/old"])
})

test("archivedProjects returns [] for no sessions", () => {
  expect(archivedProjects([], HOME)).toEqual([])
})

// --- filterByProject ---

test("filterByProject returns only sessions in the given project", () => {
  const sessions = [s("/home/ahmet/projects/foo"), s("/home/ahmet/projects/bar")]
  expect(filterByProject(sessions, "/home/ahmet/projects/foo", HOME)).toEqual([
    s("/home/ahmet/projects/foo"),
  ])
})

test("filterByProject matches a worktree session by repo_root", () => {
  const wt = s("/home/ahmet/.mux/worktrees/x/abc", { repo_root: "/home/ahmet/projects/foo" })
  expect(filterByProject([wt], "/home/ahmet/projects/foo", HOME)).toEqual([wt])
})

test("filterByProject returns all sessions when key is null", () => {
  const sessions = [s("/home/ahmet/projects/foo"), s("/home/ahmet/projects/bar")]
  expect(filterByProject(sessions, null, HOME)).toEqual(sessions)
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bun test src/web-app/src/lib/archived-projects.test.ts`
Expected: FAIL — `Cannot find module './archived-projects'` (the module does not exist yet).

- [ ] **Step 3: Implement the module**

Create `src/web-app/src/lib/archived-projects.ts`:

```ts
import { inferHomeDir, normalizeWorkdirKey } from "./workdir-display"

/** Minimal shape needed to derive a project — a subset of the store's ArchivedSession. */
export interface ArchivedLike {
  workdir: string
  repo_root?: string
  killed_at?: string
}

export interface ArchivedProject {
  /** normalizeWorkdirKey result — identity for dedupe + filtering. */
  key: string
  /** Display label: shortened path with parent folder. */
  label: string
  /** Number of archived sessions in this project. */
  count: number
}

/** A session's project path: its repo (for worktrees) else its workdir. */
function projectPath(s: ArchivedLike): string {
  return s.repo_root ?? s.workdir
}

/**
 * Shortened path with parent folder: `parent/leaf`, prefixed with `…/` when
 * deeper, `~/leaf` directly under home, `~` for home itself.
 */
export function projectLabel(workdir: string, homeDir?: string | null): string {
  const key = normalizeWorkdirKey(workdir, homeDir)
  const home = homeDir ? normalizeWorkdirKey(homeDir) : inferHomeDir(key)
  if (home && key === home) return "~"
  const segments = key.split("/").filter(Boolean)
  if (segments.length <= 1) return key
  const leaf = segments[segments.length - 1]!
  const parent = segments[segments.length - 2]!
  const parentPath = "/" + segments.slice(0, -1).join("/")
  if (home && parentPath === home) return `~/${leaf}`
  const base = `${parent}/${leaf}`
  return segments.length > 2 ? `…/${base}` : base
}

/** Distinct projects across archived sessions, most-recently-archived first. */
export function archivedProjects(sessions: ArchivedLike[], homeDir?: string | null): ArchivedProject[] {
  const byKey = new Map<string, { key: string; label: string; count: number; latest: string }>()
  for (const s of sessions) {
    const path = projectPath(s)
    const key = normalizeWorkdirKey(path, homeDir)
    const killed = s.killed_at ?? ""
    const existing = byKey.get(key)
    if (existing) {
      existing.count += 1
      if (killed > existing.latest) existing.latest = killed
    } else {
      byKey.set(key, { key, label: projectLabel(path, homeDir), count: 1, latest: killed })
    }
  }
  return [...byKey.values()]
    .sort((a, b) => (a.latest === b.latest ? a.label.localeCompare(b.label) : b.latest.localeCompare(a.latest)))
    .map(({ key, label, count }) => ({ key, label, count }))
}

/** Sessions in the given project (by key). A null/empty key returns all sessions. */
export function filterByProject<T extends ArchivedLike>(
  sessions: T[],
  key: string | null,
  homeDir?: string | null,
): T[] {
  if (!key) return sessions
  return sessions.filter((s) => normalizeWorkdirKey(projectPath(s), homeDir) === key)
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `bun test src/web-app/src/lib/archived-projects.test.ts`
Expected: PASS — all 13 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/web-app/src/lib/archived-projects.ts src/web-app/src/lib/archived-projects.test.ts
git commit -m "feat(web): add archived-projects helper (label + group + filter)"
```

---

## Task 4: `ArchivedProjectFilter.vue` — searchable filter dropdown

**Files:**
- Create: `src/web-app/src/components/ArchivedProjectFilter.vue`

A self-contained dropdown mirroring `ProjectPathPicker.vue`'s search-list pattern: a trigger showing the selected label, and a content panel with a search input + a keyboard-navigable list. Emits the selected project `key` (or `null` for "All projects").

- [ ] **Step 1: Create the component**

Create `src/web-app/src/components/ArchivedProjectFilter.vue`:

```vue
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
```

- [ ] **Step 2: Typecheck**

Run: `cd src/web-app && bunx vue-tsc --noEmit`
Expected: PASS (no errors).

- [ ] **Step 3: Commit**

```bash
git add src/web-app/src/components/ArchivedProjectFilter.vue
git commit -m "feat(web): add ArchivedProjectFilter dropdown"
```

---

## Task 5: Wire the filter + project labels into `ArchivedListView.vue`

**Files:**
- Modify: `src/web-app/src/views/ArchivedListView.vue`

Add filter state, render the dropdown above each list variant, swap the row loops to the filtered `visible` list, and replace the raw workdir line with the project label.

- [ ] **Step 1: Update the script block**

The script currently starts:

```ts
<script setup lang="ts">
import { ref, onMounted } from "vue"
import { useRouter } from "vue-router"
import { ChevronLeft } from "lucide-vue-next"
import { useSessions } from "@/stores/sessions"
import { useLayout } from "@/stores/layout"
import { toast } from "vue-sonner"
import { formatWorkdir } from "@/lib/format-workdir"

const props = defineProps<{ compact?: boolean }>()

const sessions = useSessions()
const layout = useLayout()
const router = useRouter()
const loading = ref(false)
```

Replace that span with (adds `computed`/`watch`, the `Folder` icon, the new lib + component imports, drops the now-unused `formatWorkdir`, and adds the filter state):

```ts
<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue"
import { useRouter } from "vue-router"
import { ChevronLeft, Folder } from "lucide-vue-next"
import { useSessions } from "@/stores/sessions"
import { useLayout } from "@/stores/layout"
import { toast } from "vue-sonner"
import { archivedProjects, filterByProject, projectLabel } from "@/lib/archived-projects"
import ArchivedProjectFilter from "@/components/ArchivedProjectFilter.vue"

const props = defineProps<{ compact?: boolean }>()

const sessions = useSessions()
const layout = useLayout()
const router = useRouter()
const loading = ref(false)

const selectedProjectKey = ref<string | null>(null)
const projects = computed(() => archivedProjects(sessions.archivedSessions, sessions.homeDir))
const visible = computed(() => filterByProject(sessions.archivedSessions, selectedProjectKey.value, sessions.homeDir))

// If the selected project disappears (e.g. its last session was resumed), clear the filter.
watch(projects, (list) => {
  if (selectedProjectKey.value && !list.some((p) => p.key === selectedProjectKey.value)) {
    selectedProjectKey.value = null
  }
})
```

- [ ] **Step 2: Add the filter bar + project labels to the compact (desktop) variant**

In the compact `<div v-if="props.compact" ...>` block, insert the filter bar between the closing `</header>` and the `<div class="flex-1 overflow-y-auto">`:

```html
    <div v-if="sessions.archivedSessions.length > 0" class="px-3 py-2 border-b border-border/50 shrink-0">
      <ArchivedProjectFilter v-model="selectedProjectKey" :projects="projects" />
    </div>
```

Then, inside that scroll container, change the row loop from `sessions.archivedSessions` to `visible` and replace the workdir line. The block currently reads:

```html
      <button
        v-for="s in sessions.archivedSessions"
        :key="s.id"
        class="w-full text-left px-4 py-2.5 border-b border-border/50 hover:bg-muted/30 transition"
        @click="openSession(s.id)"
      >
        <div class="flex items-baseline justify-between gap-1">
          <span class="text-xs font-medium truncate">{{ s.name }}</span>
          <span v-if="s.agent" class="text-[10px] shrink-0 text-primary/70">{{ s.agent }}</span>
        </div>
        <div class="text-[10px] text-muted-foreground truncate font-mono">{{ formatWorkdir(s.workdir, sessions.homeDir) }}</div>
        <div v-if="s.killed_at" class="text-[10px] text-muted-foreground/60 mt-0.5">{{ formatKillDate(s.killed_at) }}</div>
      </button>
```

Replace it with:

```html
      <button
        v-for="s in visible"
        :key="s.id"
        class="w-full text-left px-4 py-2.5 border-b border-border/50 hover:bg-muted/30 transition"
        @click="openSession(s.id)"
      >
        <div class="flex items-baseline justify-between gap-1">
          <span class="text-xs font-medium truncate">{{ s.name }}</span>
          <span v-if="s.agent" class="text-[10px] shrink-0 text-primary/70">{{ s.agent }}</span>
        </div>
        <div class="flex items-center gap-1 text-[10px] text-muted-foreground mt-0.5">
          <Folder class="size-3 shrink-0 opacity-70" />
          <span class="truncate font-mono">{{ projectLabel(s.repo_root ?? s.workdir, sessions.homeDir) }}</span>
        </div>
        <div v-if="s.killed_at" class="text-[10px] text-muted-foreground/60 mt-0.5">{{ formatKillDate(s.killed_at) }}</div>
      </button>
```

- [ ] **Step 3: Add the filter bar + project labels to the full-page (mobile) variant**

In the `<div v-else ...>` block, insert the filter bar between the closing `</header>` and the `<div v-if="loading" ...>` line:

```html
    <div v-if="sessions.archivedSessions.length > 0" class="px-4 py-2 border-b border-border">
      <ArchivedProjectFilter v-model="selectedProjectKey" :projects="projects" />
    </div>
```

Then change the mobile row loop from `sessions.archivedSessions` to `visible` and replace its workdir line. The block currently reads:

```html
    <button
      v-for="s in sessions.archivedSessions"
      :key="s.id"
      class="w-full text-left px-4 py-3 border-b border-border flex items-start gap-3 hover:bg-muted/30 transition"
      @click="openSession(s.id)"
    >
      <div class="min-w-0 flex-1">
        <div class="flex items-baseline justify-between gap-2">
          <span class="text-sm font-medium truncate">{{ s.name }}</span>
          <span v-if="s.agent" class="text-[11px] shrink-0 text-primary/70">{{ s.agent }}{{ s.model ? `:${s.model}` : '' }}</span>
        </div>
        <div class="text-[12px] text-muted-foreground truncate font-mono mt-0.5">{{ formatWorkdir(s.workdir, sessions.homeDir) }}</div>
        <div v-if="s.killed_at" class="text-[11px] text-muted-foreground/60 mt-0.5">Archived {{ formatKillDate(s.killed_at) }}</div>
      </div>
    </button>
```

Replace it with:

```html
    <button
      v-for="s in visible"
      :key="s.id"
      class="w-full text-left px-4 py-3 border-b border-border flex items-start gap-3 hover:bg-muted/30 transition"
      @click="openSession(s.id)"
    >
      <div class="min-w-0 flex-1">
        <div class="flex items-baseline justify-between gap-2">
          <span class="text-sm font-medium truncate">{{ s.name }}</span>
          <span v-if="s.agent" class="text-[11px] shrink-0 text-primary/70">{{ s.agent }}{{ s.model ? `:${s.model}` : '' }}</span>
        </div>
        <div class="flex items-center gap-1 text-[12px] text-muted-foreground mt-0.5">
          <Folder class="size-3 shrink-0 opacity-70" />
          <span class="truncate font-mono">{{ projectLabel(s.repo_root ?? s.workdir, sessions.homeDir) }}</span>
        </div>
        <div v-if="s.killed_at" class="text-[11px] text-muted-foreground/60 mt-0.5">Archived {{ formatKillDate(s.killed_at) }}</div>
      </div>
    </button>
```

- [ ] **Step 4: Typecheck**

Run: `cd src/web-app && bunx vue-tsc --noEmit`
Expected: PASS. (If it flags `formatWorkdir` as unused, confirm its import line was removed in Step 1.)

- [ ] **Step 5: Commit**

```bash
git add src/web-app/src/views/ArchivedListView.vue
git commit -m "feat(web): filter archived list by project + show project per row"
```

---

## Task 6: Full verification

**Files:** none (verification only)

- [ ] **Step 1: Run the whole test suite**

Run: `bun test`
Expected: PASS, including the new `archived-projects.test.ts`.

- [ ] **Step 2: Typecheck both sides**

Run: `bun run typecheck`
Then: `cd src/web-app && bunx vue-tsc --noEmit`
Expected: both PASS.

- [ ] **Step 3: Build the web app**

Run: `cd src/web-app && bun run build`
Expected: `vue-tsc --noEmit && vite build` completes without errors.

- [ ] **Step 4: Manual check (broker running, archived sessions present)**

Open the archived list (three-dots → Archived, or `/archived` on mobile) and confirm:
- Each row shows a folder-iconed project label like `…/projects/foo` (or `~/foo`).
- The "All projects ▾" dropdown lists each project with a count and is searchable.
- Selecting a project narrows the list; "All projects" restores it.
- Resuming the last session in the selected project resets the filter to "All".
- Both the desktop sidebar and mobile full-page views behave the same.

---

## Self-Review Notes

- **Spec coverage:** per-row label (Task 5), searchable filter dropdown (Task 4 + 5), projects-with-archived-only + counts (Task 3 `archivedProjects` + Task 4), `repo_root ?? workdir` project identity (Task 1–3), recency order (Task 3), auto-reset on resume (Task 5), both variants (Task 5). All spec sections map to a task.
- **Types:** `ArchivedProject { key, label, count }` and `ArchivedLike` defined in Task 3 are used unchanged in Tasks 4–5; `repo_root?` added in Tasks 1–2 is consumed in Tasks 3 & 5.
- **No placeholders:** every code/command step shows full content.
