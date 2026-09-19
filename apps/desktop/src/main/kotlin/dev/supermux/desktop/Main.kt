package dev.supermux.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import dev.supermux.ui.widgets.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.ui.chat.MessageTts
import dev.supermux.desktop.chat.decodeImageBytes
import dev.supermux.ui.chat.AssistantMessage
import dev.supermux.ui.chat.fetchImageBytesWithPolicy
import dev.supermux.desktop.platform.prunePasteCache
import dev.supermux.desktop.editor.isMacOs
import dev.supermux.desktop.host.DesktopHostBootstrap
import dev.supermux.desktop.host.DesktopHostStores
import dev.supermux.desktop.settings.DesktopSettingsStore
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.state.cioHttpFactory
import dev.supermux.desktop.host.HostWizard
import dev.supermux.ui.intro.FirstRunIntroOverlay
import dev.supermux.ui.intro.INTRO_VERSION
import dev.supermux.ui.intro.shouldShowIntro
import dev.supermux.ui.prefs.seedIntroSeen
import dev.supermux.desktop.notify.DesktopNotifications
import dev.supermux.desktop.notify.TrayNotificationManager
import dev.supermux.desktop.shell.DesktopWindowHostController
import dev.supermux.pairing.PairingState
import dev.supermux.pairing.PairingTokenStore
import dev.supermux.ui.intro.OnboardingScreen
import dev.supermux.state.HostStore
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.Space
import dev.supermux.desktop.theme.DesktopTheme
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.widthClassForPx
import dev.supermux.desktop.ui.LocalModalPresence
import dev.supermux.desktop.ui.ModalPresence
import dev.supermux.ui.shell.SupermuxApp
import dev.supermux.ui.shell.windows.tearOutTabLive
import dev.supermux.ui.shell.windows.tearOutCanvasLive
import dev.supermux.desktop.settings.DesktopSettingsExtra
import dev.supermux.desktop.settings.DesktopSettingsSection
import dev.supermux.ui.prefs.seedLauncher
import dev.supermux.ui.prefs.seedCollapsedProjectPaths
import androidx.compose.foundation.layout.width
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.foundation.layout.height
import dev.supermux.ui.shell.windows.RegistryShellWindows
import dev.supermux.desktop.shell.DesktopStripChrome
import dev.supermux.desktop.shell.MacSidebarToggle
import dev.supermux.desktop.shell.MacTitleBarHeight
import dev.supermux.desktop.shell.macTitleBarDragRegion
import dev.supermux.desktop.shell.PersistedUiState
import dev.supermux.ui.prefs.seedShellState
import dev.supermux.ui.prefs.ShellStateSeed
import dev.supermux.ui.prefs.SIDEBAR_WIDTH_DEFAULT
import dev.supermux.ui.session.SessionListMode
import dev.supermux.ui.notify.NotificationController
import dev.supermux.desktop.shell.DetachedWorkspaceWindow
import dev.supermux.desktop.shell.LocalMacTrafficLightsInset
import dev.supermux.ui.shell.windows.extraWindowTitle
import dev.supermux.desktop.shell.LocalMacWindowChrome
import dev.supermux.desktop.shell.MacTrafficLightsWidth
import dev.supermux.desktop.shell.rememberMacWindowChrome
import dev.supermux.desktop.shell.ShellStateStore
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.windows.WindowBounds
import dev.supermux.ui.shell.windows.tearOutGroupLive
import dev.supermux.workspace.collectActiveViewIds
import dev.supermux.workspace.groupIdOf
import java.io.File
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.prefs.seedAppearance
import dev.supermux.proto.ServerFrame
import dev.supermux.state.WalkthroughSeam
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.desktop.platform.SharedDesktopTts

/** The desktop half of the walkthrough seam: `:shared`'s [WalkthroughSeam] over `:ui`'s
 *  [WalkthroughState]. Five lines, kept beside the DI that installs it (see the `HostStore`
 *  construction below) rather than in `:ui`, because the store's generic parameter is chosen
 *  per app. */
object DesktopWalkthroughSeam : WalkthroughSeam<WalkthroughState> {
    override fun create(sessionId: String) = WalkthroughState(sessionId)
    override fun apply(state: WalkthroughState, frame: ServerFrame) = state.applyServerFrame(frame)
}

private val desktopDeps: HostStoreDeps by lazy {
    HostStoreDeps(
        httpFactory = cioHttpFactory(),
        settings = DesktopSettingsStore(DesktopHostStores.defaultDir().resolve("settings.json")),
    )
}

/** Editor + chat-detail + APPEARANCE preferences, on the same store as drafts / launcher prefs. */
private val desktopUiPrefs: UiPrefs by lazy { UiPrefs(desktopDeps.settings) }

/**
 * [DesktopTokenStore] as the shared [PairingTokenStore] the `:shared` `PairingState` writes
 * through (cluster G6). The four members line up one-to-one; the interface exists only because
 * Android's store is a different type with the same shape.
 */
private fun DesktopTokenStore.asPairingStore(): PairingTokenStore = object : PairingTokenStore {
    override fun load(): String? = this@asPairingStore.load()
    override fun save(token: String) = this@asPairingStore.save(token)
    override fun loadBaseUrl(): String? = this@asPairingStore.loadBaseUrl()
    override fun saveBaseUrl(url: String) = this@asPairingStore.saveBaseUrl(url)
}

/**
 * Desktop's legacy `intro-seen` marker file (contents = the intro version), read ONCE so
 * `UiPrefs.seedIntroSeen` can drain it into `SettingsKeys.INTRO_SEEN`. Nothing else reads the
 * file again; a missing/garbage marker is simply "never seen".
 */
private fun legacyIntroSeenVersion(): Int? =
    runCatching {
        java.nio.file.Files.readString(
            DesktopTokenStore.defaultPath().parent.resolve("intro-seen"),
        ).trim().toIntOrNull()
    }.getOrNull()

private val desktopBindTts: (
    resolveEngine: suspend () -> String,
    speakRemoteStream: suspend (String, (ByteArray) -> Unit) -> Unit,
) -> Unit = { resolve, speak ->
    MessageTts.resolveEngine = resolve
    MessageTts.speakRemoteStream = speak
}

