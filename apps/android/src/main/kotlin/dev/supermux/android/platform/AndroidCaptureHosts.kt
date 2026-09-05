// The activity-scoped launcher hosts the cluster-D seams need, beside `PickerHost`/`QrScanHost`:
// camera capture, SAF "create document", and a runtime permission prompt.
//
// They exist for the same reason those two do: `rememberLauncherForActivityResult` can only be
// called from a composition inside a `ComponentActivity`, so a plain `AndroidPlatform` can never
// own a launcher. Each host holds the launch hook (installed by its `remember…Host()`) plus the
// deferred the suspending seam awaits, and each follows the same three rules as `PickerHost`:
// one request in flight, a `rememberSaveable` marker so an activity recreation does not wedge the
// host, and "nothing in flight -> drop the delivery".
package dev.supermux.android.platform

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which camera contract to launch. */
enum class CaptureKind { Image, Video }

/** A finished capture nobody was left to await, tagged with the screen that asked for it. */
data class UnclaimedCapture(val result: Uri, val requester: String)

/**
 * The activity-scoped half of `AndroidPlatform.captureImage` / `captureVideo`.
 *
 * Unlike a pick, the app supplies the DESTINATION: `TakePicture`/`CaptureVideo` write into a
 * `FileProvider` URI we create first and hand to the camera app, and the result is only a boolean
 * "did it write". So the in-flight marker carries the URI as well, and it is that URI — not
 * anything the OS returns — that becomes the staged file.
 *
 * Recreation is the case that matters: a camera app is a full-screen activity and rotating behind
 * it is routine. The marker (id + kind + requester + URI) is mirrored into `rememberSaveable`, so a
 * capture that finishes with nobody awaiting it is published on [unclaimed] and the re-created
 * screen stages the photo instead of the user losing it.
 */
class CaptureHost {

    /** Installed by [rememberCaptureHost]; null in a composition that never registered launchers. */
    internal var onLaunch: ((CaptureKind, Uri) -> Unit)? = null

    var inFlightId: Long? = null
        private set
    var inFlightKind: CaptureKind? = null
        private set
    var inFlightRequester: String? = null
        private set
    var inFlightUri: Uri? = null
        private set

    private var nextId = 1L
    private var pending: CompletableDeferred<Uri?>? = null

    private val _unclaimed = MutableStateFlow<UnclaimedCapture?>(null)

    /** Captures nobody was left to await. Collected by the screen that asked for them. */
    val unclaimed: StateFlow<UnclaimedCapture?> = _unclaimed.asStateFlow()

    /** Re-installs the marker saved across an activity recreation. */
    internal fun restoreInFlight(id: Long, kind: CaptureKind, requester: String, uri: Uri) {
        inFlightId = id
        inFlightKind = kind
        inFlightRequester = requester
        inFlightUri = uri
    }

    /**
     * Launches the camera writing into [target] and suspends until it returns. Null when the user
     * backed out, when no launcher is registered, or when a capture is already in flight.
     */
    suspend fun capture(kind: CaptureKind, target: Uri, requester: String): Uri? {
        if (inFlightId != null) return null // the camera is already up
        val launch = onLaunch ?: return null
        val id = nextId++
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        inFlightId = id
        inFlightKind = kind
        inFlightRequester = requester
        inFlightUri = target
        launch(kind, target)
        return try {
            deferred.await()
        } finally {
            // The caller went away while the camera is still up: forget the waiter but KEEP the
            // marker, so the eventual result is stashed on [unclaimed] instead of being lost.
            if (pending === deferred && !deferred.isCompleted) pending = null
        }
    }

    /** Called from the launcher callback: true = the camera wrote into the URI we supplied. */
    internal fun deliver(ok: Boolean) {
        if (inFlightId == null) return // nothing in flight — not ours to route
        val uri = inFlightUri
        val requester = inFlightRequester
        inFlightId = null
        inFlightKind = null
        inFlightRequester = null
        inFlightUri = null
        val result = if (ok) uri else null
        val deferred = pending
        pending = null
        if (deferred != null) {
            deferred.complete(result)
            return
        }
        if (result != null && requester != null) _unclaimed.value = UnclaimedCapture(result, requester)
    }

    /** Forget a marker the OS can no longer complete. See `PickerHost.clearStuckInFlight`. */
    fun clearStuckInFlight(): Boolean {
        if (inFlightId == null || pending != null) return false
        inFlightId = null
        inFlightKind = null
        inFlightRequester = null
        inFlightUri = null
        return true
    }

    /** Marks [stash] consumed. A second claim of the same stash is a no-op. */
    fun claim(stash: UnclaimedCapture) {
        _unclaimed.compareAndSet(stash, null)
    }
}

