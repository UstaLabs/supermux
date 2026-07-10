package dev.supermux.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.desktop.pairing.OnboardingScreen
import dev.supermux.desktop.pairing.PairingState
import dev.supermux.desktop.state.DesktopAppState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.SupermuxTheme
import dev.supermux.desktop.workspace.WorkspaceRoot
import dev.supermux.desktop.workspace.WorkspaceStateStore
import dev.supermux.desktop.workspace.WorkspaceUiState

// Headless-verification env hooks (ALL off by default; for Xvfb runs with no input injection).
// Catalogued here for discoverability — some are read at their use-site rather than in main():
//   SM_PAIR_TOKEN + SM_PAIR_BASE  — seed a pairing without onboarding (both required)   [main, below]
//   SM_AUTOSELECT=1               — auto-select a session so a pane renders             [WorkspaceRoot]
//   SM_PANES=etd                  — force Editor/Terminal/Display panes on              [WorkspaceRoot]
//   SM_SMOKE_SEND="name:text"     — send a chat message to a session                    [main]
//   SM_TERM_INPUT="name:text"     — type into a session's scratch terminal (M2)         [main]
//   SM_OPEN_FILE="name:path[:ln]" — open a file in the editor at a line (M3)            [main]
//   SM_LAUNCH_TEST="wd|agent|msg[|attach]" — drive the launcher spawn→first-msg chain (M4a) [main]
//   SM_LAUNCH_PAUSE_MS=<ms>       — hold the launcher OPEN this long before submitting (M4a) [main]
//   SM_FINISH_TEST=<session-name> — select the session + open its Finish dialog (menu, M4b) [main]
//   SM_GIT_MENU="<name>[:fetch|:pull]" — force-open the header git-badge menu; :fetch/:pull ALSO
//                                  fire that op live (M4c, no Push/Publish member — see
//                                  GitMenuForceOp KDoc)                                   [main]
//   SM_LINKS_MENU=<session-name>  — force-open the header session-links (proxies) menu (M4c) [main]
//   SM_OVERFLOW_MENU=<session-name> — force-open the header ⋮ overflow menu (M4c)          [main]
//   SM_CHAT_ATTACH="<session-name>|<file-path>|<text>" — stage+upload a file into the chat
//                                  composer via the SAME stage()/stageFiles() path, wait for the
//                                  chip to reach Done, then send with <text> (M4d)             [main]
//   SMX_KCEF_FORCE_ERROR=1        — force KcefState.Error (native-fallback editor, M3)   [KcefRuntime]
//   SM_EDITOR_SAVE_TEST           — drive the editor save path (M3)                      [EditorPanel]
//   SMX_KCEF_EXTRA_ARGS="…"       — extra CEF switches for headless CI                   [KcefRuntime]
fun main() {
    val store = DesktopTokenStore()
    // Dev override, mirrors the mac app's SM_PAIR_TOKEN/SM_PAIR_BASE guard (SupermuxApp.swift
    // requires BOTH to be present and non-empty) — lets a dev/CI run seed a pairing without the
    // onboarding UI. Requiring both prevents a stray SM_PAIR_TOKEN from silently clobbering a
    // real user token, and a mismatched token/baseUrl pair from bypassing TOFU.
    val envToken = System.getenv("SM_PAIR_TOKEN")?.takeIf { it.isNotBlank() }
    val envBase = System.getenv("SM_PAIR_BASE")?.takeIf { it.isNotBlank() }
    if (envToken != null && envBase != null) {
        // A filesystem error here must not crash main() before the window ever opens —
        // log and fall through to normal onboarding instead.
        runCatching {
            store.save(envToken)
            store.saveBaseUrl(envBase)
        }.onSuccess {
            println("[Main] dev pairing seed applied from SM_PAIR_* env")
        }.onFailure { e ->
            println("[Main] dev pairing seed failed (falling through to onboarding): $e")
        }
    } else if (envToken != null || envBase != null) {
        println("[Main] ignoring partial SM_PAIR_* env — both SM_PAIR_TOKEN and SM_PAIR_BASE must be set")
    }
    application {
        Window(
            // Dispose the shared KCEF (embedded Chromium) runtime BEFORE the process exits, on this
            // (main/AWT) thread while the window still exists — CEF wants an orderly shutdown here,
            // not from a JVM shutdown hook. No-op if the editor never booted KCEF. Without it,
            // closing the window orphans the Chromium helper/GPU/renderer subprocesses. try/finally:
            // a CEF teardown failure must NEVER trap the user in a window that won't close. See
            // KcefRuntime.dispose (incl. the mid-download skip).
            onCloseRequest = {
                try {
                    dev.supermux.desktop.editor.KcefRuntime.dispose()
                } finally {
                    exitApplication()
                }
            },
            title = "supermux",
            state = rememberWindowState(width = 1440.dp, height = 900.dp),
        ) {
            // Paired flag + workspace UI state are hoisted to the Window (FrameWindowScope) so the
            // native MenuBar — which must be called on this scope, not inside a nested @Composable —
            // can reach the same WorkspaceLayout/selection the shortcuts drive.
            var paired by remember {
                mutableStateOf(!store.load().isNullOrBlank() && !store.loadBaseUrl().isNullOrBlank())
            }
            val uiStore = remember { WorkspaceStateStore() }
            val launcherStore = remember { dev.supermux.desktop.session.LauncherStore() }
            // Hydrate the layout + last selection from ui-state.json (the selection is re-validated
            // against live sessions inside WorkspaceRoot once the first snapshot lands).
            val ui = remember {
                WorkspaceUiState().apply {
                    val persisted = uiStore.load()
                    persisted.layout?.let { layout.restore(it) }
                    selectedId = persisted.selectedId
                }
            }
            var showUnpairConfirm by remember { mutableStateOf(false) }

            // Menu bar (paired only). DEDUPE DECISION: the View toggle items are clickable-only —
            // they carry NO KeyShortcut, because the in-app `Modifier.workspaceShortcuts` already
            // owns Ctrl/Cmd+B/E/T/D; registering the same accelerator on the menu would risk a
            // double-toggle (menu action + the key event still bubbling to the modifier). File ▸
            // New Session keeps its conventional Ctrl+N accelerator too — it and the in-app shortcut
            // both just flip `ui.launcherOpen`, which is idempotent, so a double-fire (menu action +
            // the key event still bubbling to workspaceShortcuts) is harmless.
            if (paired) {
                MenuBar {
                    Menu("File", mnemonic = 'F') {
                        Item("New Session", shortcut = KeyShortcut(Key.N, ctrl = true)) {
                            ui.launcherOpen = true
                        }
                        Separator()
                        Item("Unpair…") { showUnpairConfirm = true }
                    }
                    Menu("View", mnemonic = 'V') {
                        CheckboxItem("Show Sidebar", checked = !ui.layout.sidebarCollapsed) {
                            ui.layout.sidebarCollapsed = !ui.layout.sidebarCollapsed
                        }
                        CheckboxItem("Editor", checked = ui.selectedId?.let { ui.layout.panesFor(it).editor } == true) {
                            ui.selectedId?.let { ui.layout.toggleEditor(it) }
                        }
                        CheckboxItem("Terminal", checked = ui.selectedId?.let { ui.layout.panesFor(it).terminal } == true) {
                            ui.selectedId?.let { ui.layout.toggleTerminal(it) }
                        }
                        CheckboxItem("Display", checked = ui.selectedId?.let { ui.layout.panesFor(it).display } == true) {
                            ui.selectedId?.let { ui.layout.toggleDisplay(it) }
                        }
                    }
                }
            }

            // TODO(M4): drive from Settings/Appearance instead of a hardcoded default.
            SupermuxTheme(appearance = AppearanceMode.DARK) {
                if (!paired) {
                    val scope = rememberCoroutineScope()
                    val pairing = remember { PairingState(store, scope) }
                    DisposableEffect(Unit) { onDispose { pairing.close() } }
                    OnboardingScreen(pairing, onPaired = { paired = true })
                } else {
                    val scope = rememberCoroutineScope()
                    val app = remember { DesktopAppState(store.loadBaseUrl()!!, store.load()!!, scope) }
                    DisposableEffect(Unit) { onDispose { app.close() } }

                    // Headless-verification hook (no input injection on CI boxes): SM_SMOKE_SEND=
                    // "<session-name>:<text>" resolves the named session after the first snapshot
                    // and sends <text> through the SAME send path the composer calls, so a live
                    // send/receive round-trip can be proven under Xvfb without a pointer/keyboard.
                    // Harmless in production (unset by default); M2 terminal verification reuses it.
                    val smoke = System.getenv("SM_SMOKE_SEND")?.takeIf { it.isNotBlank() }
                    if (smoke != null) {
                        LaunchedEffect(app) {
                            val sep = smoke.indexOf(':')
                            if (sep <= 0) {
                                println("[smoke] bad SM_SMOKE_SEND (expected <session-name>:<text>)")
                                return@LaunchedEffect
                            }
                            val name = smoke.substring(0, sep)
                            val text = smoke.substring(sep + 1)
                            // Wait (≤30s) for the snapshot to carry the named session.
                            var target = app.sessions.value.firstOrNull { it.name == name }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == name }
                            }
                            val t = target
                            if (t == null) {
                                println("[smoke] session '$name' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            app.updateViewing(t.id, true)
                            app.ensureMessagesLoaded(t.id)
                            app.sendMessage(t.id, text)
                            println("[smoke] sent to ${t.name} (${t.id}): $text")
                        }
                    }

                    // Headless terminal-verification hook (M2): SM_TERM_INPUT="<session-name>:<text>"
                    // resolves the named session after the first snapshot, opens/ensures its SCRATCH
                    // terminal (kind="scratch", terminal "main" — via app.connectTerminal, which
                    // CANNOT produce a kind=agent client), and writes <text> as pty bytes so the full
                    // JediTerm→WS→tmux→WS→JediTerm round-trip can be proven under Xvfb without a
                    // keyboard. Backslash escapes \n \r \t in <text> are unescaped to their control
                    // bytes (so a trailing "\n" submits the command). Scratch-ONLY by construction:
                    // there is no code path here to reach the agent PTY, so it can never type into a
                    // live Claude TUI. Harmless in production (unset by default).
                    val termInput = System.getenv("SM_TERM_INPUT")?.takeIf { it.isNotBlank() }
                    if (termInput != null) {
                        LaunchedEffect(app) {
                            val sep = termInput.indexOf(':')
                            if (sep <= 0) {
                                println("[terminput] bad SM_TERM_INPUT (expected <session-name>:<text>)")
                                return@LaunchedEffect
                            }
                            val name = termInput.substring(0, sep)
                            val raw = termInput.substring(sep + 1)
                            val text = raw
                                .replace("\\n", "\n")
                                .replace("\\r", "\r")
                                .replace("\\t", "\t")
                            // Wait (≤30s) for the snapshot to carry the named session.
                            var target = app.sessions.value.firstOrNull { it.name == name }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == name }
                            }
                            val t = target
                            if (t == null) {
                                println("[terminput] session '$name' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            // Settle so the scratch tmux terminal is ready, then open a SCRATCH
                            // ("main") client and drive its run-loop as a child of this effect.
                            delay(5_000)
                            val client = app.connectTerminal(t.id, "main")   // kind=scratch, enforced
                            val runJob = launch { client.run() }
                            // Wait (≤15s) for the socket to reach CONNECTED before writing.
                            val connectDeadline = System.currentTimeMillis() + 15_000
                            while (client.status.value != dev.supermux.net.TerminalStatus.CONNECTED &&
                                System.currentTimeMillis() < connectDeadline) {
                                delay(200)
                            }
                            if (client.status.value != dev.supermux.net.TerminalStatus.CONNECTED) {
                                println("[terminput] scratch terminal for '$name' never connected")
                                runJob.cancel()
                                return@LaunchedEffect
                            }
                            client.sendInput(text.toByteArray(Charsets.UTF_8))
                            println("[terminput] wrote ${text.length} chars to scratch 'main' of ${t.name} (${t.id})")
                        }
                    }

                    // Headless editor-verification hook (M3): SM_OPEN_FILE="<session-name>:<path>[:line]"
                    // resolves the named session after the first snapshot, SELECTS it, flips its
                    // editor pane on, and delivers a PendingEditorOpen(path, line) through the SAME
                    // handler chain a chat file-path tap uses (WorkspaceUiState.externalOpen →
                    // SessionDetail.onOpenFile → toWorkdirRelativePath → the pending-open holder →
                    // openFileAtLine → cmRevealLine). <path> is workdir-relative or absolute-within-
                    // workdir; the optional trailing :<line> reveals+centers that 1-based line. So a
                    // file can be opened at a line under Xvfb without a pointer/keyboard. Harmless in
                    // production (unset by default).
                    val openFile = System.getenv("SM_OPEN_FILE")?.takeIf { it.isNotBlank() }
                    if (openFile != null) {
                        LaunchedEffect(app) {
                            val firstSep = openFile.indexOf(':')
                            if (firstSep <= 0) {
                                println("[openfile] bad SM_OPEN_FILE (expected <session-name>:<path>[:line])")
                                return@LaunchedEffect
                            }
                            val name = openFile.substring(0, firstSep)
                            var rest = openFile.substring(firstSep + 1)
                            // Peel an optional trailing :<line> (a bare integer); leaves ':'-bearing
                            // paths intact when the tail isn't numeric.
                            var line: Int? = null
                            val lastColon = rest.lastIndexOf(':')
                            if (lastColon > 0) {
                                val tail = rest.substring(lastColon + 1).toIntOrNull()
                                if (tail != null) {
                                    line = tail
                                    rest = rest.substring(0, lastColon)
                                }
                            }
                            val path = rest
                            // Wait (≤30s) for the snapshot to carry the named session.
                            var target = app.sessions.value.firstOrNull { it.name == name }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == name }
                            }
                            val t = target
                            if (t == null) {
                                println("[openfile] session '$name' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            // Flip the editor pane on up-front so the panel mounts even before the
                            // routed onOpenFile runs (onOpenFile flips it too — this is belt-and-braces
                            // for the screenshot deadline).
                            ui.layout.setPanes(t.id, ui.layout.panesFor(t.id).copy(editor = true))
                            ui.externalOpen = t.id to dev.supermux.ui.FilePathRef(path, line)
                            println("[openfile] requested '$path'${line?.let { ":$it" } ?: ""} in ${t.name} (${t.id})")
                        }
                    }

                    // Headless launcher-verification hook (M4a): SM_LAUNCH_TEST=
                    // "<workdir>|<agent>|<message>[|<attachPath>]" drives the SAME onSubmit chain the
                    // launcher UI uses — after settle it flips the launcher overlay OPEN, then (unless
                    // SM_LAUNCH_PAUSE_MS holds it open first, for a screenshot of the composer card /
                    // a restored draft) exercises the real spawn→first-message→uploads path:
                    // createSessionWithFirstMessage(workdir, agent, model=null, reasoning=null, message,
                    // staged, worktree=false, baseBranch=null) → select the new session → sendMessage
                    // with consumeFirstUploads → close the overlay. PIPE-delimited (not colon) so the
                    // message may contain colons/spaces; an optional 4th field stages one real file
                    // (FileChunkSource) that uploads post-spawn. A BLANK message opens the launcher
                    // without submitting (draft/prefs screenshot mode). This SPAWNS a real session —
                    // point it at a throwaway temp workdir, never a real project. Off by default.
                    val launchTest = System.getenv("SM_LAUNCH_TEST")?.takeIf { it.isNotBlank() }
                    if (launchTest != null) {
                        LaunchedEffect(app) {
                            val parts = launchTest.split("|", limit = 4)
                            if (parts.size < 3) {
                                println("[launch] bad SM_LAUNCH_TEST (expected <workdir>|<agent>|<message>[|<attach>])")
                                return@LaunchedEffect
                            }
                            val workdir = parts[0]
                            val agent = parts[1]
                            val message = parts[2]
                            val attachPath = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
                            // Let the first WS snapshot land so the session list is populated before a
                            // (possibly blank-id) spawn has to resolve its id against it.
                            delay(3_000)
                            ui.launcherOpen = true
                            // Optional hold so a screenshot can capture the OPEN launcher (composer card
                            // / a pre-written restored draft) before the spawn submit runs.
                            val pauseMs = System.getenv("SM_LAUNCH_PAUSE_MS")?.toLongOrNull() ?: 0L
                            if (pauseMs > 0) delay(pauseMs)
                            if (message.isBlank()) {
                                println("[launch] launcher opened (blank message → no submit)")
                                return@LaunchedEffect
                            }
                            val staged = attachPath?.let { p ->
                                val file = java.io.File(p)
                                if (!file.isFile) {
                                    println("[launch] attach path is not a file, skipping: $p")
                                    return@let null
                                }
                                val mime = runCatching { java.nio.file.Files.probeContentType(file.toPath()) }
                                    .getOrNull() ?: "application/octet-stream"
                                listOf(dev.supermux.desktop.session.StagedUpload(
                                    dev.supermux.desktop.upload.FileChunkSource(file), file.name, mime,
                                ))
                            } ?: emptyList()
                            val id = app.createSessionWithFirstMessage(
                                workdir = workdir,
                                agent = agent,
                                model = null,
                                reasoningLevel = null,
                                text = message,
                                staged = staged,
                                worktree = false,
                                baseBranch = null,
                            )
                            if (id == null) {
                                println("[launch] createSessionWithFirstMessage returned null (invalid workdir / spawn failed)")
                                ui.launcherOpen = false
                                return@LaunchedEffect
                            }
                            ui.selectedId = id
                            app.sendMessage(id, message, app.consumeFirstUploads(id))
                            ui.launcherOpen = false
                            println("[launch] spawned session $id in '$workdir' (agent=$agent, staged=${staged.size}); first message sent")
                        }
                    }

                    // Headless Finish-verification hook (M4b): SM_FINISH_TEST="<session-name>"
                    // resolves the named session after the first snapshot, SELECTS it, and flips the
                    // shared one-shot `forceFinishDialogFor` so the matching SessionDetail opens its
                    // Finish dialog in the MENU state — the SAME state the FinishButton click flips,
                    // which loads finishReadiness → the ReadinessCard + Merge/PR/Keep/Discard rows. It
                    // does NOT trigger any finish action (no merge/discard/keep) — it only opens the
                    // menu so the readiness card can be screenshot under Xvfb without a pointer. Only
                    // effective when the session has session_branch != null (else the button/dialog
                    // don't render and the flag is a no-op). Harmless in production (unset by default).
                    val finishTest = System.getenv("SM_FINISH_TEST")?.takeIf { it.isNotBlank() }
                    if (finishTest != null) {
                        LaunchedEffect(app) {
                            // Wait (≤30s) for the snapshot to carry the named session.
                            var target = app.sessions.value.firstOrNull { it.name == finishTest }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == finishTest }
                            }
                            val t = target
                            if (t == null) {
                                println("[finish] session '$finishTest' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            if (t.session_branch == null) {
                                println("[finish] session '$finishTest' has no session_branch — Finish button/dialog won't render")
                            }
                            ui.selectedId = t.id
                            ui.forceFinishDialogFor = t.id
                            println("[finish] opened Finish dialog for ${t.name} (${t.id}); branch=${t.session_branch}")
                        }
                    }

                    // Headless git-badge-menu verification hook (M4c): SM_GIT_MENU="<session-name>
                    // [:fetch|:pull]" resolves the named session after the first snapshot, SELECTS
                    // it, and flips the shared one-shot `forceGitMenuFor` so the matching
                    // SessionDetail's GitBadgeMenu expands — the SAME state a click on the badge
                    // flips. A bare name (no suffix) only opens the dropdown (screenshot the
                    // Fetch/Pull/Publish-or-Push rows); a `:fetch` or `:pull` suffix ADDITIONALLY
                    // fires that op through the real `run(...)` path a click uses (both are safe-ish
                    // per the M4c live-verification ground rules), so the inline `git_op_result`
                    // label can be screenshot too. There is NO `:push`/`:publish` suffix — those
                    // mutate a real remote, so this hook cannot ever auto-fire them (see
                    // GitMenuForceOp's KDoc in SessionHeaderMenus.kt). Harmless in production (unset
                    // by default).
                    val gitMenuTest = System.getenv("SM_GIT_MENU")?.takeIf { it.isNotBlank() }
                    if (gitMenuTest != null) {
                        LaunchedEffect(app) {
                            val parts = gitMenuTest.split(":", limit = 2)
                            val name = parts[0]
                            val op = when (parts.getOrNull(1)?.trim()?.lowercase()) {
                                "fetch" -> dev.supermux.desktop.workspace.GitMenuForceOp.FETCH
                                "pull" -> dev.supermux.desktop.workspace.GitMenuForceOp.PULL
                                null, "" -> dev.supermux.desktop.workspace.GitMenuForceOp.OPEN
                                else -> {
                                    println("[gitmenu] unknown SM_GIT_MENU suffix '${parts[1]}' — falling back to open-only")
                                    dev.supermux.desktop.workspace.GitMenuForceOp.OPEN
                                }
                            }
                            // Wait (≤30s) for the snapshot to carry the named session.
                            var target = app.sessions.value.firstOrNull { it.name == name }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == name }
                            }
                            val t = target
                            if (t == null) {
                                println("[gitmenu] session '$name' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            ui.forceGitMenuFor = t.id to op
                            println("[gitmenu] forced git-badge menu ($op) for ${t.name} (${t.id}); git=${t.git}")
                        }
                    }

                    // Headless session-links-menu verification hook (M4c): SM_LINKS_MENU=
                    // "<session-name>" resolves the named session, SELECTS it, and flips the shared
                    // one-shot `forceLinksMenuFor` so the matching SessionDetail's SessionLinksMenu
                    // (globe icon) expands. Never opens a URL — only the dropdown. A no-op if the
                    // session has no proxies (the menu doesn't render). Harmless in production.
                    val linksMenuTest = System.getenv("SM_LINKS_MENU")?.takeIf { it.isNotBlank() }
                    if (linksMenuTest != null) {
                        LaunchedEffect(app) {
                            var target = app.sessions.value.firstOrNull { it.name == linksMenuTest }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == linksMenuTest }
                            }
                            val t = target
                            if (t == null) {
                                println("[linksmenu] session '$linksMenuTest' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            ui.forceLinksMenuFor = t.id
                            println("[linksmenu] forced session-links menu for ${t.name} (${t.id})")
                        }
                    }

                    // Headless overflow-menu verification hook (M4c): SM_OVERFLOW_MENU=
                    // "<session-name>" resolves the named session, SELECTS it, and flips the shared
                    // one-shot `forceOverflowFor` so the matching SessionDetail's ⋮ OverflowMenu
                    // expands. NEVER auto-clicks Rename/Mute/Kill — only the dropdown. Harmless in
                    // production (unset by default).
                    val overflowMenuTest = System.getenv("SM_OVERFLOW_MENU")?.takeIf { it.isNotBlank() }
                    if (overflowMenuTest != null) {
                        LaunchedEffect(app) {
                            var target = app.sessions.value.firstOrNull { it.name == overflowMenuTest }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == overflowMenuTest }
                            }
                            val t = target
                            if (t == null) {
                                println("[overflowmenu] session '$overflowMenuTest' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            ui.forceOverflowFor = t.id
                            println("[overflowmenu] forced overflow menu for ${t.name} (${t.id})")
                        }
                    }

                    // Headless chat-attach-verification hook (M4d): SM_CHAT_ATTACH=
                    // "<session-name>|<file-path>|<text>" resolves the named session after the first
                    // snapshot, SELECTS it, and hands (filePath, text) to the matching ChatPanel via
                    // WorkspaceUiState.externalAttach — DesktopComposer's LaunchedEffect(externalAttach)
                    // then stages the file through the SAME stageFiles() funnel the Attach dialog and
                    // drag-drop use (so the chip uploads through the real uploadResumable seam), polls
                    // until that chip reaches a terminal state, and — on Done — sends through the SAME
                    // gather-and-send path the Send button uses (send-gated, chips cleared after). A
                    // failed upload (or a missing/blank path) is logged and dropped — no send fires.
                    // PIPE-delimited (not colon) so <text> may contain colons/spaces. This drives a
                    // REAL upload + REAL send against the named session's chat — point it at a
                    // throwaway/idle session, never a busy one. Off by default.
                    val chatAttach = System.getenv("SM_CHAT_ATTACH")?.takeIf { it.isNotBlank() }
                    if (chatAttach != null) {
                        LaunchedEffect(app) {
                            val parts = chatAttach.split("|", limit = 3)
                            if (parts.size < 3) {
                                println("[chatattach] bad SM_CHAT_ATTACH (expected <session-name>|<file-path>|<text>)")
                                return@LaunchedEffect
                            }
                            val name = parts[0]
                            val filePath = parts[1]
                            val text = parts[2]
                            // Wait (≤30s) for the snapshot to carry the named session.
                            var target = app.sessions.value.firstOrNull { it.name == name }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == name }
                            }
                            val t = target
                            if (t == null) {
                                println("[chatattach] session '$name' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            ui.externalAttach = t.id to dev.supermux.desktop.chat.ComposerExternalAttach(filePath, text)
                            println("[chatattach] requested attach '$filePath' + send for ${t.name} (${t.id})")
                        }
                    }

                    WorkspaceRoot(app, ui, uiStore, launcherStore)

                    // Unpair confirmation (File ▸ Unpair…): clears the credential store and flips
                    // back to onboarding, which disposes `app` (DisposableEffect above).
                    if (showUnpairConfirm) {
                        AlertDialog(
                            onDismissRequest = { showUnpairConfirm = false },
                            title = { Text("Unpair this device?") },
                            text = { Text("This removes the saved pairing credentials and returns to the onboarding screen. Your sessions on the broker are untouched.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    store.clear()
                                    showUnpairConfirm = false
                                    paired = false
                                }) { Text("Unpair") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showUnpairConfirm = false }) { Text("Cancel") }
                            },
                        )
                    }
                }
            }
        }
    }
}
