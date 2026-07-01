# New Session Launcher Draft Persistence — Web Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the New Session launcher's project pick, worktree settings, and typed message text to `localStorage`, so they survive leaving `/new` and coming back — or fully closing and reopening the app — and clear automatically once a session is actually created. Attachments are explicitly out of scope (ephemeral upload blobs — see the spec's Decisions §2).

**Architecture:** A new Pinia store (`launcherDraft.ts`), shaped exactly like the existing `editorSettings.ts` store (reactive state + deep `watch` → `localStorage`, defensive JSON parsing). A small renderless component (`LauncherDraftSync.vue`), shaped like the existing `PromptInputDraftSync.vue`, bridges the composer's text into that store. `SessionLauncherView.vue` restores the draft on setup and clears it after a successful `onPromptSubmit`.

**Tech Stack:** Vue 3 `<script setup>`, Pinia, `bun:test` (via `bun test`), TypeScript (`vue-tsc`).

**Spec:** `docs/superpowers/specs/2026-07-01-launcher-draft-persistence-design.md`

---

## Prerequisites

This plan touches `src/web-app`. If you're in a freshly created worktree, `node_modules` will be empty and `bun test`/`vue-tsc` will fail with misleading "Cannot find package" errors. Fix once, up front:

```bash
cd src/web-app && bun install
```

All commands below assume your shell's working directory is `src/web-app` (the repo's web app package), not the repo root.

---

### Task 1: `launcherDraft` Pinia store

**Files:**
- Create: `src/web-app/src/stores/launcherDraft.ts`
- Test: `src/web-app/src/stores/launcherDraft.test.ts`

- [ ] **Step 1: Write the failing tests**

```typescript
// src/web-app/src/stores/launcherDraft.test.ts
import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { nextTick } from "vue"
import { useLauncherDraft } from "./launcherDraft"

const mem = new Map<string, string>()

;(globalThis as any).localStorage = {
  getItem: (k: string) => (mem.has(k) ? mem.get(k)! : null),
  setItem: (k: string, v: string) => { mem.set(k, v) },
  removeItem: (k: string) => { mem.delete(k) },
  clear: () => { mem.clear() },
}

beforeEach(() => {
  mem.clear()
  setActivePinia(createPinia())
})

test("defaults to an empty draft when storage is empty", () => {
  const draft = useLauncherDraft()
  expect(draft.state.workdir).toBeNull()
  expect(draft.state.useWorktree).toBe(true)
  expect(draft.state.baseBranch).toBe("")
  expect(draft.state.text).toBe("")
})

test("persists and restores workdir", async () => {
  const draft = useLauncherDraft()
  draft.setWorkdir("/home/user/project")
  await nextTick()
  expect(mem.get("cmux:launcher-draft")).toContain("\"workdir\":\"/home/user/project\"")

  setActivePinia(createPinia())
  const restored = useLauncherDraft()
  expect(restored.state.workdir).toBe("/home/user/project")
})

test("persists and restores worktree + base branch", async () => {
  const draft = useLauncherDraft()
  draft.setWorktree(false)
  draft.setBaseBranch("feature/x")
  await nextTick()

  setActivePinia(createPinia())
  const restored = useLauncherDraft()
  expect(restored.state.useWorktree).toBe(false)
  expect(restored.state.baseBranch).toBe("feature/x")
})

test("persists and restores typed text", async () => {
  const draft = useLauncherDraft()
  draft.setText("fix the flaky test")
  await nextTick()

  setActivePinia(createPinia())
  const restored = useLauncherDraft()
  expect(restored.state.text).toBe("fix the flaky test")
})

test("clear resets every field to defaults", async () => {
  const draft = useLauncherDraft()
  draft.setWorkdir("/home/user/project")
  draft.setWorktree(false)
  draft.setBaseBranch("feature/x")
  draft.setText("hello")
  await nextTick()

  draft.clear()
  await nextTick()
  expect(draft.state.workdir).toBeNull()
  expect(draft.state.useWorktree).toBe(true)
  expect(draft.state.baseBranch).toBe("")
  expect(draft.state.text).toBe("")

  setActivePinia(createPinia())
  const restored = useLauncherDraft()
  expect(restored.state.workdir).toBeNull()
  expect(restored.state.text).toBe("")
})

test("falls back to defaults on malformed JSON", () => {
  mem.set("cmux:launcher-draft", "{not valid json")
  const draft = useLauncherDraft()
  expect(draft.state.workdir).toBeNull()
  expect(draft.state.text).toBe("")
})

test("falls back to defaults when storage holds a non-object", () => {
  mem.set("cmux:launcher-draft", "\"just a string\"")
  const draft = useLauncherDraft()
  expect(draft.state.workdir).toBeNull()
  expect(draft.state.useWorktree).toBe(true)
})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `bun test src/stores/launcherDraft.test.ts`
Expected: FAIL — `error: Cannot find module './launcherDraft'` (the file doesn't exist yet).

- [ ] **Step 3: Write the store**

```typescript
// src/web-app/src/stores/launcherDraft.ts
import { defineStore } from "pinia"
import { reactive, watch } from "vue"

