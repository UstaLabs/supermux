# Windows/Linux Desktop Client — Milestone 4g-4 (LSP Settings Screen) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** The LSP settings/management screen — enable/disable a server, install one with a live streamed log, and add/remove a custom server — ported from Android `EditorLspScreen.kt`. This is the LAST piece of M4g (editor extras) and desktop's FIRST settings screen: a full-pane overlay (the fourth, alongside launcher/archived/usage) reached from File ▸ "Editor / LSP…" and the session header's overflow ⋮ row. M4g-3 wired the in-editor LSP CONNECT flow against whatever the broker already reports as `ready`; this milestone is what lets a desktop-only user actually turn a server on in the first place.

**Architecture:** Three layers, each a near-verbatim port of an already-proven layer elsewhere in this module:
1. **`DesktopAppState`** gets five HTTP wrappers (`lspLoad`/`lspToggle`/`lspInstall`/`lspAddCustom`/`lspRemoveCustom`, all `runApi`-wrapped, mirroring `AppViewModel.kt:736-747`) plus two new StateFlows (`lspInstallLog: StateFlow<Map<String, List<String>>>`, `lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>`) folded from the `lsp_install_progress`/`lsp_install_done` WS frames in `reduce()` — the two branches M4g-3 deliberately left falling through `else -> {}` (see `DesktopAppState.kt:334-335`'s "Out of scope here (M4g-4...)" comment).
2. **`apps/desktop/.../settings/EditorLspScreen.kt`** (new file) is a self-contained port of Android's `EditorLspSection` + `LspServerRow` + `AddLspForm`, but restructured as the TOP-LEVEL full-pane screen (Android embeds it inside a shared Editor settings page that doesn't exist on desktop; desktop has no settings hub yet, so this overlay IS the whole page). Unlike the Usage/Archived overlays — where `WorkspaceRoot` owns a single point-in-time snapshot — this screen owns its OWN `servers`/loading/toggling/installing/removing state internally (mirrors Android exactly), because toggle/install/add/remove all need to mutate the list in place, not just swap one slice.
3. **Overlay wiring**: `WorkspaceUiState.lspSettingsOpen` + `openLspSettings()` (the same mutual-exclusion shape as `openLauncher`/`openArchived`/`openUsage`), a `Box(testTag("lsp_settings_overlay"))` in `WorkspaceRoot.kt` (self-focusing + Escape-to-close, matching the Usage overlay's shape since this screen also has no obvious first-focus text field), and two entry points: File ▸ "Editor / LSP…" (`Main.kt`) and the session header's overflow ⋮ row (`SessionHeaderMenus.kt` + `SessionDetail.kt` threading) — un-omitting one row of the "Settings/Devices/Proxies/Appearance still omitted" comment left by M4c/M4f.

**Tech Stack:** Compose Desktop (`Switch`, `OutlinedTextField`, `LinearProgressIndicator`-free — this screen has no progress bars, just a scrolling log), `compose.materialIconsExtended` (already a desktop dependency — `Icons.Filled.Delete/Download/Check/Close/Add`, `Icons.AutoMirrored.Filled.ArrowBack`), shared `BrokerApi` (`getEditorSettings`/`setLspEnabled`/`installEditorLsp`/`addCustomEditorLsp`/`removeCustomEditorLsp`, `apps/shared/.../net/BrokerApi.kt:1159-1195`) + `ServerFrame.LspInstallProgress`/`LspInstallDone` (`apps/shared/.../proto/Frames.kt:241-249`), `runComposeUiTest` (this screen is pure Compose — no KCEF — so it hosts cleanly, like `DiffView.kt`/`UsageScreen.kt`).

---

## Ground rules

All prior-milestone rules hold: standard gradle invocation with `/home/ahmet/.cache` logs + `TMPDIR`; Xvfb `:77` + `SKIKO_RENDER_API=SOFTWARE`; paired config at `/home/ahmet/.cache/smx-test-config`; xwd+Pillow for screenshots; **NO xdotool** — everything is driven through off-by-default env hooks (`SM_*`) that call the underlying `DesktopAppState` methods directly (never a simulated click) — see `SM_ARCHIVED_RESUME`/`SM_GIT_MENU`'s `:fetch`/`:pull` precedent; **never restart the broker**; snake_case test method names for every NEW test this plan adds (some pre-existing files in this module use camelCase — e.g. `SessionHeaderMenusTest.kt` — do not rename those, just don't copy the style forward); commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; touch **ONLY** `apps/desktop/src` — **NEVER** `apps/shared` or the broker (every DTO/frame this plan needs already exists — `LspServer`/`EditorSettingsResponse`/`LspInstallResult`/`LspMutationResult` in `BrokerApi.kt:640-673`, `ServerFrame.LspInstallProgress`/`LspInstallDone` in `Frames.kt:241-249`).

**Suite baseline:** desktop `:desktop:test` currently sits at **527 tests** (confirmed via `grep -c "@Test"` across `apps/desktop/src/test` at the time this plan was written, right after M4g-3 landed) — **re-read the actual count at execution start** rather than trusting this number; if a same-day M4g sibling plan landed in between, the real baseline will be higher.

