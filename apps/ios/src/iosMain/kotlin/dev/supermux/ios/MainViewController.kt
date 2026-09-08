package dev.supermux.ios

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import dev.supermux.auth.SecureTokenStore
import dev.supermux.host.IosHostStores
import dev.supermux.net.PairUrl
import dev.supermux.net.iosHttpFactory
import dev.supermux.pairing.PairingState
import dev.supermux.pairing.asPairingStore
import dev.supermux.proto.ServerFrame
import dev.supermux.settings.NSUserDefaultsSettingsStore
import dev.supermux.settings.migrateSwiftPrefsOnce
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.state.WalkthroughSeam
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.intro.OnboardingFlow
import dev.supermux.ui.prefs.ShellStateSeed
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.prefs.seedShellState
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.SupermuxApp
import dev.supermux.ui.theme.AppearanceMode
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSUserDefaults
import platform.UIKit.UIDevice
import platform.UIKit.UIViewController

/**
 * The iOS half of the walkthrough seam — `:shared`'s [WalkthroughSeam] over `:ui`'s
 * [WalkthroughState]. Identical to `AndroidWalkthroughSeam` and `DesktopWalkthroughSeam`, and kept
 * beside the DI that installs it, because the store's generic parameter is chosen per app.
 */
object IosWalkthroughSeam : WalkthroughSeam<WalkthroughState> {
    override fun create(sessionId: String) = WalkthroughState(sessionId)
    override fun apply(state: WalkthroughState, frame: ServerFrame) = state.applyServerFrame(frame)
}

/**
 * The single entry point Swift calls: a `UIViewController` hosting the shared Compose root.
 *
 * Swift's job is to build this, put it inside a `UINavigationController` with a hidden bar (which
 * is what delivers the interactive swipe-back to `PredictiveBackHandler`), and hand over an
 * [IosBridge] for the things only Swift can do.
 *
 * The launch ORDER below is load-bearing and mirrors `MainActivity`'s, including the invariant that
 * cost Android a regression once:
 *
 *  1. **Migrations, before anything reads.** The Swift preferences are copied onto the shared keys
 *     and the legacy single-host pairing is folded into the fleet store. Both are idempotent, and
 *     both must precede the pairing check — the `SM_PAIR_TOKEN`/`SM_PAIR_BASE` launch seed writes
 *     the LEGACY store, so without the second one a seeded simulator would boot un-paired.
 *  2. **Seeds are read BLOCKING, before the first frame.** `NSUserDefaults` is synchronous, so this
 *     costs nothing and buys a first frame in the right theme with the sidebar at the right width.
 *     Collecting them asynchronously instead would paint every cold start in the default theme for
 *     a frame or two. (This is why `runBlocking` is safe here specifically: the store is a
 *     dictionary read, and nothing inside dispatches back to Main.)
 *  3. **The `FleetStore` is built BELOW the pairing gate.** Its initialiser snapshots the paired-host
 *     list exactly once, and pairing writes that list — so a store built before the gate would
 *     snapshot zero hosts and a freshly-paired device would sit empty until the app was relaunched.
 *     See `MainActivity`'s `launchOrder`, which is the same rule written as a test.
 */
