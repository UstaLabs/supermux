package dev.supermux.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import dev.supermux.host.HostSnapshotStore
import dev.supermux.proto.ServerFrame
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.state.LocalStorageSettingsStore
import dev.supermux.state.WalkthroughSeam
import dev.supermux.state.jsHttpFactory
import dev.supermux.ui.chat.MessageTts
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.prefs.seedShellState
import dev.supermux.ui.session.SessionListMode
import dev.supermux.ui.settings.FleetSettingsExtra
import dev.supermux.ui.settings.FleetSettingsSection
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.SupermuxApp
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.web.auth.CookieSession
import dev.supermux.web.auth.SessionState
import dev.supermux.web.auth.WebPairScreen
import dev.supermux.web.nav.UrlSync
import dev.supermux.web.push.WebPushBanner
import dev.supermux.web.push.WebPushRegistrar
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.configureWebResources

/**
 * The browser half of the walkthrough seam — `:shared`'s [WalkthroughSeam] over `:ui`'s
 * [WalkthroughState]. Identical to `IosWalkthroughSeam`/`AndroidWalkthroughSeam`, and kept beside
 * the DI that installs it because the store's generic parameter is chosen per app.
 *
 * Not optional here: `WEB_CAPS.walkthrough` is true, so the shell READS the walkthrough holder, and
 * a `HostStore` built without a seam throws when it does.
 */
object WebWalkthroughSeam : WalkthroughSeam<WalkthroughState> {
    override fun create(sessionId: String) = WalkthroughState(sessionId)
    override fun apply(state: WalkthroughState, frame: ServerFrame) = state.applyServerFrame(frame)
}

/** Group the session list by project — a per-BROWSER view preference, like iOS's NSUserDefaults one. */
private const val GROUP_BY_PROJECT_KEY = "web:groupByProject"