**DANGER — read before Task 4 (live verification):**
- **`installEditorLsp` runs a REAL `bun install -g <pkg>` (or whatever the server's `installCmd` is) on the broker HOST** — a real network fetch + a real global package-manager mutation, not a dry run, and it can take a while. **Never fire it from a live-verification hook.** Task 2's UI tests cover the install button + streamed log + terminal result entirely through a FAKE `lspInstall` lambda and fake `installLog`/`installDone` StateFlows — that is the only coverage for the install path, mirroring how M4c never auto-fired Push/Publish and M4f never auto-fired `redeemCodexReset`.
- **`setLspEnabled` (the toggle) mutates broker-global `settings/editor` state, SHARED with web/iOS/Android** — flipping it live is real, persistent, and visible to every other client until reverted. If Task 4 exercises a live toggle at all, it MUST be a single self-contained hook that flips a server on, waits (so an operator can screenshot mid-flip), then flips it back to its ORIGINAL state before exiting — never a one-way flip left for a human to remember to undo.
- **`addCustomEditorLsp`/`removeCustomEditorLsp` also mutate the same shared broker-global state.** If Task 4 exercises them live, use a throwaway id that cannot collide with a real server (e.g. `m4g4-live-check`) and remove it again in the SAME hook run before exiting.
- Screenshots go to `/home/ahmet/.cache/m4g4v-shots/` (not committed).

---

### Task 1: `DesktopAppState` — LSP settings wrappers + install-log/install-done reducer fold (TDD)

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/state/DesktopAppState.kt`
- Test: Modify `apps/desktop/src/test/kotlin/dev/supermux/desktop/state/DesktopAppStateReducerTest.kt`
- Test: Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/state/DesktopLspSettingsTest.kt`

Port of `AppViewModel.kt:173-180` (the two install StateFlows), `AppViewModel.kt:296-300` (the reducer branches, currently `DesktopAppState.kt`'s `else -> {}` fallthrough per M4g-3's explicit "Out of scope here (M4g-4...)" comment at `DesktopAppState.kt:334-335`), and `AppViewModel.kt:736-747` (`lspLoad`/`lspToggle`/`lspInstall`/`lspAddCustom`/`lspRemoveCustom`).

- [x] **Step 1: Write the failing reducer tests.** In `DesktopAppStateReducerTest.kt`, add a new section right after the existing `// ── M4g-3 LSP: reducer fold + outbound control-plane senders ──` block (before the class's closing `}`):

```kotlin
    // ── M4g-4 LSP settings: install-progress/install-done reducer fold ────────────────────
    @Test fun lsp_install_progress_appends_lines_in_order_for_the_matching_server() {
        val s = state()
        s.reduce(ServerFrame.LspInstallProgress(serverId = "pyright", line = "Fetching pyright…"))
        s.reduce(ServerFrame.LspInstallProgress(serverId = "pyright", line = "npm install -g pyright"))
        assertEquals(
            listOf("Fetching pyright…", "npm install -g pyright"),
            s.lspInstallLog.value["pyright"],
        )
    }

    @Test fun lsp_install_progress_for_one_server_does_not_touch_anothers_log() {
        val s = state()
        s.reduce(ServerFrame.LspInstallProgress(serverId = "pyright", line = "a"))
        s.reduce(ServerFrame.LspInstallProgress(serverId = "bash", line = "b"))
        assertEquals(listOf("a"), s.lspInstallLog.value["pyright"])
        assertEquals(listOf("b"), s.lspInstallLog.value["bash"])
    }

    @Test fun lsp_install_done_is_recorded_by_server_id_ok_and_error() {
        val s = state()
        s.reduce(ServerFrame.LspInstallDone(serverId = "pyright", ok = true))
        s.reduce(ServerFrame.LspInstallDone(serverId = "bash", ok = false, error = "not found"))
        assertEquals(true, s.lspInstallDone.value["pyright"]?.ok)
        assertEquals(false, s.lspInstallDone.value["bash"]?.ok)
        assertEquals("not found", s.lspInstallDone.value["bash"]?.error)
    }
```

- [x] **Step 2: Run to confirm the compile/assertion failure.**

Run: `cd apps/desktop && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./../../gradlew :desktop:test --tests "dev.supermux.desktop.state.DesktopAppStateReducerTest" 2>&1 | tail -60`
Expected: compile failure — `lspInstallLog`/`lspInstallDone` are unresolved references on `DesktopAppState`.

- [x] **Step 3: Add the two install StateFlows.** In `DesktopAppState.kt`, right after the existing `_lspRpc`/`lspRpc` pair inside the `// ── LSP (M4g-3) ──` block (~line 165-166):

```kotlin
    private val _lspRpc = MutableSharedFlow<ServerFrame.LspRpcIn>(extraBufferCapacity = 256)
    val lspRpc: SharedFlow<ServerFrame.LspRpcIn> = _lspRpc.asSharedFlow()

    // Live install progress/result per LSP serverId (M4g-4). Drives LspSettingsScreen's streamed
    // install log + terminal result row — mirrors AppViewModel:173-180.
    private val _lspInstallLog = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val lspInstallLog: StateFlow<Map<String, List<String>>> = _lspInstallLog

    private val _lspInstallDone = MutableStateFlow<Map<String, ServerFrame.LspInstallDone>>(emptyMap())
    val lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>> = _lspInstallDone
```

Also update the block comment right above `_lspStatus` (currently reads "── LSP (M4g-3) ──") to "── LSP (M4g-3/M4g-4) ──" since this block now spans both milestones.

- [x] **Step 4: Add the reducer branches**, replacing the M4g-3 "out of scope" comment in `reduce()` (~line 334-335):

```kotlin
            is ServerFrame.LspInstallProgress ->
                _lspInstallLog.update { it + (frame.serverId to ((it[frame.serverId] ?: emptyList()) + frame.line)) }
            is ServerFrame.LspInstallDone -> _lspInstallDone.update { it + (frame.serverId to frame) }
```

(This replaces the two-line comment `// Out of scope here (M4g-4 LSP settings screen owns install progress/results): // lsp_install_progress, lsp_install_done. Still fall through to \`else\` below.` entirely — those frames are no longer out of scope.)

- [x] **Step 5: Run the reducer tests again.**

Run: same command as Step 2.
Expected: all 3 new tests PASS; the full `DesktopAppStateReducerTest` class stays green (M1/M3/M4b/M4g-3 tests unaffected).

- [x] **Step 6: Write the failing HTTP-wrapper tests.** Create `DesktopLspSettingsTest.kt`:

```kotlin
package dev.supermux.desktop.state

import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M4g-4 Task 1: the `DesktopAppState` LSP-settings wrappers (lspLoad/lspToggle/lspInstall/
 * lspAddCustom/lspRemoveCustom). Mirrors [DesktopDiffReviewTest]'s MockEngine layer: BrokerApi is a
 * final concrete class, so the `apiOverride` seam takes a real instance constructed against a ktor
 * [MockEngine] HttpClient — no live broker required. Each wrapper is asserted for its exact HTTP
 * method + path + (where relevant) request body, that a 2xx response decodes into the real DTO, and
 * that a 5xx degrades gracefully via [DesktopAppState.runApi] — AppViewModel.kt:736-747 parity
 * (there via `runCatching{}.getOrNull()`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLspSettingsTest {

    private data class Rec(val method: HttpMethod, val path: String, val body: String)

    private fun bodyText(content: Any?): String = when (content) {
        is TextContent -> content.text
        else -> ""
    }

    /** DesktopAppState whose BrokerApi answers every request with [body]/[status], recording
     *  each request's method + path + raw body into [recorded]. */
    private fun appRecording(
        recorded: MutableList<Rec>,
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = """{"status":"ok"}""",
    ): DesktopAppState {
        val engine = MockEngine { req ->
            recorded.add(Rec(req.method, req.url.encodedPath, bodyText(req.body)))
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            respond(ByteReadChannel(body), status, jsonHeaders)
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = TestScope(UnconfinedTestDispatcher()),
            connectOnInit = false,
            apiOverride = api,
        )
    }

    // ── lspLoad ─────────────────────────────────────────────────────────────────────

    @Test fun lsp_load_gets_the_settings_editor_path_and_decodes_the_server_list() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """
                {"lsp":{"servers":[
                  {"id":"typescript","label":"TypeScript","extensions":[".ts",".tsx"],"enabled":true,"state":"ready","installable":true},
                  {"id":"pyright","label":"Pyright","extensions":[".py"],"enabled":false,"state":"missing","installable":true,"installLabel":"Install"}
                ]}}
                """.trimIndent(),
        )

        val result = app.lspLoad()

        val rec = recorded.single()
        assertEquals(HttpMethod.Get, rec.method)
        assertEquals("/settings/editor", rec.path)
        assertEquals(listOf("typescript", "pyright"), result.map { it.id })
        assertEquals("ready", result.first { it.id == "typescript" }.state)
    }

    @Test fun lsp_load_returns_an_empty_list_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        val result = app.lspLoad()

        assertTrue(result.isEmpty())
    }

    // ── lspToggle ───────────────────────────────────────────────────────────────────

    @Test fun lsp_toggle_puts_the_enable_patch_and_decodes_the_updated_server_list() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """{"lsp":{"servers":[{"id":"pyright","label":"Pyright","extensions":[".py"],"enabled":true,"state":"missing"}]}}""",
        )

        val result = app.lspToggle("pyright", true)

        val rec = recorded.single()
        assertEquals(HttpMethod.Put, rec.method)
        assertEquals("/settings/editor", rec.path)
        assertTrue(rec.body.contains("\"pyright\":{\"enabled\":true}"))
        assertEquals(true, result?.single()?.enabled)
    }

    @Test fun lsp_toggle_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.lspToggle("pyright", false))
    }

    // ── lspInstall ──────────────────────────────────────────────────────────────────

    @Test fun lsp_install_posts_to_the_install_path_and_decodes_the_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"ok":true,"lines":["Fetching…","Installed pyright@1.2.3"]}""")

        val result = app.lspInstall("pyright")

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/settings/editor/lsp/pyright/install", rec.path)
        assertEquals(true, result?.ok)
        assertEquals(listOf("Fetching…", "Installed pyright@1.2.3"), result?.lines)
    }

    @Test fun lsp_install_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.lspInstall("pyright"))
    }

    // ── lspAddCustom ────────────────────────────────────────────────────────────────

    @Test fun lsp_add_custom_posts_the_body_and_decodes_the_mutation_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(
            recorded,
            body = """{"ok":true,"lsp":{"servers":[{"id":"zig","label":"Zig","extensions":[".zig"],"enabled":true,"state":"missing"}]}}""",
        )

        val result = app.lspAddCustom(
            id = "zig", label = "Zig", command = "zls", extensions = listOf(".zig", ".zon"),
            args = listOf("--stdio"), languageId = "zig", installCmd = "apt install -y zls",
        )

        val rec = recorded.single()
        assertEquals(HttpMethod.Post, rec.method)
        assertEquals("/settings/editor/lsp/custom", rec.path)
        assertTrue(rec.body.contains("\"id\":\"zig\""))
        assertTrue(rec.body.contains("\"command\":\"zls\""))
        assertTrue(rec.body.contains("\"installCmd\":\"apt install -y zls\""))
        assertEquals(true, result?.ok)
        assertEquals(listOf("zig"), result?.lsp?.servers?.map { it.id })
    }

    @Test fun lsp_add_custom_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.lspAddCustom(id = "zig", label = "Zig", command = "zls", extensions = listOf(".zig")))
    }

    // ── lspRemoveCustom ─────────────────────────────────────────────────────────────

    @Test fun lsp_remove_custom_deletes_the_custom_path_and_decodes_the_mutation_result() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, body = """{"ok":true,"lsp":{"servers":[]}}""")

        val result = app.lspRemoveCustom("zig")

        val rec = recorded.single()
        assertEquals(HttpMethod.Delete, rec.method)
        assertEquals("/settings/editor/lsp/custom/zig", rec.path)
        assertEquals(true, result?.ok)
        assertTrue(result?.lsp?.servers.orEmpty().isEmpty())
    }

    @Test fun lsp_remove_custom_returns_null_on_a_5xx() = runTest {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, status = HttpStatusCode.InternalServerError)

        assertNull(app.lspRemoveCustom("zig"))
    }
}
```

- [x] **Step 7: Run to confirm the compile failure.**

Run: `cd apps/desktop && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./../../gradlew :desktop:test --tests "dev.supermux.desktop.state.DesktopLspSettingsTest" 2>&1 | tail -60`
Expected: compile failure — `lspLoad`/`lspToggle`/`lspInstall`/`lspAddCustom`/`lspRemoveCustom` are unresolved references on `DesktopAppState`.

- [x] **Step 8: Add the five wrappers.** In `DesktopAppState.kt`, right after the existing `redeemCodexReset` (~line 728, end of the `// ── Usage panel (M4f Task 1) ──` block), add a new section:

```kotlin
    // ── LSP settings (M4g-4 Task 1) ────────────────────────────────────────────────────
    // Backs the LspSettingsScreen overlay (M4g-4 Task 2/3): enable/disable + install + add/remove
    // custom language servers. [lspInstallLog]/[lspInstallDone] (above) already stream the live
    // install progress/result via lsp_install_progress/lsp_install_done frames; these wrappers are
    // the HTTP half — mirrors AppViewModel.lspLoad/lspToggle/lspInstall/lspAddCustom/
    // lspRemoveCustom:736-747.

    /** GET /settings/editor → the server list. Empty (not null) on any failure, mirroring
     *  Android's `?: emptyList()` — a load failure shows an empty list rather than an error
     *  banner, since this is the FIRST load and there is no prior state to preserve. */
    suspend fun lspLoad(): List<LspServer> =
        runApi("lspLoad") { api.getEditorSettings().lsp.servers } ?: emptyList()

    /** PUT /settings/editor {lsp:{servers:{id:{enabled}}}} → the updated server list. Null (not a
     *  fallback list) on failure — the caller leaves the row exactly as it was rather than
     *  guessing at the new state. */
    suspend fun lspToggle(id: String, enabled: Boolean): List<LspServer>? =
        runApi("lspToggle") { api.setLspEnabled(id, enabled).lsp.servers }

    /** POST /settings/editor/lsp/<id>/install → {ok, lines}. The LIVE install log/result the
     *  caller actually renders arrives over the WS as lsp_install_progress/lsp_install_done
     *  ([lspInstallLog]/[lspInstallDone] above); this response only signals the HTTP round-trip
     *  finished so the caller can reload the server list (mirrors AppViewModel.lspInstall +
     *  EditorLspSection's `lspInstall(id); reload()` idiom). DANGER: runs a REAL install command
     *  on the broker host — see this plan's Ground rules. */
    suspend fun lspInstall(id: String): LspInstallResult? =
        runApi("lspInstall") { api.installEditorLsp(id) }

    /** POST /settings/editor/lsp/custom → {ok, error?, lsp?}. Null only on a transport failure —
     *  a validation rejection from the broker still decodes 2xx with ok=false + error (see
     *  BrokerApi.addCustomEditorLsp), which [runApi] does NOT swallow; the caller surfaces
     *  `.error` in the add-form. */
    suspend fun lspAddCustom(
        id: String,
        label: String,
        command: String,
        extensions: List<String>,
        args: List<String> = emptyList(),
        languageId: String? = null,
        installCmd: String? = null,
    ): LspMutationResult? =
        runApi("lspAddCustom") {
            api.addCustomEditorLsp(id, label, command, extensions, args, languageId, installCmd)
        }

    /** DELETE /settings/editor/lsp/custom/<id> → {ok, error?, lsp?}. */
    suspend fun lspRemoveCustom(id: String): LspMutationResult? =
        runApi("lspRemoveCustom") { api.removeCustomEditorLsp(id) }
```

Add the three new DTO imports to the top of the file (alongside the existing `dev.supermux.net.*` imports, ~line 15-38, kept alphabetical with the surrounding block):

```kotlin
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
```

- [x] **Step 9: Run the wrapper tests.**

Run: same command as Step 7.
Expected: all 10 new tests PASS.

- [x] **Step 10: Full-module compile + test check.**

Run: `cd apps/desktop && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./../../gradlew :desktop:compileKotlin :desktop:test 2>&1 | tail -100`
Expected: BUILD SUCCESSFUL; full desktop suite green (527 baseline + 13 new = 540, adjust for the exact baseline re-read at Step 2).

- [x] **Step 11: Commit.**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/state/DesktopAppState.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/state/DesktopAppStateReducerTest.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/state/DesktopLspSettingsTest.kt
git commit -m "$(cat <<'EOF'
feat(desktop): add DesktopAppState LSP-settings wrappers + install-log/done reducer fold (M4g-4 T1)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

### Task 2: `apps/desktop/.../settings/EditorLspScreen.kt` — the screen (port, TDD)

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/settings/EditorLspScreen.kt`
- Test: Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/settings/EditorLspScreenTest.kt`

Port of `apps/android/.../settings/EditorLspScreen.kt` in full (`EditorLspSection` → renamed `LspSettingsScreen` and promoted to the top-level full-pane screen; `LspServerRow`, `AddLspForm`, `LspField`, `stateLabel`, `extSummary`, `slugId` port near-verbatim). Read the Android file in full before writing — every string/condition (`server.enabled && server.installable && !ready` for the install button, `installLines.takeLast(6)` for the log window, `dismissedResults` for the result-dismiss set) is load-bearing.

Desktop deltas from Android (state them in the file's header comment, mirroring `DiffView.kt`'s header):
- `painterResource(R.drawable.ic_trash/ic_download/ic_check/ic_x)` → `Icons.Filled.Delete/Download/Check/Close` (established `compose.materialIconsExtended` mapping — see `DiffView.kt`/`SessionsRail.kt`).
- No haptics (desktop has no touch feedback — established convention throughout this module).
- `LocalPanes.current.warning` (Android) → `dev.supermux.desktop.theme.LocalSemantics.current.warning` (desktop's equivalent semantic-color holder, already used by `UsageScreen.kt`'s `barColor`).
- Android's `lspError` state is declared but never actually SET anywhere in `EditorLspSection` (`lspLoad()` never throws — it degrades to `emptyList()` internally, per `AppViewModel.kt:737`) — it is dead code in the source being ported. Desktop drops it: just `loading` → spinner, else → the list (an empty list renders as "no rows, still show the Add-server button", exactly what Android's dead error-branch would never actually reach anyway). Call this out explicitly as a deliberate simplification, not an oversight.
- `KeyboardOptions(autoCorrectEnabled=false, capitalization=...)` on the add-form's text fields is dropped — desktop `OutlinedTextField`s don't have mobile IME autocorrect/autocapitalize concerns (no other desktop text field in this module sets it either).
- Android embeds this inside a shared "Editor" settings page (`EditorLspSection`, a fragment of a bigger screen); desktop has NO settings hub yet, so `LspSettingsScreen` is the WHOLE overlay — it gets its own back row + title (mirrors `UsageScreen`'s shape: `IconButton(ArrowBack) + Text("Language servers")`), not a bare section.
- `stateLabel`/`extSummary`/`slugId` are `internal` (not `private`), matching `EditorPanel.kt`'s `joinPath`/`pathToUri` convention, so they're independently unit-testable.

- [x] **Step 1: Write the failing pure-helper tests.** Create `EditorLspScreenTest.kt`:

```kotlin
package dev.supermux.desktop.settings

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M4g-4 Task 2: [LspSettingsScreen] + [LspServerRow]/[AddLspForm], a port of Android
 * `EditorLspScreen.kt`. Pure helpers ([stateLabel]/[extSummary]/[slugId]) are tested directly;
 * the composables are tested via [runComposeUiTest] with faked lspLoad/lspToggle/lspInstall/
 * lspAddCustom/lspRemoveCustom suspend lambdas + a controllable installLog/installDone
 * MutableStateFlow — no broker, no WorkspaceRoot (that's [EditorLspScreenOverlayTest] instead).
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class EditorLspScreenTest {

    // ── (1) pure helpers ──────────────────────────────────────────────────────────────────────

    @Test fun state_label_ready_non_custom_server_is_just_ready() {
        val s = LspServer(id = "typescript", label = "TypeScript", state = "ready")
        assertEquals("Ready", stateLabel(s))
    }

    @Test fun state_label_ready_custom_server_includes_its_command() {
        val s = LspServer(id = "zig", label = "Zig", state = "ready", custom = true, command = "zls")
        assertEquals("Ready · zls", stateLabel(s))
    }

    @Test fun state_label_prereq_missing_names_the_missing_requirement() {
        val s = LspServer(id = "x", label = "X", state = "prereq-missing", requires = "python3")
        assertEquals("Needs python3", stateLabel(s))
    }

    @Test fun state_label_prereq_missing_defaults_to_toolchain_when_requires_is_null() {
        val s = LspServer(id = "x", label = "X", state = "prereq-missing", requires = null)
        assertEquals("Needs toolchain", stateLabel(s))
    }

    @Test fun state_label_missing_non_custom_is_not_installed() {
        val s = LspServer(id = "pyright", label = "Pyright", state = "missing")
        assertEquals("Not installed", stateLabel(s))
    }

    @Test fun state_label_missing_custom_is_binary_not_found() {
        val s = LspServer(id = "zig", label = "Zig", state = "missing", custom = true)
        assertEquals("Binary not found on broker", stateLabel(s))
    }

    @Test fun ext_summary_joins_unique_extensions_with_a_trailing_ellipsis_past_six() {
        val exts = listOf(".a", ".b", ".c", ".d", ".e", ".f", ".g")
        assertEquals(".a, .b, .c, .d, .e, .f…", extSummary(exts))
    }

    @Test fun ext_summary_dedupes_and_does_not_truncate_six_or_fewer() {
        assertEquals(".ts, .tsx", extSummary(listOf(".ts", ".tsx", ".ts")))
    }

    @Test fun slug_id_lowercases_and_hyphenates_non_alphanumerics() {
        assertEquals("my-cool-server", slugId("My Cool Server!!"))
    }

    @Test fun slug_id_falls_back_to_server_when_the_label_has_no_alphanumerics() {
        assertEquals("server", slugId("!!!"))
    }

    // ── (2) LspSettingsScreen: load, toggle, badges ───────────────────────────────────────────────

    private fun ts() = LspServer(id = "typescript", label = "TypeScript", extensions = listOf(".ts", ".tsx"), enabled = true, state = "ready", installable = true)
    private fun pyright() = LspServer(id = "pyright", label = "Pyright", extensions = listOf(".py"), enabled = false, state = "missing", installable = true, installLabel = "Install")
    private fun zig() = LspServer(id = "zig", label = "Zig", extensions = listOf(".zig"), enabled = true, state = "missing", custom = true, installable = false, command = null)

    private fun screen(
        servers: List<LspServer> = listOf(ts(), pyright()),
        lspToggle: suspend (String, Boolean) -> List<LspServer>? = { _, _ -> null },
        lspInstall: suspend (String) -> LspInstallResult? = { null },
        installLog: MutableStateFlow<Map<String, List<String>>> = MutableStateFlow(emptyMap()),
        installDone: MutableStateFlow<Map<String, ServerFrame.LspInstallDone>> = MutableStateFlow(emptyMap()),
        lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult? = { null },
        lspRemoveCustom: suspend (String) -> LspMutationResult? = { null },
        onBack: () -> Unit = {},
    ) = @Composable {
        LspSettingsScreen(
            lspLoad = { servers },
            lspToggle = lspToggle,
            lspInstall = lspInstall,
            lspInstallLog = installLog,
            lspInstallDone = installDone,
            lspAddCustom = lspAddCustom,
            lspRemoveCustom = lspRemoveCustom,
            onBack = onBack,
        )
    }

    @Test fun servers_render_from_a_fake_lsp_load_list() = runComposeUiTest {
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen()() } }
        waitForIdle()
        onNodeWithTag("lsp_server_row_typescript").assertIsDisplayed()
        onNodeWithTag("lsp_server_row_pyright").assertIsDisplayed()
        onNodeWithText("TypeScript").assertIsDisplayed()
        onNodeWithText("Ready").assertIsDisplayed()
        onNodeWithText("Not installed").assertIsDisplayed()
    }

    @Test fun the_enable_switch_fires_lsp_toggle_with_the_desired_state() = runComposeUiTest {
        var toggled: Pair<String, Boolean>? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(lspToggle = { id, enabled -> toggled = id to enabled; null })()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_toggle_pyright").performClick()
        waitForIdle()
        assertEquals("pyright" to true, toggled)
    }

    @Test fun toggle_updates_the_row_from_the_returned_server_list() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(lspToggle = { id, enabled -> listOf(pyright().copy(enabled = enabled)) })()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_toggle_pyright").performClick()
        waitForIdle()
        // The returned list REPLACES the rendered rows — typescript's row is gone, only pyright remains.
        onNodeWithTag("lsp_server_row_typescript").assertDoesNotExist()
        onNodeWithTag("lsp_server_row_pyright").assertIsDisplayed()
    }

    @Test fun install_button_only_shown_when_enabled_installable_and_not_ready() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(servers = listOf(ts(), pyright().copy(enabled = true)))()
            }
        }
        waitForIdle()
        // typescript: enabled but already ready -> no install button.
        onNodeWithTag("lsp_install_typescript").assertDoesNotExist()
        // pyright: enabled, installable, not ready -> install button shown, using its installLabel.
        onNodeWithTag("lsp_install_pyright").assertIsDisplayed()
        onNodeWithText("Install").assertIsDisplayed()
    }

    @Test fun install_button_streams_log_lines_from_the_install_log_state_flow() = runComposeUiTest {
        val log = MutableStateFlow<Map<String, List<String>>>(emptyMap())
        // lspInstall suspends until we explicitly release it, so `installing` stays true WHILE we
        // push log lines — without this gate, a fake lspInstall that returns immediately would let
        // the row's `installing` flip back to false before the test ever sets `log.value`, and the
        // log Column (rendered only `if (installing && installLines.isNotEmpty())`) would never
        // appear — this is the realistic shape of a long-running install, not a test artifact.
        val installGate = CompletableDeferred<Unit>()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    servers = listOf(pyright().copy(enabled = true)),
                    installLog = log,
                    lspInstall = { installGate.await(); null },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_install_pyright").performClick()
        waitForIdle()
        log.value = mapOf("pyright" to listOf("Fetching pyright…", "npm install -g pyright"))
        waitForIdle()
        onNodeWithTag("lsp_install_log_pyright").assertIsDisplayed()
        onNodeWithText("Fetching pyright…").assertIsDisplayed()
        onNodeWithText("npm install -g pyright").assertIsDisplayed()
        installGate.complete(Unit) // let the install coroutine finish so it doesn't leak past the test
        waitForIdle()
    }

    @Test fun install_done_shows_ok_result_and_dismiss_clears_it() = runComposeUiTest {
        val done = MutableStateFlow<Map<String, ServerFrame.LspInstallDone>>(
            mapOf("pyright" to ServerFrame.LspInstallDone(serverId = "pyright", ok = true)),
        )
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(servers = listOf(pyright().copy(enabled = true)), installDone = done)()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_install_result_pyright").assertIsDisplayed()
        onNodeWithTag("lsp_install_dismiss_pyright").performClick()
        waitForIdle()
        onNodeWithTag("lsp_install_result_pyright").assertDoesNotExist()
    }

    @Test fun install_done_shows_the_error_result_when_not_ok() = runComposeUiTest {
        val done = MutableStateFlow<Map<String, ServerFrame.LspInstallDone>>(
            mapOf("pyright" to ServerFrame.LspInstallDone(serverId = "pyright", ok = false, error = "network unreachable")),
        )
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(servers = listOf(pyright().copy(enabled = true)), installDone = done)()
            }
        }
        waitForIdle()
        onNodeWithText("network unreachable").assertIsDisplayed()
    }

    @Test fun custom_server_shows_a_remove_button_and_firing_it_calls_lsp_remove_custom() = runComposeUiTest {
        var removedId: String? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    servers = listOf(zig()),
                    lspRemoveCustom = { id -> removedId = id; LspMutationResult(ok = true, lsp = null) },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_remove_zig").assertIsDisplayed()
        onNodeWithTag("lsp_remove_typescript").assertDoesNotExist() // non-custom server has no remove button
        onNodeWithTag("lsp_remove_zig").performClick()
        waitForIdle()
        assertEquals("zig", removedId)
        onNodeWithTag("lsp_server_row_zig").assertDoesNotExist() // ok=true, lsp=null -> falls back to filtering it out locally
    }

    // ── (3) the add-custom-server form ────────────────────────────────────────────────────────────

    @Test fun add_form_toggle_reveals_the_form_and_save_validates_required_fields() = runComposeUiTest {
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen(servers = emptyList())() } }
        waitForIdle()
        onNodeWithTag("lsp_add_form").assertDoesNotExist()
        onNodeWithTag("lsp_add_toggle").performClick()
        waitForIdle()
        onNodeWithTag("lsp_add_form").assertIsDisplayed()
        onNodeWithTag("lsp_add_save").performClick()
        waitForIdle()
        onNodeWithTag("lsp_add_error").assertIsDisplayed()
        onNodeWithText("Fill in display name, command, and extensions").assertIsDisplayed()
    }

    @Test fun submitting_a_valid_add_form_calls_lsp_add_custom_and_closes_the_form_on_success() = runComposeUiTest {
        var submitted: AddCustomLspArgs? = null
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(
                    servers = emptyList(),
                    lspAddCustom = { args ->
                        submitted = args
                        LspMutationResult(ok = true, lsp = dev.supermux.net.LspConfig(servers = listOf(zig())))
                    },
                )()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_add_toggle").performClick()
        waitForIdle()
        onNodeWithTag("lsp_add_label").performTextInput("Zig")
        onNodeWithTag("lsp_add_command").performTextInput("zls")
        onNodeWithTag("lsp_add_extensions").performTextInput(".zig, .zon")
        onNodeWithTag("lsp_add_save").performClick()
        waitForIdle()
        assertEquals("zig", submitted?.id) // auto-slugged from the label since Server id was left blank
        assertEquals("Zig", submitted?.label)
        assertEquals(listOf(".zig", ".zon"), submitted?.extensions)
        onNodeWithTag("lsp_add_form").assertDoesNotExist() // closed on success
        onNodeWithTag("lsp_server_row_zig").assertIsDisplayed() // rendered from the returned lsp.servers
    }

    @Test fun a_failed_add_shows_the_returned_error_and_keeps_the_form_open() = runComposeUiTest {
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                screen(servers = emptyList(), lspAddCustom = { LspMutationResult(ok = false, error = "id already exists") })()
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_add_toggle").performClick()
        waitForIdle()
        onNodeWithTag("lsp_add_label").performTextInput("Zig")
        onNodeWithTag("lsp_add_command").performTextInput("zls")
        onNodeWithTag("lsp_add_extensions").performTextInput(".zig")
        onNodeWithTag("lsp_add_save").performClick()
        waitForIdle()
        onNodeWithText("id already exists").assertIsDisplayed()
        onNodeWithTag("lsp_add_form").assertIsDisplayed() // stays open on failure
    }

    // ── (4) back ───────────────────────────────────────────────────────────────────────────────────

    @Test fun back_button_fires_on_back() = runComposeUiTest {
        var backCalled = false
        setContent { SupermuxTheme(appearance = AppearanceMode.DARK) { screen(onBack = { backCalled = true })() } }
        waitForIdle()
        onNodeWithTag("lsp_settings_back").performClick()
        assertTrue(backCalled)
    }
}
```

- [x] **Step 2: Run to confirm the compile failure.**

Run: `cd apps/desktop && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./../../gradlew :desktop:test --tests "dev.supermux.desktop.settings.EditorLspScreenTest" 2>&1 | tail -80`
Expected: compile errors — `LspSettingsScreen`, `AddCustomLspArgs`, `stateLabel`, `extSummary`, `slugId` unresolved.

- [x] **Step 3: Write `EditorLspScreen.kt`.**

```kotlin
// Ported from apps/android/src/main/kotlin/dev/supermux/android/settings/EditorLspScreen.kt
// (M4g-4) — keep in sync until a shared UI module exists.
//
// Desktop is the FIRST platform where this renders as a standalone full-pane screen: Android
// embeds `EditorLspSection` inside a shared "Editor" settings page that doesn't exist on desktop
// yet, so `LspSettingsScreen` below IS the whole overlay — it gets its own back row + title
// (mirrors UsageScreen.kt's shape), not a bare embedded section. It is also desktop's FIRST
// settings screen; there is no shared SettingsShared.kt-equivalent to reuse yet (Android has one),
// so the header/caption/field composables are inlined here privately — a future second settings
// screen can extract a shared file then (YAGNI for now).
//
// Desktop adaptations vs. the Android source:
//   - painterResource(R.drawable.ic_trash/ic_download/ic_check/ic_x) -> Icons.Filled.Delete/
//     Download/Check/Close (established compose.materialIconsExtended mapping — DiffView.kt/
//     SessionsRail.kt precedent).
//   - No haptics (desktop has no touch feedback concept — established elsewhere in this module).
//   - LocalPanes.current.warning (Android) -> dev.supermux.desktop.theme.LocalSemantics.current.warning
//     (desktop's equivalent semantic-color holder; already used by UsageScreen.kt's barColor).
//   - Android's `lspError` state is declared but NEVER SET anywhere in EditorLspSection — lspLoad()
//     never throws (AppViewModel.kt:737 degrades to emptyList() internally), so that branch is dead
//     code in the ported source. Dropped here: just loading -> spinner, else -> the list (an empty
//     list still shows the "Add language server" affordance, which is what Android's unreachable
//     error branch would never actually preempt anyway).
//   - Add-form text fields drop KeyboardOptions(autoCorrectEnabled=false, capitalization=...) — no
//     other desktop OutlinedTextField in this module sets it (no mobile IME concern here).
//   - testTags added throughout (`lsp_settings_screen`, `lsp_server_row_<id>`, `lsp_toggle_<id>`,
//     `lsp_install_<id>`, `lsp_install_log_<id>`, `lsp_install_result_<id>`, `lsp_remove_<id>`,
//     `lsp_add_*`) so runComposeUiTest can drive every interactive surface without a pointer — this
//     screen is pure Compose (no KCEF), so it hosts cleanly under the Compose UI test harness.
//   - stateLabel/extSummary/slugId are `internal` (not `private`), matching EditorPanel.kt's
//     joinPath/pathToUri convention, so they're independently unit-tested.
package dev.supermux.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.LocalSemantics
import dev.supermux.desktop.theme.Radii
import dev.supermux.desktop.theme.Space
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The 7 add-custom-LSP fields, mirroring BrokerApi.addCustomEditorLsp(...)/
 *  DesktopAppState.lspAddCustom so the screen -> app-state call carries a single bundle
 *  (Android SettingsShared.kt's AddCustomLspArgs parity). */
data class AddCustomLspArgs(
    val id: String,
    val label: String,
    val command: String,
    val extensions: List<String>,
    val args: List<String> = emptyList(),
    val languageId: String? = null,
    val installCmd: String? = null,
)

/**
 * The LSP settings overlay: a back row + title, then per-server rows (enable Switch + state badge
 * + install with a streamed log + remove for custom servers) and an add-custom-server form. Owns
 * its OWN server-list state (loads via [lspLoad] on first composition, then mutates it in place on
 * toggle/install-reload/add/remove) — unlike the Usage/Archived overlays, where WorkspaceRoot owns
 * a single point-in-time snapshot, because every mutation here needs to patch the list in place
 * (mirrors Android's EditorLspSection exactly). [lspInstallLog]/[lspInstallDone] are the LIVE
 * per-server install stream (DesktopAppState, folded from lsp_install_progress/lsp_install_done
 * frames) — not reloaded, just observed.
 */
@Composable
fun LspSettingsScreen(
    lspLoad: suspend () -> List<LspServer>,
    lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>?,
    lspInstall: suspend (id: String) -> LspInstallResult?,
    lspInstallLog: StateFlow<Map<String, List<String>>>,
    lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>,
    lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult?,
    lspRemoveCustom: suspend (id: String) -> LspMutationResult?,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf<List<LspServer>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var toggling by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var showAddForm by remember { mutableStateOf(false) }

    val installLog by lspInstallLog.collectAsState()
    val installDone by lspInstallDone.collectAsState()
    var dismissedResults by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun reload() {
        loading = true
        servers = lspLoad()
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.surfaceContainerHigh)
            .testTag("lsp_settings_screen"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("lsp_settings_back")) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = cs.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(Space.sm))
            Text("Language servers", color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxSize()) {
            if (loading) {
                CircularProgressIndicator(
                    color = cs.primary,
                    modifier = Modifier.align(Alignment.Center).testTag("lsp_settings_spinner"),
                )
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = Space.lg, end = Space.lg, top = Space.sm, bottom = Space.xl),
                ) {
                    Text(
                        "Language servers run on the broker host.",
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = Space.sm),
                    )
                    servers.forEach { server ->
                        HorizontalDivider(color = cs.outlineVariant)
                        LspServerRow(
                            server = server,
                            toggling = toggling == server.id,
                            installing = installing == server.id,
                            removing = removing == server.id,
                            installBlocked = installing != null,
                            installLines = installLog[server.id].orEmpty(),
                            installResult = installDone[server.id]?.takeIf { server.id !in dismissedResults },
                            onToggle = { enabled ->
                                if (toggling == null) {
                                    scope.launch {
                                        toggling = server.id
                                        val updated = lspToggle(server.id, enabled)
                                        if (updated != null) servers = updated
                                        toggling = null
                                    }
                                }
                            },
                            onInstall = {
                                if (installing == null) {
                                    scope.launch {
                                        installing = server.id
                                        dismissedResults = dismissedResults - server.id
                                        lspInstall(server.id)
                                        reload()
                                        installing = null
                                    }
                                }
                            },
                            onRemove = {
                                if (removing == null) {
                                    scope.launch {
                                        removing = server.id
                                        val r = lspRemoveCustom(server.id)
                                        if (r?.ok == true) {
                                            servers = r.lsp?.servers ?: servers.filterNot { it.id == server.id }
                                        }
                                        removing = null
                                    }
                                }
                            },
                            onDismissResult = { dismissedResults = dismissedResults + server.id },
                        )
                    }
                    HorizontalDivider(color = cs.outlineVariant)

                    if (!showAddForm) {
                        TextButton(onClick = { showAddForm = true }, modifier = Modifier.testTag("lsp_add_toggle")) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp), tint = cs.primary)
                            Spacer(Modifier.width(Space.xs))
                            Text("Add language server", color = cs.primary)
                        }
                    } else {
                        AddLspForm(
                            onCancel = { showAddForm = false },
                            onSubmit = { args, onResult ->
                                scope.launch {
                                    val r = lspAddCustom(args)
                                    if (r?.ok == true && r.lsp != null) {
                                        servers = r.lsp!!.servers
                                        showAddForm = false
                                        onResult(null)
                                    } else {
                                        onResult(r?.error ?: "Couldn't add language server")
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LspServerRow(
    server: LspServer,
    toggling: Boolean,
    installing: Boolean,
    removing: Boolean,
    installBlocked: Boolean,
    installLines: List<String>,
    installResult: ServerFrame.LspInstallDone?,
    onToggle: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
    onDismissResult: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantics = LocalSemantics.current
    val ready = server.state == "ready"

    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.sm).testTag("lsp_server_row_${server.id}"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(server.label, color = cs.onSurface, fontSize = 14.sp)
                    if (server.custom) {
                        Text("CUSTOM", color = cs.onSurfaceVariant, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (server.extensions.isNotEmpty()) {
                    Text(extSummary(server.extensions), color = cs.onSurfaceVariant, fontSize = 11.sp)
                }
                Text(stateLabel(server), color = if (ready) semantics.success else semantics.warning, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (server.custom) {
                    IconButton(onClick = onRemove, enabled = !removing, modifier = Modifier.testTag("lsp_remove_${server.id}")) {
                        if (removing) {
                            CircularProgressIndicator(color = cs.error, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = cs.error, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Switch(
                    checked = server.enabled,
                    onCheckedChange = onToggle,
                    enabled = !toggling,
                    modifier = Modifier.testTag("lsp_toggle_${server.id}"),
                )
            }
        }

        // Install affordance (enabled + installable + not ready).
        if (server.enabled && server.installable && !ready) {
            TextButton(onClick = onInstall, enabled = !installBlocked, modifier = Modifier.testTag("lsp_install_${server.id}")) {
                if (installing) {
                    CircularProgressIndicator(color = cs.primary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                } else {
                    Icon(Icons.Filled.Download, contentDescription = null, tint = cs.primary, modifier = Modifier.size(14.dp))
                }
                Spacer(Modifier.width(Space.xs))
                Text(server.installLabel ?: "Install", color = cs.primary, fontSize = 12.sp)
            }
        }

        // Live install log (while installing this server).
        if (installing && installLines.isNotEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(cs.surfaceContainerLowest)
                    .padding(Space.sm)
                    .testTag("lsp_install_log_${server.id}"),
            ) {
                installLines.takeLast(6).forEach { line ->
                    Text(line, color = cs.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // Terminal install result (until dismissed).
        installResult?.let { result ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.testTag("lsp_install_result_${server.id}"),
            ) {
                Icon(
                    if (result.ok) Icons.Filled.Check else Icons.Filled.Close,
                    contentDescription = null,
                    tint = if (result.ok) semantics.success else cs.error,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    if (result.ok) (installLines.lastOrNull() ?: "Installed") else (result.error ?: "Install failed"),
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = onDismissResult,
                    modifier = Modifier.size(24.dp).testTag("lsp_install_dismiss_${server.id}"),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = cs.onSurfaceVariant, modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}

@Composable
private fun AddLspForm(
    onCancel: () -> Unit,
    onSubmit: (AddCustomLspArgs, onResult: (String?) -> Unit) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var label by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    var extensions by remember { mutableStateOf("") }
    var languageId by remember { mutableStateOf("") }
    var installCmd by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var addError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().padding(vertical = Space.sm).testTag("lsp_add_form"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Add language server", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        addError?.let { Text(it, color = cs.error, fontSize = 12.sp, modifier = Modifier.testTag("lsp_add_error")) }

        LspField("Display name", "Zig", label, mono = false, testTag = "lsp_add_label") {
            label = it
            // Auto-slug the id from the label until the user edits it directly.
            if (id.isBlank() && it.isNotBlank()) id = slugId(it)
        }
        LspField("Server id", "zig", id, mono = true, testTag = "lsp_add_id") { id = it }
        LspField("Command on broker", "zls", command, mono = true, testTag = "lsp_add_command") { command = it }
        LspField("Args (optional)", "--stdio", args, mono = true, testTag = "lsp_add_args") { args = it }
        LspField("Extensions", ".zig, .zon", extensions, mono = true, testTag = "lsp_add_extensions") { extensions = it }
        LspField("Language id (optional)", "zig", languageId, mono = true, testTag = "lsp_add_language_id") { languageId = it }
        LspField("Install command (optional)", "apt install -y zls", installCmd, mono = true, testTag = "lsp_add_install_cmd") { installCmd = it }

        Text("Install command runs as the broker user — do not use sudo.", color = cs.onSurfaceVariant, fontSize = 11.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    val idStr = id.trim().ifEmpty { slugId(label) }
                    val labelStr = label.trim()
                    val commandStr = command.trim()
                    val extStr = extensions.trim()
                    if (idStr.isEmpty() || labelStr.isEmpty() || commandStr.isEmpty() || extStr.isEmpty()) {
                        addError = "Fill in display name, command, and extensions"
                        return@Button
                    }
                    val argsList = args.trim().split(" ").map { it.trim() }.filter { it.isNotEmpty() }
                    val extList = extStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    saving = true
                    addError = null
                    onSubmit(
                        AddCustomLspArgs(
                            id = idStr,
                            label = labelStr,
                            command = commandStr,
                            extensions = extList,
                            args = argsList,
                            languageId = languageId.trim().ifEmpty { null },
                            installCmd = installCmd.trim().ifEmpty { null },
                        ),
                    ) { err ->
                        saving = false
                        if (err != null) addError = err
                    }
                },
                enabled = !saving,
                modifier = Modifier.weight(1f).testTag("lsp_add_save"),
                colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
            ) {
                if (saving) {
                    CircularProgressIndicator(color = cs.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(Space.sm))
                    Text("Saving…", color = cs.onPrimary)
                } else {
                    Text("Save", color = cs.onPrimary)
                }
            }
            OutlinedButton(onClick = onCancel, enabled = !saving, modifier = Modifier.testTag("lsp_add_cancel")) {
                Text("Cancel", color = cs.onSurface)
            }
        }
    }
}

@Composable
private fun LspField(
    label: String,
    placeholder: String,
    value: String,
    mono: Boolean,
    testTag: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        singleLine = true,
        textStyle = if (mono) {
            MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        } else {
            MaterialTheme.typography.bodyMedium
        },
    )
}

/** "Ready" / "Ready · <command>" (custom) / "Needs <requires>" / "Not installed" /
 *  "Binary not found on broker" (custom) — port of Android's `stateLabel`. */
internal fun stateLabel(server: LspServer): String = when (server.state) {
    "ready" -> if (server.custom && server.command != null) "Ready · ${server.command}" else "Ready"
    "prereq-missing" -> "Needs ${server.requires ?: "toolchain"}"
    else -> if (server.custom) "Binary not found on broker" else "Not installed"
}

/** Up to 6 unique extensions, comma-joined, with a trailing "…" if more were truncated. */
internal fun extSummary(exts: List<String>): String {
    val unique = exts.distinct()
    val shown = unique.take(6)
    val tail = if (unique.size > shown.size) "…" else ""
    return shown.joinToString(", ") + tail
}

/** Lowercase + hyphenate a display name into a server id; falls back to "server" if nothing
 *  alphanumeric survives. */
internal fun slugId(label: String): String {
    val slug = label
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else '-' }
        .joinToString("")
        .split("-")
        .filter { it.isNotEmpty() }
        .joinToString("-")
        .take(48)
    return slug.ifEmpty { "server" }
}
```

- [x] **Step 4: Run the tests.**

Run: same command as Step 2.
Expected: all tests in `EditorLspScreenTest.kt` PASS (10 pure-helper + ~12 composable tests).

- [x] **Step 5: Full-module compile + test check.**

Run: `cd apps/desktop && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./../../gradlew :desktop:compileKotlin :desktop:test 2>&1 | tail -100`
Expected: BUILD SUCCESSFUL; full suite green.

- [x] **Step 6: Commit.**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/settings/EditorLspScreen.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/settings/EditorLspScreenTest.kt
git commit -m "$(cat <<'EOF'
feat(desktop): add LspSettingsScreen, a port of Android EditorLspScreen.kt (M4g-4 T2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

### Task 3: Overlay wiring — `WorkspaceUiState`, `WorkspaceRoot`, `Main.kt`, `SessionHeaderMenus.kt`, `SessionDetail.kt`

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/WorkspaceRoot.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/SessionHeaderMenus.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/SessionDetail.kt`
- Test: Modify `apps/desktop/src/test/kotlin/dev/supermux/desktop/settings/EditorLspScreenTest.kt` (append an overlay-wiring section)
- Test: Modify `apps/desktop/src/test/kotlin/dev/supermux/desktop/workspace/SessionHeaderMenusTest.kt`

Mirrors the Usage overlay's exact shape (`WorkspaceRoot.kt:507-563`) but WITHOUT a `LaunchedEffect(ui.lspSettingsOpen)` fetch — `LspSettingsScreen` already owns its own load (Task 2's `LaunchedEffect(Unit) { reload() }`), and because the `Box` is only composed while `ui.lspSettingsOpen` is true, a close+reopen naturally re-mounts the screen and re-fetches (same net effect as Usage's explicit reset-to-null-on-close, just achieved by composition lifecycle instead of an owned snapshot variable).

- [x] **Step 1: Add `lspSettingsOpen` + `openLspSettings()` to `WorkspaceUiState`.** In `WorkspaceRoot.kt`, right after the `usageOpen` property (~line 93):

```kotlin
    var usageOpen by mutableStateOf(false)

    /**
     * Whether the LSP settings overlay (M4g-4) is showing. Flipped on by the File ▸
     * "Editor / LSP…" menu item in Main.kt and the SessionDetail overflow ⋮ row (both reach this
     * shared state the same way New-Session/Archived/Usage do); flipped off by the screen's own
     * back button or Escape. Lives here (not local to [WorkspaceRoot]) for the SAME reason as
     * [launcherOpen]/[archivedOpen]/[usageOpen] — Main's MenuBar renders outside WorkspaceRoot's
     * composition but must open it.
     */
    var lspSettingsOpen by mutableStateOf(false)
```

Extend `overlayOpen` (~line 102):

```kotlin
    val overlayOpen: Boolean get() = launcherOpen || archivedOpen || usageOpen || lspSettingsOpen
```

Add `lspSettingsOpen = false` to the three existing `openX()` mutual-exclusion setters, and add the new `openLspSettings()` right after `openUsage()` (~line 129):

```kotlin
    fun openLauncher() {
        launcherOpen = true
        archivedOpen = false
        usageOpen = false
        lspSettingsOpen = false
    }

    fun openArchived() {
        archivedOpen = true
        launcherOpen = false
        usageOpen = false
        lspSettingsOpen = false
    }

    fun openUsage() {
        usageOpen = true
        launcherOpen = false
        archivedOpen = false
        lspSettingsOpen = false
    }

    /** Open the LSP settings overlay; the "at most one overlay" mirror of [openLauncher]/
     *  [openArchived]/[openUsage]. */
    fun openLspSettings() {
        lspSettingsOpen = true
        launcherOpen = false
        archivedOpen = false
        usageOpen = false
    }
```

- [x] **Step 2: Add the overlay `Box` in `WorkspaceRoot`**, right after the Usage overlay block closes (~line 563, before the enclosing composable's final `}`):

```kotlin
            // ── LSP settings: a FULL-PANE overlay above the workspace (M4g-4 Task 3) ──
            // Same shape as the launcher/archived/usage overlays, but UNLIKE them the SCREEN itself
            // owns its server-list load/toggle/install/add/remove state (LspSettingsScreen's own
            // LaunchedEffect(Unit) — mirrors Android's EditorLspSection, since toggle/install/add/
            // remove all need to mutate the list in place, unlike Usage's single redeem-swap).
            // WorkspaceRoot only supplies the app.lsp* lambdas + the live app.lspInstallLog/
            // app.lspInstallDone StateFlows (folded from lsp_install_progress/lsp_install_done
            // frames by DesktopAppState) — because the composable is torn down + rebuilt each time
            // ui.lspSettingsOpen flips off/on, a re-open always re-fetches (same net effect as
            // Usage's explicit reset-to-null-on-close).
            if (ui.lspSettingsOpen) {
                // Self-focusing (mirrors the Usage overlay, NOT the launcher/archived Boxes): this
                // screen has no text field a user would naturally focus first when it opens, so
                // Escape needs a focus owner from frame one.
                val lspFocus = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { lspFocus.requestFocus() } }
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("lsp_settings_overlay")
                        .focusRequester(lspFocus)
                        .focusable()
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) {
                                ui.lspSettingsOpen = false
                                true
                            } else {
                                false
                            }
                        },
                ) {
                    LspSettingsScreen(
                        lspLoad = { app.lspLoad() },
                        lspToggle = { id, enabled -> app.lspToggle(id, enabled) },
                        lspInstall = { id -> app.lspInstall(id) },
                        lspInstallLog = app.lspInstallLog,
                        lspInstallDone = app.lspInstallDone,
                        lspAddCustom = { args ->
                            app.lspAddCustom(args.id, args.label, args.command, args.extensions, args.args, args.languageId, args.installCmd)
                        },
                        lspRemoveCustom = { id -> app.lspRemoveCustom(id) },
                        onBack = { ui.lspSettingsOpen = false },
                    )
                }
            }
