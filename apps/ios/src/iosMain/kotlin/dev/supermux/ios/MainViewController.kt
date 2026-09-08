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
import dev.supermux.host.PairingPayload
import dev.supermux.host.workspaceForSession
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
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.MessageTts
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.intro.OnboardingFlow
import dev.supermux.ui.prefs.ShellStateSeed
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.prefs.seedShellState
import dev.supermux.ui.push.PushTapHandle
import dev.supermux.ui.push.notificationCancelSessionIds
import dev.supermux.ui.push.pushTapHandleDecision
import dev.supermux.ui.push.resolvePushTap
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.SupermuxApp
import dev.supermux.ui.shell.visibleWorkspaceChatIdsAt
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.workspace.toDomainOrNull
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
    // The legacy single-host pairing into the fleet store, here at CONSTRUCTION — the same place
    // Android runs it (`MainActivity`'s pre-gate `remember`, and again in `AppViewModel`'s fleet
    // initialiser). Running it only from the pairing gate's `onPaired`, as this first did, is
    // wrong for the two cases that never pass through that callback:
    //
    //  1. The `SM_PAIR_TOKEN`/`SM_PAIR_BASE` launch seed. `SupermuxApp.swift` writes ONLY the
    //     legacy pairing, so `isPaired()` is already true on the next launch, the gate is skipped
    //     entirely, and `buildFleet` would snapshot an empty `PairedHostStore` — a seeded
    //     simulator that looks paired and has no hosts.
    //  2. A user upgrading from a PRE-multi-host SwiftUI build, whose only record is the legacy
    //     pair. Same path, same empty fleet, except it is a real person's device.
    //
    // It is idempotent (it no-ops once the store holds any host), so running it on every launch —
    // and again from `onPaired` below, for the pairing that happens after this point — is safe.
    IosHostStores.migrateFromLegacyIfNeeded()

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
                        // Fold the pairing the gate JUST wrote into the fleet store, so the
                        // FleetStore built on the next composition sees a host rather than none.
                        // The construction-time call above cannot cover this one: it ran before
                        // this pairing existed.
                        IosHostStores.migrateFromLegacyIfNeeded()
                        IosAppState.consumeOpenedUrl()
                        paired = true
                        // The one call the SwiftUI branch made here and the Compose branch did
                        // not: APNs registration is gated on being paired, so a device that pairs
                        // during THIS launch — the common case, and the only case for a first
                        // install opened from a `supermux://pair` link — never registers unless
                        // something asks again once pairing exists. The `LaunchedEffect(paired)`
                        // below is that something; this is belt and braces for the same reason
                        // `PushManager.registerIfPaired` is idempotent.
                        platform.push.registerIfPaired()
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

            // Silence read-aloud when the app leaves the screen, the way Android stops it on
            // ON_STOP. Without this an utterance keeps talking out of a phone the user has put
            // away — and iOS, unlike Android, never destroys the composition that owns it, so
            // nothing else would ever stop it.
            LaunchedEffect(foreground) { if (!foreground) MessageTts.stop(platform.tts) }

            // A `supermux://pair` link that arrives while ALREADY paired adds a second host rather
            // than re-entering the gate. It goes to the shared Add host screen through
            // `Platform.pendingScans()`, which that screen already collects and claims — the same
            // door a QR scan comes through, rather than a second path doing the same thing. The
            // link is handed over BEFORE navigating only because `pendingPairLink` is state: the
            // screen picks it up whenever it composes. Consuming `openedUrl` keeps a
            // recomposition from re-reading it.
            LaunchedEffect(openedUrl) {
                val raw = openedUrl ?: return@LaunchedEffect
                IosAppState.consumeOpenedUrl()
                if (!isPairLink(raw)) return@LaunchedEffect
                IosAppState.setPendingPairLink(raw)
                ui.openAddHost()
            }

            // Registration is gated on being paired, so it belongs here and not at launch: this
            // fires on every cold start of an already-paired app AND on the first composition
            // after the gate above hands over. `PushManager` no-ops when it has no credentials
            // and is idempotent when it does.
            LaunchedEffect(Unit) { platform.push.registerIfPaired() }

            val workspaces by fleet.workspaces.collectAsState()
            val sessionHost by fleet.sessionHost.collectAsState()
            val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact

            // Withdraw the delivered notifications for every chat that is now on screen — the
            // user is looking at it, so a banner about it is noise. Same rule, same shared
            // helpers, as `MainActivity`. Gated on [foreground] because iOS keeps the last
            // composition alive behind the app switcher.
            LaunchedEffect(ui.selectedId, workspaces, compact, foreground) {
                val sid = ui.selectedId ?: return@LaunchedEffect
                if (!foreground) return@LaunchedEffect
                val ws = workspaceForSession(workspaces, sid)
                val visibleIds = ws?.let {
                    visibleWorkspaceChatIdsAt(compact, it, it.layout.toDomainOrNull())
                }.orEmpty()
                for (id in notificationCancelSessionIds(visibleIds, sid)) {
                    platform.push.cancelForSession(id)
                }
            }

            // A tapped notification carries the chat id (`sm_session_id`, stashed by the
            // notification service extension and read by `PushAppDelegate`). Resolve the owning
            // workspace and activate that chat view without PATCHing the layout; an old broker or
            // a session in no workspace falls back to the session-only screen. The extra is
            // consumed only once the workspace list has arrived, so a tap that launches the app
            // cold still lands after the fleet connects.
            var handledPushSessionId by remember { mutableStateOf<String?>(null) }
            val pendingPush by IosAppState.pendingPushSessionId.collectAsState()
            LaunchedEffect(ui.selectedId) { if (ui.selectedId == null) handledPushSessionId = null }
            LaunchedEffect(pendingPush, workspaces) {
                val decision =
                    pushTapHandleDecision(pendingPush, handledPushSessionId, workspaces.isNotEmpty())
                // Skip means "this tap is spent" — most often because the chat it names is the one
                // already open. The id must still be CLEARED, which Android does by removing the
                // Intent extra. Leaving it in the flow is not inert: `handledPushSessionId` is
                // reset whenever the selection goes back to null, so the next workspaces emission
                // would find a live pending id again and drag the user back into the chat they had
                // just left.
                if (decision == PushTapHandle.Skip) {
                    if (pendingPush != null) IosAppState.consumePendingPushSessionId()
                    return@LaunchedEffect
                }
                val sid = pendingPush!!
                val hostId = sessionHost[sid] ?: fleet.activeHost.value
                val owned = hostId?.let { fleet.workspaceForSession(it, sid) }
                val tap = resolvePushTap(sid, owned?.let { listOf(it) } ?: workspaces)
                ui.selectSession(sid)
                // Destructured into locals rather than smart-cast: `PushTapResolution` is `:ui`'s,
                // and Kotlin will not smart-cast a public property from another module.
                val tappedWorkspace = tap.workspaceId
                val tappedView = tap.activeViewId
                if (tappedWorkspace != null && tappedView != null) {
                    fleet.setActiveView(tappedWorkspace, tappedView)
                }
                if (decision == PushTapHandle.ApplyConsume) {
                    handledPushSessionId = sid
                    IosAppState.consumePendingPushSessionId()
                }
            }

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
                // A newly added host has its own relay: this device has to register with it too,
                // or that host's pushes never arrive. Android does the same here.
                onAddedHost = { platform.push.registerIfPaired() },
                // No `chatFallback`, `settingsExtra` or `settingsSection`: those slots exist for a
                // host with a screen the shared shell has no version of, and iOS has none — the
                // SwiftUI screens they would name are the ones this cluster is replacing.
            )
        }
    }
}

/**
 * Whether [raw] is a pairing link at all.
 *
 * Both forms the Add host screen accepts, asked in the same order it asks them: the current
 * `PairingPayload` QR/link payload, then the legacy `supermux://pair?t=` / `https://…/pair?t=`
 * URL. Anything else — a `supermux://` URL that means something other than pairing, a link the
 * system handed us by mistake — is ignored rather than dropped into the Add host screen, where it
 * would render as "that isn't a valid supermux pairing link" for a link the user never pasted.
 */
private fun isPairLink(raw: String): Boolean =
    PairingPayload.parse(raw) != null || PairUrl.parse(raw) != null

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
                // Read-aloud's BROKER half: which engine this host is configured for, and the
                // `/speak` stream for the non-platform (codex) voice. `MessageTts` holds them as
                // process-wide function references, so this must be bound per host — the active
                // host's config is the one that decides. Identical to `AppViewModel`'s.
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
