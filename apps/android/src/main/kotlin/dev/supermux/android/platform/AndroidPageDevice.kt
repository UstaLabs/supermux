package dev.supermux.android.platform

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import dev.supermux.ui.platform.PageDevice
import dev.supermux.ui.platform.DeviceGrant
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * What a web page in a Browser tab reaches on Android (#157). Its downloads land in the app's cache, as #95's do, for the downloads
 * list to open, save or share; its file inputs get the system document picker, its types and multiple choice as
 * asked, handing WebView `content:` URIs it reads with the grant the picker gave; and a permission the user gave a
 * site is backed by the app's runtime permission, asked for then.
 */
internal class AndroidPageDevice(
    private val context: Context,
    private val picks: PagePickHost,
    private val permissions: PermissionHost,
) : PageDevice {
    override val downloadsDir: String by lazy { File(context.cacheDir, "downloads").apply { mkdirs() }.absolutePath }

    override suspend fun pickForPage(acceptTypes: List<String>, multiple: Boolean, title: String): List<String>? =
        picks.pick(pickerTypes(acceptTypes), multiple)?.map(Uri::toString)

    override suspend fun grantOs(what: DeviceGrant): Boolean {
        val needed = when (what) {
            DeviceGrant.Camera -> android.Manifest.permission.CAMERA
            DeviceGrant.Microphone -> android.Manifest.permission.RECORD_AUDIO
            DeviceGrant.Location -> android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED) return true
        // Location: approximate is a grant too (Android 12+ offers it in the same prompt).
        if (what == DeviceGrant.Location &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        return permissions.request(needed)
    }
}

/** The picker's MIME types for an input's `accept` list: extensions become their types; nothing, or anything, is any file. */
internal fun pickerTypes(accept: List<String>): Array<String> {
    val types = accept.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.mapNotNull { a ->
        if (a.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(a.drop(1)) else a
    }.distinct()
    return if (types.isEmpty()) arrayOf("*/*") else types.toTypedArray()
}

/**
 * The activity's document picker for pages: one at a time. No stash: a page's chooser does not outlive the activity
 * that showed it (WebView cancels it), so a recreation mid-pick simply cancels.
 */
class PagePickHost {
    internal var onLaunch: ((Array<String>, Boolean) -> Unit)? = null
    private var pending: CompletableDeferred<List<Uri>?>? = null

    suspend fun pick(types: Array<String>, multiple: Boolean): List<Uri>? {
        if (pending != null) return null
        val launch = onLaunch ?: return null
        val deferred = CompletableDeferred<List<Uri>?>()
        pending = deferred
        launch(types, multiple)
        return try {
            deferred.await()
        } finally {
            if (pending === deferred) pending = null
        }
    }

    internal fun deliver(uris: List<Uri>?) {
        val deferred = pending ?: return
        pending = null
        deferred.complete(uris?.takeIf { it.isNotEmpty() })
    }
}

/** Registers the page pickers (one file, several) for the current activity. */
@Composable
fun rememberPagePickHost(): PagePickHost {
    val host = remember { PagePickHost() }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> host.deliver(uri?.let(::listOf)) }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> host.deliver(uris) }
    SideEffect {
        host.onLaunch = { types, multiple ->
            runCatching { if (multiple) many.launch(types) else one.launch(types) }.onFailure { host.deliver(null) }
        }
    }
    return host
}