```

Add the import right after the existing `dev.supermux.desktop.usage.UsageScreen` import (~line 52):

```kotlin
import dev.supermux.desktop.settings.LspSettingsScreen
```

- [x] **Step 3: Wire the File menu.** In `Main.kt`, right after the `Item("Usage…") { ui.openUsage() }` item (~line 169-171):

```kotlin
                        Item("Usage…") {
                            ui.openUsage()
                        }
                        Item("Editor / LSP…") {
                            ui.openLspSettings()
                        }
```

- [x] **Step 4: Wire the overflow ⋮ row.** In `SessionHeaderMenus.kt`, add an `onLspSettings` param to `OverflowMenu` (right after `onUsage`, ~line 315) and a new `DropdownMenuItem` (right after the "Usage" one, ~line 344-349):

```kotlin
@Composable
fun OverflowMenu(
    session: SessionInfo,
    onRename: (String) -> Unit,
    onToggleMute: (Boolean) -> Unit,
    onKill: () -> Unit,
    onUsage: () -> Unit = {},
    // Opens the LSP settings overlay (WorkspaceUiState.openLspSettings()) — threaded down to the
    // header's OverflowMenu "Editor / LSP…" row (M4g-4). Defaults to a no-op so existing callers/
    // tests that don't exercise it keep compiling.
    onLspSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
    forceOpen: Boolean = false,
    onForceOpenConsumed: () -> Unit = {},
) {
```

```kotlin
            DropdownMenuItem(
                text = { Text("Usage") },
                modifier = Modifier.testTag("overflow_usage"),
                onClick = { expanded = false; onUsage() },
            )
            DropdownMenuItem(
                text = { Text("Editor / LSP…") },
                modifier = Modifier.testTag("overflow_lsp_settings"),
                onClick = { expanded = false; onLspSettings() },
            )
```

Update the file's KDoc above `OverflowMenu` (~line 298-303): the "Settings/Devices/Proxies/Appearance are still omitted" sentence now un-omits Editor/LSP specifically — reword to: `"The REST of Android's management-nav rows (Settings/Devices/Proxies/Appearance) are still omitted — those screens don't exist on desktop yet. Editor/LSP is no longer one of them (M4g-4)."`

- [x] **Step 5: Thread `onLspSettings` through `SessionDetail`.** Add the param right after `onUsage` (~line 196):

```kotlin
    onUsage: () -> Unit = {},
    // Opens the LSP settings overlay (WorkspaceUiState.openLspSettings()) — threaded down to the
    // header's OverflowMenu "Editor / LSP…" row (M4g-4). Defaults to a no-op so existing callers/
    // tests that don't exercise it keep compiling.
    onLspSettings: () -> Unit = {},
```

Pass it into the `OverflowMenu(...)` call (~line 508):

```kotlin
            OverflowMenu(
                session = session,
                onRename = { newName -> app.rename(session.id, newName) },
                onToggleMute = { muted -> app.setMute(session.id, muted) },
                onKill = { app.kill(session.id) },
                onUsage = onUsage,
                onLspSettings = onLspSettings,
                forceOpen = forceOverflowMenu,
                onForceOpenConsumed = onForceOverflowMenuConsumed,
            )
```

Update the comment right above the `OverflowMenu(` call (~line 499-502) to also mention Editor/LSP.

- [x] **Step 6: Wire `WorkspaceRoot`'s `SessionDetail(...)` call.** Right after `onUsage = { ui.openUsage() },` (~line 403):

```kotlin
                        onUsage = { ui.openUsage() },
                        onLspSettings = { ui.openLspSettings() },
```

- [x] **Step 7: Write the failing overlay-wiring tests.** Append to `EditorLspScreenTest.kt` (after the existing `back_button_fires_on_back` test, before the class's closing `}`):

```kotlin
    // ── (5) overlay wiring into WorkspaceRoot ─────────────────────────────────────────────────────

    private val tempFiles = mutableListOf<java.nio.file.Path>()

    private fun tempPath(name: String): java.nio.file.Path {
        val f = java.nio.file.Files.createTempFile("lsp_settings_test_$name", ".json")
        java.nio.file.Files.deleteIfExists(f)
        tempFiles.add(f)
        return f
    }

    @kotlin.test.AfterTest fun cleanup() {
        tempFiles.forEach { runCatching { java.nio.file.Files.deleteIfExists(it) } }
    }

    /** A [dev.supermux.desktop.state.DesktopAppState] whose HTTP serves GET /settings/editor. */
    private fun appForLspSettings(): dev.supermux.desktop.state.DesktopAppState {
        val engine = io.ktor.client.engine.mock.MockEngine { req ->
            val jsonHeaders = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json")
            if (req.method == io.ktor.http.HttpMethod.Get && req.url.encodedPath == "/settings/editor") {
                io.ktor.client.engine.mock.respond(
                    """
                    {"lsp":{"servers":[
                      {"id":"typescript","label":"TypeScript","extensions":[".ts",".tsx"],"enabled":true,"state":"ready","installable":true},
                      {"id":"pyright","label":"Pyright","extensions":[".py"],"enabled":false,"state":"missing","installable":true,"installLabel":"Install"}
                    ]}}
                    """.trimIndent(),
                    io.ktor.http.HttpStatusCode.OK, jsonHeaders,
                )
            } else {
                io.ktor.client.engine.mock.respond(io.ktor.utils.io.ByteReadChannel("{}"), io.ktor.http.HttpStatusCode.OK, jsonHeaders)
            }
        }
        val api = dev.supermux.net.BrokerApi("ws://test:9898", "t", io.ktor.client.HttpClient(engine))
        return dev.supermux.desktop.state.DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = kotlinx.coroutines.test.TestScope(kotlinx.coroutines.test.UnconfinedTestDispatcher()),
            connectOnInit = false,
            sendFrameOverride = { },
            apiOverride = api,
        )
    }

    @Test fun lsp_settings_overlay_opens_from_ui_and_loads_the_server_list() = runComposeUiTest {
        val ui = dev.supermux.desktop.workspace.WorkspaceUiState().apply { lspSettingsOpen = true }
        val app = appForLspSettings()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                dev.supermux.desktop.workspace.WorkspaceRoot(
                    app, ui,
                    dev.supermux.desktop.workspace.WorkspaceStateStore(tempPath("state")),
                    dev.supermux.desktop.session.LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_settings_overlay").assertIsDisplayed()
        onNodeWithTag("lsp_settings_screen").assertIsDisplayed()
        onNodeWithText("TypeScript").assertIsDisplayed()
        onNodeWithText("Pyright").assertIsDisplayed()
    }

    @Test fun escape_closes_the_lsp_settings_overlay() = runComposeUiTest {
        val ui = dev.supermux.desktop.workspace.WorkspaceUiState().apply { lspSettingsOpen = true }
        val app = appForLspSettings()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                dev.supermux.desktop.workspace.WorkspaceRoot(
                    app, ui,
                    dev.supermux.desktop.workspace.WorkspaceStateStore(tempPath("state")),
                    dev.supermux.desktop.session.LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        onNodeWithTag("lsp_settings_overlay").performKeyInput { androidx.compose.ui.test.pressKey(androidx.compose.ui.input.key.Key.Escape) }
        waitForIdle()
        assertFalse(ui.lspSettingsOpen)
        onNodeWithTag("lsp_settings_overlay").assertDoesNotExist()
    }

    @Test fun workspace_shortcuts_are_gated_off_while_the_lsp_settings_overlay_is_up() = runComposeUiTest {
        val ui = dev.supermux.desktop.workspace.WorkspaceUiState().apply { lspSettingsOpen = true }
        val app = appForLspSettings()
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                dev.supermux.desktop.workspace.WorkspaceRoot(
                    app, ui,
                    dev.supermux.desktop.workspace.WorkspaceStateStore(tempPath("state")),
                    dev.supermux.desktop.session.LauncherStore(tempPath("launcher")),
                )
            }
        }
        waitForIdle()
        assertFalse(ui.layout.sidebarCollapsed)
        onNodeWithTag("lsp_settings_screen").performKeyInput {
            androidx.compose.ui.test.withKeyDown(androidx.compose.ui.input.key.Key.CtrlLeft) {
                androidx.compose.ui.test.pressKey(androidx.compose.ui.input.key.Key.B)
            }
        }
        waitForIdle()
        assertFalse(ui.layout.sidebarCollapsed)
        assertTrue(ui.lspSettingsOpen)
    }

    @Test fun opening_lsp_settings_closes_any_other_open_overlay() {
        val ui = dev.supermux.desktop.workspace.WorkspaceUiState()
        ui.openUsage()
        ui.openLspSettings()
        assertFalse(ui.usageOpen)
        assertTrue(ui.lspSettingsOpen)
    }
