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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.supermux.android.chat.ContentResolverChunkSource
import dev.supermux.ui.platform.Caps
import dev.supermux.ui.platform.PickKind
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.theme.Haptics
import kotlinx.coroutines.CompletableDeferred
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
    override suspend fun pickFiles(kind: PickKind): List<PickedFile> {
        val uri = pickerHost.pick(kind) ?: return emptyList()
        return listOfNotNull(pickedFileFromUri(context, uri))
    }

    /**
     * The result of a pick the user started BEFORE the activity was recreated (rotation, dark-mode
     * flip, process death → restore). The coroutine that was awaiting it died with the old
     * composition, so the re-created screen claims the stashed URI here — exactly what the old
     * per-screen `rememberLauncherForActivityResult` callbacks did for free.
     */
    suspend fun claimPendingPick(): PickedFile? {
        val uri = pickerHost.drainUnclaimed() ?: return null
        return pickedFileFromUri(context, uri)
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

/**
 * The activity-scoped half of [AndroidPlatform.pickFiles], as a `suspend` bridge over the
 * callback-shaped activity-result API.
 *
 * `rememberLauncherForActivityResult` can only be called from a composition inside a
 * `ComponentActivity`, so a plain class can never own a picker. The host holds the launch hook
 * (installed by [rememberPickerHost]) plus the deferred that [AndroidPlatform] awaits.
 *
 * It is deliberately free of Android types (the result type [R] is `Uri` in production) so the
 * three behaviours that actually bite — recreation, stale results, cancel — are unit-testable
 * without an activity: drive it through [pick] / [deliver] / [drainUnclaimed].
 *
 * Three rules:
 *  1. **Recreation.** The OS keeps working on a launched pick across an activity restart, but the
 *     coroutine awaiting it does not survive. [inFlightId] is mirrored into `rememberSaveable`, so
 *     a result arriving with nobody waiting is stashed and the re-created screen claims it through
 *     [drainUnclaimed] (`AndroidPlatform.claimPendingPick`) instead of silently losing the file.
 *  2. **Stale results.** Every launch takes a monotonic request id, queued in launch order (the
 *     order the OS delivers them). A result whose id is not the current request — a superseded
 *     pick answering late — is dropped rather than resuming the wrong caller.
 *  3. **One at a time.** Starting a pick cancels any previous one with `null` (an empty list).
 */
class PickerHost<R : Any> {

    /** Installed by [rememberPickerHost]; `null` in a composition that never registered launchers. */
    internal var onLaunch: ((PickKind) -> Unit)? = null

    /**
     * The request the OS is currently working on, or null. Mirrored into `rememberSaveable` by
     * [rememberPickerHost] so it survives activity recreation — that is the whole recreation fix.
     */
    var inFlightId: Long? = null
        internal set

    private var nextId = 1L
    private val launched = ArrayDeque<Long>()
    private var pending: CompletableDeferred<R?>? = null
    private var pendingId: Long? = null
    private var unclaimed: R? = null

    /** Launches a pick and suspends until its result (or `null` for cancel / no launcher). */
    suspend fun pick(kind: PickKind): R? {
        // A pick that completed while this caller's composition was being re-created: hand it over
        // instead of opening a second picker.
        drainUnclaimed()?.let { return it }
        pending?.complete(null)
        val launch = onLaunch
        if (launch == null) {
            pending = null
            pendingId = null
            inFlightId = null
            return null
        }
        val id = nextId++
        val deferred = CompletableDeferred<R?>()
        pending = deferred
        pendingId = id
        inFlightId = id
        launched.addLast(id)
        launch(kind)
        return deferred.await()
    }

    /**
     * Called from the launcher callback with the picked result (`null` = cancelled). Attributes it
     * to the oldest outstanding launch; after recreation nothing is outstanding, so it falls back
     * to the id restored from `rememberSaveable`.
     */
    internal fun deliver(result: R?) {
        val id = launched.removeFirstOrNull() ?: inFlightId ?: return
        if (id != inFlightId) return // a superseded pick answering late — never resume on it
        val deferred = pending
        if (deferred != null && pendingId == id) {
            pending = null
            pendingId = null
            inFlightId = null
            deferred.complete(result)
            return
        }
        // Nobody is waiting: the activity was re-created mid-pick. Stash for the new screen.
        inFlightId = null
        unclaimed = result
    }

    /** Takes the result stashed by rule 1, if any. Idempotent: a second call returns null. */
    fun drainUnclaimed(): R? {
        val stashed = unclaimed ?: return null
        unclaimed = null
        return stashed
    }
}

/** Registers the two picker launchers for the current activity and returns the host to hand to
 *  [AndroidPlatform]. Called once per entry point, from `AndroidTheme`. */
@Composable
fun rememberPickerHost(): PickerHost<Uri> {
    val host = remember { PickerHost<Uri>() }
    // Survives activity recreation, so a result arriving for a pick started before the restart is
    // recognised (and stashed) rather than dropped. See PickerHost rule 1.
    var savedInFlight by rememberSaveable { mutableStateOf(0L) }
    remember(host) { host.inFlightId = savedInFlight.takeIf { it != 0L }; Unit }
    val anyFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        host.deliver(uri)
        savedInFlight = host.inFlightId ?: 0L
    }
    val visualMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        host.deliver(uri)
        savedInFlight = host.inFlightId ?: 0L
    }
    host.onLaunch = { kind ->
        when (kind) {
            PickKind.Any -> anyFile.launch("*/*")
            PickKind.Images ->
                visualMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            PickKind.Media ->
                visualMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        }
        savedInFlight = host.inFlightId ?: 0L
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