/** Registers the two camera launchers for the current activity. Called beside `rememberPickerHost`. */
@Composable
fun rememberCaptureHost(): CaptureHost {
    val host = remember { CaptureHost() }
    var savedMarker by rememberSaveable { mutableStateOf<String?>(null) }
    remember(host) {
        // The marker round-trips through a Bundle that outlives this build: an unparseable id or a
        // CaptureKind that no longer exists must drop the marker, not crash the first composition.
        runCatching {
            savedMarker?.split(' ')?.let { (id, kind, requester, uri) ->
                host.restoreInFlight(id.toLong(), CaptureKind.valueOf(kind), requester, Uri.parse(uri))
            }
        }
        Unit
    }
    fun syncMarker() {
        val id = host.inFlightId
        savedMarker = if (id == null) {
            null
        } else {
            // Space-separated: an id is digits, a kind is a Kotlin identifier, a requester is a
            // screen key and a content URI is percent-encoded — none can contain a space.
            listOf(
                id.toString(),
                host.inFlightKind!!.name,
                host.inFlightRequester!!,
                host.inFlightUri!!.toString(),
            ).joinToString(" ")
        }
    }
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        host.deliver(ok == true)
        syncMarker()
    }
    val captureVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        host.deliver(ok == true)
        syncMarker()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && host.clearStuckInFlight()) savedMarker = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SideEffect {
        host.onLaunch = { kind, uri ->
            when (kind) {
                CaptureKind.Image -> takePicture.launch(uri)
                CaptureKind.Video -> captureVideo.launch(uri)
            }
            syncMarker()
        }
    }
    return host
}

/**
 * The activity-scoped half of `AndroidPlatform.files.saveAs`: SAF `CreateDocument`.
 *
 * Deliberately WITHOUT an unclaimed stash, unlike [CaptureHost]. The bytes being saved live in the
 * caller's memory, so an activity recreation mid-save loses them regardless — re-delivering the
 * chosen document URI to a screen that no longer has anything to write would be worse than useless.
 * A recreation therefore just cancels the save; the stuck marker is cleared on the next ON_RESUME
 * so the NEXT save still works.
 */
class SaveHost {

    internal var onLaunch: ((mime: String, name: String) -> Unit)? = null

    var inFlight: Boolean = false
        private set

    private var pending: CompletableDeferred<Uri?>? = null

    /** Launches the create-document dialog and suspends until the user picks or cancels. */
    suspend fun save(mime: String, name: String): Uri? {
        if (inFlight) return null // one dialog at a time
        val launch = onLaunch ?: return null
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        inFlight = true
        launch(mime, name)
        return try {
            deferred.await()
        } finally {
            if (pending === deferred && !deferred.isCompleted) pending = null
        }
    }

    internal fun deliver(uri: Uri?) {
        if (!inFlight) return
        inFlight = false
        val deferred = pending
        pending = null
        deferred?.complete(uri)
    }

    /** Forget a marker whose result died with the process (see `PickerHost.clearStuckInFlight`). */
    fun clearStuckInFlight(): Boolean {
        if (!inFlight || pending != null) return false
        inFlight = false
        return true
    }
}

/** Registers the SAF create-document launcher for the current activity. */
@Composable
fun rememberSaveHost(): SaveHost {
    val host = remember { SaveHost() }
    // The mime is a launcher-CONSTRUCTION argument, so one launcher per mime is impossible; the
    // generic contract takes the mime at construction and the NAME at launch. `*/*` lets the picker
    // offer any location, and the file keeps the extension carried by the name we pass.
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        host.deliver(uri)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) host.clearStuckInFlight()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SideEffect { host.onLaunch = { _, name -> launcher.launch(name) } }
    return host
}

/**
 * The activity-scoped half of `AndroidPlatform.mic.requestPermission`: one runtime permission
 * prompt at a time.
 *
 * No stash either: a recreation mid-prompt loses the awaiting coroutine, and the re-created screen
 * simply re-reads the grant with `checkSelfPermission` — which is authoritative and free — rather
 * than being handed a stale answer.
 */
class PermissionHost {

    internal var onLaunch: ((String) -> Unit)? = null

    var inFlight: Boolean = false
        private set

    private var pending: CompletableDeferred<Boolean>? = null

    /** Prompts for [permission] and suspends until the user answers. False when no launcher is
     *  registered or a prompt is already up. */
    suspend fun request(permission: String): Boolean {
        if (inFlight) return false
        val launch = onLaunch ?: return false
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        inFlight = true
        launch(permission)
        return try {
            deferred.await()
        } finally {
            if (pending === deferred && !deferred.isCompleted) pending = null
        }
    }

    internal fun deliver(granted: Boolean) {
        if (!inFlight) return
        inFlight = false
        val deferred = pending
        pending = null
        deferred?.complete(granted)
    }

    /** Forget a marker whose result died with the process (see `PickerHost.clearStuckInFlight`). */
    fun clearStuckInFlight(): Boolean {
        if (!inFlight || pending != null) return false
        inFlight = false
        return true
    }
}

/** Registers the runtime-permission launcher for the current activity. */
@Composable
fun rememberPermissionHost(): PermissionHost {
    val host = remember { PermissionHost() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        host.deliver(granted)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) host.clearStuckInFlight()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    SideEffect { host.onLaunch = { permission -> launcher.launch(permission) } }
    return host
}
