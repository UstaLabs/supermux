package dev.supermux.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.supermux.ui.chat.MessageTts
import dev.supermux.android.host.HostStores
import dev.supermux.android.settings.AndroidSettingsStore
import dev.supermux.host.HostSnapshotStore
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.state.cioHttpFactory
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import dev.supermux.proto.ServerFrame
import dev.supermux.state.WalkthroughSeam
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.prefs.UiPrefs

/** The Android half of the walkthrough seam: `:shared`'s [WalkthroughSeam] over `:ui`'s
 *  [WalkthroughState]. Identical to desktop's `DesktopWalkthroughSeam` and kept beside the DI that
 *  installs it (the `HostStore` factory below), because the store's generic parameter is chosen
 *  per app. */
object AndroidWalkthroughSeam : WalkthroughSeam<WalkthroughState> {
    override fun create(sessionId: String) = WalkthroughState(sessionId)
    override fun apply(state: WalkthroughState, frame: ServerFrame) = state.applyServerFrame(frame)
}

/**
 * Activity-scoped holder for the shared [FleetStore] (spec §5). All multi-host state and every
 * action live in [fleet]; this class only owns the Android-only pieces: the DataStore-backed
 * settings, the read-aloud seam and the offline-snapshot publisher the
 * push service reads.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext

    val deps = HostStoreDeps(
        httpFactory = cioHttpFactory(),
        settings = AndroidSettingsStore(appContext),
    )

    /** Editor + chat-detail preferences, on the same DataStore as drafts / launcher prefs. */
    val uiPrefs = UiPrefs(deps.settings)

    /** Per-host offline-snapshot cache (spec §5), also read by the push service. */
    private val snapshotStore: HostSnapshotStore = HostStores.snapshotStore(appContext)

    val fleet: FleetStore = run {
        HostStores.migrateFromLegacyIfNeeded(appContext)
        FleetStore(
            store = HostStores.store(appContext),
            scope = viewModelScope,
            deps = deps,
            snapshots = snapshotStore,
            appFactory = { url, token, onConn ->
                HostStore(
                    url, token, viewModelScope, deps,
                    onConnectionChange = onConn,
                    walkthroughSeam = AndroidWalkthroughSeam,
                    bindTts = { resolve, speak ->
                        MessageTts.resolveEngine = resolve
                        MessageTts.speakRemoteStream = speak
                    },
                )
            },
        )
    }

    init {
        fleet.bindMessageTts()
        // Spec §5: persist each host's last-known LIVE session list so the push service (and the
        // next launch) can name a session for a host that is currently offline.
        viewModelScope.launch {
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
    }

    override fun onCleared() {
        super.onCleared()
        fleet.close()
    }

    companion object {
        /** Factory so the VM is Activity-scoped via `viewModel(factory = …)` and survives config changes. */
        fun factory(application: Application) = viewModelFactory { initializer { AppViewModel(application) } }
    }
}
