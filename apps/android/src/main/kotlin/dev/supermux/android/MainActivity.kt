// Android's entry point: splash, edge-to-edge, the pairing gate, DI — and `SupermuxApp()`.
//
// Cluster G8 moved the whole scaffold into `:ui`. What used to be nine `composable<Route.X>`
// destinations, a phone/tablet keep-alive host, a workspace tab strip and four back handlers is
// now one shared root; `androidx.navigation:navigation-compose` left the app with it (the shell
// drives Navigation 3 on both hosts). What is left here is the part that is genuinely Android:
// the splash screen, edge-to-edge, the notification channel + push registration, the encrypted
// token store and the legacy single-host migration, the deep-link intake, the lifecycle signal the
// shell needs for viewing presence, the push-tap route, and the `AppViewModel` that owns the fleet.
package dev.supermux.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.supermux.android.chat.SessionChatFallback
import dev.supermux.android.host.HostStores
import dev.supermux.android.pairing.PairingHolder
import dev.supermux.android.push.AndroidPushRegistrar
import dev.supermux.android.push.SupermuxMessagingService
import dev.supermux.android.session.readGroupByProject
import dev.supermux.android.session.readLegacyCollapsedPaths
import dev.supermux.android.session.seedSessionListPrefs
import dev.supermux.android.session.writeGroupByProject
import dev.supermux.android.settings.AndroidSettingsStore
import dev.supermux.android.settings.readLegacyAppearancePrefs
import dev.supermux.android.settings.seedAppearancePrefs
import dev.supermux.android.theme.AndroidTheme
import dev.supermux.android.update.AppUpdateNotifier
import dev.supermux.android.windows.AndroidWindows
import dev.supermux.auth.SecureTokenStore
import dev.supermux.auth.SecureTokenStoreContext
import dev.supermux.host.workspaceForSession
import dev.supermux.net.PairUrl
import dev.supermux.ui.intro.OnboardingFlow
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.PushRegistrar
import dev.supermux.ui.prefs.TEXT_SCALE_DEFAULT
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.prefs.seedShellState
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.SupermuxApp
import dev.supermux.ui.shell.visibleWorkspaceChatIdsAt
import dev.supermux.ui.push.PushTapHandle
import dev.supermux.ui.push.notificationCancelSessionIds
import dev.supermux.ui.push.pushTapHandleDecision
import dev.supermux.ui.push.resolvePushTap
import dev.supermux.ui.settings.FleetSettingsExtra
import dev.supermux.ui.settings.FleetSettingsSection
import dev.supermux.ui.theme.AppearanceMode
import kotlinx.coroutines.launch
import dev.supermux.workspace.toDomainOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.unit.dp

/**
 * The launch steps `MainActivity`'s `setContent` runs, in the order it runs them.
 *
 * This is an executable statement of an invariant that is otherwise only visible as the physical
 * order of lines inside one composable — and one that has already regressed once:
 * [LaunchStep.CreateViewModel] MUST come after [LaunchStep.LegacyMigration] and
 * [LaunchStep.PairingGate], because `AppViewModel`'s `fleet` initializer snapshots the paired-host
 * list exactly once. A VM built before pairing sees zero hosts forever.
 */
internal enum class LaunchStep { DebugSeed, LegacyMigration, PairingGate, CreateViewModel }

/** The steps that actually run for a given pairing state — un-paired stops at the gate. */
internal fun launchOrder(paired: Boolean): List<LaunchStep> = buildList {
    add(LaunchStep.DebugSeed)
    add(LaunchStep.LegacyMigration)
    add(LaunchStep.PairingGate)
    if (paired) add(LaunchStep.CreateViewModel)
}

