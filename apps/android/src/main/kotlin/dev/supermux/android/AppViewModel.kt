package dev.supermux.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStoreDeps
import dev.supermux.proto.ServerFrame
import dev.supermux.state.WalkthroughSeam
import dev.supermux.ui.editor.WalkthroughState
import dev.supermux.ui.prefs.UiPrefs

/** The Android half of the walkthrough seam: `:shared`'s [WalkthroughSeam] over `:ui`'s
 *  [WalkthroughState]. Identical to desktop's `DesktopWalkthroughSeam` and kept beside the DI that
 *  installs it (the `HostStore` factory in [AppFleet]), because the store's generic parameter is chosen
 *  per app. */
object AndroidWalkthroughSeam : WalkthroughSeam<WalkthroughState> {
    override fun create(sessionId: String) = WalkthroughState(sessionId)
    override fun apply(state: WalkthroughState, frame: ServerFrame) = state.applyServerFrame(frame)
}

/**
 * Activity-scoped handle on the process's [FleetStore] (spec §5). The store itself lives in
 * [AppFleet], which the assistant overlay's service shares; this view model holds it while the
 * activity lives (a rotation keeps it) and lets go when the activity really finishes. With nothing
 * else holding it, that closes every connection, exactly as when the view model owned it.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val shared = AppFleet.acquire(application, this)

    val deps: HostStoreDeps = shared.deps

    /** Editor + chat-detail preferences, on the same DataStore as drafts / launcher prefs. */
    val uiPrefs: UiPrefs = shared.uiPrefs

    val fleet: FleetStore = shared.fleet

    override fun onCleared() {
        super.onCleared()
        AppFleet.release(this)
    }

    companion object {
        /** Factory so the VM is Activity-scoped via `viewModel(factory = …)` and survives config changes. */
        fun factory(application: Application) = viewModelFactory { initializer { AppViewModel(application) } }
    }
}