```

(Fully-qualified names above avoid adding a long new import block mid-plan — when implementing, prefer adding proper `import` statements at the top of the file over inline FQNs, matching the rest of the module's style; either compiles, but imports are more readable and are what a reviewer will expect.)

- [x] **Step 8: Run the new tests.**

Run: `cd apps/desktop && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./../../gradlew :desktop:test --tests "dev.supermux.desktop.settings.EditorLspScreenTest" 2>&1 | tail -80`
Expected: all 4 new overlay-wiring tests PASS.

- [x] **Step 9: Add the overflow-row test.** Append to `SessionHeaderMenusTest.kt`, right after the existing `overflowUsageRowFiresOnUsage` test (~line 385):

```kotlin
    @Test
    fun overflow_lsp_settings_row_fires_on_lsp_settings() = runComposeUiTest {
        var opened = false
        setContent {
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                OverflowMenu(
                    session = baseSession,
                    onRename = {},
                    onToggleMute = {},
                    onKill = {},
                    onLspSettings = { opened = true },
                )
            }
        }
        onNodeWithTag("workspace_overflow").performClick()
        onNodeWithTag("overflow_lsp_settings").assertIsDisplayed()
        onNodeWithTag("overflow_lsp_settings").performClick()
        assertTrue(opened)
    }
