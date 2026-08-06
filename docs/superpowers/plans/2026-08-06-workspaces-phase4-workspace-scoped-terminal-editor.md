# Workspaces Phase 4 — Workspace-Scoped Terminal and Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Free the terminal and the editor from the session. A terminal view opens a shell in the workspace work directory with no agent involved, and an editor view reads and writes that same directory — so a workspace with zero chats is still a usable workspace.

**Architecture:** The broker gains a workspace scope alongside the existing session scope: `/ws/term?workspace=<id>` and six `/workspaces/:id/fs*` routes that mirror the six `/sessions/:id/fs*` routes exactly. `TerminalManager` needs no change — its `sessionName` argument is already an opaque key, so a workspace terminal uses `w:<workspaceId>`, which cannot collide with a session name. The desktop client then replaces the Phase 3 placeholder with the real widgets. This plan also fixes the `Viewing` defect Phase 3 introduced.

**Tech Stack:** TypeScript on Bun (broker), Kotlin Multiplatform (`apps/shared`), Compose Desktop (`apps/desktop`).

**Spec:** `docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md`, sections 7.3, 7.4, and 11.

**Depends on:** the Phase 1, 1b, 2, and 3 plans, all of them.

**Client scope:** desktop and shared KMP only.

---

## What stays session-scoped, and why

| Thing | Scope after this plan | Reason |
|---|---|---|
| Scratch terminal | **Workspace** | A shell in a directory needs no agent. This is the whole point of the phase. |
| Agent terminal (`kind=agent`) | **Session** | It shows the agent process's own tmux pane. There is no such thing as a workspace's agent pane. |
| Editor file tree, read, write, search | **Workspace** | The files belong to the work directory, not to a chat. |
| Editor diff and refs | **Workspace** | Same repository, same base branch. The workspace carries `base_branch` and `branch`. |
| `fs_changed` frame | **Workspace** | It follows the watcher, and the watcher watches a directory. |
| **LSP** | **Session — unchanged** | Deliberate. See below. |

⚠ **LSP stays keyed by session in this phase.** `lsp_status`, `lsp_ready`, `lsp_rpc`, `lsp_exit`, and `ClientFrame.LspRpcOut` all carry a `session`, and the broker holds one `LspConnection` per WebSocket keyed that way. Rekeying it to a workspace is a second, separate change with its own failure modes (a language server is an expensive child process; getting its lifetime wrong leaks one per tab). An editor view therefore drives LSP through its **workspace's primary session**, and a workspace with no chat view gets **no code intelligence** — the editor still opens, reads, writes, and diffs. Say this out loud in the UI rather than failing silently (Task 6).

---

## File structure

| File | Responsibility |
|---|---|
| `src/core/workspace/scope.ts` | **Create.** Parse and build the `w:<id>` terminal scope key. One place owns the format. |
| `src/core/workspace/scope.test.ts` | **Create.** |
| `src/channels/web/index.ts` | **Modify.** `/ws/term?workspace=`, the term list and close routes, the six `/workspaces/:id/fs*` routes, and the `workspace` field on `fs_changed`. |
| `src/channels/web/workspace-fs.test.ts` | **Create.** Containment and parity with the session routes. |
| `apps/shared/.../proto/Frames.kt` | **Modify.** `FsChanged` gains an optional `workspace`. |
| `apps/shared/.../net/BrokerApi.kt` | **Modify.** Workspace variants of the fs and terminal calls. |
| `apps/desktop/.../shell/ViewHost.kt` | **Modify.** Replace `WorkspaceTerminalPending` with the real terminal; point the editor at the workspace routes. |
| `apps/desktop/.../state/DesktopAppState.kt` | **Modify.** The `Viewing` fix of spec §11. |

---

## Task 1: The terminal scope key

**Files:**
- Create: `src/core/workspace/scope.ts`
- Test: `src/core/workspace/scope.test.ts`

- [ ] **Step 1: Write the failing tests**

Create `src/core/workspace/scope.test.ts`:

