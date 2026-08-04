package dev.supermux.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.desktop.host.DesktopHostBootstrap
import dev.supermux.desktop.host.DesktopHostStores
import dev.supermux.desktop.host.FleetState
import dev.supermux.desktop.host.HostWizard
import dev.supermux.desktop.intro.FirstRunIntroOverlay
import dev.supermux.desktop.intro.IntroStateStore
import dev.supermux.desktop.notify.NotificationController
import dev.supermux.desktop.notify.TrayNotificationManager
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
//   SM_LSP="name|path"            — open a file and let the real LSP connect flow run (M4g-3) [main]
//   SM_EDITOR_PREVIEW="name|md-path" — select the session, flip its editor pane on, open <md-path>
//                                  via the SAME externalOpen chain SM_OPEN_FILE uses, then (once
//                                  that path is the active tab) flip previewMode=true so the
//                                  rendered markdown preview overlay can be screenshotted headlessly
//                                  (M4g-1). The previewMode flip is EditorPanel's own env read
//                                  (SM_EDITOR_SAVE_TEST precedent) — not driven from here.  [main, EditorPanel]
//   SM_LAUNCH_TEST="wd|agent|msg[|attach]" — drive the launcher spawn→first-msg chain (M4a) [main]
//   SM_LAUNCH_PAUSE_MS=<ms>       — hold the launcher OPEN this long before submitting (M4a) [main]
//   SM_FINISH_TEST=<session-name> — select the session + open its Finish dialog (menu, M4b) [main]
//   SM_GIT_MENU="<name>[:fetch|:pull]" — force-open the header git-badge menu; :fetch/:pull ALSO
//                                  fire that op live (M4c, no Push/Publish member — see
//                                  GitMenuForceOp KDoc)                                   [main]
//   SM_LINKS_MENU=<session-name>  — force-open the header session-links (proxies) menu (M4c) [main]
//   SM_OVERFLOW_MENU=<session-name> — force-open the header ⋮ overflow menu (M4c)          [main]
//   SM_DICTATE="<session-name>|<wav-path>" — feed a pre-recorded WAV through the real mic-dictation
//                                  transcribe->append path (no mic under Xvfb) (M5-1)              [main]
//   SM_CHAT_ATTACH="<session-name>|<file-path>|<text>" — stage+upload a file into the chat
//                                  composer via the SAME stage()/stageFiles() path, wait for the
//                                  chip to reach Done, then send with <text> (M4d)             [main]
//   SM_DISPLAY="<session-name>" — open the session + display pane + connect/start its VNC stream
//                                  (real Xvfb+VNC on the broker host) (M5-2)                       [main]
//   SM_ARCHIVED=1                 — open the Archived-sessions overlay (File ▸ Archived…'s SAME
//                                  ui.openArchived()) on start, loading the real app.archived()
//                                  list (M4e)                                                  [main]
//   SM_ARCHIVED_OPEN=<name>       — ALSO opens the overlay, then resolves <name> in the real
//                                  app.archived() list and seeds ui.forceArchivedOpenFor so that
//                                  session's read-only ArchivedChatView renders with no click
//                                  (M4e-T3 live verification of the transcript view)            [main]
//   SM_ARCHIVED_RESUME=<name>     — ALSO opens the overlay, then resolves <name> in the real
//                                  app.archived() list and drives the SAME resume path the
//                                  ArchivedScreen's Resume button uses: app.resume(id) fire-and-
//                                  forget + close the overlay (M4e). SPAWNS/UN-ARCHIVES a real
//                                  session — point it at a throwaway you archived yourself.  [main]
//   SM_SETTINGS=1                 — open the Settings hub on Agents (File ▸ "Settings…"'s SAME
//                                  ui.openSettings()) on start, loading real app.agentStatuses()
//                                  (GET /agents/status, read-only) from the active host          [main]
//   SM_DEVICES=1                  — open the Settings hub on Devices via ui.openSettings(Devices)
//                                  on start, loading real app.devices() (GET /devices, read-only)
//                                  from the active host. Off by default; never mints/revokes.    [main]
//   SM_PROXIES=1                  — open the Settings hub on Proxies via ui.openSettings(Proxies)
//                                  on start, loading real app.proxiesForSettings() (GET /proxies,
//                                  read-only). Never creates/removes/toggles. Off by default.    [main]
//   SM_ASSISTANT=1                — open the Settings hub on Assistant via ui.openSettings(Assistant)
//                                  on start, loading real app.assistantLoad() + curatorSettings()
//                                  (GET /settings/config, /settings/soul, /settings/curator,
//                                  read-only). Never saves or runs curator. Off by default.      [main]
//   SM_VOICE=1                    — open the Settings hub on Voice via ui.openSettings(Voice) on
//                                  start, loading real app.appConfig() + fetchGlossary() (GET
//                                  /settings/config, /config/voice-glossary, read-only). Never
//                                  mutates engines or glossary. Off by default.                  [main]
//   SM_USAGE=1                    — open the Usage overlay (File ▸ "Usage…"'s SAME ui.openUsage())
//                                  on start, loading the real app.usage() (GET /usage, read-only)
//                                  (M4f). Read-only — never calls redeemCodexReset() (that burns a
//                                  real banked Codex reset; the redeem path stays UI-test-covered
//                                  only). Off by default.                                    [main]
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
//   SM_DIFF="<session-name>"      — select the session + flip its editor pane on; EditorPanel's own
//                                  env read (SM_EDITOR_SAVE_TEST precedent) then fires the SAME
//                                  editor.loadDiff(fsDiff) the "View changes" button drives (a real
//                                  GET /fs/diff) once the panel mounts, so the rendered DiffView
//                                  (repo/file grouping + +/- diff rows) can be screenshotted
//                                  headlessly (M4g-2). Off by default.              [main, EditorPanel]
//   SM_DIFF_COMMENT=1             — paired with SM_DIFF: once the diff has loaded, ALSO fires a real
//                                  POST /review/comments on the diff's first addable line (the SAME
//                                  onReviewAddComment the +-gutter composer drives), then reloads so
//                                  the resulting comment thread renders — never touches
//                                  reviewSubmit (M4g-2). Off by default.                [EditorPanel]
//   SM_DIFF_EXPAND=1              — paired with SM_DIFF: DiffView's `autoExpandAll` starts every
//                                  file expanded instead of collapsed, so a headless screenshot
//                                  shows diff +/- lines with no pointer/xdotool available (M4g-2;
//                                  desktop-only verification convenience, not an Android field).
//                                  Off by default.                                      [EditorPanel]
//   SMX_KCEF_FORCE_ERROR=1        — force KcefState.Error (native-fallback editor, M3)   [KcefRuntime]
//   SM_EDITOR_SAVE_TEST           — drive the editor save path (M3)                      [EditorPanel]
//   SM_NOTIFY_TEST="<session-name>"  — force-unselect a session so its NEXT agent reply is
//                                  guaranteed "unviewed" (M5-3); watch stdout for the
//                                  unconditional "[notify] session=... text=..." decision+dispatch
//                                  log line NotificationController prints right before it would
//                                  raise a tray toast. Off by default; harmless in production.  [main]
//   SMX_KCEF_EXTRA_ARGS="…"       — extra CEF switches for headless CI                   [KcefRuntime]
//   SM_INTRO=1 / SM_INTRO=0       — force-show / force-suppress the first-run intro cinematic.
//                                  Default: plays once ever (intro-seen marker), and NEVER in
//                                  SM_PAIR_TOKEN-seeded runs so headless verification shots are
//                                  unaffected. A forced (SM_INTRO=1) run does NOT mark seen.  [main]
//   SM_INTRO_FREEZE=<t 0..1>      — freeze the intro timeline at <t> (no auto-advance/finish)
//                                  for deterministic phase screenshots              [FirstRunIntro]
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

    // Multi-host fleet store (spec §3.2). Migrate any legacy single-host (baseUrl, token) — including
    // one just seeded by SM_PAIR_* or a prior single-host install — into PairedHost[0] so existing
    // desktop users land in the fleet with ZERO re-pairing. Idempotent (no-op once the store holds a
    // host). Built once here and shared with the composition below.
    val hostStore = DesktopHostStores.store()
    runCatching { DesktopHostStores.migrateFromLegacyIfNeeded(hostStore, store) }
        .onFailure { println("[Main] legacy→fleet migration failed (falling through): $it") }

    application {
        // M5-3: TrayState + NotificationController are hoisted ABOVE Window because Tray(...) is
        // an ApplicationScope-receiver composable (confirmed via javap: Tray_desktopKt.Tray's
        // first parameter is ApplicationScope) — it can only be called directly inside
        // `application { }`'s body, never nested inside Window's content (whose implicit
        // receiver is FrameWindowScope). windowState is hoisted alongside it — a plain val, not
        // receiver-bound — purely so the tray icon's click handler can un-minimize the SAME
        // WindowState instance passed to Window below.
        val windowState = rememberWindowState(width = 1440.dp, height = 900.dp)
        val trayState = rememberTrayState()
        // Published once pairing completes (below, inside Window's content) so the tray icon's
        // click handler can select a session on the live WorkspaceUiState. null before pairing
        // and after unpair, when there is no workspace to select into — the click handler
        // no-ops in that case (see onAction below).
        var pairedUi by remember { mutableStateOf<WorkspaceUiState?>(null) }
        val notificationController = remember {
            NotificationController(TrayNotificationManager(trayState))
        }

        if (isTraySupported) {
            Tray(
                icon = rememberVectorPainter(Icons.Filled.Terminal),
                state = trayState,
                tooltip = "supermux",
                onAction = {
                    // Best-effort "bring the app forward": un-minimizing is portable; actually
                    // RAISING the window above others is window-manager-dependent (especially
                    // under a bare Xvfb with no WM) and not attempted further. Compose's
                    // Notification carries no per-toast click callback/id (confirmed via javap)
                    // — only the tray ICON has one (this onAction) — so a click can only jump to
                    // the LAST-notified session, not necessarily the specific toast the user
                    // meant if several stacked up. See this plan's Goal, scoping decision 3.
                    windowState.isMinimized = false
                    notificationController.lastNotifiedSession?.let { sid -> pairedUi?.selectedId = sid }
                },
            )
        } else {
            // Expected under a bare Xvfb with no tray-hosting panel — see this plan's Ground
            // rules. Desktop notifications are simply disabled; nothing else degrades.
            println(
                "[Main] system tray not supported on this platform/session " +
                    "(java.awt.SystemTray.isSupported()==false) — desktop notifications are disabled",
            )
        }

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
            state = windowState,
        ) {
            // Paired flag + workspace UI state are hoisted to the Window (FrameWindowScope) so the
            // native MenuBar — which must be called on this scope, not inside a nested @Composable —
            // can reach the same WorkspaceLayout/selection the shortcuts drive.
            // Paired once the fleet holds a host (legacy single-host users were migrated to
            // PairedHost[0] above; onboarding seeds it via the same migration on success).
            var paired by remember { mutableStateOf(hostStore.list().isNotEmpty()) }
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
            // M5-3: publish this pairing's WorkspaceUiState up to the tray icon's onAction
            // handler (declared above, outside Window) so a click can select the last-notified
            // session. Cleared on dispose (unpair / window teardown) so a stale ui never lingers.
            DisposableEffect(ui) {
                pairedUi = ui
                onDispose { pairedUi = null }
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
                            ui.openLauncher()
                        }
                        Item("Archived…") {
                            ui.openArchived()
                        }
                        Item("Usage…") {
                            ui.openUsage()
                        }
                        Item("Settings…") {
                            ui.openSettings()
                        }
                        // Existing items keep working — they open the Settings hub focused on
                        // that section (same overlay as Settings…, not a separate stack).
                        Item("Editor / LSP…") {
                            ui.openLspSettings()
                        }
                        Item("Personal Assistants…") {
                            ui.openPersonalAssistants()
                        }
                        Item("Check for Updates…") {
                            ui.openAppUpdate()
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
                    // First-run choice (spec §6 / D6 choice A): on every native-host desktop platform
                    // the default first run is the desktop-as-host wizard — this computer becomes a host,
                    // shows a pairing QR for the phone, and auto-pairs "This computer" into the fleet. A
                    // "Connect to a different broker instead" escape hatch drops to the classic onboarding.
                    var connectInstead by remember { mutableStateOf(false) }
                    val showHostWizard = DesktopHostBootstrap.isNativeHostPlatform() && !connectInstead

                    if (showHostWizard) {
                        // The sidecar spawns OR adopts the local broker (adopt = read-only probe of an
                        // already-running :9898 broker; it never stops a broker it didn't start). NOT
                        // stopped on dispose — a freshly-spawned managed broker must keep hosting after
                        // the wizard closes (the login keep-alive agent owns its persistence).
                        val sidecar = remember { DesktopHostBootstrap.sidecar() }
                        val model = remember { DesktopHostBootstrap.buildModel(scope, hostStore, sidecar) }
                        HostWizard(
                            model = model,
                            onDone = {
                                // The model auto-paired "This computer" into the fleet store; reflect it.
                                paired = hostStore.list().isNotEmpty()
                                if (!paired) connectInstead = true // bootstrap failed → fall back to onboarding
                            },
                            onConnectInstead = { connectInstead = true },
                        )
                    } else {
                        val pairing = remember { PairingState(store, scope) }
                        DisposableEffect(Unit) { onDispose { pairing.close() } }
                        OnboardingScreen(pairing, onPaired = {
                            // Onboarding persisted the legacy (baseUrl, token) into DesktopTokenStore;
                            // fold it into the fleet as PairedHost[0] before flipping to the app.
                            runCatching { DesktopHostStores.migrateFromLegacyIfNeeded(hostStore, store) }
                            paired = true
                        })
                    }
                } else {
                    val scope = rememberCoroutineScope()
                    // The multi-host fleet: one connection per paired host, merged into WorkspaceRoot.
                    val fleet = remember { FleetState(hostStore, scope) }
                    DisposableEffect(Unit) { onDispose { fleet.close() } }
                    // The active host's app backs the single-host headless hooks below and is
                    // WorkspaceRoot's fallback; WorkspaceRoot itself routes through `fleet`. Non-null
                    // because `paired` ⟹ the store holds a host ⟹ FleetState opened its connection.
                    val app = remember(fleet) { fleet.activeApp() } ?: return@SupermuxTheme

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

                    // Headless markdown-preview verification hook (M4g-1): SM_EDITOR_PREVIEW=
                    // "<session-name>|<md-path>" resolves the named session, selects it, flips its
                    // editor pane on, and opens <md-path> via the SAME externalOpen chain SM_OPEN_FILE
                    // uses above. The previewMode=true flip itself happens on the OTHER side (EditorPanel's
                    // own env read, SM_EDITOR_SAVE_TEST precedent) once <md-path> becomes the active tab —
                    // this block only gets the file open. Off by default.
                    val editorPreview = System.getenv("SM_EDITOR_PREVIEW")?.takeIf { it.isNotBlank() }
                    if (editorPreview != null) {
                        LaunchedEffect(app) {
                            val sep = editorPreview.indexOf('|')
                            if (sep <= 0) {
                                println("[editorpreview] bad SM_EDITOR_PREVIEW (expected <session-name>|<md-path>)")
                                return@LaunchedEffect
                            }
                            val name = editorPreview.substring(0, sep)
                            val path = editorPreview.substring(sep + 1)
                            var target = app.sessions.value.firstOrNull { it.name == name }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == name }
                            }
                            val t = target
                            if (t == null) {
                                println("[editorpreview] session '$name' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            ui.layout.setPanes(t.id, ui.layout.panesFor(t.id).copy(editor = true))
                            ui.externalOpen = t.id to dev.supermux.ui.FilePathRef(path, null)
                            println("[editorpreview] requested '$path' in ${t.name} (${t.id}) — EditorPanel flips previewMode on once it's active")
                        }
                    }

                    // Headless LSP-connect verification hook (M4g-3): SM_LSP="<session-name>|<file-path>"
                    // resolves the named session, SELECTS it, flips its editor pane on, and opens
                    // <file-path> via the SAME externalOpen chain SM_OPEN_FILE/SM_EDITOR_PREVIEW use
                    // above. Opening the file is enough — EditorPanel's OWN connect-sequencing
                    // LaunchedEffect (Task 5) then drives the real lsp_status_query → lsp_open →
                    // cmLspConnect round trip against the broker's live language server once the file
                    // becomes the active tab and the KCEF engine reports ready; no further driving is
                    // needed from here. Point <file-path> at a file extension the broker's LSP config
                    // covers (GET /settings/editor lists supported extensions per server, e.g. the
                    // typescript server covers .ts/.tsx/.js/...). Off by default; harmless in production.
                    val lspTest = System.getenv("SM_LSP")?.takeIf { it.isNotBlank() }
                    if (lspTest != null) {
                        LaunchedEffect(app) {
                            val sep = lspTest.indexOf('|')
                            if (sep <= 0) {
                                println("[lsp] bad SM_LSP (expected <session-name>|<file-path>)")
                                return@LaunchedEffect
                            }
                            val name = lspTest.substring(0, sep)
                            val path = lspTest.substring(sep + 1)
                            var target = app.sessions.value.firstOrNull { it.name == name }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == name }
                            }
                            val t = target
                            if (t == null) {
                                println("[lsp] session '$name' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            ui.layout.setPanes(t.id, ui.layout.panesFor(t.id).copy(editor = true))
                            ui.externalOpen = t.id to dev.supermux.ui.FilePathRef(path, null)
                            println(
                                "[lsp] requested '$path' in ${t.name} (${t.id}) — " +
                                    "EditorPanel's connect effect drives the LSP round trip from here",
                            )
                        }
                    }

                    // Headless diff-verification hook (M4g-2): SM_DIFF="<session-name>" resolves +
                    // selects the named session and flips its editor pane on; EditorPanel's own env
                    // read (SM_EDITOR_SAVE_TEST precedent) fires editor.loadDiff(fsDiff) once the
                    // panel mounts — this side just gets the right session showing. Off by default.
                    val diffTest = System.getenv("SM_DIFF")?.takeIf { it.isNotBlank() }
                    if (diffTest != null) {
                        LaunchedEffect(app) {
                            val name = diffTest
                            var target = app.sessions.value.firstOrNull { it.name == name }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == name }
                            }
                            val t = target
                            if (t == null) {
                                println("[diff] session '$name' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            ui.layout.setPanes(t.id, ui.layout.panesFor(t.id).copy(editor = true))
                            println("[diff] selected ${t.name} (${t.id}) — EditorPanel fires loadDiff once mounted")
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
                            ui.openLauncher()
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

                    // Headless dictation-verification hook (M5-1): SM_DICTATE=
                    // "<session-name>|<wav-path>" resolves the named session after the first
                    // snapshot, SELECTS it, and hands <wav-path> to the matching ChatPanel via
                    // WorkspaceUiState.externalDictate — DesktopComposer's LaunchedEffect(externalDictate)
                    // reads the WAV bytes off disk and feeds them through the SAME onTranscribeAudio
                    // seam the mic button uses (app.transcribeAudio(session.id, bytes, filename) -> a
                    // REAL POST to the broker's whisper endpoint), then appends the cleaned text to
                    // the composer draft — proving the full record(*)->POST->append round-trip with
                    // no real mic (there is none under Xvfb). PIPE-delimited (not colon) so
                    // <wav-path> stays simple. Off by default; drives a REAL transcription job —
                    // point it at a throwaway/idle session, never a busy one.
                    val dictateTest = System.getenv("SM_DICTATE")?.takeIf { it.isNotBlank() }
                    if (dictateTest != null) {
                        LaunchedEffect(app) {
                            val parts = dictateTest.split("|", limit = 2)
                            if (parts.size < 2) {
                                println("[dictate] bad SM_DICTATE (expected <session-name>|<wav-path>)")
                                return@LaunchedEffect
                            }
                            val name = parts[0]
                            val wavPath = parts[1]
                            var target = app.sessions.value.firstOrNull { it.name == name }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == name }
                            }
                            val t = target
                            if (t == null) {
                                println("[dictate] session '$name' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            ui.externalDictate = t.id to dev.supermux.desktop.chat.ComposerExternalDictate(wavPath)
                            println("[dictate] requested transcribe '$wavPath' for ${t.name} (${t.id})")
                        }
                    }

                    // Headless display/VNC-verification hook (M5-2): SM_DISPLAY="<session-name>"
                    // resolves the named session after the first snapshot, SELECTS it, flips its
                    // display pane on (the SAME layout.setPanes(...display=true) toggle the header's
                    // PaneToggleCluster click uses), then ensures a running display stream exists —
                    // reusing one if `listDisplays()` already shows one for this session, else firing
                    // a REAL app.startDisplay(name) (broker default provider: linux-xvfb here). The
                    // mounted DisplayPanel then connects the REAL VncClient to that stream and paints
                    // it, so a screenshot proves the whole RFB-over-WS round trip with no
                    // pointer/mic/xdotool involved. DANGER: startDisplay spawns a real Xvfb+VNC
                    // process on the broker host — only point this at a throwaway session, and clean
                    // up the started stream afterward (see this plan's Task 5 live-verify steps). Off
                    // by default; harmless in production.
                    val displayTest = System.getenv("SM_DISPLAY")?.takeIf { it.isNotBlank() }
                    if (displayTest != null) {
                        LaunchedEffect(app) {
                            var target = app.sessions.value.firstOrNull { it.name == displayTest }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == displayTest }
                            }
                            val t = target
                            if (t == null) {
                                println("[display] session '$displayTest' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            ui.layout.setPanes(t.id, ui.layout.panesFor(t.id).copy(display = true))
                            val existing = app.listDisplays().firstOrNull { it.sessionName == t.name && it.status == "running" }
                            if (existing != null) {
                                println("[display] reusing existing running display ${existing.id} for ${t.name}")
                            } else {
                                println("[display] no running display for '${t.name}' — starting one (real Xvfb+VNC on the broker host)")
                                val started = app.startDisplay(t.name)
                                println("[display] startDisplay result: $started")
                            }
                        }
                    }

                    // Headless Archived-sessions verification hook (M4e): SM_ARCHIVED=1 opens the
                    // Archived overlay on start via the SAME `ui.openArchived()` the File ▸
                    // "Archived…" menu item calls, so `WorkspaceRoot`'s own LaunchedEffect(ui.archivedOpen)
                    // loads the real `app.archived()` list and the screen renders under Xvfb without a
                    // menu click. SM_ARCHIVED_OPEN=<archived-session-name> ALSO opens the overlay, then
                    // resolves <name> against a fresh `app.archived()` fetch (polled — a separate fetch
                    // from WorkspaceRoot's own, to avoid racing it) and seeds `ui.forceArchivedOpenFor`
                    // with its id, so that row's read-only ArchivedChatView (transcript, no composer)
                    // renders with no click. SM_ARCHIVED_RESUME=<archived-session-name> instead drives the
                    // SAME resume path `ArchivedScreen`'s onResume callback uses in WorkspaceRoot: a
                    // fire-and-forget `app.resume(id)` immediately followed by closing the overlay
                    // (`ui.archivedOpen = false`) — the resumed session then arrives back in the live
                    // sidebar via a WS frame, same as a real click. SM_ARCHIVED_OPEN and SM_ARCHIVED_RESUME
                    // may both be set (open renders first, then resume fires) or used independently. This
                    // SPAWNS/UN-ARCHIVES a real session on SM_ARCHIVED_RESUME — only point it at a
                    // throwaway you archived yourself, never a real archived session. Off by default;
                    // harmless in production.
                    val archivedHook = System.getenv("SM_ARCHIVED") == "1"
                    val archivedOpenName = System.getenv("SM_ARCHIVED_OPEN")?.takeIf { it.isNotBlank() }
                    val archivedResumeName = System.getenv("SM_ARCHIVED_RESUME")?.takeIf { it.isNotBlank() }
                    if (archivedHook || archivedOpenName != null || archivedResumeName != null) {
                        LaunchedEffect(app) {
                            // Let the first WS snapshot land, same settle window as the other hooks.
                            delay(3_000)
                            ui.openArchived()
                            println("[archived] opened the Archived overlay")

                            // Resolve <name> against a fresh archived() fetch, polling ≤30s (the fetch
                            // is a separate call from WorkspaceRoot's own list load).
                            suspend fun resolveArchived(name: String): dev.supermux.net.ArchivedDto? {
                                var found = app.archived().firstOrNull { it.name == name }
                                val deadline = System.currentTimeMillis() + 30_000
                                while (found == null && System.currentTimeMillis() < deadline) {
                                    delay(1_000)
                                    found = app.archived().firstOrNull { it.name == name }
                                }
                                return found
                            }

                            if (archivedOpenName != null) {
                                val t = resolveArchived(archivedOpenName)
                                if (t == null) {
                                    println("[archived] SM_ARCHIVED_OPEN session '$archivedOpenName' not found in archived() after 30s")
                                } else {
                                    ui.forceArchivedOpenFor = t.id
                                    println("[archived] opened read-only transcript for '$archivedOpenName' (${t.id})")
                                }
                            }
                            if (archivedResumeName != null) {
                                val t = resolveArchived(archivedResumeName)
                                if (t == null) {
                                    println("[archived] SM_ARCHIVED_RESUME session '$archivedResumeName' not found in archived() after 30s")
                                } else {
                                    app.resume(t.id)
                                    ui.archivedOpen = false
                                    println("[archived] resumed '$archivedResumeName' (${t.id}) and closed the overlay")
                                }
                            }
                        }
                    }

                    // Headless Settings-hub / Agents verification hook (desktop-parity Task 1):
                    // SM_SETTINGS=1 opens the Settings hub on Agents via the SAME
                    // `ui.openSettings()` the File ▸ "Settings…" menu item calls, so the hub loads
                    // real `app.agentStatuses()` (GET /agents/status) under Xvfb. Read-only by
                    // construction — never starts install/login. Off by default; harmless in prod.
                    val settingsHook = System.getenv("SM_SETTINGS") == "1"
                    if (settingsHook) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openSettings(dev.supermux.desktop.workspace.SettingsSection.Agents)
                            println("[settings] opened the Settings hub (Agents)")
                            // Prove the screen loads REAL data from the live broker, not just the shell.
                            val statuses = app.agentStatuses()
                            println("[settings] agentStatuses count=${statuses?.size ?: "null"} kinds=${statuses?.joinToString { "${it.kind}:${if (it.installed) "inst" else "miss"}:${if (it.authed) "auth" else "noauth"}" } ?: "(load failed)"}")
                        }
                    }

                    // Headless Devices verification hook (desktop-parity Task 2): SM_DEVICES=1 opens
                    // the Settings hub on Devices via the SAME `ui.openSettings(Devices)` path the
                    // rail uses, so DevicesSettingsScreen loads real `app.devices()` (GET /devices)
                    // under Xvfb. Read-only by construction — never calls addDevice/revokeDevice.
                    // Off by default; harmless in prod.
                    val devicesHook = System.getenv("SM_DEVICES") == "1"
                    if (devicesHook) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openSettings(dev.supermux.desktop.workspace.SettingsSection.Devices)
                            println("[devices] opened the Settings hub (Devices)")
                            val devices = app.devices()
                            println(
                                "[devices] devices count=${devices?.size ?: "null"} " +
                                    "names=${devices?.joinToString { it.name } ?: "(load failed)"}",
                            )
                        }
                    }

                    // Headless Proxies verification hook (desktop-parity Task 5): SM_PROXIES=1 opens
                    // the Settings hub on Proxies via the SAME ui.openSettings(Proxies) path the
                    // rail uses. Read-only — never createProxy/setProxyPublic/removeProxy.
                    val proxiesHook = System.getenv("SM_PROXIES") == "1"
                    if (proxiesHook) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openSettings(dev.supermux.desktop.workspace.SettingsSection.Proxies)
                            println("[proxies] opened the Settings hub (Proxies)")
                            val proxies = app.proxiesForSettings()
                            println(
                                "[proxies] proxies count=${proxies?.size ?: "null"} " +
                                    "domains=${proxies?.joinToString { it.domain } ?: "(load failed)"}",
                            )
                        }
                    }

                    // Headless Assistant verification hook (desktop-parity Task 5): SM_ASSISTANT=1
                    // opens Assistant (soul + curator). Read-only — never saves or runCuratorNow.
                    val assistantHook = System.getenv("SM_ASSISTANT") == "1"
                    if (assistantHook) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openSettings(dev.supermux.desktop.workspace.SettingsSection.Assistant)
                            println("[assistant] opened the Settings hub (Assistant)")
                            val pair = app.assistantLoad()
                            val curator = app.curatorSettings()
                            println(
                                "[assistant] paName=${pair?.first ?: "(load failed)"} " +
                                    "soulChars=${pair?.second?.length ?: "null"} " +
                                    "curatorEnabled=${curator?.config?.enabled ?: "(load failed)"} " +
                                    "nextRun=${curator?.nextRun ?: "—"}",
                            )
                        }
                    }

                    // Headless Voice verification hook (desktop-parity Task 5): SM_VOICE=1 opens
                    // Voice settings. Read-only — never mutates STT/TTS/cleanup/glossary.
                    val voiceHook = System.getenv("SM_VOICE") == "1"
                    if (voiceHook) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openSettings(dev.supermux.desktop.workspace.SettingsSection.Voice)
                            println("[voice] opened the Settings hub (Voice)")
                            val cfg = app.appConfig()
                            val glossary = app.fetchGlossary()
                            println(
                                "[voice] stt=${cfg?.voiceSttEngine ?: "(default)"} " +
                                    "tts=${cfg?.voiceTtsEngine ?: "(default)"} " +
                                    "cleanup=${cfg?.voiceCleanupEngine ?: "(default)"} " +
                                    "model=${cfg?.voiceCleanupModel ?: "(default)"} " +
                                    "glossaryTerms=${glossary?.size ?: "null"}",
                            )
                        }
                    }

                    // Headless Usage-panel verification hook (M4f): SM_USAGE=1 opens the Usage
                    // overlay on start via the SAME `ui.openUsage()` the File ▸ "Usage…" menu item
                    // calls, so WorkspaceRoot's own LaunchedEffect(ui.usageOpen) loads the real
                    // `app.usage()` (GET /usage) and the provider cards render under Xvfb without a
                    // menu click. Read-only by construction: this hook NEVER calls
                    // app.redeemCodexReset() — that burns a real banked Codex reset (irreversible);
                    // the redeem path stays UI-test-covered only (mirrors how M4c never auto-fired
                    // Push/Publish). Off by default; harmless in production.
                    val usageHook = System.getenv("SM_USAGE") == "1"
                    if (usageHook) {
                        LaunchedEffect(app) {
                            // Let the first WS snapshot land, same settle window as the other hooks
                            // (usage() itself doesn't need the snapshot, but this keeps the hook's
                            // timing consistent/predictable alongside the others when combined).
                            delay(3_000)
                            ui.openUsage()
                            println("[usage] opened the Usage overlay")
                        }
                    }

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

                    // Headless notification-verification hook (M5-3): SM_NOTIFY_TEST=
                    // "<session-name>" resolves the named session after the first snapshot and
                    // FORCES ui.selectedId to null (overriding any prior selection, incl. a
                    // persisted ui-state.json or SM_AUTOSELECT default) so the target is
                    // guaranteed "unviewed" — the SAME condition NotifyDecision.shouldNotify
                    // requires. It does NOT itself send anything: drive a REAL agent reply into
                    // this session from elsewhere (another live session/tool, NOT this desktop
                    // process) and watch stdout for the unconditional "[notify] session=... " line
                    // NotificationController prints right before dispatching (see this plan's Task
                    // 4 for why the OS toast itself can't be proven headlessly — only the decision
                    // + dispatch can). Off by default; harmless in production.
                    val notifyTest = System.getenv("SM_NOTIFY_TEST")?.takeIf { it.isNotBlank() }
                    if (notifyTest != null) {
                        LaunchedEffect(app) {
                            var target = app.sessions.value.firstOrNull { it.name == notifyTest }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == notifyTest }
                            }
                            val t = target
                            if (t == null) {
                                println("[notify-test] session '$notifyTest' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = null
                            println(
                                "[notify-test] ensured '$notifyTest' (${t.id}) is NOT the selected session — " +
                                    "its next agent reply should print a [notify] line",
                            )
                        }
                    }

                    WorkspaceRoot(app, ui, uiStore, launcherStore, notificationController, fleet = fleet)

                    // Unpair confirmation (File ▸ Unpair…): clears the credential store and flips
                    // back to onboarding, which disposes `app` (DisposableEffect above).
                    if (showUnpairConfirm) {
                        AlertDialog(
                            onDismissRequest = { showUnpairConfirm = false },
                            title = { Text("Unpair this device?") },
                            text = { Text("This removes the saved pairing credentials and returns to the onboarding screen. Your sessions on the broker are untouched.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    // Clear the legacy credential AND forget every fleet host
                                    // (drops each record + token, closes its socket).
                                    store.clear()
                                    hostStore.list().map { it.recordId }.forEach { fleet.forgetHost(it) }
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

            // First-run intro cinematic ("mux boot": boot log → 2×2 agent-pane split → particle
            // converge → the logo mark draws itself → fade into the app). Emitted LAST inside
            // SupermuxTheme so it stacks above the already-composed wizard/workspace — the exit
            // fade is a real reveal, not a cut. Shown once ever; SM_INTRO/SM_INTRO_FREEZE hooks
            // in the catalog at the top of this file.
            val introStore = remember { IntroStateStore() }
            var introVisible by remember {
                mutableStateOf(
                    IntroStateStore.shouldShow(
                        envIntro = System.getenv("SM_INTRO"),
                        envPairToken = System.getenv("SM_PAIR_TOKEN"),
                        store = introStore,
                    ),
                )
            }
            if (introVisible) {
                FirstRunIntroOverlay(onFinished = {
                    introVisible = false
                    if (System.getenv("SM_INTRO") != "1") runCatching { introStore.markSeen() }
                })
            }
        }
    }
}