/**
 * The browser's entry point, mirroring `MainViewController.kt` — deps → seeds → pairing gate →
 * fleet → `SupermuxApp` — with the two changes the platform forces:
 *
 *  1. **No `runBlocking`.** wasm has none, so the seeds (and the pairing probe, which is a network
 *     round trip and could never have been blocking anyway) are awaited in a coroutine and the
 *     viewport is mounted once, inside it. Nothing paints before the seeds land, which is the
 *     property iOS gets from `runBlocking`: no frame with the sidebar at the wrong width.
 *  2. **Pairing is decided by the broker, not by a local store.** The credential is an HttpOnly
 *     cookie, so `isPaired()` cannot be answered here — [CookieSession.probe] asks `/me`.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // The app draws its own context menus (spec carry-forward); the native one would cover them.
    document.addEventListener("contextmenu", { it.preventDefault() })
    // stageForBroker puts the Compose resource tree under assets/ so it inherits the broker's
    // immutable cache rule; the runtime must look for it there. The two MUST agree.
    configureWebResources { resourcePathMapping { path -> "assets/$path" } }
    WebAppState.install()

    val settings = LocalStorageSettingsStore()
    val deps = HostStoreDeps(httpFactory = jsHttpFactory(), settings = settings)
    val uiPrefs = UiPrefs(settings)
    // The page's own scope: `Dispatchers.Main` because everything it drives ends in a Compose state
    // read, `SupervisorJob` so one failed collector cannot tear the app's state down. Never
    // cancelled — this scope's lifetime IS the document's.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // A 10 s timeout, unlike every other client this page builds: the probe runs BEFORE
    // `ComposeViewport`, so a broker that accepts the connection and never answers would leave the
    // splash on screen forever with no way back. A timeout turns that into `Offline` + "Check again".
    val session = CookieSession(http = deps.httpFactory(10_000))
    // Unpairing the origin host has to end the cookie session too — see [WebHostStores]. Wired
    // before anything reads the store, because the hook lives on its persistence.
    WebHostStores.init(session, appScope)
    // The fleet is built inside the composition (see the gate below), but the push registrar has
    // to exist BEFORE the platform, which exists before the composition. This holder is the join:
    // the registrar resolves its `BrokerApi` per call, so it is constructed once and simply reads
    // "no host yet" until the fleet lands.
    var fleetRef: FleetStore? = null
    val push = WebPushRegistrar(api = { fleetRef?.activeApp()?.api }, scope = appScope)
    // ONE platform for the page: `FlowNotices` is a bus, and a second instance would mean notices
    // shown on one and rendered from another.
    val platform = WebPlatform(push = push)

    appScope.launch {
        val appearanceSeed = uiPrefs.appearance(AppearanceMode.SYSTEM).first()
        val textScaleSeed = uiPrefs.textScale.first()
        // Nothing to drain — the browser had no pre-KMP store under these keys (the Vue app's
        // were different keys entirely, and spec §10 chose not to migrate them), so this reads
        // back whatever this app itself last wrote.
        val shellSeed = uiPrefs.seedShellState()
        val collapsedPathsSeed = uiPrefs.collapsedProjectPaths.first()
        var gate by mutableStateOf(session.probe())

        ComposeViewport(document.body!!) {
            val appearance by uiPrefs.appearance(AppearanceMode.SYSTEM).collectAsState(appearanceSeed)
            val textScale by uiPrefs.textScale.collectAsState(textScaleSeed)
            // Hold the splash until Compose has actually painted: removing it before the first
            // frame leaves one blank frame.
            LaunchedEffect(Unit) { window.requestAnimationFrame { document.getElementById("splash")?.remove() } }

            WebTheme(platform, appearance, textScale, uiPrefs) {
                // The gate, and the reason the fleet below is built INSIDE it: `FleetStore`'s
                // initialiser snapshots the host list once, so a store built before the origin
                // host exists would connect to nothing for the life of the page. Same invariant
                // iOS documents around its onboarding branch.
                val g = gate
                if (g !is SessionState.Paired) {
                    var checking by remember { mutableStateOf(false) }
                    WebPairScreen(
                        state = g,
                        checking = checking,
                        onRetry = {
                            // In-flight state, because `probe()` can take the full 10 s timeout and
                            // a button that does nothing visible invites a queue of retries.
                            checking = true
                            appScope.launch {
                                gate = session.probe()
                                checking = false
                            }
                        },
                    )
                    return@WebTheme
                }

                val fleet = remember {
                    // The record's hostId/platform/version are backfilled from `GET /host` by the
                    // fleet's own probe; all it needs up front is a URL to connect to.
                    WebHostStores.ensureOriginHost()
                    buildFleet(deps, appScope).also { fleetRef = it }
                }
                val ui = remember {
                    ShellUiState().apply {
                        sidebarCollapsed = shellSeed.sidebarCollapsed
                        setSidebarWidth(shellSeed.sidebarWidthDp.dp)
                        collapsedProjectPaths = collapsedPathsSeed
                        // Desktop's sidebar theme toggle reads this off the shell state, so it has
                        // to start where the stored appearance is.
                        this.appearance = appearance
                        // NO `selectedId = shellSeed.selectedSession`: the ADDRESS BAR is this
                        // app's memory of which chat was open, and `UrlSync`'s initial apply of a
                        // bare `/` clears the selection a frame later anyway. `persistSelection =
                        // true` below still writes the stored id (the URL is derived from it), so
                        // nothing is lost — it is simply read back from the URL, not from settings.
                    }
                }
                var groupByProject by remember {
                    mutableStateOf(settings.stringNow(GROUP_BY_PROJECT_KEY) != "false")
                }
                val foreground by WebAppState.foreground.collectAsState()

                // Silence read-aloud when the tab goes to the background, the way Android stops it
                // on ON_STOP: nothing else would ever stop it, since the composition stays alive.
                LaunchedEffect(foreground) { if (!foreground) MessageTts.stop(platform.tts) }

                // The address bar IS this app's back stack. Exactly once, and only here: it reads
                // `window.location` on its first composition.
                UrlSync(ui)

                // A push tap (plan 4's `sw.js` posts the session id; inert until then).
                val pendingPush by WebAppState.pendingPushSessionId.collectAsState()
                LaunchedEffect(pendingPush) {
                    val sid = pendingPush ?: return@LaunchedEffect
                    ui.selectSession(sid)
                    WebAppState.consumePendingPushSessionId()
                }

                // Push, exactly where iOS registers it (`MainViewController.kt`): once, after the
                // fleet exists, so `registerIfPaired()` has a broker to talk to. It is a no-op
                // until the user has granted the permission — the banner above asks for that.
                LaunchedEffect(Unit) { platform.push?.registerIfPaired() }

                Box(Modifier.fillMaxSize()) {
                    SupermuxApp(
                        fleet = fleet,
                        ui = ui,
                        appearance = appearance,
                        onToggleTheme = {
                            appScope.launch {
                                uiPrefs.putAppearance(
                                    if (appearance == AppearanceMode.DARK) AppearanceMode.LIGHT else AppearanceMode.DARK,
                                )
                            }
                        },
                        appForeground = foreground,
                        // A tab reopens where it was, and the URL says where that is.
                        persistSelection = true,
                        defaultDeviceName = "Browser",
                        sessionListMode = SessionListMode.Fleet,
                        groupByProject = groupByProject,
                        onGroupByProjectChange = { value ->
                            groupByProject = value
                            appScope.launch { settings.putString(GROUP_BY_PROJECT_KEY, value.toString()) }
                        },
                        settingsExtra = { extra, scope -> FleetSettingsExtra(extra, scope) },
                        settingsSection = { section, scope -> FleetSettingsSection(section, scope, fleet) },
                    )
                    // An OVERLAY at the top, not a row above the shell: the strip must not reflow
                    // the app (see [WebPushBanner]). It draws nothing at all unless this browser
                    // can do push and the user has answered neither way.
                    WebPushBanner(push, Modifier.align(Alignment.TopCenter))
                }
            }
        }
    }
}

/**
 * The multi-host store, wired exactly as iOS's `buildFleet` wires its own — one host here, but the
 * snapshot collector still earns its keep: the sidebar paints this host's last-known session list
 * before the socket opens (spec §5), which on a cold browser tab is the difference between a list
 * and an empty screen.
 */