```

- [x] **Step 10: Run it + the full module.**

Run: `cd apps/desktop && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./../../gradlew :desktop:compileKotlin :desktop:test 2>&1 | tail -100`
Expected: BUILD SUCCESSFUL; full suite green (no regressions in `WorkspaceRoot`/`SessionDetail`/`SessionHeaderMenus` callers — every new param has a default so no existing call site breaks).

- [x] **Step 11: Commit.**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/WorkspaceRoot.kt \
        apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt \
        apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/SessionHeaderMenus.kt \
        apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/SessionDetail.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/settings/EditorLspScreenTest.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/workspace/SessionHeaderMenusTest.kt
git commit -m "$(cat <<'EOF'
feat(desktop): wire the LSP settings overlay into WorkspaceRoot + File menu + overflow row (M4g-4 T3)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

### Task 4: Live verification (`SM_LSP_SETTINGS` / `SM_LSP_TOGGLE` / `SM_LSP_ADD_REMOVE` hooks) + full suite run + report

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt` (env-hook catalog comment + the three hooks)

Three narrowly-scoped, off-by-default hooks — one render-only, two that fire real (self-restoring) broker mutations. Re-read the Ground rules DANGER block before writing this task's code.

- [x] **Step 1: Add the three catalog lines.** In `Main.kt`'s env-hook comment block, right after the `SM_USAGE=1` entry (~line 74):

