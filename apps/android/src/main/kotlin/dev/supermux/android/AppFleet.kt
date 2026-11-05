package dev.supermux.android

import android.content.Context
import androidx.annotation.MainThread
import dev.supermux.android.host.HostStores
import dev.supermux.android.settings.AndroidSettingsStore
import dev.supermux.host.HostSnapshotStore
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.state.cioHttpFactory
import dev.supermux.ui.chat.MessageTts
import dev.supermux.ui.prefs.UiPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The process's one [FleetStore]: every broker connection and all multi-host state (spec §5).
 *
 * The main window's [AppViewModel] and the assistant overlay's service share it, so the overlay never
 * opens a second connection or fights the main window over viewing state. It lives while anyone holds
 * it and closes when the last holder lets go. With the overlay off, the only holder is the activity's
 * view model, so the lifetime is the one it always had: built below the pairing gate, closed when the
 * activity really finishes (a rotation keeps the view model, so it keeps the fleet).
 */
object AppFleet {
    class Shared(val deps: HostStoreDeps, val uiPrefs: UiPrefs, val fleet: FleetStore, internal val scope: CoroutineScope)

    private var shared: Shared? = null
    private val holders = mutableSetOf<Any>()

    @MainThread
    fun acquire(context: Context, holder: Any): Shared {
        holders += holder
        return shared ?: build(context.applicationContext).also { shared = it }
    }

    @MainThread
    fun release(holder: Any) {
        if (!holders.remove(holder) || holders.isNotEmpty()) return
        shared?.let { it.fleet.close(); it.scope.cancel() }
        shared = null
    }

    private fun build(appContext: Context): Shared {
        // The same scope a ViewModel gets, but the process's.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val deps = HostStoreDeps(
            httpFactory = cioHttpFactory(),
            settings = AndroidSettingsStore(appContext),
            media = dev.supermux.android.platform.AndroidMedia.cache(appContext),
            clientInfo = dev.supermux.net.ClientInfo(
                surface = "android",
                version = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull(),
                os = "android",
                osMajor = android.os.Build.VERSION.RELEASE.substringBefore('.').toIntOrNull(),
            ),
        )
        // Per-host offline-snapshot cache (spec §5), also read by the push service.
        val snapshotStore: HostSnapshotStore = HostStores.snapshotStore(appContext)
        HostStores.migrateFromLegacyIfNeeded(appContext)
        val fleet = FleetStore(
            store = HostStores.store(appContext),
            scope = scope,
            deps = deps,
            snapshots = snapshotStore,
            appFactory = { url, token, onConn ->
                HostStore(
                    url, token, scope, deps,
                    onConnectionChange = onConn,
                    walkthroughSeam = AndroidWalkthroughSeam,
                    bindTts = { resolve, speak, speakUrl ->
                        MessageTts.resolveEngine = resolve
                        MessageTts.speakRemoteStream = speak
                        MessageTts.speakRemoteUrl = speakUrl
                    },
                )
            },
        )
        fleet.bindMessageTts()
        // Spec §5: persist each host's last-known LIVE session list so the push service (and the
        // next launch) can name a session for a host that is currently offline.
        scope.launch {
            combine(fleet.sessions, fleet.sessionHost) { sessions, owners -> sessions to owners }
                .collect { (sessions, owners) ->
                    val records = fleet.store.list()
                    snapshotStore.retainOnly(records.map { it.recordId })
                    val now = System.currentTimeMillis()
                    records.forEach { host ->
                        val mine = sessions.filter { owners[it.id] == host.recordId }
                        if (mine.isNotEmpty()) snapshotStore.replace(host.recordId, mine, now, host.version)
                    }
                }
        }
        return Shared(deps, UiPrefs(deps.settings), fleet, scope)
    }
}