fun MainViewController(bridge: IosBridge = NoopIosBridge): UIViewController {
    val defaults = NSUserDefaults.standardUserDefaults
    // The SwiftUI shell's preferences onto the shared keys, once (see migrateSwiftPrefsOnce): the
    // theme, chat density, drafts and collapsed groups an upgrading user already chose.
    migrateSwiftPrefsOnce(defaults)

    val deps = HostStoreDeps(
        httpFactory = iosHttpFactory(),
        settings = NSUserDefaultsSettingsStore(defaults),
    )
    val uiPrefs = UiPrefs(deps.settings)

    // The app's own scope. `Dispatchers.Main` because everything it drives ends up in a Compose
    // state read; `SupervisorJob` so one failed collector cannot tear the app's state down. It is
    // deliberately never cancelled — this scope's lifetime IS the app's, and iOS does not destroy
    // and re-create the root view controller the way an Android activity is destroyed.
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val appearanceSeed: AppearanceMode
    val textScaleSeed: Float
    val shellSeed: ShellStateSeed
    val collapsedPathsSeed: Set<String>
    runBlocking {
        appearanceSeed = uiPrefs.appearance(AppearanceMode.SYSTEM).first()
        textScaleSeed = uiPrefs.textScale.first()
        // Nothing to drain: the SwiftUI sidebar values were migrated above under the shared keys,
        // so this reads back whatever is now stored.
        shellSeed = uiPrefs.seedShellState()
        collapsedPathsSeed = uiPrefs.collapsedProjectPaths.first()
    }

    return ComposeUIViewController {
        val platform = remember { IosPlatform(bridge) }
        val appearance by uiPrefs.appearance(AppearanceMode.SYSTEM).collectAsState(appearanceSeed)
        val textScale by uiPrefs.textScale.collectAsState(textScaleSeed)

        IosTheme(
            platform = platform,
            appearance = appearance,
            textScale = textScale,
            uiPrefs = uiPrefs,
        ) {
            // The legacy single-host migration runs at construction above, so "paired" is the union
            // of both stores: the fleet has hosts, or the legacy pairing the gate itself writes is
            // present. Android asks only the legacy store; asking both additionally covers the user
            // who paired under the SwiftUI shell, whose hosts live in the fleet store already.
            //
            // `remember` and not `rememberSaveable`, unlike Android: there is no activity
            // recreation on iOS, so there is no saved state to restore across one.
            var paired by remember { mutableStateOf(isPaired()) }

            val openedUrl by IosAppState.openedUrl.collectAsState()

            if (!paired) {
                // The same shared intro + pairing flow Android runs (cluster G6), over `:shared`'s
                // PairingState. It writes the LEGACY store, which `IosHostStores` then folds into
                // the fleet — which is why the fleet below must not exist yet.
                val pairing = remember {
                    PairingState(
                        store = SecureTokenStore().asPairingStore(),
                        scope = appScope,
                        httpFactory = { deps.httpFactory(null) },
                    )
                }
                val deepLink = remember(openedUrl) {
                    openedUrl?.let { PairUrl.parse(it, SecureTokenStore().loadBaseUrl()) }
                }
                OnboardingFlow(
                    pairing = pairing,
                    onPaired = {
                        // Fold the pairing the gate just wrote into the fleet store, so the
                        // FleetStore built on the next composition sees a host rather than none.
                        IosHostStores.migrateFromLegacyIfNeeded()
                        IosAppState.consumeOpenedUrl()
                        paired = true
                    },
                    initialDeepLink = deepLink,
                )
                return@IosTheme
            }

            val fleet = remember { buildFleet(deps, appScope) }
            val ui = remember {
                ShellUiState().apply {
                    sidebarCollapsed = shellSeed.sidebarCollapsed
                    setSidebarWidth(shellSeed.sidebarWidthDp.dp)
                    collapsedProjectPaths = collapsedPathsSeed
                }
            }
            var groupByProject by remember { mutableStateOf(readGroupByProject(defaults)) }
            val foreground by IosAppState.foreground.collectAsState()

            // A `supermux://pair` link that arrives while ALREADY paired adds a second host rather
            // than re-entering the gate; consuming it keeps a recomposition from re-reading it.
            LaunchedEffect(openedUrl) { if (openedUrl != null) IosAppState.consumeOpenedUrl() }

            SupermuxApp(
                fleet = fleet,
                ui = ui,
                appForeground = foreground,
                // A phone opens on the session list, never on the chat it was last in.
                persistSelection = false,
                defaultDeviceName = UIDevice.currentDevice.name,
                groupByProject = groupByProject,
                onGroupByProjectChange = { value ->
                    groupByProject = value
                    defaults.setBool(value, forKey = GROUP_BY_PROJECT_KEY)
                },
                // No `chatFallback`, `settingsExtra` or `settingsSection`: those slots exist for a
                // host with a screen the shared shell has no version of, and iOS has none — the
                // SwiftUI screens they would name are the ones this cluster is replacing.
            )
        }
    }
}

/**
 * Whether this device is paired with anything.
 *
 * Both halves, because they can disagree legitimately: a device paired under the SwiftUI shell has
 * hosts in the fleet store, while a device pairing right now has only written the legacy
 * `(token, baseUrl)` that the gate itself produces.
 */
private fun isPaired(): Boolean {
    if (IosHostStores.store().list().isNotEmpty()) return true
    val legacy = SecureTokenStore()
    return !legacy.load().isNullOrBlank() && !legacy.loadBaseUrl().isNullOrBlank()
}

/**
 * The multi-host store, wired exactly as `AppViewModel` wires Android's.
 *
 * The snapshot collector at the end is spec §5: each host's last-known LIVE session list is
 * persisted so a host that is offline at launch renders its last known sessions instead of an empty
 * group. On Android the same cache is additionally read by the push service; here nothing else
 * reads it yet, but the shell's cold-start behaviour is the reason it exists on both.
 */
private fun buildFleet(deps: HostStoreDeps, scope: CoroutineScope): FleetStore {
    val snapshots = IosHostStores.snapshotStore()
    val fleet = FleetStore(
        store = IosHostStores.store(),
        scope = scope,
        deps = deps,
        snapshots = snapshots,
        appFactory = { url, token, onConn ->
            HostStore(
                url, token, scope, deps,
                onConnectionChange = onConn,
                walkthroughSeam = IosWalkthroughSeam,
                // No `bindTts`: `IosPlatform.tts` is not wired until cluster H3, so there is no
                // engine for a remote audio stream to reach. Binding a half-engine here would make
                // read-aloud look available and then do nothing.
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

/**
 * Group the session list by project?
 *
 * A per-DEVICE view preference, never synced and never part of the shared settings — the same
 * reasoning (and the same behaviour) as Android's `cmux-session-list` flag, which is likewise kept
 * out of the shared store. Defaults to true, matching every other host.
 */
private fun readGroupByProject(defaults: NSUserDefaults): Boolean =
    if (defaults.objectForKey(GROUP_BY_PROJECT_KEY) == null) true
    else defaults.boolForKey(GROUP_BY_PROJECT_KEY)

private const val GROUP_BY_PROJECT_KEY = "sessionList:groupByProject"