```
//   SM_LSP_SETTINGS=1             — open the LSP settings overlay (File ▸ "Editor / LSP…"'s SAME
//                                  ui.openLspSettings()) on start, loading the real app.lspLoad()
//                                  (GET /settings/editor, read-only) (M4g-4). Read-only — never
//                                  calls lspInstall/lspToggle/lspAddCustom/lspRemoveCustom. Off by
//                                  default.                                                 [main]
//   SM_LSP_TOGGLE=<serverId>      — ALSO opens the overlay, then flips <serverId>'s enabled state,
//                                  holds for 5s (screenshot window), then flips it BACK to its
//                                  original value before exiting — a real, but self-restoring,
//                                  PUT /settings/editor (M4g-4). Mutates broker-global state shared
//                                  with web/iOS/Android for the duration of the hold. Off by
//                                  default; point it at a low-stakes server.                [main]
//   SM_LSP_ADD_REMOVE=1           — ALSO opens the overlay, adds a throwaway custom server
//                                  (id "m4g4-live-check"), holds for 5s (screenshot window), then
//                                  removes it again — a real, but self-cleaning, POST+DELETE
//                                  /settings/editor/lsp/custom round trip (M4g-4). Off by default.
//                                  NEVER combine with SM_LSP_TOGGLE in the same run.         [main]
```