// Headless-verification env hooks (ALL off by default; for Xvfb runs with no input injection).
// Catalogued here for discoverability — some are read at their use-site rather than in main():
//   SM_PAIR_TOKEN + SM_PAIR_BASE  — seed a pairing without onboarding (both required)   [main, below]
//   SM_AUTOSELECT=1               — auto-select a session so a pane renders             [AppShell]
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
//   SM_GIT_MENU="<name>[:fetch|:pull]" — force-open the WORKSPACE header's git-badge menu;
//                                  :fetch/:pull ALSO
//                                  fire that op live (M4c, no Push/Publish member — see
//                                  GitMenuForceOp KDoc)                                   [main]
//   SM_LINKS_MENU=<session-name>  — force-open the CHAT VIEW header's session-links (proxies)
//                                  menu (M4c)                                              [main]
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
//   SM_GIT_HOSTING=1              — open the Settings hub on Git hosting (Task 4) via the SAME
//                                  ui.openSettings(GitHosting), loading real app.forgesLoad()
//                                  (GET /forge/connections, read-only). Off by default.       [main]
//   SM_SYSTEM=1                   — open the Settings hub on System via ui.openSettings(System)
//                                  on start, loading real app.updateStatus() (GET /api/update/status,
//                                  read-only) from the active host. Off by default; never calls
//                                  runUpdate/restartBroker (those kill/update the live broker).  [main]
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
//   SM_USAGE=1                    — open the Usage card popover (File ▸ "Usage…"'s SAME ui.openUsage())
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
//   SM_DIFF="<session-name>"      — select the session, then open a `mode: diff` editor VIEW in its
//                                  workspace. DiffPane fires the SAME loadDiff(fsDiff) the "View
//                                  changes" button drives (a real GET /fs/diff), so the rendered
//                                  DiffView can be screenshotted headlessly (M4g-2). Off by
//                                  default.                                     [main, AppShell]
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
//   SMX_JCEF_FORCE_ERROR=1        — force JcefState.Error (native-fallback editor, M3)  [JcefRuntime]
//   SM_EDITOR_SAVE_TEST           — drive the editor save path (M3)                      [EditorPanel]
//   SM_NOTIFY_TEST="<session-name>"  — force-unselect a session so its NEXT agent reply is
//                                  guaranteed "unviewed" (M5-3); watch stdout for the
//                                  unconditional "[notify] session=... text=..." decision+dispatch
//                                  log line NotificationController prints right before it would
//                                  raise a tray toast. Off by default; harmless in production.  [main]
//   SMX_JCEF_EXTRA_ARGS="…"       — extra CEF switches for headless CI                  [JcefRuntime]
//   SM_INTRO=1 / SM_INTRO=0       — force-show / force-suppress the first-run intro cinematic.
//                                  Default: plays once ever (intro-seen marker), and NEVER in
//                                  SM_PAIR_TOKEN-seeded runs so headless verification shots are
//                                  unaffected. A forced (SM_INTRO=1) run does NOT mark seen.  [main]
//   SM_INTRO_FREEZE=<t 0..1>      — freeze the intro timeline at <t> (no auto-advance/finish)
//                                  for deterministic phase screenshots              [FirstRunIntro]
//   SM_MD_IMAGE=<url-or-path|1>   — render a chat AssistantMessage containing a markdown image for
//                                  headless screenshot verification of inline-image layout (natural
//                                  size / shrink-only, loading placeholder, click seam). Value is
//                                  an https URL (production fetch), a local file path (decoded from
//                                  disk — never posted to a real session), or "1" for a built-in
//                                  2×2 PNG fixture. Overlay only; no broker traffic. Off by default.
//                                  [main]
fun main() {
    // Let Compose paint over the Swing interop children (the JediTerm terminals).
    // Must be set before the first Compose window: the flag is read through a
    // memoizing `lazy`, so setting it later is silently ignored.
    //
    // ⚠ This is NOT currently sufficient to stop the terminal hiding behind a
    // modal, and the reason is worth keeping: blending composites, it does not
    // re-route INPUT. On Metal a dialog does paint correctly over a live
    // terminal — verified with `renderApi=METAL` read off the live SkiaLayer —
    // but the AWT child remains topmost for hit-testing, so every click still
    // lands on JediTerm and the dialog's buttons are dead. Ahmet: "it renders
    // correctly on top of the terminal. But if I try to click on any button, it
    // doesn't work." So HeavyweightModalShield still hides the terminal, and
    // this flag is currently inert for modals.
    //
    // It stays because it is the half of the problem that IS solved, at no cost
    // measured on Metal, and because the remaining half is about input routing
    // rather than painting. The moment input is sorted, the terminal can stop
    // hiding by flipping one argument in JediTermTerminalView — read the note
    // there first.
    //
    // Compose 1.11.1 gates blending on the render API — Direct3D and Metal only,
    // never OpenGL — so it is a no-op on Linux by construction. It also does not
    // rescue JCEF at all (a native NSView: the dialog comes out sheared off at
    // the page's top edge). Still marked experimental by JetBrains.
    System.setProperty("compose.interop.blending", "true")

    val store = DesktopTokenStore()
    // Reclaim aged clipboard-paste PNGs under <config>/paste-cache/ (app-owned; never /tmp).
    // Individual files are never deleted by path during composer lifecycle — only this age prune.
    runCatching { prunePasteCache() }
        .onFailure { println("[Main] paste-cache prune failed (continuing): $it") }
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

    try {
        application {
        // M5-3: TrayState + NotificationController are hoisted ABOVE Window because Tray(...) is
        // an ApplicationScope-receiver composable (confirmed via javap: Tray_desktopKt.Tray's
        // first parameter is ApplicationScope) — it can only be called directly inside
        // `application { }`'s body, never nested inside Window's content (whose implicit
        // receiver is FrameWindowScope). windowState is hoisted alongside it — a plain val, not
        // receiver-bound — purely so the tray icon's click handler can un-minimize the SAME
        // WindowState instance passed to Window below.
        val windowState = rememberWindowState(width = 1440.dp, height = 900.dp)
        var shuttingDown by remember { mutableStateOf(false) }
        LaunchedEffect(shuttingDown) {
            if (shuttingDown) {
                // First let the Window content recompose empty, detaching every SwingPanel while
                // Compose's Skia layer is still live. Then close the native window.
                delay(100)
                exitApplication()
            }
        }
        val trayState = rememberTrayState()
        // Published once pairing completes (below, inside Window's content) so the tray icon's
        // click handler can select a session on the live ShellUiState. null before pairing
        // and after unpair, when there is no shell to select into — the click handler
        // no-ops in that case (see onAction below).
        var pairedUi by remember { mutableStateOf<ShellUiState?>(null) }
        val uiStore = remember { ShellStateStore() }
        val persistedUi = remember { uiStore.load() }
        // Appearance is NOT in ui-state.json any more (cluster E7): it lives in the shared settings
        // store under `SettingsKeys.APPEARANCE`, which is also what the shared Appearance screen
        // writes — so the sidebar's theme toggle and Settings are one value, not two.
        // `ui.appearance` stays the in-memory copy every composable reads.
        //
        // Seeded SYNCHRONOUSLY here, before the first composition: `ShellUiState.appearance`
        // defaults to DARK, so resolving the stored value in an effect would show a LIGHT user one
        // dark frame on every launch. `DesktopSettingsStore` already holds its map in an eager
        // `StateFlow`, so this `runBlocking` never actually waits on IO; the one-time write it can
        // do is the ui-state.json migration.
        val seededAppearance = remember {
            runBlocking {
                desktopUiPrefs.seedAppearance(
                    default = AppearanceMode.DARK,
                    legacy = persistedUi.appearance
                        ?.let { raw -> runCatching { AppearanceMode.valueOf(raw) }.getOrNull() },
                )
            }
        }
        val seededTextScale = remember { runBlocking { desktopUiPrefs.textScale.first() } }
        // The extra OS windows, and the shared shell's view of them (cluster G8).
        val desktopWindows = remember { RegistryShellWindows() }
        // Cluster G8: the sidebar chrome + the selection moved out of `ui-state.json` into the
        // shared settings store (`SettingsKeys.SHELL_*`), which is what Android reads too. The old
        // file is drained ONCE here and then only carries the window bounds. Synchronous for the
        // same reason the appearance seed above is: an asynchronous read would paint the first
        // frames with the sidebar at the wrong width and no session selected.
        val shellSeed = remember {
            runCatching {
                runBlocking {
                    desktopUiPrefs.seedShellState(
                        legacySidebarCollapsed = persistedUi.layout?.sidebarCollapsed,
                        legacySidebarWidthDp = persistedUi.layout?.sidebarWidthDp,
                        legacySelectedSession = persistedUi.selectedId,
                    )
                }
            }.onFailure { println("[Main] shell-state seed failed (keeping ui-state.json): $it") }
        }
        // A THROWN seed leaves the legacy file as the only copy, so fall back to its values in
        // memory and keep writing them through (see the `ui-state.json` writer below).
        val seededShell = shellSeed.getOrElse {
            ShellStateSeed(
                sidebarCollapsed = persistedUi.layout?.sidebarCollapsed ?: false,
                sidebarWidthDp = persistedUi.layout?.sidebarWidthDp ?: SIDEBAR_WIDTH_DEFAULT,
                selectedSession = persistedUi.selectedId,
            )
        }
        // The launcher pair and the collapsed project groups (cluster F1), drained from this host's
        // old files before anything reads them — the seed must win the race against the launcher's
        // first `loadPrefs()` and the sidebar's first frame.
        val launcherStoreForSeed = remember { dev.supermux.desktop.session.LauncherStore() }
        val collapsedSeed = remember {
            runBlocking {
                runCatching {
                    desktopUiPrefs.seedLauncher(
                        launcherStoreForSeed.loadPrefs(),
                        launcherStoreForSeed.loadDraft(),
                    )
                }
                runCatching {
                    desktopUiPrefs.seedCollapsedProjectPaths(
                        persistedUi.layout?.collapsedProjectPaths?.toSet().orEmpty(),
                    )
                }
            }
        }
        val seededCollapsedPaths = collapsedSeed.getOrElse {
            persistedUi.layout?.collapsedProjectPaths?.toSet().orEmpty()
        }
        /** True once BOTH one-way drains actually landed — see the writer below. */
        val legacyDrained = remember { shellSeed.isSuccess && collapsedSeed.isSuccess }
        val ui = remember {
            ShellUiState().apply {
                windows = desktopWindows
                sidebarCollapsed = seededShell.sidebarCollapsed
                setSidebarWidth(seededShell.sidebarWidthDp.dp)
                selectedId = seededShell.selectedSession
                collapsedProjectPaths = seededCollapsedPaths
                appearance = seededAppearance
            }
        }
        LaunchedEffect(Unit) { desktopWindows.pending = persistedUi.windows }
        // Debounced `ui-state.json` write — the DETACHED WINDOW BOUNDS only; every other field it
        // used to carry now lives in the shared settings store (see `seedShellState` above), which
        // the shell itself writes.
        //
        // ...UNLESS a drain THREW, in which case this file is still the only copy of the user's
        // sidebar, selection and collapsed groups: carry those fields forward UNTOUCHED rather than
        // erasing them on the first window move. Same rule cluster F1 used for the collapsed paths.
        LaunchedEffect(uiStore, legacyDrained) {
            snapshotFlow { desktopWindows.persistedExtras() }
                .collectLatest { extras ->
                    delay(500)
                    withContext(Dispatchers.IO) {
                        uiStore.save(
                            if (legacyDrained) {
                                PersistedUiState(windows = extras)
                            } else {
                                persistedUi.copy(windows = extras)
                            },
                        )
                    }
                }
        }
        // Releasing an extra window's claim is bound HERE, not in `AppShell`: the windows are owned
        // by this scope and can outlive a composed shell (unpair with a detached window still open).
        DisposableEffect(ui) {
            DesktopWindowHostController.bindRelease { hostId -> desktopWindows.registry.unclaim(hostId) }
            onDispose { }
        }
        // Tearing a pane out needs the LIVE `panesBind` (only this scope holds it), so the window
        // owner binds the verbs into the `Platform.windows` seam. Was `AppShell`'s job until G8
        // moved the shell into `:ui`, which knows no `panesBind` of its own to tear from.
        DisposableEffect(ui) {
            DesktopWindowHostController.bindTearOut(
                tearOutTab = { viewId ->
                    val bind = ui.panesBind ?: return@bindTearOut
                    val layoutSync = bind.ws.layoutSync
                    tearOutTabLive(
                        desktopWindows.registry, layoutSync.tree, viewId, bind.current.id,
                    ) { next ->
                        layoutSync.edit { next }
                        layoutSync.tree
                    }
                    Unit
                },
                tearOutCanvas = {
                    val bind = ui.panesBind ?: return@bindTearOut
                    tearOutCanvasLive(desktopWindows.registry, bind.current.id)
                    Unit
                },
            )
            onDispose { DesktopWindowHostController.unbindTearOut() }
        }

        // ...and mirrored from here on, so a change made in Settings repaints the shell.
        LaunchedEffect(Unit) {
            desktopUiPrefs.appearance(AppearanceMode.DARK).collect { ui.appearance = it }
        }
        val appTextScale by desktopUiPrefs.textScale.collectAsState(seededTextScale)
        val themeScope = rememberCoroutineScope()
        // Cluster G1: the tray manager is installed INTO the `Platform.notifications` seam, so the
        // shell's notification controller and any shared caller raise the same toast.
        val notificationController = remember {
            DesktopNotifications.install(TrayNotificationManager(trayState))
            NotificationController(DesktopNotifications)
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
            onCloseRequest = { shuttingDown = true },
            // Empty on macOS: with the transparent/full-size-content title bar below, a non-empty
            // title still paints centred over our own UI on runtimes that ignore
            // `apple.awt.windowTitleVisible`. Other platforms keep the normal caption text.
            title = if (isMacOs()) "" else "supermux",
            state = windowState,
        ) {
            if (shuttingDown) return@Window

            // macOS chrome: no title bar strip — the window content runs edge-to-edge to the top
            // and only the traffic lights (close / minimize / zoom) float over it. Preferred route
            // is the JBR custom title bar (rememberMacWindowChrome): same edge-to-edge look, but
            // the title-bar band stops hijacking drags over Compose content (tabs!) — the window
            // only drags from areas opted in via macTitleBarDragRegion (see MacWindowChrome.kt).
            // On a runtime without that API it returns null and we fall back to the
            // plain client properties — AWT's route to NSWindow's FullSizeContentView +
            // titlebarAppearsTransparent + hidden title — where the band drag (and the tab-drag
            // collision) remains, since stock AWT has no hit-test hook. AppShell leaves a left
            // inset under the traffic lights for the sidebar toggle (see MacChrome.kt).
            // No-ops off macOS, but gated anyway to keep it obvious.
            val macChrome = if (isMacOs()) rememberMacWindowChrome(window) else null
            if (isMacOs() && macChrome?.titleBar == null) {
                LaunchedEffect(window) {
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
            }
            // Paired flag + shell UI state are hoisted to the Window (FrameWindowScope) so the
            // native MenuBar — which must be called on this scope, not inside a nested @Composable —
            // can reach the same sidebar state/selection the shortcuts drive.
            // Paired once the fleet holds a host (legacy single-host users were migrated to
            // PairedHost[0] above; onboarding seeds it via the same migration on success).
            var paired by remember { mutableStateOf(hostStore.list().isNotEmpty()) }
            // M5-3: publish this pairing's ShellUiState up to the tray icon's onAction
            // handler (declared above, outside Window) so a click can select the last-notified
            // session. Cleared on dispose (unpair / window teardown) so a stale ui never lingers.
            DisposableEffect(ui) {
                pairedUi = ui
                onDispose { pairedUi = null }
            }
            var showUnpairConfirm by remember { mutableStateOf(false) }

            // Menu bar (paired only). DEDUPE DECISION: the View toggle items are clickable-only —
            // they carry NO KeyShortcut, because the in-app `Modifier.shellShortcuts` already
            // owns Ctrl/Cmd+B/E/T/D; registering the same accelerator on the menu would risk a
            // double-toggle (menu action + the key event still bubbling to the modifier). File ▸
            // New Session keeps its conventional Ctrl+N accelerator too — it and the in-app shortcut
            // both just flip `ui.launcherOpen`, which is idempotent, so a double-fire (menu action +
            // the key event still bubbling to shellShortcuts) is harmless.
            if (paired) {
                MenuBar {
                    Menu("File", mnemonic = 'F') {
                        Item("New Session", shortcut = KeyShortcut(Key.N, ctrl = true)) {
                            ui.openLauncher()
                        }
                        Item("Move workspace to New Window") {
                            // Through the seam (cluster G1); AppShell binds the live registry into it.
                            DesktopWindowHostController.tearOutCanvas()
                        }
                        Item("Move group to New Window") {
                            val bind = ui.panesBind
                            if (bind != null) {
                                val tree = bind.ws.layoutSync.tree
                                val viewId = collectActiveViewIds(tree).firstOrNull()
                                val gid = viewId?.let { groupIdOf(tree, it) }
                                if (gid != null) {
                                    tearOutGroupLive(desktopWindows.registry, tree, gid, bind.current.id)
                                }
                            }
                        }
                        Item("Archived…") {
                            ui.openArchived()
                        }
                        Item("Displays…") {
                            ui.openDisplays()
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
                    Menu("Edit", mnemonic = 'E') {
                        // Same paste-image path as Ctrl/Cmd+V / right-click in the composer.
                        // Accelerator label documents the chord; the composer's onPreviewKeyEvent
                        // owns the live key handling when the field is focused.
                        Item("Paste image", shortcut = KeyShortcut(Key.V, ctrl = true)) {
                            ui.requestPasteImage()
                        }
                    }
                    Menu("View", mnemonic = 'V') {
                        // Only the sidebar is left: Editor/Terminal/Display were toggles for the
                        // old shell's four fixed panes. A workspace has no fixed panes — views are
                        // created, split and closed on its own layout tree, from the tab strip's
                        // "+" — so there is nothing here to check on or off.
                        CheckboxItem("Show Sidebar", checked = !ui.sidebarCollapsed) {
                            ui.sidebarCollapsed = !ui.sidebarCollapsed
                        }
                    }
                }
            }

            // Appearance lives on [ui] so the sidebar toggle, theme, and ui-state.json share one source.
            // One presence for the whole window: a dialog opened anywhere must hide
            // EVERY heavyweight AWT child (JediTerm, JCEF), not just the one in the
            // pane that owns it — Compose cannot paint over any of them, and a split
            // can show a terminal next to the pane the dialog came from.
            val modalPresence = remember { ModalPresence() }
            CompositionLocalProvider(LocalModalPresence provides modalPresence) {
            ProvideDesktopAdaptiveLocals {
            DesktopTheme(appearance = ui.appearance, textScale = appTextScale, uiPrefs = desktopUiPrefs) {
              // Edge-to-edge fill. On macOS the traffic lights float over the top-left; AppShell
              // places the sidebar toggle next to them and pads only the sidebar body under that
              // band — no full-window dead strip across the title bar.
              Box(
                  Modifier
                      .fillMaxSize()
                      .background(MaterialTheme.colorScheme.background),
              ) {
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
                        val pairing = remember {
                            PairingState(store.asPairingStore(), scope, httpFactory = { cioHttpFactory()(null) })
                        }
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
                    // The multi-host fleet: one connection per paired host, merged into AppShell.
                    val fleet = remember {
                        FleetStore(
                            hostStore,
                            scope,
                            desktopDeps,
                            appFactory = { url, token, onConn ->
                                HostStore(
                                    url, token, scope, desktopDeps,
                                    onConnectionChange = onConn,
                                    walkthroughSeam = DesktopWalkthroughSeam,
                                    bindTts = desktopBindTts,
                                )
                            },
                            localHostDisplayName = { DesktopHostBootstrap.defaultHostName() },
                        )
                    }
                    DisposableEffect(Unit) { onDispose { fleet.close() } }
                    // The active host's app backs the single-host headless hooks below and is
                    // AppShell's fallback; AppShell itself routes through `fleet`. Non-null
                    // because `paired` ⟹ the store holds a host ⟹ FleetStore opened its connection.
                    val app = remember(fleet) { fleet.activeApp() } ?: return@Box

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
                    // handler chain a chat file-path tap uses (ShellUiState.externalOpen →
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
                            // No pane to flip on: in a workspace, opening the file IS the pane
                            // (AppShell routes externalOpen through WorkspaceFileOpener).
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
                    // becomes the active tab and the JCEF engine reports ready; no further driving is
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
                            // Open a diff VIEW in the workspace on screen. The old shell flipped a
                            // fixed editor pane and let it switch itself to diff mode; a workspace
                            // has no fixed pane, and `mode: diff` IS the pane (ViewHost → DiffPane).
                            ui.forceWorkspaceView = "editor" to buildJsonObject {
                                put("mode", JsonPrimitive("diff"))
                            }
                            println("[diff] selected ${t.name} (${t.id}) — requested a diff view in its workspace")
                        }
                    }

                    // Headless launcher-verification hook (M4a): SM_LAUNCH_TEST=
                    // "<workdir>|<agent>|<message>[|<attachPath>]" drives the SAME onSubmit chain the
                    // launcher UI uses — after settle it flips the launcher overlay OPEN, then (unless
                    // SM_LAUNCH_PAUSE_MS holds it open first, for a screenshot of the composer card /
                    // a restored draft) exercises the real spawn→first-message→uploads path:
                    // createSessionWithFirstMessage(workdir, agent, model=null, reasoning=null, message,
                    // staged, worktree=false, baseBranch=null) — the broker delivers the first turn —
                    // → select the new session → close the overlay. PIPE-delimited (not colon) so the
                    // message may contain colons/spaces; an optional 4th field stages one real file
                    // (FileChunkSource) that uploads before the spawn. A BLANK message opens the launcher
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
                                listOf(dev.supermux.state.StagedUpload(
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
                                ui.closeLauncher()
                                return@LaunchedEffect
                            }
                            ui.selectedId = id
                            ui.closeLauncher()
                            println("[launch] spawned session $id in '$workdir' (agent=$agent, staged=${staged.size}); first message sent")
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
                    // GitMenuForceOp's KDoc in ui/shell/SessionHeaderMenus.kt). Harmless in production (unset
                    // by default).
                    val gitMenuTest = System.getenv("SM_GIT_MENU")?.takeIf { it.isNotBlank() }
                    if (gitMenuTest != null) {
                        LaunchedEffect(app) {
                            val parts = gitMenuTest.split(":", limit = 2)
                            val name = parts[0]
                            val op = when (parts.getOrNull(1)?.trim()?.lowercase()) {
                                "fetch" -> dev.supermux.ui.shell.GitMenuForceOp.FETCH
                                "pull" -> dev.supermux.ui.shell.GitMenuForceOp.PULL
                                null, "" -> dev.supermux.ui.shell.GitMenuForceOp.OPEN
                                else -> {
                                    println("[gitmenu] unknown SM_GIT_MENU suffix '${parts[1]}' — falling back to open-only")
                                    dev.supermux.ui.shell.GitMenuForceOp.OPEN
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

                    // Headless chat-attach-verification hook (M4d): SM_CHAT_ATTACH=
                    // "<session-name>|<file-path>|<text>" resolves the named session after the first
                    // snapshot, SELECTS it, and hands (filePath, text) to the matching ChatPanel via
                    // ShellUiState.externalAttach — the shared Composer's LaunchedEffect(externalAttach)
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
                            // Resolve the path HERE: `:ui`'s composer takes an upload-ready
                            // PickedFile (no java.io.File in commonMain), and a path that is not a
                            // file becomes a null request the composer consumes without staging.
                            val attachFile = java.io.File(filePath).takeIf { it.isFile }
                            ui.externalAttach = t.id to dev.supermux.ui.chat.ComposerExternalAttach(
                                file = attachFile?.let {
                                    dev.supermux.ui.platform.PickedFile(
                                        name = it.name,
                                        mime = dev.supermux.desktop.platform.probeMime(it),
                                        source = dev.supermux.desktop.upload.FileChunkSource(it),
                                    )
                                },
                                text = text,
                            )
                            if (attachFile == null) println("[chatattach] path is not a file: $filePath")
                            println("[chatattach] requested attach '$filePath' + send for ${t.name} (${t.id})")
                        }
                    }

                    // Headless dictation-verification hook (M5-1): SM_DICTATE=
                    // "<session-name>|<wav-path>" resolves the named session after the first
                    // snapshot, SELECTS it, and hands <wav-path> to the matching ChatPanel via
                    // ShellUiState.externalDictate — the shared Composer's LaunchedEffect(externalDictate)
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
                            // Read the WAV here — `:ui` has no file system.
                            val wavFile = java.io.File(wavPath).takeIf { it.isFile }
                            ui.externalDictate = t.id to dev.supermux.ui.chat.ComposerExternalDictate(
                                bytes = wavFile?.readBytes(),
                                filename = wavFile?.name ?: "dictation.wav",
                            )
                            if (wavFile == null) println("[dictate] path is not a file: $wavPath")
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
                            val existing = app.listDisplays().firstOrNull { it.sessionName == t.name && it.status == "running" }
                            if (existing != null) {
                                println("[display] reusing existing running display ${existing.id} for ${t.name}")
                            } else {
                                println("[display] no running display for '${t.name}' — starting one (real Xvfb+VNC on the broker host)")
                                val started = app.startDisplay(t.name)
                                println("[display] startDisplay result: $started")
                            }
                            // Then open a display VIEW on that stream — a workspace has no fixed
                            // display pane to flip on, and a `display` view names its stream by id.
                            val streamId = app.listDisplays()
                                .firstOrNull { it.sessionName == t.name && it.status == "running" }?.id
                            if (streamId == null) {
                                println("[display] no running stream for '${t.name}' after start — no view opened")
                            } else {
                                ui.forceWorkspaceView = "display" to buildJsonObject {
                                    put("displayId", JsonPrimitive(streamId))
                                }
                            }
                        }
                    }

                    // Headless Archived-sessions verification hook (M4e): SM_ARCHIVED=1 opens the
                    // Archived overlay on start via the SAME `ui.openArchived()` the File ▸
                    // "Archived…" menu item calls, so `AppShell`'s own LaunchedEffect(ui.archivedOpen)
                    // loads the real `app.archived()` list and the screen renders under Xvfb without a
                    // menu click. SM_ARCHIVED_OPEN=<archived-session-name> ALSO opens the overlay, then
                    // resolves <name> against a fresh `app.archived()` fetch (polled — a separate fetch
                    // from AppShell's own, to avoid racing it) and seeds `ui.forceArchivedOpenFor`
                    // with its id, so that row's read-only ArchivedChatView (transcript, no composer)
                    // renders with no click. SM_ARCHIVED_RESUME=<archived-session-name> instead drives the
                    // SAME resume path `ArchivedScreen`'s onResume callback uses in AppShell: a
                    // fire-and-forget `app.resume(id)` immediately followed by closing the overlay
                    // (`ui.goBack()`) — the resumed session then arrives back in the live
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
                            // is a separate call from AppShell's own list load).
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
                                    ui.goBack()
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
                            ui.openSettings(dev.supermux.ui.nav.SettingsSection.Agents)
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
                            ui.openSettings(dev.supermux.ui.nav.SettingsSection.Devices)
                            println("[devices] opened the Settings hub (Devices)")
                            val devices = app.devices()
                            println(
                                "[devices] devices count=${devices?.size ?: "null"} " +
                                    "names=${devices?.joinToString { it.name } ?: "(load failed)"}",
                            )
                        }
                    }

                    // Headless Git-hosting verification hook (desktop-parity Task 4):
                    // SM_GIT_HOSTING=1 opens the Settings hub on the Git hosting section via the
                    // SAME ui.openSettings(GitHosting) path, then loads real app.forgesLoad()
                    // (GET /forge/connections) under Xvfb. Read-only by construction — never
                    // add/import/remove. Off by default; harmless in production.
                    val gitHostingHook = System.getenv("SM_GIT_HOSTING") == "1"
                    if (gitHostingHook) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openSettings(dev.supermux.ui.nav.SettingsSection.GitHosting)
                            println("[git-hosting] opened the Settings hub (Git hosting)")
                            val forges = app.forgesLoad()
                            val conns = forges?.connections.orEmpty()
                            val cli = forges?.cli
                            println(
                                "[git-hosting] forgesLoad connections=${conns.size} " +
                                    "accounts=${conns.joinToString { "${it.kind}:@${it.account.login}" }.ifEmpty { "(none)" }} " +
                                    "cli.gh=${cli?.github?.available == true} cli.glab=${cli?.gitlab?.available == true}",
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
                            ui.openSettings(dev.supermux.ui.nav.SettingsSection.Proxies)
                            println("[proxies] opened the Settings hub (Proxies)")
                            val proxies = app.proxiesForSettings()
                            println(
                                "[proxies] proxies count=${proxies?.size ?: "null"} " +
                                    "domains=${proxies?.joinToString { it.domain } ?: "(load failed)"}",
                            )
                        }
                    }

                    // Headless System verification hook (desktop-parity Task 3): SM_SYSTEM=1 opens
                    // the Settings hub on System via ui.openSettings(System) so SystemSettingsScreen
                    // loads real `app.updateStatus()` (GET /api/update/status) under Xvfb.
                    // Read-only by construction — never calls runUpdate/restartBroker (those mutate
                    // the live broker). Distinct from File ▸ "Check for Updates…" (desktop app).
                    // Off by default; harmless in prod.
                    val systemHook = System.getenv("SM_SYSTEM") == "1"
                    if (systemHook) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openSettings(dev.supermux.ui.nav.SettingsSection.System)
                            println("[system] opened the Settings hub (System)")
                            val st = app.updateStatus()
                            println(
                                "[system] updateStatus current=${st?.current ?: "null"} " +
                                    "latest=${st?.latest ?: "-"} available=${st?.updateAvailable} " +
                                    "mode=${st?.mode ?: "-"} state=${st?.state ?: "-"} " +
                                    "commit=${st?.commit?.take(8) ?: "-"} " +
                                    "lastChecked=${st?.lastChecked ?: "-"}",
                            )
                        }
                    }

                    // Headless Assistant verification hook (desktop-parity Task 5): SM_ASSISTANT=1
                    // opens Assistant (Identity: soul). Read-only — never saves.
                    val assistantHook = System.getenv("SM_ASSISTANT") == "1"
                    if (assistantHook) {
                        LaunchedEffect(app) {
                            delay(3_000)
                            ui.openSettings(dev.supermux.ui.nav.SettingsSection.Assistant)
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
                            ui.openSettings(dev.supermux.ui.nav.SettingsSection.Voice)
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
                    // calls, so AppShell's own LaunchedEffect(ui.usageOpen) loads the real
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
                            println("[usage] opened the Usage card popover")
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

                    // macChrome (JBR title-bar hit test) scoped to the shell only: the drag-region
                    // modifiers inside AppShell resolve it via LocalMacWindowChrome; overlays and
                    // onboarding have no chrome in the title-bar band. Null provider = no-op.
                    CompositionLocalProvider(
                        LocalMacWindowChrome provides macChrome?.regions,
                        LocalMacTrafficLightsInset provides (
                            macChrome?.trafficLightsInset ?: MacTrafficLightsWidth
                        ),
                    ) {
                        SupermuxApp(
                            fleet = fleet,
                            ui = ui,
                            notify = notificationController,
                            appForeground = LocalWindowInfo.current.isWindowFocused,
                            // Desktop's sidebar lists WORKSPACES; Android's lists the fleet's
                            // sessions. The one genuine per-host choice left in the shell.
                            sessionListMode = SessionListMode.Workspaces,
                            homeFallback = System.getProperty("user.home").orEmpty(),
                            stripChrome = DesktopStripChrome,
                            // macOS: no full-window dead strip under the transparent title bar.
                            // Detail content runs to the top edge; only the sidebar body is padded
                            // under the traffic-light band.
                            sidebarTopPad = if (isMacOs()) MacTitleBarHeight else 0.dp,
                            sidebarChrome = { sidebarWidth, collapsed ->
                                if (isMacOs() && !collapsed) {
                                    // Native window-drag handle: the empty sidebar band under the
                                    // traffic lights. Layout-only Box (draws nothing, no pointer
                                    // input); the toggle punches itself out.
                                    Box(
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .width(sidebarWidth)
                                            .height(MacTitleBarHeight)
                                            .macTitleBarDragRegion("sidebar-band"),
                                    )
                                    MacSidebarToggle(
                                        onCollapse = { ui.sidebarCollapsed = true },
                                        modifier = Modifier.align(Alignment.TopStart).zIndex(30f),
                                    )
                                }
                            },
                            // Desktop reopens on the session it was last showing.
                            persistSelection = true,
                            // Dispose CAN BE the window closing, which cancels the shell's scope
                            // before a launched write runs. This one blocks until the draft is on
                            // disk — and swallows a failure (disk full, prefs locked mid-shutdown):
                            // a dying window must not throw out of onDispose over a lost draft.
                            onLauncherDraftFlush = { draft ->
                                runCatching { runBlocking { desktopUiPrefs.putLauncherDraft(draft) } }
                                Unit
                            },
                            autoSelect = System.getenv("SM_AUTOSELECT") == "1",
                            autoSelectName = System.getenv("SM_SMOKE_SEND")
                                ?.substringBefore(':')?.takeIf { it.isNotBlank() },
                            defaultDeviceName = remember {
                                runCatching { java.net.InetAddress.getLocalHost().hostName }
                                    .getOrNull()?.ifBlank { null } ?: "Desktop host"
                            },
                            settingsExtra = { extra, scope -> DesktopSettingsExtra(extra, scope) },
                            settingsSection = { section, scope ->
                                DesktopSettingsSection(section, scope, fleet.activeApp() ?: app)
                            },
                            appearance = ui.appearance,
                            // The toggle writes the SAME stored value the Appearance screen does
                            // (the collector above mirrors it back onto `ui.appearance`), so the
                            // two never drift apart.
                            onToggleTheme = {
                                val next = if (ui.appearance == AppearanceMode.DARK) {
                                    AppearanceMode.LIGHT
                                } else {
                                    AppearanceMode.DARK
                                }
                                ui.appearance = next
                                themeScope.launch { desktopUiPrefs.putAppearance(next) }
                            },
                        )
                    }

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
            }
            } // ProvideDesktopAdaptiveLocals

            // First-run intro cinematic ("mux boot": boot log → 2×2 agent-pane split → particle
            // converge → the logo mark draws itself → fade into the app). Emitted LAST inside
            // SupermuxTheme so it stacks above the already-composed wizard/shell — the exit
            // fade is a real reveal, not a cut. Shown once ever; SM_INTRO/SM_INTRO_FREEZE hooks
            // in the catalog at the top of this file.
            //
            // The seen flag now lives on `SettingsKeys.INTRO_SEEN` (cluster G6). It is seeded
            // from the legacy `intro-seen` marker file and read SYNCHRONOUSLY, before the first
            // frame — an async read would start the cinematic over an app someone has used for
            // months, one frame before the stored value landed. `DesktopSettingsStore` holds its
            // map in an eager StateFlow, so this never actually waits on IO.
            val introSeen = remember {
                runBlocking { desktopUiPrefs.seedIntroSeen(legacyIntroSeenVersion(), INTRO_VERSION) }
            }
            var introVisible by remember {
                mutableStateOf(
                    shouldShowIntro(
                        envIntro = System.getenv("SM_INTRO"),
                        envPairToken = System.getenv("SM_PAIR_TOKEN"),
                        seen = introSeen,
                    ),
                )
            }
            if (introVisible) {
                FirstRunIntroOverlay(
                    onFinished = {
                        introVisible = false
                        // A forced (SM_INTRO=1) run never consumes a real user's one viewing.
                        // Written SYNCHRONOUSLY, exactly as the marker file was: launching it on
                        // a scope inside this `if` would race the overlay leaving composition
                        // (the scope is cancelled the moment `introVisible` flips). Neither
                        // `DesktopSettingsStore.putString` nor the old `Files.writeString`
                        // actually suspends, so this is the same one small write in the same
                        // place.
                        if (System.getenv("SM_INTRO") != "1") {
                            runCatching { runBlocking { desktopUiPrefs.putIntroSeen(INTRO_VERSION) } }
                        }
                    },
                    freezeAt = System.getenv("SM_INTRO_FREEZE")?.toFloatOrNull(),
                )
            }

            // Headless inline-image layout verification (SM_MD_IMAGE) — see catalogue above.
            val mdImageSrc = System.getenv("SM_MD_IMAGE")?.takeIf { it.isNotBlank() }
            if (mdImageSrc != null) {
                MdImageVerifyOverlay(source = mdImageSrc)
            }
        }

        // Extra claimed layout windows. Close unclaims only — never exitApplication.
        // Each extra uses the bind for ITS workspace so switching sessions does not
        // dispose pop-outs of another workspace.
        for (host in desktopWindows.registry.extras()) {
            // Always compose the Window while the claim exists. Gating on panesBindFor
            // skipped a frame on workspace switch, Compose disposed the Window, and
            // onCloseRequest unclaimed it — the pop-out stayed gone.
            val extraBind = ui.panesBindFor(host.workspaceId)
            key(host.id) {
                val extraState = rememberWindowState(
                    position = WindowPosition(host.bounds.x.dp, host.bounds.y.dp),
                    width = host.bounds.width.dp.coerceAtLeast(200.dp),
                    height = host.bounds.height.dp.coerceAtLeast(200.dp),
                )
                LaunchedEffect(host.id) {
                    snapshotFlow {
                        extraState.position to extraState.size
                    }.collect { (pos, size) ->
                        if (pos is WindowPosition.Absolute) {
                            desktopWindows.registry.updateBounds(
                                host.id,
                                WindowBounds(
                                    x = pos.x.value,
                                    y = pos.y.value,
                                    width = size.width.value,
                                    height = size.height.value,
                                ),
                            )
                        }
                    }
                }
                Window(
                    onCloseRequest = { DesktopWindowHostController.release(host.id) },
                    title = extraBind?.let {
                        extraWindowTitle(
                            it.current.name,
                            ui.windows.layoutFor(host.id, it.ws.layoutSync.tree),
                            it.ws.viewsById,
                        )
                    } ?: "supermux",
                    state = extraState,
                ) {
                    val extraModal = remember { ModalPresence() }
                    CompositionLocalProvider(LocalModalPresence provides extraModal) {
                        ProvideDesktopAdaptiveLocals {
                            DesktopTheme(appearance = ui.appearance, textScale = appTextScale, uiPrefs = desktopUiPrefs) {
                                if (extraBind != null) {
                                    DetachedWorkspaceWindow(host, extraBind, ui)
                                }
                            }
                        }
                    }
                }
            }
        }
        }
        }
    } finally {
        // No-op if the editor never started. Runs after every Compose window/interoperability child
        // has been disposed, but before JVM shutdown, so Chromium helper processes exit cleanly.
        dev.supermux.desktop.editor.DesktopEditorEngineFactory.shared.dispose()
        dev.supermux.desktop.editor.JcefRuntime.dispose()
        // Read-aloud is a process singleton (see SharedDesktopTts) and owns a child `say`/`ffplay`
        // process; release it here so a quit mid-sentence does not outlive the window.
        runCatching { MessageTts.stop(SharedDesktopTts) }
        runCatching { SharedDesktopTts.shutdown() }
    }
}