const KEY = "cmux:launcher-draft"

// In-progress New Session launcher state: the project pick (only once the user
// has explicitly engaged — null means "nothing in flight, follow the recency
// default"), worktree settings, and typed message text. Persists across
// navigation and app relaunch; cleared the moment a session is actually
// created (see SessionLauncherView.onPromptSubmit). Sibling of editorSettings.ts
// (same localStorage-backed reactive-store shape) — NOT the same store as the
// sticky agent/model prefs (cmux:launcher-prefs), which persist forever.
export interface LauncherDraft {
  workdir: string | null
  useWorktree: boolean
  baseBranch: string
  text: string
}

function defaults(): LauncherDraft {
  return { workdir: null, useWorktree: true, baseBranch: "", text: "" }
}

function load(): LauncherDraft {
  const base = defaults()
  try {
    const raw = localStorage.getItem(KEY)
    if (!raw) return base
    const p = JSON.parse(raw)
    if (!p || typeof p !== "object") return base
    return {
      workdir: typeof p.workdir === "string" ? p.workdir : base.workdir,
      useWorktree: typeof p.useWorktree === "boolean" ? p.useWorktree : base.useWorktree,
      baseBranch: typeof p.baseBranch === "string" ? p.baseBranch : base.baseBranch,
      text: typeof p.text === "string" ? p.text : base.text,
    }
  } catch {
    return base
  }
}

export const useLauncherDraft = defineStore("launcherDraft", () => {
  const state = reactive<LauncherDraft>(load())

  watch(state, () => {
    try { localStorage.setItem(KEY, JSON.stringify(state)) } catch {}
  }, { deep: true })

  function setWorkdir(value: string) { state.workdir = value }
  function setWorktree(value: boolean) { state.useWorktree = value }
  function setBaseBranch(value: string) { state.baseBranch = value }
  function setText(value: string) { state.text = value }
  function clear() {
    state.workdir = null
    state.useWorktree = true
    state.baseBranch = ""
    state.text = ""
  }

  return { state, setWorkdir, setWorktree, setBaseBranch, setText, clear }
})
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `bun test src/stores/launcherDraft.test.ts`
Expected: `7 pass, 0 fail`

- [ ] **Step 5: Commit**

```bash
git add src/web-app/src/stores/launcherDraft.ts src/web-app/src/stores/launcherDraft.test.ts
git commit -m "feat(web): add launcherDraft store for New Session draft persistence"
```

---

### Task 2: `LauncherDraftSync` renderless component

**Files:**
- Create: `src/web-app/src/components/LauncherDraftSync.vue`

There's no existing test file for the component this mirrors (`PromptInputDraftSync.vue`) — testing it requires mounting inside the full `<PromptInput>` provider tree, which the codebase doesn't do for this kind of thin sync component. This task is implementation + the manual check in Task 4, consistent with existing practice. `launcherDraft.ts` (Task 1) already has full unit coverage of the actual persistence logic; this component only bridges `textInput` into `draft.setText`.

- [ ] **Step 1: Write the component**

```vue
<!-- src/web-app/src/components/LauncherDraftSync.vue -->
<script setup lang="ts">
// Renderless bridge between the launcher composer's textInput and the local
// draft store. Must live INSIDE <PromptInput> so it can inject the composer
// context (see usePromptInput). Mirrors PromptInputDraftSync.vue's debounce,
// but there's no session yet to sync over the wire — just localStorage via
// the launcherDraft store, no cross-device remote-apply branch.
import { onBeforeUnmount, watch } from "vue"
import { usePromptInput } from "@/components/ai-elements/prompt-input"
import { useLauncherDraft } from "@/stores/launcherDraft"

const { textInput } = usePromptInput()
const draft = useLauncherDraft()

const DEBOUNCE_MS = 800
let timer: ReturnType<typeof setTimeout> | undefined

watch(textInput, (text) => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => { draft.setText(text) }, DEBOUNCE_MS)
})

onBeforeUnmount(() => {
  if (timer) clearTimeout(timer)
})
</script>

<template>
  <!-- renderless -->
</template>
```

- [ ] **Step 2: Typecheck**

