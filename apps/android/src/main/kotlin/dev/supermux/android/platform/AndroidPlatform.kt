package dev.supermux.android.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.supermux.android.chat.ContentResolverChunkSource
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.theme.Haptics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android's [Platform]: intents for links, the system clipboard, the SAF / photo pickers through a
 * [PickerHost], and [AndroidHaptics].
 *
 * `fileSystem = false` on purpose — Android can only read the content URIs the user granted, which
 * is not a browsable file system; screens that want a path picker must degrade on this flag.
 */
class AndroidPlatform(
    private val context: Context,
    private val pickerHost: PickerHost<Uri>,
    override val haptics: Haptics,
) : Platform {

    override val caps: Caps get() = ANDROID_CAPS

    /** ACTION_VIEW into whatever the user set as their browser. Swallows the "no activity" case. */
    override fun openUrl(url: String) {
        runCatching {
            // No FLAG_ACTIVITY_NEW_TASK: the browser must sit on top of our task so Back returns
            // to supermux (the behaviour of every call site this replaced).
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    /** Parity with iOS `UIPasteboard.general.string`; the clip label is not user-visible. */
    override fun copyToClipboard(text: String) {
        runCatching {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        }
    }

    /**
     * Suspends on the activity-result launcher registered by [rememberPickerHost] and resumes with
     * the picked URI turned into a streaming [PickedFile]. Cancel → empty list. Single-select: SAF
     * `GetContent` and the visual-media picker both hand back one URI here (unchanged behaviour).
     */
    override suspend fun pickFiles(kind: PickKind, requester: String): List<PickedFile> {
        val uri = pickerHost.pick(kind, requester) ?: return emptyList()
        return listOfNotNull(pickedFileFromUri(context, uri))
    }

    /**
     * Results of picks that completed with nobody left to await them — the user rotated the device
     * while the system picker was in the foreground, so the coroutine inside [pickFiles] died with
     * the old composition. The re-created screen collects this and stages the file exactly as if
     * its own `pickFiles` had returned it (what the old per-screen
     * `rememberLauncherForActivityResult` callbacks did for free).
     *
     * Only the screen that asked for the pick sees it: [requester] must match the one passed to
     * [pickFiles]. Collect it for the lifetime of the screen — a one-shot read races the delivery.
     */
    fun pendingPicks(requester: String): Flow<PickedFile> =
        pickerHost.unclaimed
            .filterNotNull()
            .filter { it.requester == requester }
            .mapNotNull { stash ->
                pickerHost.claim(stash)
                pickedFileFromUri(context, stash.result)
            }

    private companion object {
        const val CLIP_LABEL = "supermux"
    }
}

/**
 * What an Android device can do. A top-level value so it is assertable without an activity: a unit
 * test has no `Context`, and the capability table is exactly the part worth pinning.
 */
val ANDROID_CAPS = Caps(
    push = true,
    camera = true,
    tray = false,
    externalDisplay = true,
    hardwareVideoDecode = true,
    localBroker = false,
    multiWindow = false,
    fileSystem = false,
)

/** A pick that completed with nobody awaiting it, tagged with what asked for it. */
data class UnclaimedPick<R : Any>(val result: R, val kind: PickKind, val requester: String)

/**
 * The activity-scoped half of [AndroidPlatform.pickFiles], as a `suspend` bridge over the
 * callback-shaped activity-result API.
 *
 * `rememberLauncherForActivityResult` can only be called from a composition inside a
 * `ComponentActivity`, so a plain class can never own a picker. The host holds the launch hook
 * (installed by [rememberPickerHost]) plus the deferred that [AndroidPlatform] awaits.
 *
 * It is deliberately free of Android types (the result type [R] is `Uri` in production) so the
 * behaviours that actually bite are unit-testable without an activity: drive it through [pick] /
 * [deliver] / [unclaimed].
 *
 * Three rules:
 *  1. **Exactly one pick in flight.** The OS shows one picker; a second [pick] while one is
 *     outstanding (a double-tapped Attach button) returns `null` WITHOUT launching, so a result can
 *     only ever belong to the one caller recorded in [inFlightId] — no queue to fall out of sync.
 *  2. **Recreation.** The OS keeps working on a launched pick across an activity restart, but the
 *     coroutine awaiting it does not survive. [inFlightId] (plus its kind and requester) is
 *     mirrored into `rememberSaveable`, so a result arriving with nobody waiting is published on
 *     [unclaimed] and the re-created screen collects it instead of losing the file.
 *  3. **Nothing in flight → drop.** A delivery with no [inFlightId] (a duplicated callback, a
 *     result from a picker whose marker was never restored) is ignored rather than resuming or
 *     stashing on someone else's behalf.
 */
class PickerHost<R : Any> {

    /** Installed by [rememberPickerHost]; `null` in a composition that never registered launchers. */
    internal var onLaunch: ((PickKind) -> Unit)? = null

    /**
     * The request the OS is currently working on, or null. Mirrored into `rememberSaveable` by
     * [rememberPickerHost] (with [inFlightKind] / [inFlightRequester]) so it survives activity
     * recreation — that is the whole recreation fix.
     */
    var inFlightId: Long? = null
        private set
    var inFlightKind: PickKind? = null
        private set
    var inFlightRequester: String? = null
        private set

    private var nextId = 1L
    private var pending: CompletableDeferred<R?>? = null

    private val _unclaimed = MutableStateFlow<UnclaimedPick<R>?>(null)

    /** Results nobody was left to await (rule 2). Collected by the screen that asked for them. */
    val unclaimed: StateFlow<UnclaimedPick<R>?> = _unclaimed.asStateFlow()

    /** Re-installs the marker saved across an activity recreation. */
    internal fun restoreInFlight(id: Long, kind: PickKind, requester: String) {
        inFlightId = id
        inFlightKind = kind
        inFlightRequester = requester
    }

    /**
     * Launches a pick and suspends until its result. Returns `null` — an empty file list — when the
     * user cancels, when no launcher is registered, or when a pick is already in flight (rule 1).
     */
    suspend fun pick(kind: PickKind, requester: String): R? {
        if (inFlightId != null) return null // rule 1: the picker is already up
        val launch = onLaunch ?: return null
        val id = nextId++
        val deferred = CompletableDeferred<R?>()
        pending = deferred
        inFlightId = id
        inFlightKind = kind
        inFlightRequester = requester
        launch(kind)
        return try {
            deferred.await()
        } finally {
            // The caller went away (its screen left the composition, or the whole composition was
            // cancelled by an activity recreation) while the picker is still up: forget the waiter
            // but KEEP inFlightId, so the eventual result is stashed on [unclaimed] instead of
            // being completed into a dead deferred and lost.
            if (pending === deferred && !deferred.isCompleted) pending = null
        }
    }

    /** Called from the launcher callback with the picked result (`null` = cancelled). */
    internal fun deliver(result: R?) {
        val kind = inFlightKind
        val requester = inFlightRequester
        if (inFlightId == null) return // rule 3: nothing in flight — not ours to route
        inFlightId = null
        inFlightKind = null
        inFlightRequester = null
        val deferred = pending
        pending = null
        if (deferred != null) {
            deferred.complete(result)
            return
        }
        // Nobody is waiting: the activity was re-created mid-pick (rule 2).
        if (result != null && kind != null && requester != null) {
            _unclaimed.value = UnclaimedPick(result, kind, requester)
        }
    }

    /**
     * Forget an in-flight marker that can never be completed.
     *
     * Called on ON_RESUME (see [rememberPickerHost]). Being resumed means the picker activity is
     * gone, and the activity-result registry dispatches a pending result while the launcher
     * re-registers — i.e. before ON_RESUME — so a marker with no waiter at this point is one whose
     * callback will never arrive: the marker was restored from `rememberSaveable` but the result
     * was lost with the process. Leaving it would wedge rule 1 and every later pick would silently
     * return null.
     *
     * A marker WITH a waiter ([pending] non-null) is a live pick — the brief window right after
     * [pick] launches, before the picker covers us — and is left alone.
     *
     * @return true when a stuck marker was cleared (the caller re-syncs its saved copy).
     */
    fun clearStuckInFlight(): Boolean {
        if (inFlightId == null || pending != null) return false
        inFlightId = null
        inFlightKind = null
        inFlightRequester = null
        return true
    }

    /** Marks [stash] consumed. A second claim of the same stash is a no-op. */
    fun claim(stash: UnclaimedPick<R>) {
        _unclaimed.compareAndSet(stash, null)
    }
}

/** Registers the two picker launchers for the current activity and returns the host to hand to
 *  [AndroidPlatform]. Called once per entry point, from `AndroidTheme`. */
@Composable
fun rememberPickerHost(): PickerHost<Uri> {
    val host = remember { PickerHost<Uri>() }
    // The in-flight marker survives activity recreation, so a result arriving for a pick started
    // before the restart is recognised, tagged and re-routed rather than dropped (rule 2).
    var savedMarker by rememberSaveable { mutableStateOf<String?>(null) }
    remember(host) {
        // A marker is app-written, but it round-trips through a Bundle that outlives this build:
        // an unparseable id or a PickKind that no longer exists must drop the marker, not crash the
        // first composition after an upgrade.
        runCatching {
            savedMarker?.split('\u0000')?.let { (id, kind, requester) ->
                host.restoreInFlight(id.toLong(), PickKind.valueOf(kind), requester)
            }
        }
        Unit
    }
    fun syncMarker() {
        val id = host.inFlightId
        savedMarker = if (id == null) null
        else listOf(id.toString(), host.inFlightKind!!.name, host.inFlightRequester!!).joinToString("\u0000")
    }
    val anyFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        host.deliver(uri)
        syncMarker()
    }
    val visualMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        host.deliver(uri)
        syncMarker()
    }
    // Clear a marker the OS can no longer complete (see PickerHost.clearStuckInFlight): the
    // activity was re-created mid-pick and the result died with the old process, so nothing will
    // ever call deliver() and rule 1 would refuse every later pick.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && host.clearStuckInFlight()) savedMarker = null
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    host.onLaunch = { kind ->
        when (kind) {
            PickKind.Any -> anyFile.launch("*/*")
            PickKind.Images ->
                visualMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            PickKind.Media ->
                visualMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        syncMarker()
    }
    return host
}

/** The [Platform] for this activity: pickers + haptics bound to the hosting view. */
@Composable
fun rememberAndroidPlatform(): AndroidPlatform {
    val context = LocalContext.current
    val view = LocalView.current
    val host = rememberPickerHost()
    val haptics = remember(view) { AndroidHaptics(view) }
    return remember(context, host, haptics) { AndroidPlatform(context, host, haptics) }
}

/**
 * Name + byte size for a content URI (DISPLAY_NAME / SIZE, falling back to the fd's `statSize`),
 * wrapped as a streaming [PickedFile]. Null when the size cannot be determined or is zero — an
 * unchunkable source, which the composer/launcher previously dropped the same way.
 *
 * Shared by the pickers, the camera-capture callbacks and paste-to-attach, which all end up with a
 * URI and need identical staging (it used to be a copy of `queryNameSize`/`stageFromUri` in each
 * of `ChatPanel` and `SessionLauncherScreen`).
 */
suspend fun pickedFileFromUri(context: Context, uri: Uri): PickedFile? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size: Long? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
    }
    if (size == null) {
        size = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { it.statSize.takeIf { s -> s >= 0 } }
        }.getOrNull()
    }
    val bytes = size ?: return@withContext null
    if (bytes <= 0L) return@withContext null
    PickedFile(name, mime, ContentResolverChunkSource(resolver, uri, bytes))
}