/**
 * Full-window overlay that paints one [AssistantMessage] containing a markdown image so inline-
 * image layout (natural size, max-height clamp, loading placeholder) can be screenshotted under
 * Xvfb without posting into a real session. [source] is `1` (built-in 2×2 PNG), an https URL, or a
 * local filesystem path.
 */
@Composable
private fun MdImageVerifyOverlay(source: String) {
    val tinyPngHex =
        "89504e470d0a1a0a0000000d4948445200000002000000020802000000fdd49a73" +
            "0000001049444154789c63f8cfc000440c100a001fee03fd8b5f14d40000000049454e44ae426082"
    val tinyPng = remember {
        ByteArray(tinyPngHex.length / 2) { i ->
            tinyPngHex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
    val isHttps = source.startsWith("https://", ignoreCase = true)
    val localFile = if (!isHttps && source != "1") File(source).takeIf { it.isFile } else null
    val localBytes: ByteArray? = when {
        source == "1" -> tinyPng
        localFile != null -> runCatching { localFile.readBytes() }.getOrNull()
        else -> null
    }
    // Synthetic https so MarkdownImage takes the inline path; [loadImage] serves local bytes.
    val displayUrl = if (isHttps) source else "https://md-image-verify.local/fixture.png"
    val md = "![md-image-verify]($displayUrl)"
    val loadImage: suspend (String) -> ImageBitmap? = when {
        localBytes != null -> ({ decodeImageBytes(localBytes) })
        isHttps -> ({ url -> fetchImageBytesWithPolicy(url)?.let { decodeImageBytes(it) } })
        else -> ({ null })
    }
    LaunchedEffect(source) {
        when {
            localBytes != null ->
                println("[md-image] SM_MD_IMAGE overlay source=$source localBytes=${localBytes.size}")
            isHttps ->
                println("[md-image] SM_MD_IMAGE overlay source=$source https production fetch")
            else ->
                println("[md-image] SM_MD_IMAGE bad source (need 1, https URL, or readable file): $source")
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .testTag("sm_md_image_overlay"),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 860.dp)
                .fillMaxWidth()
                .padding(Space.lg)
                .testTag("sm_md_image_message"),
        ) {
            Text(
                "SM_MD_IMAGE verification",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(bottom = Space.sm),
            )
            // Same chat stack: AssistantMessage → MarkdownBody → MarkdownImage (with seams).
            AssistantMessage(
                text = md,
                onOpenUrl = { /* never launch browser under Xvfb */ },
                loadImage = loadImage,
            )
        }
    }
}

/**
 * Per-window adaptive locals (task A3). Desktop is always [InputMode.Pointer]; the width class
 * comes from the window's own container size in dp and follows resizes — `containerSize` is
 * window-scoped state, so a detached window classifies itself independently of the main one. The
 * first frame reports width 0; `widthClassForPx` maps that to `Expanded` so the phone layout never
 * flashes before the window is measured.
 */
@Composable
private fun ProvideDesktopAdaptiveLocals(content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    val widthPx = LocalWindowInfo.current.containerSize.width
    CompositionLocalProvider(
        LocalWindowWidthClass provides widthClassForPx(widthPx, density),
        LocalInputMode provides InputMode.Pointer,
        LocalPointerAvailable provides true,
        content = content,
    )
}
