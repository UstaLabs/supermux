# Diff Base Selector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the editor's diff view compare the working tree against a user-chosen base (session-start / uncommitted / a previous commit / another branch) instead of only the fixed session-start commit.

**Architecture:** The base becomes a string "spec" threaded from a header dropdown in `DiffView` → `useEditor` → `GET /fs/diff?base=` → `computeWorkdirDiff`, which resolves the spec into an effective base commit per repo (merge-base for branches). A new `GET /fs/refs` endpoint feeds the commit/branch submenus. Target is always the working tree.

**Tech Stack:** Bun + TypeScript backend, Vue 3 (`<script setup>`) web app, `bun test`, git via `execFileSync`.

---

## File Structure

- `src/core/editor/workdir-diff.ts` — add `DiffBaseSpec` parsing, per-repo base resolution, `listRepoRefs`; extend `computeWorkdirDiff`.
- `src/core/editor/workdir-diff.test.ts` — **new** unit tests (build temp git repos).
- `src/channels/web/index.ts` — `?base=` on `/fs/diff`; new `/fs/refs` route.
- `src/web-app/src/api/client.ts` — `fsDiff(id, base?)`, new `fsRefs(id)`, `RepoRefs` type.
- `src/web-app/src/composables/useEditor.ts` — `diffBase`/`diffRefs` state, base-aware load.
- `src/web-app/src/components/editor/DiffView.vue` — base dropdown + submenus + header label.

Base spec wire format (single source of truth): `session-start` (default) · `head` · `commit:<sha>` · `branch:<name>`.

---

## Task 1: Base-spec parsing + per-repo resolution (backend core)

**Files:**
- Modify: `src/core/editor/workdir-diff.ts`
- Test: `src/core/editor/workdir-diff.test.ts` (create)

- [ ] **Step 1: Write the failing test**

Create `src/core/editor/workdir-diff.test.ts`:

```ts
import { describe, expect, test, beforeEach, afterEach } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { parseBaseSpec, computeWorkdirDiff, listRepoRefs } from "./workdir-diff"

function git(cwd: string, ...args: string[]): string {
  return execFileSync("git", args, { cwd, encoding: "utf-8" }).trim()
}

let dir: string
function commit(file: string, body: string, msg: string) {
  writeFileSync(join(dir, file), body)
  git(dir, "add", "-A")
  git(dir, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", msg)
}

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "wdiff-"))
  git(dir, "init", "-q", "-b", "main")
  commit("a.txt", "one\n", "c1")
})
afterEach(() => rmSync(dir, { recursive: true, force: true }))

describe("parseBaseSpec", () => {
  test("defaults to session-start", () => {
    expect(parseBaseSpec(undefined)).toEqual({ kind: "session-start" })
    expect(parseBaseSpec("")).toEqual({ kind: "session-start" })
    expect(parseBaseSpec("garbage")).toEqual({ kind: "session-start" })
  })
  test("parses head/commit/branch", () => {
    expect(parseBaseSpec("head")).toEqual({ kind: "head" })
    expect(parseBaseSpec("commit:abc123")).toEqual({ kind: "commit", sha: "abc123" })
    expect(parseBaseSpec("branch:dev")).toEqual({ kind: "branch", name: "dev" })
  })
})

describe("computeWorkdirDiff base specs", () => {
  test("head base shows only uncommitted changes", async () => {
    commit("a.txt", "two\n", "c2")               // committed change (not in HEAD diff)
    writeFileSync(join(dir, "a.txt"), "three\n")  // uncommitted
    const repos = await computeWorkdirDiff(dir, {}, undefined, "head")
    const diff = repos[0]!.files.find((f) => f.path === "a.txt")!.diff
    expect(diff).toContain("three")
    expect(diff).not.toContain("+two")            // c2 already committed → not shown vs HEAD
  })

  test("branch base uses merge-base (no phantom deletions of mainline commits)", async () => {
    git(dir, "checkout", "-q", "-b", "feature")
    commit("a.txt", "feat\n", "on-feature")
    git(dir, "checkout", "-q", "main")
    commit("b.txt", "main-only\n", "on-main")     // main advances after branch point
    git(dir, "checkout", "-q", "feature")
    const repos = await computeWorkdirDiff(dir, {}, undefined, "branch:main")
    const paths = repos[0]!.files.map((f) => f.path)
    expect(paths).toContain("a.txt")              // feature's own change shows
    expect(paths).not.toContain("b.txt")          // main-only commit is NOT a phantom deletion
  })

  test("invalid commit spec falls back to session-start", async () => {
    const base = git(dir, "rev-parse", "HEAD")
    commit("a.txt", "two\n", "c2")
    const repos = await computeWorkdirDiff(dir, { "": base }, undefined, "commit:deadbeef")
    expect(repos[0]!.files.find((f) => f.path === "a.txt")!.diff).toContain("two")
  })
})

describe("listRepoRefs", () => {
  test("returns branches and recent commits for the repo", () => {
    git(dir, "branch", "dev")
    const refs = listRepoRefs(dir)
    expect(refs[0]!.branches).toEqual(expect.arrayContaining(["main", "dev"]))
    expect(refs[0]!.commits[0]!.subject).toBe("c1")
    expect(refs[0]!.commits[0]!.sha).toMatch(/^[0-9a-f]{7,}$/)
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/editor/workdir-diff.test.ts`
Expected: FAIL — `parseBaseSpec`/`listRepoRefs` not exported; `computeWorkdirDiff` ignores the 4th arg.