Run: `bun run build`
Expected: no new TypeScript errors (this step only adds a new, unused-so-far file — it won't be type-checked into the app until Task 3 imports it, so confirm there are zero errors reported for `LauncherDraftSync.vue` itself once Task 3 wires it in; for now just confirm the command still succeeds).

- [ ] **Step 3: Commit**

```bash
git add src/web-app/src/components/LauncherDraftSync.vue
git commit -m "feat(web): add LauncherDraftSync to bridge composer text into launcherDraft"
```

---

### Task 3: Wire the draft into `SessionLauncherView.vue`

**Files:**
- Modify: `src/web-app/src/views/SessionLauncherView.vue`

This task restores the draft on setup (before the workdir-triggered `refreshRepoInfo` call so we don't fire it once with the stale default), seeds the composer's initial text, and clears the draft after a session is successfully created. Agent/model persistence (`cmux:launcher-prefs`, `loadPrefs`/`savePrefs`) is untouched — different lifecycle, already correct.

- [ ] **Step 1: Add the import**

Modify `src/web-app/src/views/SessionLauncherView.vue:9` — after the existing `usePendingFirstMessage` import, add:

```typescript
import { useLauncherDraft } from "@/stores/launcherDraft"
```

And after the `LauncherComposeLock` import (`SessionLauncherView.vue:39`), add:

```typescript
import LauncherDraftSync from "@/components/LauncherDraftSync.vue"
```

- [ ] **Step 2: Restore the draft before the workdir watcher fires**

Modify `src/web-app/src/views/SessionLauncherView.vue:61-63` (currently):

```typescript
const repoInfo = ref<{ isGitRepo: boolean; eligible: boolean; repoRoot?: string; currentBranch?: string; branches?: { local: string[]; remote: string[] } } | null>(null)
const useWorktree = ref(true)
const baseBranch = ref("")
```

Replace with:

```typescript
const repoInfo = ref<{ isGitRepo: boolean; eligible: boolean; repoRoot?: string; currentBranch?: string; branches?: { local: string[]; remote: string[] } } | null>(null)
const useWorktree = ref(true)
const baseBranch = ref("")

const launcherDraft = useLauncherDraft()
if (launcherDraft.state.workdir) {
  workdir.value = launcherDraft.state.workdir
  workdirTouched.value = true
}
useWorktree.value = launcherDraft.state.useWorktree
baseBranch.value = launcherDraft.state.baseBranch
```

This must run before the `watch(workdir, (p) => { if (p?.trim()) void refreshRepoInfo(p) }, { immediate: true })` call a few lines later (`SessionLauncherView.vue:73`, unchanged) — otherwise that immediate watch fires once against the stale `"~"` default before the restored value lands, wasting a `validatePath`/`getRepoInfo` round trip. Placing the restore block right after `useWorktree`/`baseBranch` are declared (and before `refreshRepoInfo`/the watch that calls it) guarantees this.

- [ ] **Step 3: Stop `refreshRepoInfo` from clobbering a restored base branch**

`refreshRepoInfo` unconditionally sets `baseBranch.value = repoInfo.value?.currentBranch ?? ""` every time it resolves — including its very first, `{ immediate: true }`-triggered call for the restored `workdir`. Since that call is `async` (two `await`s deep), it resolves *after* Step 2's synchronous restore has already set `baseBranch.value` from the draft, and would silently overwrite a restored non-default branch pick back to the repo's actual current branch. Guard it so only a genuine *subsequent* workdir change (the user picking a different project) resets `baseBranch` — the same "don't clobber on the first run" rule Task 3 of the iOS and Android plans apply to their equivalent effects.

Modify `src/web-app/src/views/SessionLauncherView.vue:65-72` (currently):

```typescript
async function refreshRepoInfo(p: string) {
  try {
    const validation = await api.validatePath(p)
    if (!validation.ok || !validation.path) { repoInfo.value = null; return }
    repoInfo.value = await api.getRepoInfo(validation.path)
    baseBranch.value = repoInfo.value?.currentBranch ?? ""
  } catch { repoInfo.value = null }
}
```

Replace with:

```typescript
// True once refreshRepoInfo has resolved at least once. Gates the "default to the
// repo's current branch" reset so a restored draft's baseBranch survives the first,
// restore-triggered call — later calls (the user picking a different project) still
// reset it, matching today's behavior for a fresh pick.
let repoInfoInitialized = false
async function refreshRepoInfo(p: string) {
  try {
    const validation = await api.validatePath(p)
    if (!validation.ok || !validation.path) { repoInfo.value = null; return }
    repoInfo.value = await api.getRepoInfo(validation.path)
    if (repoInfoInitialized) {
      baseBranch.value = repoInfo.value?.currentBranch ?? ""
    } else {
      repoInfoInitialized = true
      if (!baseBranch.value) baseBranch.value = repoInfo.value?.currentBranch ?? ""
    }
  } catch { repoInfo.value = null }
}
```

- [ ] **Step 4: Seed the composer's initial text and persist changes**

Modify `src/web-app/src/views/SessionLauncherView.vue:130-132` (currently):

```typescript
watch([agent, model], () => {
  savePrefs()
})
```

Add immediately after it:

```typescript
watch([workdir, workdirTouched, useWorktree, baseBranch], () => {
  if (workdirTouched.value) launcherDraft.setWorkdir(workdir.value)
  launcherDraft.setWorktree(useWorktree.value)
  launcherDraft.setBaseBranch(baseBranch.value)
})
```

- [ ] **Step 5: Pass the restored text into the composer and mount the sync component**

Modify `src/web-app/src/views/SessionLauncherView.vue:302-310` (currently):

```vue
        <PromptInput
          class="relative"
          group-class="rounded-2xl border-border/70 bg-card dark:bg-card shadow-lg shadow-black/[0.04] dark:shadow-black/30"
          :max-files="10"
          :max-file-size="25 * 1024 * 1024"
          :global-drop="isDesktop"
          @submit="onPromptSubmit"
        >
          <LauncherComposeLock @engaged="composeStarted = true" />
```

Replace with:

```vue
        <PromptInput
          class="relative"
          group-class="rounded-2xl border-border/70 bg-card dark:bg-card shadow-lg shadow-black/[0.04] dark:shadow-black/30"
          :max-files="10"
          :max-file-size="25 * 1024 * 1024"
          :global-drop="isDesktop"
          :initial-input="launcherDraft.state.text"
          @submit="onPromptSubmit"
        >
          <LauncherComposeLock @engaged="composeStarted = true" />
          <LauncherDraftSync />
```

- [ ] **Step 6: Clear the draft after a session is created**

Modify `src/web-app/src/views/SessionLauncherView.vue:233-243` (currently):

```typescript
    sessions.add({
      id: result.id,
      name: result.name,
      workdir: result.workdir,
      mute: false,
      connected: true,
      agent: result.agent,
      model: result.model,
      reasoningLevel: result.reasoningLevel,
    })
    pending.set(result.id, payload)
```

Replace with:

```typescript
    sessions.add({
      id: result.id,
      name: result.name,
      workdir: result.workdir,
      mute: false,
      connected: true,
      agent: result.agent,
      model: result.model,
      reasoningLevel: result.reasoningLevel,
    })
    launcherDraft.clear()
    pending.set(result.id, payload)
```

- [ ] **Step 7: Typecheck**

Run: `bun run build`
Expected: no new TypeScript errors.

- [ ] **Step 8: Commit**

```bash
git add src/web-app/src/views/SessionLauncherView.vue
git commit -m "feat(web): restore + persist launcher draft (project, worktree, text)"
```

---

### Task 4: Manual verification

No new automated test covers the full `SessionLauncherView` wiring (it has no existing test file, and the view pulls in router/WS/uploads stores that would need heavy mocking disproportionate to this change — see the spec's Testing section). Verify by hand:

- [ ] **Step 1: Start the dev server**

Run: `bun run dev` (from `src/web-app`)

- [ ] **Step 2: Smoke-test restore across in-app navigation**

1. Open `/new`. Pick a specific project that's a git repo, and in the worktree picker choose a base branch that is **not** the repo's current branch (this is the scenario that exercises the Task 3 Step 3 guard — picking the already-current branch would pass even if that guard were missing). Type a message like `testing draft persistence`.
2. Navigate to Settings (or any other page), then back to `/new`.
3. Confirm: the same project is selected, the base branch you picked is still selected (not silently reverted to the repo's actual current branch), and the typed text is still in the composer.

- [ ] **Step 3: Smoke-test restore across a full reload**

1. With the same in-progress draft from Step 2, fully reload the page (not just an in-app route change).
2. Confirm the draft is still restored (this is the part `localStorage` buys over an in-memory-only store).

- [ ] **Step 4: Smoke-test clearing on submit**

1. From the restored draft, submit the message to actually create a session.
2. Once the new session opens, navigate back to `/new`.
3. Confirm: the composer is empty and the project has reverted to the recency default (not stuck on your old draft's project) — but the agent/model pickers still show your last-used choices (unaffected by the clear).

- [ ] **Step 5: Run the full web test suite**

Run: `bun test` (from `src/web-app`)
Expected: all tests pass, including the new `launcherDraft.test.ts`.