```ts
import { test, expect } from "bun:test"
import { workspaceScope, parseScope } from "./scope"

test("workspaceScope prefixes the id", () => {
  expect(workspaceScope("abc-123")).toBe("w:abc-123")
})

test("parseScope reads a workspace scope", () => {
  expect(parseScope("w:abc-123")).toEqual({ kind: "workspace", id: "abc-123" })
})

test("parseScope treats anything else as a session scope", () => {
  expect(parseScope("my-session")).toEqual({ kind: "session", id: "my-session" })
})

test("parseScope keeps a session name that merely contains a colon", () => {
  // Session names are free-form human titles. Only a leading "w:" is a workspace.
  expect(parseScope("fix: the thing")).toEqual({ kind: "session", id: "fix: the thing" })
})

test("parseScope round-trips workspaceScope", () => {
  const id = "6b1f0e2a-0000-4000-8000-abcdefabcdef"
  expect(parseScope(workspaceScope(id))).toEqual({ kind: "workspace", id })
})
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
bun test src/core/workspace/scope.test.ts
```

Expected: FAIL — `Cannot find module './scope'`.

- [ ] **Step 3: Write the implementation**

Create `src/core/workspace/scope.ts`:

```ts
/**
 * The key TerminalManager uses to namespace a terminal.
 *
 * TerminalManager takes a `sessionName` argument, but it treats it as an opaque
 * string: a map key and a component of the tmux name. That lets a workspace own
 * terminals with no change to the manager at all — a workspace terminal keys on
 * "w:<workspaceId>".
 *
 * The prefix cannot collide with a real session name: session names are
 * human-readable titles and a leading "w:" is not a shape the namer produces.
 * Existing session terminals keep their exact key, so nothing is orphaned by
 * this change.
 */

const WORKSPACE_PREFIX = "w:"

export type TerminalScope =
  | { kind: "workspace"; id: string }
  | { kind: "session"; id: string }

export function workspaceScope(workspaceId: string): string {
  return WORKSPACE_PREFIX + workspaceId
}

export function parseScope(key: string): TerminalScope {
  return key.startsWith(WORKSPACE_PREFIX)
    ? { kind: "workspace", id: key.slice(WORKSPACE_PREFIX.length) }
    : { kind: "session", id: key }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
bun test src/core/workspace/scope.test.ts
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Make the service use it**

`src/core/workspace/service.ts` (Phase 1b Task 2) has its own `workspaceTerminalScope`. Delete it and import `workspaceScope` from here instead, so the format lives in exactly one place. Update the import in `service.ts` and re-run:

```bash
bun test src/core/workspace/
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/core/workspace/scope.ts src/core/workspace/scope.test.ts src/core/workspace/service.ts
git commit -m "feat(workspace): one owner for the terminal scope key

TerminalManager already treats its sessionName argument as an opaque key, so a
workspace terminal needs no manager change — just a 'w:<id>' prefix that cannot
collide with a human session name. Existing session terminals keep their exact
key and are not orphaned."
```

---

## Task 2: Workspace terminals over the WebSocket

**Files:**
- Modify: `src/channels/web/index.ts`

- [ ] **Step 1: Read the current handler**

Open `src/channels/web/index.ts` at `if (url.pathname === "/ws/term")` (around line 594). Note what it does today:

1. Reads `session`, `kind`, and `terminal` from the query.
2. `kind=agent` forces `terminalId = "agent"`; otherwise the id is sanitized to `[A-Za-z0-9]` and capped at 64 characters, because it becomes a tmux name.
3. Rejects when `getSessionWorkdir(session)` returns nothing.
4. For `kind=agent`, resolves `getSessionTmuxTarget` and rejects when it is missing — agent terminals are claude-only.
5. Upgrades with `terminalSession`, `terminalId`, `terminalKind`, and `terminalAgentTarget` on `ws.data`.

The workspace variant changes only steps 1 and 3.

- [ ] **Step 2: Change the handler**

Replace the top of the `/ws/term` block with:

```ts
    if (url.pathname === "/ws/term") {
      // Either ?session=<name> (a session-scoped terminal, incl. the agent pane)
      // or ?workspace=<id> (a plain shell in the workspace work directory).
      // Exactly one is accepted. Spec §7.3.
      const sessionName = url.searchParams.get("session") ?? ""
      const workspaceId = url.searchParams.get("workspace") ?? ""
      const kind = url.searchParams.get("kind") === "agent" ? "agent" : "scratch"
      const terminalId = kind === "agent"
        ? "agent"
        : ((url.searchParams.get("terminal") ?? "").replace(/[^A-Za-z0-9]/g, "").slice(0, 64) || "main")
      const auth = this.authenticate(req)
      if (!auth.ok) return this.authFailureResponse(auth)
      const dev = auth.device

      if (sessionName && workspaceId) {
        return new Response("pass session or workspace, not both", { status: 400 })
      }

      // The scope key TerminalManager namespaces by, and the workdir it spawns in.
      let scopeKey: string
      if (workspaceId) {
        // An agent pane belongs to an agent, never to a workspace.
        if (kind === "agent") return new Response("agent terminal needs a session", { status: 400 })
        const wd = this.opts.getWorkspaceWorkdir?.(workspaceId)
        if (!wd) return new Response("workspace not found", { status: 404 })
        scopeKey = workspaceScope(workspaceId)
      } else {
        if (!sessionName || !this.opts.getSessionWorkdir?.(sessionName)) {
          return new Response("session not found", { status: 404 })
        }
        scopeKey = sessionName
      }

      let agentTarget: string | undefined
      if (kind === "agent") {
        agentTarget = await this.opts.getSessionTmuxTarget?.(sessionName)
        if (!agentTarget) return new Response("agent terminal unsupported", { status: 404 })
      }
      const upgraded = server.upgrade(req, {
        data: { deviceName: dev.name, openedAt: Date.now(), terminal: true, terminalKind: kind, terminalSession: scopeKey, terminalId, terminalAgentTarget: agentTarget } as WSData,
      })
      if (upgraded) return undefined
      return new Response("upgrade failed", { status: 500 })
    }