- [ ] **Step 3: Implement in `src/core/editor/workdir-diff.ts`**

Add after the `EMPTY_TREE` constant:

```ts
export type DiffBaseSpec =
  | { kind: "session-start" }
  | { kind: "head" }
  | { kind: "commit"; sha: string }
  | { kind: "branch"; name: string }

// A git ref-name safe enough to hand to execFileSync (no leading dash → no option injection).
function safeRefName(name: string): boolean {
  return /^[\w][\w./-]*$/.test(name)
}

export function parseBaseSpec(spec: string | undefined | null): DiffBaseSpec {
  if (!spec || spec === "session-start") return { kind: "session-start" }
  if (spec === "head") return { kind: "head" }
  if (spec.startsWith("commit:")) return { kind: "commit", sha: spec.slice(7) }
  if (spec.startsWith("branch:")) return { kind: "branch", name: spec.slice(7) }
  return { kind: "session-start" }
}

export interface RepoRefs {
  repo: string
  branches: string[]
  commits: Array<{ sha: string; subject: string }>
}

export function listRepoRefs(workdir: string): RepoRefs[] {
  const out: RepoRefs[] = []
  for (const r of scanRepos(workdir)) {
    let branches: string[] = []
    let commits: Array<{ sha: string; subject: string }> = []
    try {
      branches = runGit(r.absPath, ["branch", "--format=%(refname:short)"])
        .split("\n").map((s) => s.trim()).filter(Boolean)
    } catch { /* no branches yet */ }
    try {
      commits = runGit(r.absPath, ["log", "-30", "--format=%h%x00%s"])
        .split("\n").filter(Boolean)
        .map((l) => { const i = l.indexOf("\0"); return { sha: l.slice(0, i), subject: l.slice(i + 1) } })
    } catch { /* no history */ }
    out.push({ repo: r.relPath, branches, commits })
  }
  return out
}
```

Then add a resolver that layers over `resolveBase` (which stays the session-start path):

```ts
// Resolve a user-chosen base spec into an effective base commit for one repo.
// Any spec that can't be resolved in THIS repo falls back to session-start.
function resolveSpecBase(
  repoAbs: string,
  spec: DiffBaseSpec,
  stored: string | undefined,
  createdAt?: string,
): string {
  switch (spec.kind) {
    case "session-start":
      return resolveBase(repoAbs, stored, createdAt)
    case "head":
      try {
        const sha = runGit(repoAbs, ["rev-parse", "--verify", "HEAD"]).trim()
        if (/^[0-9a-f]{7,40}$/i.test(sha)) return sha
      } catch { /* no HEAD */ }
      return EMPTY_TREE
    case "commit": {
      if (!/^[0-9a-f]{4,40}$/i.test(spec.sha)) return resolveBase(repoAbs, stored, createdAt)
      try {
        const sha = runGit(repoAbs, ["rev-parse", "--verify", `${spec.sha}^{commit}`]).trim()
        if (/^[0-9a-f]{7,40}$/i.test(sha)) return sha
      } catch { /* commit not in this repo */ }
      return resolveBase(repoAbs, stored, createdAt)
    }
    case "branch": {
      if (!safeRefName(spec.name)) return resolveBase(repoAbs, stored, createdAt)
      try {
        const mb = runGit(repoAbs, ["merge-base", spec.name, "HEAD"]).trim()
        if (/^[0-9a-f]{7,40}$/i.test(mb)) return mb
      } catch { /* branch missing here */ }
      return resolveBase(repoAbs, stored, createdAt)
    }
  }
}
```

