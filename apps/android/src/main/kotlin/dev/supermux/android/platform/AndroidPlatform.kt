package dev.supermux.android.platform

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    private val pickerHost: PickerHost,
    override val haptics: Haptics,
) : Platform {

    override val caps: Caps get() = ANDROID_CAPS

    /** ACTION_VIEW into whatever the user set as their browser. Swallows the "no activity" case. */
    override fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
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
 * The activity-scoped half of [AndroidPlatform.pickFiles].
 *
 * `rememberLauncherForActivityResult` can only be called from a composition inside a
 * `ComponentActivity`, so a plain class can never own a picker. The host holds the launchers
 * (registered by [rememberPickerHost]) plus the deferred that [AndroidPlatform] awaits, bridging
 * the callback-shaped result API to a `suspend fun`.
 *
 * One pick at a time: starting a second pick cancels the first with `null` (the same thing the OS
 * does — a new picker activity replaces the old result destination).
 */
class PickerHost {
    internal var anyFileLauncher: ActivityResultLauncher<String>? = null
    internal var visualMediaLauncher: ActivityResultLauncher<PickVisualMediaRequest>? = null

    private var pending: CompletableDeferred<Uri?>? = null

    internal suspend fun pick(kind: PickKind): Uri? {
        pending?.complete(null)
        val deferred = CompletableDeferred<Uri?>()
        pending = deferred
        val launched = when (kind) {
            PickKind.Any -> anyFileLauncher?.let { it.launch("*/*"); true }
            PickKind.Images -> visualMediaLauncher?.let {
                it.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                true
            }
            PickKind.Media -> visualMediaLauncher?.let {
                it.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                true
            }
        }
        if (launched != true) {
            // No launcher registered (a composition without rememberPickerHost) — behave like cancel.
            pending = null
            return null
        }
        return deferred.await()
    }

    internal fun deliver(uri: Uri?) {
        pending?.complete(uri)
        pending = null
    }
}

/** Registers the two picker launchers for the current activity and returns the host to hand to
 *  [AndroidPlatform]. Called once per entry point, from `AndroidTheme`. */
@Composable
fun rememberPickerHost(): PickerHost {
    val host = remember { PickerHost() }
    host.anyFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        host.deliver(uri)
    }
    host.visualMediaLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            host.deliver(uri)
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