class MainActivity : ComponentActivity() {
    // Current launch/deep-link intent, surfaced to Compose. Seeded in onCreate; updated by
    // onNewIntent so a supermux://pair link delivered while foregrounded re-enters pairing.
    private val intentState = mutableStateOf<android.content.Intent?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intentState.value = intent
    }

    @OptIn(ExperimentalSharedTransitionApi::class, ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        SecureTokenStoreContext.init(applicationContext)
        // Native push: ensure the notification channel exists and ask for POST_NOTIFICATIONS
        // (API 33+) so decrypted session pushes can be shown. Must run before the activity is
        // STARTED, hence here in onCreate before setContent. Both go through the `PushRegistrar`
        // seam (cluster G1); the activity is built before any composition, so it constructs the
        // actual directly — the same instance shape `AndroidPlatform` hands every shared caller.
        val push: PushRegistrar = AndroidPushRegistrar(this)
        push.ensureChannel()
        // App self-update progress / failure alerts (status bar during APK download).
        AppUpdateNotifier.ensureChannels(this)
        push.requestPermission()
        intentState.value = intent
        enableEdgeToEdge()

        // The persisted UI preferences. Built here — NOT from the AppViewModel — because the VM
        // must stay below the pairing gate (see the invariant there), and `AndroidSettingsStore`
        // is a process-wide DataStore delegate: this instance and `vm.uiPrefs` read and write
        // exactly the same data.
        //
        // BLOCKING, and before `setContent`, deliberately: a DataStore read is asynchronous, so
        // collecting it with a hardcoded default would paint the first frames of every cold start
        // in the wrong theme (and with the sidebar at the wrong width) before the stored value
        // landed. Same one small disk read the SharedPreferences this replaced did synchronously.
        val settingsStore = AndroidSettingsStore(applicationContext)
        val appearanceSeed: dev.supermux.android.settings.AppearanceSeed
        val collapsedPathsSeed: Set<String>
        val shellSeed: dev.supermux.ui.prefs.ShellStateSeed
        // The group-by-project toggle stays in `cmux-session-list` (a per-device view preference,
        // never synced) and is hoisted here so no frame paints the wrong grouping.
        val groupByProjectSeed = readGroupByProject(applicationContext)
        runBlocking {
            val prefs = UiPrefs(settingsStore)
            appearanceSeed = seedAppearancePrefs(settingsStore, readLegacyAppearancePrefs(applicationContext))
            collapsedPathsSeed = seedSessionListPrefs(settingsStore, readLegacyCollapsedPaths(applicationContext))
            // Nothing to drain on Android (the sidebar chrome was `rememberSaveable`-only until
            // cluster G8); this reads back whatever the shell wrote last.
            shellSeed = prefs.seedShellState()
        }

        setContent {
            val themeUiPrefs = remember { UiPrefs(settingsStore) }
            val appearance by themeUiPrefs.appearance(AppearanceMode.SYSTEM)
                .collectAsState(appearanceSeed.appearance)
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val effectiveAppearance = when (appearance) {
                AppearanceMode.SYSTEM -> if (systemDark) AppearanceMode.DARK else AppearanceMode.LIGHT
                else -> appearance
            }
            val themeScope = androidx.compose.runtime.rememberCoroutineScope()
            val textScale by themeUiPrefs.textScale.collectAsState(appearanceSeed.textScale)
            AndroidTheme(appearance = appearance, textScale = textScale, uiPrefs = themeUiPrefs) {
                val store = remember { SecureTokenStore() }
                // Debug-only: seed token+baseUrl on debuggable builds so the already-paired
                // emulator boots past the gate (no-op on release / when DEBUG_TOKEN is empty).
                // Then run the one-time single-host → PairedHost[0] migration (spec §3.2).
                remember {
                    DevConfig.seedDebugPairingIfEmpty(applicationContext)
                    HostStores.migrateFromLegacyIfNeeded(applicationContext)
                    Unit
                }
                // Paired ⇔ the encrypted store holds BOTH a token and a broker base URL.
                var paired by rememberSaveable {
                    mutableStateOf(
                        store.load()?.isNotBlank() == true && store.loadBaseUrl()?.isNotBlank() == true,
                    )
                }
                // Native push: re-register the FCM token with the relay whenever we are (or
                // become) paired. onNewToken alone is insufficient — FCM often issues the token
                // *before* pairing, and the old path used a placeholder base URL.
                val pushSeam = LocalPlatform.current.push
                LaunchedEffect(paired, pushSeam) {
                    if (paired) pushSeam?.registerIfPaired()
                }
                // Deep-link intake: parse supermux://pair (or a pasted https pair URL) from the
                // current intent. Recomputed when onNewIntent swaps the intent in.
                val currentIntent by intentState
                val deepLink: PairUrl? = remember(currentIntent) {
                    currentIntent?.data?.toString()?.let { PairUrl.parse(it, store.loadBaseUrl()) }
                }

                if (!paired) {
                    // Cluster G6: one shared intro + pairing flow, over `:shared`'s PairingState,
                    // held by a RETAINED `PairingHolder` so a rotation mid-probe does not destroy
                    // the machine or dismiss an open TOFU dialog.
                    val pairing = viewModel<PairingHolder>().pairing
                    OnboardingFlow(
                        pairing = pairing,
                        onPaired = { paired = true },
                        initialDeepLink = deepLink,
                    )
                    return@AndroidTheme
                }

                // ORDERING INVARIANT — the VM is created BELOW this gate, on purpose.
                // `AppViewModel`'s `fleet` initializer runs `HostStores.migrateFromLegacyIfNeeded`
                // and `FleetStore.init`, then snapshots `store.list()` ONCE. Pairing (and the
                // debug seed) writes only the legacy single-host store, so a VM built before the
                // gate would snapshot an empty host list and never re-sync — a fresh install would
                // sit hostless after its first pairing until the process restarts. See
                // `launchOrder(paired)` / `MainActivityLaunchOrderTest`.
                val vm: AppViewModel = viewModel(factory = AppViewModel.factory(application))

                // The shell's own state, saveable so a rotation AND process death keep the back
                // stack, the selection and the sidebar chrome (desktop keeps its copy in Main.kt
                // so the native menu bar can act on it; Android has no menu bar to share with).
                val ui = rememberSaveable(saver = ShellUiState.Saver) {
                    ShellUiState().apply {
                        sidebarCollapsed = shellSeed.sidebarCollapsed
                        setSidebarWidth(shellSeed.sidebarWidthDp.dp)
                        collapsedProjectPaths = collapsedPathsSeed
                    }
                }
                // Extra windows (windows/AndroidWindows.kt): the claim registry is the
                // process's, not this activity's — it must outlive a rotation of the main window
                // with an extra one open. Assigned here, before the shell's first read: `windows`
                // is a plain field, and a later write would not recompose anything that read it.
                ui.windows = AndroidWindows.shellWindows
                // The extra windows draw from THIS shell state's workspace binds, so it is
                // published for them while it is composed.
                DisposableEffect(ui) {
                    AndroidWindows.mainUi = ui
                    onDispose { if (AndroidWindows.mainUi === ui) AndroidWindows.mainUi = null }
                }

                var groupByProject by rememberSaveable { mutableStateOf(groupByProjectSeed) }

                // Report whether the app is in front, so the shell suppresses viewing presence (and
                // the broker keeps sending pushes) while it is backgrounded (spec §11).
                val lifecycleOwner = LocalLifecycleOwner.current
                var appVisible by remember {
                    mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                }
                DisposableEffect(lifecycleOwner) {
                    val obs = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> appVisible = true
                            Lifecycle.Event.ON_STOP -> appVisible = false
                            else -> {}
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(obs)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
                }

                val workspaces by vm.fleet.workspaces.collectAsStateWithLifecycle()
                val sessionHost by vm.fleet.sessionHost.collectAsStateWithLifecycle()
                val compact = dev.supermux.ui.adaptive.LocalWindowWidthClass.current ==
                    dev.supermux.ui.adaptive.WindowWidthClass.Compact

                // Cancel the system notification for every chat that is now on screen.
                LaunchedEffect(ui.selectedId, workspaces, compact, appVisible) {
                    val sid = ui.selectedId ?: return@LaunchedEffect
                    if (!appVisible) return@LaunchedEffect
                    val ws = workspaceForSession(workspaces, sid)
                    val visibleIds = ws?.let {
                        visibleWorkspaceChatIdsAt(compact, it, it.layout.toDomainOrNull())
                    }.orEmpty()
                    for (id in notificationCancelSessionIds(visibleIds, sid)) {
                        pushSeam?.cancelForSession(id)
                    }
                }

                // A tapped push carries the chat id. Resolve the owning workspace and activate that
                // chat view without PATCHing layout. Old broker / no workspace → the session-only
                // screen, same as before. Consume the extra once workspaces are ready so later
                // updates cannot yank the user back.
                var handledPushSessionId by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(ui.selectedId) { if (ui.selectedId == null) handledPushSessionId = null }
                LaunchedEffect(currentIntent, workspaces) {
                    val extra = currentIntent
                        ?.getStringExtra(SupermuxMessagingService.EXTRA_SESSION_ID)
                    val decision = pushTapHandleDecision(extra, handledPushSessionId, workspaces.isNotEmpty())
                    if (decision == PushTapHandle.Skip) return@LaunchedEffect
                    val sid = extra!!
                    val hostId = sessionHost[sid] ?: vm.fleet.activeHost.value
                    val owned = hostId?.let { vm.fleet.workspaceForSession(it, sid) }
                    val tap = resolvePushTap(sid, owned?.let { listOf(it) } ?: workspaces)
                    ui.selectSession(sid)
                    // Locals, not a smart cast: `PushTapResolution` moved into `:ui`, and Kotlin
                    // will not smart-cast a public property declared in another module.
                    val tappedWorkspace = tap.workspaceId
                    val tappedView = tap.activeViewId
                    if (tappedWorkspace != null && tappedView != null) {
                        vm.fleet.setActiveView(tappedWorkspace, tappedView)
                    }
                    if (decision == PushTapHandle.ApplyConsume) {
                        handledPushSessionId = sid
                        currentIntent?.removeExtra(SupermuxMessagingService.EXTRA_SESSION_ID)
                    }
                }

                SupermuxApp(
                    fleet = vm.fleet,
                    ui = ui,
                    modifier = Modifier.semantics { testTagsAsResourceId = true },
                    appForeground = appVisible,
                    homeFallback = DevConfig.HOME,
                    // A phone opens on the session list, never on the chat it was last in.
                    persistSelection = false,
                    // The sidebar footer's theme toggle (wide/unfolded only). SYSTEM resolves to
                    // what's on screen, so the icon and the flip match what the user sees.
                    appearance = effectiveAppearance,
                    onToggleTheme = {
                        val next = if (effectiveAppearance == AppearanceMode.DARK) AppearanceMode.LIGHT else AppearanceMode.DARK
                        themeScope.launch { themeUiPrefs.putAppearance(next) }
                    },
                    defaultDeviceName = android.os.Build.MODEL?.ifBlank { "Android phone" } ?: "Android phone",
                    groupByProject = groupByProject,
                    onGroupByProjectChange = { value ->
                        groupByProject = value
                        writeGroupByProject(applicationContext, value)
                    },
                    // A new host needs its own relay bootstrap → broker /push/device row.
                    onAddedHost = { pushSeam?.registerIfPaired() },
                    chatFallback = { session, visible, onBack ->
                        SessionChatFallback(
                            session = session,
                            visible = visible,
                            vm = vm,
                            onBack = onBack,
                            onSelectSession = { ui.selectSession(it) },
                            onOpenDisplays = { ui.openDisplays() },
                        )
                    },
                    settingsExtra = { extra, scope -> FleetSettingsExtra(extra, scope) },
                    settingsSection = { section, scope -> FleetSettingsSection(section, scope, vm.fleet) },
                )
            }
        }
    }
}