Change `computeWorkdirDiff`'s signature and the base line:

```ts
export async function computeWorkdirDiff(
  workdir: string,
  baseCommits: Record<string, string>,
  createdAt?: string,
  baseSpec?: string,
): Promise<RepoDiff[]> {
```

and inside the repo loop, replace

```ts
    const effectiveBase = resolveBase(repo.absPath, baseCommits[repo.relPath], createdAt)
```

with

```ts
    const spec = parseBaseSpec(baseSpec)
    const effectiveBase = resolveSpecBase(repo.absPath, spec, baseCommits[repo.relPath], createdAt)
```

(Hoist `const spec = parseBaseSpec(baseSpec)` above the loop so it parses once.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `bun test src/core/editor/workdir-diff.test.ts`
Expected: PASS (all 6 tests).

- [ ] **Step 5: Typecheck + commit**

Run: `bun run typecheck`
Expected: no new errors in `workdir-diff.ts`.

```bash
git add -f src/core/editor/workdir-diff.ts src/core/editor/workdir-diff.test.ts
git commit -m "feat(editor): resolve diff base from a user spec (head/commit/branch)"
```

---

## Task 2: Wire `?base=` into `/fs/diff` (backend route)

**Files:**
- Modify: `src/channels/web/index.ts` (the `/fs/diff` route, ~line 1757)

- [ ] **Step 1: Read the search param and pass it through**

In the `GET /sessions/:id/fs/diff` handler, after `const createdAt = ...`, change the `computeWorkdirDiff` call:

```ts
      const baseSpec = url.searchParams.get("base") ?? undefined
      const repos = await computeWorkdirDiff(workdir, baseCommits, createdAt, baseSpec)
```

- [ ] **Step 2: Manual verify with a running broker**

Run (broker already runs on :9898 via systemd, or `bun run broker`):
`curl -s "http://127.0.0.1:9898/sessions/<id>/fs/diff?base=head" | head -c 200`
Expected: JSON `{ "repos": [...], "comments": [...] }` reflecting only uncommitted changes.

- [ ] **Step 3: Commit**

```bash
git add src/channels/web/index.ts
git commit -m "feat(web): accept ?base= on /fs/diff"
```

---

## Task 3: New `/fs/refs` endpoint (backend route)

**Files:**
- Modify: `src/channels/web/index.ts` (add route next to `/fs/diff`); import `listRepoRefs`.

- [ ] **Step 1: Add the import**

At the existing `computeWorkdirDiff` import line (~14), extend it:

```ts
import { computeWorkdirDiff, listRepoRefs } from "../../core/editor/workdir-diff"
```

- [ ] **Step 2: Add the route** immediately after the `/fs/diff` block:

```ts
    if (method === "GET" && path.match(/^\/sessions\/[^/]+\/fs\/refs$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getSessionWorkdir?.(id)
      if (!workdir) return this.json({ error: "session not found" }, 404)
      return this.json({ repos: listRepoRefs(workdir) })
    }
```

- [ ] **Step 3: Manual verify**

Run: `curl -s "http://127.0.0.1:9898/sessions/<id>/fs/refs" | head -c 300`
Expected: `{ "repos": [ { "repo": "", "branches": [...], "commits": [{ "sha": "...", "subject": "..." }] } ] }`

- [ ] **Step 4: Commit**

```bash
git add src/channels/web/index.ts
git commit -m "feat(web): add /fs/refs endpoint (branches + recent commits)"
```

---

## Task 4: API client — `fsDiff(id, base?)` + `fsRefs(id)`

**Files:**
- Modify: `src/web-app/src/api/client.ts`

- [ ] **Step 1: Add `RepoRefs` type + update `fsDiff` + add `fsRefs`**

Replace the existing `fsDiff` line with:

```ts
  fsDiff: (sessionId: string, base?: string) =>
    request("GET", `/sessions/${encodeURIComponent(sessionId)}/fs/diff${base ? `?base=${encodeURIComponent(base)}` : ""}`) as Promise<{ repos: import("@/composables/useEditor").RepoDiff[]; comments: ReviewComment[] }>,
  fsRefs: (sessionId: string) =>
    request("GET", `/sessions/${encodeURIComponent(sessionId)}/fs/refs`) as Promise<{ repos: RepoRefs[] }>,
```

Add the exported type near the top of the file (next to other exported interfaces):

```ts
export interface RepoRefs {
  repo: string
  branches: string[]
  commits: Array<{ sha: string; subject: string }>
}
```

- [ ] **Step 2: Typecheck**

Run: `bun run typecheck`
Expected: no new errors.

- [ ] **Step 3: Commit**

```bash
git add src/web-app/src/api/client.ts
git commit -m "feat(web-app): fsDiff(base) + fsRefs client methods"
```

---

## Task 5: `useEditor` — base state + refs load

**Files:**
- Modify: `src/web-app/src/composables/useEditor.ts`

- [ ] **Step 1: Add state** after `const showDiff = ref(false)`:

```ts
  const diffBase = ref<string>("session-start")
  const diffRefs = ref<import("@/api/client").RepoRefs[]>([])
```

- [ ] **Step 2: Replace `loadDiff` and `reloadDiff`** with base-aware versions:

```ts
  async function loadDiff() {
    try {
      const [res, refs] = await Promise.all([
        api.fsDiff(sessionId.value, diffBase.value),
        api.fsRefs(sessionId.value).catch(() => ({ repos: [] })),
      ])
      diffRepos.value = res.repos
      diffComments.value = res.comments
      diffRefs.value = refs.repos
      showDiff.value = true
    } catch (err: any) {
      toast.error("Failed to load diff", { description: err?.message ?? String(err) })
    }
  }

  async function reloadDiff() {
    try {
      const res = await api.fsDiff(sessionId.value, diffBase.value)
      diffRepos.value = res.repos
      diffComments.value = res.comments
    } catch (err: any) {
      toast.error("Failed to reload diff", { description: err?.message ?? String(err) })
    }
  }

  async function setDiffBase(base: string) {
    diffBase.value = base
    await reloadDiff()
  }
```

- [ ] **Step 3: Export the new members** — in the returned object, add to the diff group:

```ts
    diffRepos, diffComments, showDiff, changedPaths, diffBase, diffRefs,
```

and add `setDiffBase` to the functions list alongside `loadDiff, reloadDiff`.

- [ ] **Step 4: Typecheck + commit**

Run: `bun run typecheck`

```bash
git add src/web-app/src/composables/useEditor.ts
git commit -m "feat(web-app): diff base state + refs in useEditor"
```

---

## Task 6: `DiffView` base dropdown

**Files:**
- Modify: `src/web-app/src/components/editor/DiffView.vue`
- Modify: `src/web-app/src/components/editor/EditorPane.vue` (pass new props/emit)

- [ ] **Step 1: Extend `DiffView` props + emits** (top `<script setup>`):

```ts
import type { RepoRefs } from "@/api/client"

const props = defineProps<{
  repos: RepoDiff[]
  comments?: ReviewComment[]
  sessionId: string
  base: string
  refs: RepoRefs[]
}>()

const emit = defineEmits<{
  close: []
  reload: []
  setBase: [base: string]
}>()
```

- [ ] **Step 2: Add label + menu logic** in `<script setup>`:

```ts
const baseMenuOpen = ref(false)
const baseSubmenu = ref<null | "commit" | "branch">(null)

// refs from the primary (first) repo drive the submenus
const primaryRefs = computed(() => props.refs[0] ?? { repo: "", branches: [], commits: [] })

const baseLabel = computed(() => {
  const b = props.base
  if (b === "head") return "Uncommitted"
  if (b.startsWith("commit:")) return b.slice(7, 14)
  if (b.startsWith("branch:")) return b.slice(7)
  return "Session start"
})

function chooseBase(spec: string) {
  baseMenuOpen.value = false
  baseSubmenu.value = null
  if (spec !== props.base) emit("setBase", spec)
}
```

- [ ] **Step 3: Add the dropdown** in the header, before the `Wrap` button (inside the `flex items-center gap-1` cluster, as its first child):

```html
        <div class="relative">
          <button
            class="text-[11px] px-2 py-1 rounded-md hover:bg-accent transition-colors text-muted-foreground flex items-center gap-1"
            title="Change diff base"
            @click="baseMenuOpen = !baseMenuOpen; baseSubmenu = null"
          >
            <span class="text-foreground/80">Base:</span> {{ baseLabel }}
            <ChevronDown class="size-3" />
          </button>
          <div
            v-if="baseMenuOpen"
            class="absolute right-0 mt-1 w-48 rounded-md border border-border bg-[var(--cmux-header)] shadow-lg z-20 py-1 text-[12px]"
          >
            <button class="block w-full text-left px-3 py-1.5 hover:bg-accent" @click="chooseBase('session-start')">Session start</button>
            <button class="block w-full text-left px-3 py-1.5 hover:bg-accent" @click="chooseBase('head')">Uncommitted (HEAD)</button>
            <button class="block w-full text-left px-3 py-1.5 hover:bg-accent flex items-center justify-between" @click="baseSubmenu = baseSubmenu === 'commit' ? null : 'commit'">
              Previous commit… <ChevronRight class="size-3" />
            </button>
            <div v-if="baseSubmenu === 'commit'" class="max-h-56 overflow-y-auto border-t border-border/50">
              <button
                v-for="c in primaryRefs.commits"
                :key="c.sha"
                class="block w-full text-left px-3 py-1.5 hover:bg-accent"
                @click="chooseBase('commit:' + c.sha)"
              >
                <span class="font-mono text-[11px] text-muted-foreground">{{ c.sha }}</span> {{ c.subject }}
              </button>
              <div v-if="primaryRefs.commits.length === 0" class="px-3 py-1.5 text-muted-foreground italic">No commits</div>
            </div>
            <button class="block w-full text-left px-3 py-1.5 hover:bg-accent flex items-center justify-between" @click="baseSubmenu = baseSubmenu === 'branch' ? null : 'branch'">
              Another branch… <ChevronRight class="size-3" />
            </button>
            <div v-if="baseSubmenu === 'branch'" class="max-h-56 overflow-y-auto border-t border-border/50">
              <button
                v-for="b in primaryRefs.branches"
                :key="b"
                class="block w-full text-left px-3 py-1.5 hover:bg-accent font-mono text-[11px]"
                @click="chooseBase('branch:' + b)"
              >{{ b }}</button>
              <div v-if="primaryRefs.branches.length === 0" class="px-3 py-1.5 text-muted-foreground italic">No branches</div>
            </div>
          </div>
        </div>
```

Also import `ChevronDown` (already imported) and `ChevronRight` (already imported) — both already come from `@lucide/vue` at the top; no import change needed.

- [ ] **Step 4: Wire props/emit in `EditorPane.vue`** — extend the existing `<DiffView … />`:

```html
        <DiffView
          v-if="editor.showDiff.value"
          :repos="editor.diffRepos.value"
          :comments="editor.diffComments.value"
          :session-id="sessionId"
          :base="editor.diffBase.value"
          :refs="editor.diffRefs.value"
          @close="editor.showDiff.value = false"
          @reload="editor.reloadDiff()"
          @set-base="editor.setDiffBase($event)"
        />
```

(Keep the existing `:session-id` binding as-is if already present; only add `:base`, `:refs`, and `@set-base`.)

- [ ] **Step 5: Typecheck + build + commit**

Run: `bun run typecheck`
Expected: no new errors.

```bash
git add src/web-app/src/components/editor/DiffView.vue src/web-app/src/components/editor/EditorPane.vue
git commit -m "feat(web-app): diff base selector dropdown in DiffView"
```

---

## Task 7: End-to-end verification

- [ ] **Step 1:** Run the full unit suite for the touched core file: `bun test src/core/editor/`
- [ ] **Step 2:** Build the web app (`cd src/web-app && bun run build` or the project's build command) and confirm no type/template errors.
- [ ] **Step 3:** Drive the real app: open a session's editor → open the diff → switch base to Uncommitted, a previous commit, and a branch; confirm the file list changes and the header label updates. Switch back to Session start and confirm it matches the original view.
- [ ] **Step 4:** Confirm review comments still anchor correctly (add a comment, it still appears) regardless of chosen base.

---

## Notes / decisions locked in spec

- Target is **always** the working tree; only the base changes.
- Multi-repo: selector is **global**; commit/branch submenus list refs from the **primary repo**; repos lacking the chosen ref fall back to session-start (handled inside `resolveSpecBase`).
- No disk persistence; `diffBase` lives in the composable for the session's lifetime and resets to `session-start` on reload.
- Branch base uses **merge-base** semantics to avoid phantom deletions.