```

Add the import at the top of the file:

```ts
import { workspaceScope, parseScope } from "../../core/workspace/scope"
```

- [ ] **Step 3: Resolve the workdir at attach time**

Find where the socket-open handler calls `terminalManager.attach(...)` (search for `attach({` near line 684). It resolves the workdir from `getSessionWorkdir(ws.data.terminalSession!)`. Change that to route by scope:

```ts
      const scope = parseScope(ws.data.terminalSession!)
      const workdir = scope.kind === "workspace"
        ? this.opts.getWorkspaceWorkdir?.(scope.id)
        : this.opts.getSessionWorkdir?.(scope.id)
      if (!workdir) { /* keep whatever the existing failure path does here */ }
```

⚠ Read the existing failure path and reuse it verbatim. Do not invent a new error shape — the client already knows how to render the current one.

- [ ] **Step 4: Accept `workspace` on the term list and close routes**

Change `GET /api/term/list`:

```ts
    if (method === "GET" && path === "/api/term/list") {
      const session = url.searchParams.get("session") ?? ""
      const workspace = url.searchParams.get("workspace") ?? ""
      if (session && workspace) return this.json({ error: "pass session or workspace, not both" }, 400)
      let scopeKey: string
      if (workspace) {
        if (!this.opts.getWorkspaceWorkdir?.(workspace)) return this.json({ error: "workspace not found" }, 404)
        scopeKey = workspaceScope(workspace)
      } else {
        if (!session || !this.opts.getSessionWorkdir?.(session)) return this.json({ error: "session not found" }, 404)
        scopeKey = session
      }
      const terminals = (await this.opts.terminalManager?.listForSession(scopeKey)) ?? []
      return this.json({ terminals })
    }
```

and `POST /api/term/close`:

```ts
    if (method === "POST" && path === "/api/term/close") {
      const body = (await req.json().catch(() => ({}))) as Record<string, unknown>
      const session = typeof body.session === "string" ? body.session : ""
      const workspace = typeof body.workspace === "string" ? body.workspace : ""
      const terminal = typeof body.terminal === "string" ? body.terminal : ""
      if (!terminal) return this.json({ error: "terminal required" }, 400)
      if (!session && !workspace) return this.json({ error: "session or workspace required" }, 400)
      const scopeKey = workspace ? workspaceScope(workspace) : session
      await this.opts.terminalManager?.close(scopeKey, terminal)
      return this.json({ ok: true })
    }
```

- [ ] **Step 5: Verify by hand against a real broker**

There is no unit test for a WebSocket upgrade in this file, so prove it live.

```bash
# Terminal 1
bun src/main.ts

# Terminal 2 — get a workspace id
curl -s -H "Authorization: Bearer $TOKEN" localhost:9898/workspaces | head -c 300

# Terminal 3 — attach a workspace terminal
bunx wscat -c "ws://localhost:9898/ws/term?workspace=$WID&terminal=main" -H "Authorization: Bearer $TOKEN"
```

Expected: the socket opens and a shell prompt arrives. Type `pwd` and press Enter — it must print the workspace's `workdir`.

Then confirm the rejections:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  "localhost:9898/api/term/list?workspace=nope"          # expect 404
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
  "localhost:9898/api/term/list?workspace=$WID&session=x"  # expect 400
```

- [ ] **Step 6: Confirm existing terminals still work**

Attach a session terminal exactly as before and confirm nothing regressed:

```bash
bunx wscat -c "ws://localhost:9898/ws/term?session=$SESSION_NAME&terminal=main" -H "Authorization: Bearer $TOKEN"
```

Expected: unchanged behaviour. This is the check that the scope key did not orphan anything.

- [ ] **Step 7: Commit**

```bash
git add src/channels/web/index.ts
git commit -m "feat(web): workspace-scoped terminals

/ws/term, /api/term/list, and /api/term/close accept ?workspace=<id> alongside
?session=<name>; passing both is a 400. A workspace terminal is a plain shell in
the workspace work directory with no agent. kind=agent still requires a session
— an agent pane belongs to an agent.

Session terminals keep their exact scope key, so none are orphaned.
Verified live: attach, pwd, and both rejection paths."
```

---

## Task 3: Workspace-scoped filesystem routes

**Files:**
- Modify: `src/channels/web/index.ts`
- Test: `src/channels/web/workspace-fs.test.ts` (create)

- [ ] **Step 1: Write the failing tests**

Create `src/channels/web/workspace-fs.test.ts`:

```ts
import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync, mkdirSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { FsService } from "../../core/editor/fs-service"

function fixture() {
  const root = mkdtempSync(join(tmpdir(), "ws-fs-"))
  mkdirSync(join(root, "src"))
  writeFileSync(join(root, "src", "a.ts"), "export const a = 1\n")
  writeFileSync(join(root, "README.md"), "# hi\n")
  return root
}

test("a workspace fs service lists its own directory", async () => {
  const root = fixture()
  const entries = await new FsService(root).listDir(".")
  expect(entries.map((e) => e.name).sort()).toEqual(["README.md", "src"])
})

test("a workspace fs service reads a file under its root", async () => {
  const root = fixture()
  expect(await new FsService(root).readFile("src/a.ts")).toBe("export const a = 1\n")
})

test("a path that escapes the workspace root is refused", async () => {
  const root = fixture()
  const fs = new FsService(root)
  await expect(fs.readFile("../../etc/passwd")).rejects.toThrow()
})

test("an absolute path outside the root is refused", async () => {
  const root = fixture()
  const fs = new FsService(root)
  await expect(fs.readFile("/etc/passwd")).rejects.toThrow()
})

test("two workspaces on the same repo see the same files", async () => {
  // The interesting case: two workspaces sharing one work tree (spec §10).
  const root = fixture()
  const a = await new FsService(root).listDir(".")
  const b = await new FsService(root).listDir(".")
  expect(a.map((e) => e.name)).toEqual(b.map((e) => e.name))
})
```

- [ ] **Step 2: Run the tests to verify they pass or fail**

```bash
bun test src/channels/web/workspace-fs.test.ts
```

These exercise `FsService` directly, so they should pass immediately. That is the point: they lock in the containment behaviour the new routes inherit, **before** the routes exist. If the escape tests do not throw, **stop** — the server-side containment is the security boundary, and the digest records it as deliberate defense the client guards merely duplicate.

- [ ] **Step 3: Add the six routes**

In `src/channels/web/index.ts`, find the "Editor filesystem routes" comment (around line 1950). Add a parallel block immediately after the six session routes.

The bodies are identical to the session ones, with one substitution: `this.opts.getSessionWorkdir?.(id)` becomes `this.opts.getWorkspaceWorkdir?.(id)`, and the 404 message says workspace. Write all six — `/fs`, `/fs/read`, `/fs/write`, `/fs/search`, `/fs/diff`, `/fs/refs`:

```ts
    // ── Editor filesystem routes, workspace-scoped ──────────────────────────
    // Byte-for-byte the same handlers as the /sessions/:id/fs* block above,
    // resolving the workdir from the workspace instead of the session. Spec §7.4.
    //
    // FsService enforces containment server-side: a path that escapes the root
    // throws, and that is the security boundary. The client's own guard is
    // redundant defense, not the real one.
    if (method === "GET" && path.match(/^\/workspaces\/[^/]+\/fs$/)) {
      const id = decodeURIComponent(path.split("/")[2]!)
      const workdir = this.opts.getWorkspaceWorkdir?.(id)
      if (!workdir) return this.json({ error: "workspace not found" }, 404)
      const fs = new FsService(workdir)
      const relPath = url.searchParams.get("path") ?? "."
      return this.json(await fs.listDir(relPath))
    }
```

…and the same shape for the other five. **Copy each session handler's body exactly** — including its error handling, its query parameter names, and its response shape. A client must not be able to tell the two families apart other than by the path.

⚠ Route order: `/workspaces/:id/fs` must be registered **before** the generic `PATCH /workspaces/:id` only if the methods overlap. They do not (`GET` versus `PATCH`), so order is free here — but keep the fs block together and after the workspace CRUD block for readability.

- [ ] **Step 4: Add `workspace` to the `fs_changed` frame**

The watcher fires `fs_changed` with a `session` today (around line 1009). A workspace editor needs to know the change is its own. Add the field without removing the old one:

```ts
            try { ws.send(JSON.stringify({ type: "fs_changed", session: frame.session, workspace: workspaceIdForWatch, paths })) } catch {}
```

where `workspaceIdForWatch` is resolved from whatever the watcher was opened for. If the watcher is opened per session today, resolve the session's `workspace_id` through a new opt:

```ts
  getSessionWorkspaceId?: (sessionId: string) => string | undefined
```

wired in `src/main.ts` as `registry.get(id)?.workspace_id`.

⚠ Keep `session` on the frame. An old client keys on it, and removing it breaks every shipped app.

- [ ] **Step 5: Mirror the field in the Kotlin frame**

In `apps/shared/.../proto/Frames.kt`:

```kotlin
    @Serializable @SerialName("fs_changed")
    data class FsChanged(
        val session: String,
        /** Present since the workspaces change; null from an older broker. */
        val workspace: String? = null,
        val paths: List<String> = emptyList(),
    ) : ServerFrame
```

- [ ] **Step 6: Add the shared API calls**

In `BrokerApi.kt`, add workspace twins of the existing session fs calls. Find the session ones (search for `/fs/read`) and mirror each, changing only the path. Also add:

```kotlin
    /** GET /api/term/list?workspace= */
    suspend fun listWorkspaceTerminals(workspaceId: String): List<TerminalSummary> =
        getJson<TerminalListResponse>("$httpBase/api/term/list?workspace=$workspaceId").terminals

    /** POST /api/term/close for a workspace terminal. */
    suspend fun closeWorkspaceTerminal(workspaceId: String, terminal: String) {
        ensureMutationSuccess(http.post("$httpBase/api/term/close") {
            header("Authorization", bearerHeader())
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CloseWorkspaceTerminalBody(workspaceId, terminal)))
        })
    }
```

with

```kotlin
@Serializable
data class CloseWorkspaceTerminalBody(val workspace: String, val terminal: String)
```

⚠ Match the existing `listTerminals` return type and its response wrapper exactly — read it rather than guessing the wrapper's name.

- [ ] **Step 7: Run everything**

```bash
./.mux/verify.sh
bun run typecheck
cd apps && ./gradlew :shared:jvmTest
```

Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add src/channels/web/index.ts src/channels/web/workspace-fs.test.ts src/main.ts \
        apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt \
        apps/shared/src/commonMain/kotlin/dev/supermux/net/BrokerApi.kt
git commit -m "feat(web): workspace-scoped filesystem routes

Six /workspaces/:id/fs* routes mirroring the session ones exactly, resolving the
workdir from the workspace. FsService keeps enforcing containment server-side —
that is the security boundary, and tests lock it before the routes existed.

fs_changed gains an optional workspace field and KEEPS session, so shipped
clients are unaffected."
```

---

## Task 4: Real workspace terminals on the desktop

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/ViewHost.kt`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/ViewHostTest.kt` (create)

- [ ] **Step 1: Write the failing tests**

Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/ViewHostTest.kt`:

```kotlin
package dev.supermux.desktop.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.proto.ViewDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

private fun view(kind: String, state: Map<String, String>) = ViewDto(
    id = "v1", workspaceId = "w1", kind = kind,
    state = JsonObject(state.mapValues { JsonPrimitive(it.value) }),
)

class ViewHostTest {

    @Test
    fun aWorkspaceTerminalBuildsTheTerminalWidget() = runComposeUiTest {
        setContent {
            ViewHost(
                view = view("terminal", mapOf("scope" to "workspace", "terminalId" to "main")),
                workspaceId = "w1", workdir = "/w",
            )
        }
        onNodeWithTag("terminal-w1-main").assertIsDisplayed()
    }

    @Test
    fun anUnknownKindDrawsAHintRatherThanCrashing() = runComposeUiTest {
        setContent {
            ViewHost(view = view("hologram", emptyMap()), workspaceId = "w1", workdir = "/w")
        }
        onNodeWithTag("view-unknown").assertIsDisplayed()
    }

    @Test
    fun anEditorViewGetsTheWorkspaceWorkdir() = runComposeUiTest {
        setContent {
            ViewHost(view = view("editor", mapOf("mode" to "tree")), workspaceId = "w1", workdir = "/some/dir")
        }
        onNodeWithTag("editor-/some/dir").assertIsDisplayed()
    }

    @Test
    fun aChatViewWithNoSessionIdDrawsTheHint() = runComposeUiTest {
        setContent {
            ViewHost(view = view("chat", emptyMap()), workspaceId = "w1", workdir = "/w")
        }
        onNodeWithTag("view-unknown").assertIsDisplayed()
    }
}
```

⚠ These assert on `testTag`s that the real widgets must carry. Add the tags to the adapter composables — do not weaken the tests to match untagged widgets.

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --tests '*ViewHostTest*'
```

Expected: FAIL — `terminal-w1-main` does not exist; the Phase 3 placeholder is drawn instead.

- [ ] **Step 3: Replace the placeholder**

In `ViewHost.kt`, replace the `WorkspaceTerminalPending(modifier)` branch:

```kotlin
            } else {
                WorkspaceTerminalPanel(
                    workspaceId = workspaceId,
                    terminalId = terminalId,
                    modifier = modifier.testTag("terminal-$workspaceId-$terminalId"),
                )
            }
```

Write `WorkspaceTerminalPanel` as a thin adapter over the existing terminal composable. Open `apps/desktop/.../shell/SessionDetail.kt`, find how it builds the terminal today, and copy that call — changing only the WebSocket URL it opens, from `?session=<name>` to `?workspace=<id>`.

⚠ Three JediTerm rules from the digest, all of which this widget must obey:

1. **Build it only when realized at non-zero size.** A tab that is not active must not hold a live terminal. `LayoutHost` composes only the active view of each group, which satisfies this — do not add caching that defeats it.
2. **Marshal input off non-EDT threads.**
3. **Nothing Compose paints will appear above it.** If the terminal needs an overlay, swap the pane instead.

Delete `WorkspaceTerminalPending` once nothing references it.

- [ ] **Step 4: Point the editor at the workspace routes**

In the `"editor"` branch, `EditorPanelForWorkdir` currently calls the session fs routes. Change its data source to the workspace ones from Task 3, and tag it `editor-<workdir>`.

Pass the workspace's primary session id through for LSP only, and **show the user when there is none**:

```kotlin
        "editor" -> EditorPanelForWorkdir(
            workdir = workdir,
            workspaceId = workspaceId,
            path = view.stateString("path"),
            mode = view.stateString("mode") ?: "tree",
            // LSP is still keyed by session (see the plan header). A workspace with
            // no chat view gets no code intelligence — say so rather than looking broken.
            lspSessionId = primarySessionId,
            modifier = modifier.testTag("editor-$workdir"),
        )
```

When `lspSessionId` is null, the editor must show a quiet one-line note: **"No agent in this workspace — code intelligence is off."** Do not hide it, and do not disable editing.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/shell/ViewHost.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/shell/ViewHostTest.kt
git commit -m "feat(desktop): real workspace terminals and a workspace-scoped editor

Replaces the Phase 3 placeholder. The terminal attaches with ?workspace=<id>;
the editor reads and writes through /workspaces/:id/fs*.

LSP is still keyed by session, so a workspace with no chat view has no code
intelligence — the editor says so in one line rather than looking broken."
```

---

## Task 5: Fix the `Viewing` frame

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/state/DesktopAppState.kt`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/state/ViewingFrameTest.kt` (create)

⚠ **This fixes a defect Phase 3 introduced.** Phase 3 Task 4 changed `ui.selectedId` from a session id to a workspace id, but the `Viewing` frame still sends it as a session id. Right now the broker is told the user is viewing a session that does not exist, so **push suppression for the open chat is broken**: the user gets a banner for the chat they are looking at.

- [ ] **Step 1: Read the contract**

Spec §11 and the digest:

- `ClientFrame.Viewing(session: String?, visible: Boolean)`.
- `session` **must have no kotlinx default** — the broker rejects a missing session, and a null session on the list must serialize as `"session":null`. Do not add a default to that field.
- Web sends it on chat open, chat switch, foreground, and background, with a **60-second heartbeat** and a re-assert on reconnect.
- The new rule: **one frame per visible chat view**. A chat in a background tab is not visible.

- [ ] **Step 2: Write the failing tests**

Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/state/ViewingFrameTest.kt`:

```kotlin
package dev.supermux.desktop.state

import dev.supermux.proto.ClientFrame
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewingFrameTest {

    @Test
    fun aWorkspaceWithOneVisibleChatSendsOneFrameForThatSession() {
        val frames = viewingFramesFor(visibleChatSessionIds = listOf("s1"))
        assertEquals(listOf(ClientFrame.Viewing("s1", true)), frames)
    }

    @Test
    fun twoVisibleChatsSendTwoFrames() {
        val frames = viewingFramesFor(visibleChatSessionIds = listOf("s1", "s2"))
        assertEquals(
            listOf(ClientFrame.Viewing("s1", true), ClientFrame.Viewing("s2", true)),
            frames,
        )
    }

    @Test
    fun aBackgroundTabIsNotVisible() {
        // Only the ACTIVE view of each group is visible. A chat sitting in an
        // inactive tab must not suppress its own notifications.
        val frames = viewingFramesFor(visibleChatSessionIds = emptyList())
        assertEquals(listOf(ClientFrame.Viewing(null, false)), frames)
    }

    @Test
    fun aWorkspaceIdIsNeverSentAsASession() {
        // The Phase 3 defect: selectedId became a workspace id and was still sent
        // as a session. Every id in a Viewing frame must come from a chat view.
        val frames = viewingFramesFor(visibleChatSessionIds = listOf("s1"))
        assertEquals(false, frames.any { it.session == "w1" })
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test --tests '*ViewingFrameTest*'
```

Expected: FAIL — `Unresolved reference: viewingFramesFor`.

- [ ] **Step 4: Write the function and use it**

Add to `DesktopAppState.kt`:

```kotlin
/**
 * The Viewing frames to send for the currently visible chat views (spec §11).
 *
 * Before workspaces there was exactly one open chat, so one frame was enough.
 * A workspace can show two chats at once, so the broker gets one frame per
 * visible chat SESSION — never a workspace id, and never a chat that is sitting
 * in an inactive tab.
 *
 * With nothing visible, send the single null-session frame the list view sends,
 * so the broker clears this device's viewing state instead of keeping a stale one.
 */
fun viewingFramesFor(visibleChatSessionIds: List<String>): List<ClientFrame.Viewing> =
    if (visibleChatSessionIds.isEmpty()) listOf(ClientFrame.Viewing(null, false))
    else visibleChatSessionIds.map { ClientFrame.Viewing(it, true) }
```

Then change `sendViewingIfChanged()` to:

1. Compute the visible chat session ids: for the open workspace, take each group's **active** view, keep the chat ones, and read their `sessionId`.
2. Call `viewingFramesFor(...)`.
3. Dedup against the last sent **list** (the current code dedups against a single id — widen `lastSentViewing` to a `List<ClientFrame.Viewing>`).
4. Keep the 60-second heartbeat and the reconnect re-assert exactly as they are.

⚠ The `Snapshot` branch of the reducer resets `lastSentViewing = null` and re-asserts on every reconnect. Keep that. Widening the type must not drop it.

- [ ] **Step 5: Run the tests to verify they pass**

```bash
cd apps
xvfb-run -a env SKIKO_RENDER_API=SOFTWARE ./gradlew :desktop:test
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Verify the broker accepts more than one live `Viewing` per device**

Read `src/core/session-manager/` for the viewing tracker and check whether it stores **one** session per device. If it does, it silently drops the second chat and the fix is only half done.

```bash
grep -rn "viewing" src/core/session-manager/ src/channels/web/index.ts | grep -v test | head -20
```

If the tracker is one-per-device, widen it to a set per device and add a broker test for two concurrent sessions. Report what you found either way — do not assume.

- [ ] **Step 7: Commit**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/state/DesktopAppState.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/state/ViewingFrameTest.kt
git commit -m "fix(desktop): send one Viewing frame per visible chat view

Phase 3 made ui.selectedId a workspace id while Viewing still sent it as a
session id, so push suppression for the open chat was broken. A workspace can
show two chats at once, so send one frame per visible chat SESSION, taken from
each group's active view. Background tabs are not visible.

Spec 11."
```

---

## Task 6: Live verification

**Files:** none.

- [ ] **Step 1: Start the broker and the client**

```bash
bun src/main.ts
cd apps && ./gradlew :desktop:hotRun --auto
```

- [ ] **Step 2: Walk the checklist**

Confirm each; screenshot the ones marked 📷.

1. 📷 Add a terminal view to a workspace. It opens a shell. `pwd` prints the workspace `workdir`.
2. Add a second terminal view with a different id. Both run independently.
3. Close a terminal tab, confirm, and check `curl /api/term/list?workspace=$WID` no longer lists it.
4. 📷 Add an editor view. The file tree shows the workspace's files. Open a file, edit it, save it, and confirm on disk.
5. Edit a file outside the app and confirm the editor refreshes — the `fs_changed` frame arrived with the right workspace.
6. In a workspace **with** a chat, confirm code intelligence works in the editor.
7. 📷 In a workspace with **no** chat view, confirm the editor still opens and shows the one-line "code intelligence is off" note.
8. Open two chats in one workspace, put both in visible groups, and confirm **neither** produces a push banner while on screen.
9. Put one of those chats in a background tab and confirm it **does** produce a banner.
10. Attach an old-style session terminal and confirm nothing regressed.

- [ ] **Step 3: Check memory again**

```bash
ps -o rss=,comm= -p $(pgrep -f 'dev.supermux.desktop.MainKt')
```

Open and close six terminal tabs and six editor tabs. Expected: RSS settles. A steady climb means a background tab is holding a live JediTerm or KCEF — go back to Task 4 Step 3.

- [ ] **Step 4: Report**

Send the screenshots and the memory readings. Name anything that did not pass. Then say plainly which spec sections are now complete and which clients are still on the old model — web, iOS, macOS, and Android have not been touched by any plan in this series.

---

## Self-review notes

**Spec coverage.** This plan implements spec §7.3 (workspace terminals), §7.4 (workspace filesystem), and §11 (the `Viewing` change). Together with the Phase 0–3 plans, the broker side of the spec is complete and the desktop client is fully on the new model.

**Deliberately not done, and why:**
- **LSP rekeying.** Explained in the header table. A language server is an expensive child process and its lifetime is easy to get wrong; it deserves its own plan. Today an editor view uses its workspace's primary session, and a chatless workspace has no code intelligence and says so.
- **Web, iOS, macOS, Android.** The user's instruction: write these plans after the desktop client works.
- **§8.3, the small-screen rule.** Only reachable on a phone.
- **Spec §10 risk control 2** — the multi-agent mark — landed in Phase 3 Task 4. Nothing more is needed here.

**Type consistency check.** `workspaceScope(id)` and `parseScope(key)` are defined in Task 1 and used in Task 2; `service.ts`'s duplicate is deleted in Task 1 Step 5. `getWorkspaceWorkdir` is the opt added in Phase 1b Task 3 and is used by Tasks 2 and 3. `ViewHost(view, workspaceId, workdir)` keeps the Phase 3 signature. `ClientFrame.Viewing(session, visible)` is unchanged on the wire — only how many are sent changed.