- [x] **Step 2: Add the hooks.** Right after the `SM_USAGE` hook block in `main()` (~line 764):

```kotlin
                    // Headless LSP-settings verification hooks (M4g-4). SM_LSP_SETTINGS is
                    // strictly read-only (open + load); SM_LSP_TOGGLE/SM_LSP_ADD_REMOVE are real,
                    // self-restoring/self-cleaning broker-global mutations — see this plan's Ground
                    // rules DANGER block before touching either. lspInstall is NEVER fired from a
                    // hook (a real `bun install -g` on the broker host) — it stays UI-test-covered
                    // only, mirroring how M4c never auto-fired Push/Publish.
                    val lspSettingsHook = System.getenv("SM_LSP_SETTINGS") == "1"
                    if (lspSettingsHook) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openLspSettings()
                            println("[lsp-settings] opened the LSP settings overlay")
                        }
                    }

                    val lspToggleTarget = System.getenv("SM_LSP_TOGGLE")?.takeIf { it.isNotBlank() }
                    if (lspToggleTarget != null) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openLspSettings()
                            val before = app.lspLoad().firstOrNull { it.id == lspToggleTarget }
                            if (before == null) {
                                println("[lsp-toggle] server '$lspToggleTarget' not found in lspLoad()")
                            } else {
                                val original = before.enabled
                                println("[lsp-toggle] '$lspToggleTarget' original enabled=$original — flipping to ${!original}")
                                app.lspToggle(lspToggleTarget, !original)
                                delay(5_000) // screenshot window
                                app.lspToggle(lspToggleTarget, original)
                                println("[lsp-toggle] restored '$lspToggleTarget' to enabled=$original")
                            }
                        }
                    }

                    val lspAddRemoveHook = System.getenv("SM_LSP_ADD_REMOVE") == "1"
                    if (lspAddRemoveHook) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openLspSettings()
                            val added = app.lspAddCustom(
                                id = "m4g4-live-check", label = "M4g-4 Live Check", command = "true",
                                extensions = listOf(".m4g4livecheck"),
                            )
                            println("[lsp-add-remove] added throwaway server: ok=${added?.ok}")
                            delay(5_000) // screenshot window
                            val removed = app.lspRemoveCustom("m4g4-live-check")
                            println("[lsp-add-remove] removed throwaway server: ok=${removed?.ok}")
                        }
                    }
```

- [x] **Step 3: Compile.**

Run: `cd apps/desktop && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./../../gradlew :desktop:compileKotlin 2>&1 | tail -60`
Expected: BUILD SUCCESSFUL.

- [x] **Step 4: Live checklist.**

```bash
mkdir -p /home/ahmet/.cache/m4g4v-shots
export DISPLAY=:77
export SKIKO_RENDER_API=SOFTWARE
# (use the paired config at /home/ahmet/.cache/smx-test-config per the standing ground rules)
```

**(a) Render-only pass** — `SM_LSP_SETTINGS=1` under Xvfb `:77`: launch, wait ~5s for the hook + real `GET /settings/editor` to resolve, then:
```bash
xwd -root -display :77 -out /home/ahmet/.cache/m4g4v-shots/lsp-settings.xwd
python3 -c "from PIL import Image; Image.open('/home/ahmet/.cache/m4g4v-shots/lsp-settings.xwd').save('/home/ahmet/.cache/m4g4v-shots/lsp-settings.png')"
```
Confirm the overlay shows this box's REAL server list (per the milestone brief: typescript=ready, pyright=missing, bash=ready, etc. — cross-check the screenshot's state labels against a live `curl -H "Authorization: Bearer <token>" http://127.0.0.1:9898/settings/editor` fired at the same moment). Kill the app; no state was mutated (GET only).