private fun buildFleet(deps: HostStoreDeps, scope: CoroutineScope): FleetStore {
    val snapshots: HostSnapshotStore = WebHostStores.snapshots
    val fleet = FleetStore(
        store = WebHostStores.store,
        scope = scope,
        deps = deps,
        snapshots = snapshots,
        appFactory = { url, token, onConn ->
            HostStore(
                url, token, scope, deps,
                onConnectionChange = onConn,
                walkthroughSeam = WebWalkthroughSeam,
                // Read-aloud's BROKER half, bound per host exactly as iOS/Android bind it:
                // `MessageTts` holds them as process-wide function references, and the active
                // host's config is the one that decides.
                bindTts = { resolve, speak ->
                    MessageTts.resolveEngine = resolve
                    MessageTts.speakRemoteStream = speak
                },
            )
        },
    )
    fleet.bindMessageTts()
    scope.launch {
        combine(fleet.sessions, fleet.sessionHost) { sessions, owners -> sessions to owners }
            .collect { (sessions, owners) ->
                val records = fleet.store.list()
                snapshots.retainOnly(records.map { it.recordId })
                val now = deps.nowMs()
                records.forEach { host ->
                    val mine = sessions.filter { owners[it.id] == host.recordId }
                    if (mine.isNotEmpty()) snapshots.replace(host.recordId, mine, now, host.version)
                }
            }
    }
    return fleet
}
