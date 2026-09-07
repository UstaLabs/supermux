// The `*HubTest` suites drive the REAL root over a real `HostStore` (cluster G8 kept them on
// desktop: each one is about a desktop-shaped hub/overlay, not about the shell itself, and the
// shell's own suite moved to `:ui` as `AppShellTest`).
//
// `SupermuxApp` takes a `FleetStore` — which is what desktop's `app` was always resolved from — so
// this wraps one store in a one-record fleet and pins desktop's sidebar mode.
package dev.supermux.desktop.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.supermux.desktop.settings.DesktopSettingsExtra
import dev.supermux.desktop.settings.DesktopSettingsSection
import dev.supermux.desktop.testDeps
import dev.supermux.host.HostPersistence
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.notify.NotificationController
import dev.supermux.ui.platform.NoopNotificationManager
import dev.supermux.ui.session.SessionListMode
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.SupermuxApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

internal fun testPairedHostStore(): PairedHostStore = PairedHostStore(
    object : HostPersistence {
        private var hosts = mutableListOf(
            PairedHost(
                recordId = "h1",
                hostId = "host-a",
                displayName = "Test host",
                token = "t",
                relayUrl = "https://test.relay.supermux.dev",
            ),
        )
        override fun loadAll(): List<PairedHost> = hosts.toList()
        override fun saveAll(hosts: List<PairedHost>) { this.hosts = hosts.toMutableList() }
    },
) { "rec-unused" }

/**
 * A one-record [FleetStore] whose only host IS [app].
 *
 * UNCONFINED, deliberately: `FleetStore` holds its own `lock` while a merged `StateFlow.setValue`
 * resumes whoever is collecting it, and under `runComposeUiTest` that resume takes Compose's
 * `FlushCoroutineDispatcher` monitor — a monitor the composition itself holds while calling back
 * into the store. On a real dispatcher the two orders interleave and the suite deadlocks;
 * unconfined keeps the fan-out on the caller's thread, which is what every other fleet-driven
 * suite here already does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun testFleet(app: HostStore): FleetStore = FleetStore(
    store = testPairedHostStore(),
    scope = TestScope(UnconfinedTestDispatcher()),
    deps = testDeps(),
    appFactory = { _, _, _ -> app },
)

/** The desktop-shaped root: one host, the workspace sidebar, no window seam. */
@Composable
internal fun TestAppShell(
    app: HostStore,
    ui: ShellUiState,
    fleet: FleetStore? = null,
    notify: NotificationController = remember { NotificationController(NoopNotificationManager) },
) {
    val store = fleet ?: remember(app) { testFleet(app) }
    SupermuxApp(
        fleet = store,
        ui = ui,
        notify = notify,
        sessionListMode = SessionListMode.Workspaces,
        persistSelection = true,
        // Desktop's own Settings wiring — the same one `Main.kt` passes, so a hub suite drives
        // the real sections rather than an empty slot.
        settingsExtra = { extra, scope -> DesktopSettingsExtra(extra, scope) },
        settingsSection = { section, scope ->
            DesktopSettingsSection(section, scope, store.activeApp() ?: app)
        },
    )
}