**(b) Toggle pass** — pick a LOW-STAKES server from (a)'s list (prefer one that is already disabled and non-critical to this box's own editor usage, e.g. a not-commonly-used language server — do NOT pick `typescript` if you plan to keep using the desktop editor's LSP connect flow afterward). Run `SM_LSP_TOGGLE=<that-id>` and, DURING the 5s hold window logged by `[lsp-toggle]`, screenshot:
```bash
xwd -root -display :77 -out /home/ahmet/.cache/m4g4v-shots/lsp-toggle-flipped.xwd
python3 -c "from PIL import Image; Image.open('/home/ahmet/.cache/m4g4v-shots/lsp-toggle-flipped.xwd').save('/home/ahmet/.cache/m4g4v-shots/lsp-toggle-flipped.png')"
```
Confirm from stdout: `[lsp-toggle] '<id>' original enabled=<X> — flipping to <!X>` then, ~5s later, `[lsp-toggle] restored '<id>' to enabled=<X>`. Confirm from a POST-run `curl .../settings/editor` that the server's `enabled` matches the ORIGINAL value (X), proving the restore landed. Kill the app.

**(c) Add/remove pass** — `SM_LSP_ADD_REMOVE=1`. During the 5s hold, screenshot (confirm the "M4g-4 Live Check" row with the CUSTOM badge renders):
```bash
xwd -root -display :77 -out /home/ahmet/.cache/m4g4v-shots/lsp-add-remove.xwd
python3 -c "from PIL import Image; Image.open('/home/ahmet/.cache/m4g4v-shots/lsp-add-remove.xwd').save('/home/ahmet/.cache/m4g4v-shots/lsp-add-remove.png')"
```
Confirm stdout shows `[lsp-add-remove] added throwaway server: ok=true` then `[lsp-add-remove] removed throwaway server: ok=true`. Confirm a POST-run `curl .../settings/editor` no longer lists `m4g4-live-check`. Kill the app.

**(d) Install — CODE-VERIFIED ONLY, never fired live.** Covered by `EditorLspScreenTest.kt`'s `install_button_only_shown_when_enabled_installable_and_not_ready`, `install_button_streams_log_lines_from_the_install_log_state_flow`, `install_done_shows_ok_result_and_dismiss_clears_it`, `install_done_shows_the_error_result_when_not_ok`.

- [x] **Step 5: Cleanup.** Confirm no throwaway custom server survives (`curl .../settings/editor` shows no `m4g4-live-check`), confirm the toggled server's `enabled` matches its pre-run value, confirm `ui-state.json` at the paired config path is unchanged (this milestone doesn't persist `lspSettingsOpen` — mirrors `usageOpen`'s "intentionally never persisted" note in `WorkspaceStateStore`), kill Xvfb `:77` (if self-spawned this pass) and the `:desktop:run` JVM.

- [x] **Step 6: Run the full suites (`--rerun-tasks`).**

```bash
cd apps/desktop && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ../../gradlew :desktop:test --rerun-tasks 2>&1 | tail -100
cd .. && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./gradlew :shared:jvmTest --rerun-tasks 2>&1 | tail -100
GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./gradlew :android:compileDebugKotlin 2>&1 | tail -60
```

Expected: desktop suite green at (pre-M4g-4 baseline + ~27 new tests: 3 reducer + 10 MockEngine + 10 pure-helper + 12 composable + 4 overlay + 1 overflow-row ≈ 40, adjust for the exact count written across Tasks 1-3); shared jvmTest unchanged (no shared code touched — this plan only touches `apps/desktop/src`); android compile green (no android code touched).

- [x] **Step 7: Tick every checkbox in this plan, then commit + report.**

```bash
git add docs/superpowers/plans/2026-07-10-desktop-m4g4-lsp-settings.md
git commit -m "$(cat <<'EOF'
docs(desktop): M4g-4 LSP settings screen plan executed

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

Report should cover: the LSP settings overlay now fully wired (enable/disable, install with a live streamed log, add/remove custom servers), the live-verification evidence for all three hooks (log lines + screenshot paths + the toggle/add-remove restore confirmations), the exact final test count added, and that **M4g is now fully closed** — M4g-1 (markdown preview), M4g-2 (diff view), M4g-3 (LSP connect), M4g-4 (LSP settings) together give the desktop editor everything it needs short of a full settings hub (which stays out of scope — `Settings/Devices/Proxies/Appearance` remain the documented omissions in `SessionHeaderMenus.kt`'s KDoc).

**LIVE-VERIFICATION EVIDENCE (2026-07-10, executed):** All three hooks ran headlessly under Xvfb `:77` (`SKIKO_RENDER_API=SOFTWARE`, paired config `/home/ahmet/.cache/smx-test-config`) against the live broker (`127.0.0.1:9898`), never restarting it. **(a) Render-only (`SM_LSP_SETTINGS=1`):** log `[lsp-settings] opened the LSP settings overlay`; screenshot `/home/ahmet/.cache/m4g4v-shots/lsp-settings.png` shows the REAL server list — TypeScript/JavaScript = **Ready**, Python (Pyright) = **Not installed** (with the `bun install -g pyright` install button rendered), Bash/YAML/JSON/CSS/HTML = **Ready**, Go (gopls) below — cross-checked line-for-line against a live `GET /settings/editor` fired the same moment (typescript `ready`, pyright `missing`, bash `ready`, gopls `prereq-missing`, dart `missing`). GET-only; nothing mutated. **(b) Self-restoring toggle (`SM_LSP_TOGGLE=dart`,** a low-stakes `missing` server**):** log `[lsp-toggle] 'dart' original enabled=true — flipping to false` then `[lsp-toggle] restored 'dart' to enabled=true`; a POST-run `GET /settings/editor` confirmed `dart.enabled=true` — matching its pre-run value, proving the restore landed. Screenshot `/home/ahmet/.cache/m4g4v-shots/lsp-toggle-flipped.png`. **(c) Self-cleaning add/remove (`SM_LSP_ADD_REMOVE=1`):** log `[lsp-add-remove] added throwaway server: ok=true` then `[lsp-add-remove] removed throwaway server: ok=true`; a POST-run `GET /settings/editor` server-id list (`typescript, pyright, bash, yaml, json, css, html, gopls, rust-analyzer, dart`) contains NO `m4g4-live-check`, proving self-cleanup (no separate screenshot — verified by the ok=true/ok=true log pair + the post-run GET showing the throwaway id gone). **(d) Install — NEVER fired live** (code-verified only via `EditorLspScreenTest.kt`'s four install tests), exactly as the DANGER block requires — a real `bun install -g` on the broker host was never triggered. Broker global `settings/editor` left in its original state (dart restored, no throwaway server survives). Suite green at 567 (`:desktop:test --rerun-tasks`); shared jvmTest untouched; android compile untouched. Self-spawned Xvfb `:77` killed, `ui-state.json` restored to its pre-run value.

## Self-review notes

**Spec coverage:** the state layer (Task 1: five wrappers + the two install StateFlows + reducer fold Android/M4g-3 left as `else -> {}`), the screen port (Task 2: `LspServerRow`'s enable Switch + state badge + install-with-streamed-log + custom-server remove, `AddLspForm`'s 7-field add flow with client-side validation and server-error surfacing), the overlay wiring (Task 3: `WorkspaceUiState`/`WorkspaceRoot`/File-menu/overflow-row, all four `openX()` mutual-exclusion setters kept consistent), and live verification (Task 4: three scoped hooks + the explicit "install is never fired live" decision) each map 1:1 to a numbered task. The DANGER callouts from the milestone brief (install = real system mutation; toggle/add/remove = shared broker-global state) are stated once in the Ground rules and re-stated at each site that could violate them (Task 1's wrapper KDocs, Task 4's hook comments and checklist).

**Placeholder scan:** every code step shows the real Kotlin (no "add appropriate handling"); every test shows concrete assertions. Task 4's live-verification bash is illustrative shell (screenshot + curl cross-check) rather than one fully scripted command, matching every prior M4-series plan's live-verification task — the exact launch invocation depends on which paired-broker/launch convention is live at execution time; what to check is fully spelled out.

**Type consistency:** `LspSettingsScreen`'s seven params (`lspLoad`/`lspToggle`/`lspInstall`/`lspInstallLog`/`lspInstallDone`/`lspAddCustom`/`lspRemoveCustom`/`onBack`) are defined once in Task 2 and threaded identically through Task 3's `WorkspaceRoot` wiring (`lspAddCustom = { args -> app.lspAddCustom(args.id, args.label, ...) }` matches `AddCustomLspArgs`'s field order from Task 2 and `DesktopAppState.lspAddCustom`'s exploded-param signature from Task 1). `AddCustomLspArgs` is defined once (Task 2, in the screen file — deliberately NOT in `DesktopAppState.kt`, so Task 1's wrapper stays UI-agnostic with exploded params mirroring `BrokerApi.addCustomEditorLsp`'s own signature) and referenced consistently by both the form's `onSubmit` callback and the `WorkspaceRoot` lambda that unpacks it. `onLspSettings: () -> Unit` is threaded with the identical name through `OverflowMenu` → `SessionDetail` → `WorkspaceRoot`'s `SessionDetail(...)` call, matching the established `onUsage` precedent exactly.

**Design choices flagged for the reviewer:** (1) `LspSettingsScreen` owns its own server-list state internally rather than following the Usage/Archived overlay's "WorkspaceRoot owns the snapshot" convention — a deliberate deviation, justified in Task 2's header comment and this file's Architecture section, because toggle/install/add/remove all need in-place list mutation that Usage's single-slice-swap didn't. (2) Android's `lspError` state (declared, never set — dead code in the ported source) is dropped rather than faithfully ported dead-code-and-all; called out explicitly in Task 2 rather than silently diverging. (3) Install is the one interactive affordance NEVER exercised live (Task 4(d)) — because the Install button only renders for a `!ready` server (Android's own `server.enabled && server.installable && !ready` gate), there is no "safe no-op" live-fire available (unlike, hypothetically, re-installing an already-ready server), so the decision is unconditional: UI-test-covered only, matching M4c's Push/Publish and M4f's `redeemCodexReset` precedent. (4) Two of Task 4's three hooks fire real, but self-restoring/self-cleaning, broker-global mutations (`SM_LSP_TOGGLE`, `SM_LSP_ADD_REMOVE`) — a step further than M4f/M4g-3's read-only-only hooks, justified because a toggle/add/remove round-trip is the only way to prove the settings screen's WRITE paths work end-to-end against a real broker (install, the other write path, is excluded per (3)); both hooks restore/clean up unconditionally within the same run rather than leaving state for a human to remember to revert.
