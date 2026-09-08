// iOS's implementations of the shared chat/shell seams on `Platform` (cluster H2): the pasteboard,
// files, haptics. Everything UIKit-shaped lives on this side of the seam so no shared screen ever
// names it — the same split `DesktopChatSeams.kt` and `AndroidChatSeams.kt` already make.
package dev.supermux.ios

import dev.supermux.chat.mimeForFileName
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.platform.ClipboardAccess
import dev.supermux.ui.platform.FileAccess
import dev.supermux.ui.platform.PickedFile
import dev.supermux.ui.platform.SavedFile
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Haptics
import kotlinx.cinterop.ExperimentalForeignApi
import dev.supermux.util.toByteArray
import dev.supermux.util.toNSData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSFileManager
import platform.Foundation.writeToFile
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UIPasteboard

// ── haptics ──────────────────────────────────────────────────────────────────

/**
 * `UIFeedbackGenerator`, behind the shared three-kind [Haptics].
 *
 * The mapping matches what each kind MEANS rather than what it is called, so iOS feels like iOS and
 * Android like Android:
 *  - [HapticKind.Tick] → a LIGHT impact. The low-weight "something happened" tap (a row opened, the
 *    mic started); Android's `CLOCK_TICK`.
 *  - [HapticKind.Confirm] → the SUCCESS notification, not an impact. It is the two-part pattern iOS
 *    users already read as "that completed", which is exactly what the kind is for (message sent).
 *  - [HapticKind.Heavy] → a HEAVY impact, for the destructive confirmations (kill a session).
 *
 * `prepare()` is deliberately not called. It exists to warm the Taptic Engine when you know a
 * haptic is coming within the next second; here the call site IS the event, so preparing would add
 * a wake-up with nothing to hide it behind.
 *
 * Every call is on the main thread by construction — these come from Compose event callbacks — and
 * `UIFeedbackGenerator` is main-thread-only, so there is no dispatch here to get wrong.
 */
class IosHaptics : Haptics {
    override fun perform(kind: HapticKind) {
        when (kind) {
            HapticKind.Tick -> UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).impactOccurred()
            HapticKind.Confirm ->
                UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
            HapticKind.Heavy -> UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy).impactOccurred()
        }
    }
}

// ── clipboard ────────────────────────────────────────────────────────────────

/**
 * `UIPasteboard` images, behind the shared [ClipboardAccess] — what makes paste-to-attach work in
 * the composer.
 *
 * [hasImage] is the cheap probe the paste key handler runs on the frame it arrives: `hasImages`
 * inspects the pasteboard's type descriptors and never decodes. [readImages] does the decode and
 * the PNG re-encode, which for a screenshot is megabytes of work, so it hops off the main thread.
 *
 * PNG rather than the original representation, deliberately: what `UIPasteboard` hands back is a
 * decoded `UIImage`, so the original bytes are already gone by the time we see it. Re-encoding
 * lossily (JPEG) would degrade a screenshot of code — the single most common thing pasted into this
 * app — so the size cost is the right trade.
 */
class IosClipboardAccess : ClipboardAccess {

    override fun hasImage(): Boolean = UIPasteboard.generalPasteboard.hasImages

    override suspend fun readImages(): List<PickedFile> {
        val images = UIPasteboard.generalPasteboard.images.orEmpty().filterIsInstance<UIImage>()
        if (images.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            images.mapIndexedNotNull { index, image ->
                val png = UIImagePNGRepresentation(image) ?: return@mapIndexedNotNull null
                PickedFile(
                    name = if (images.size == 1) "pasted.png" else "pasted-${index + 1}.png",
                    mime = "image/png",
                    source = ByteArrayChunkSource(png.toByteArray()),
                )
            }
        }
    }
}

// ── files ────────────────────────────────────────────────────────────────────

/**
 * "Save as…", "Open with…" and temp staging on iOS.
 *
 * The two user-facing halves are Swift's — `UIDocumentPickerViewController(forExporting:)` and
 * `UIActivityViewController` both have to be PRESENTED from a view controller, which a Compose
 * surface does not have — so they go out through [IosBridge] and come back as callbacks.
 * [stageTemp] is pure file I/O and stays here.
 */
@OptIn(ExperimentalForeignApi::class)
class IosFileAccess(private val bridge: IosBridge) : FileAccess {

    override suspend fun saveAs(name: String, mime: String, bytes: ByteArray): SavedFile? {
        val leaf = safeFileName(name)
        val location = awaitCallback<String?> { done -> bridge.saveAs(leaf, mime, bytes, done) }
            ?: return null
        return SavedFile(leaf, location)
    }

    /**
     * False, and finished — the same answer Android gives, for the same reason.
     *
     * A `UIDocumentPicker` export hands the file to a location the user chose inside another app's
     * container; we are given a display name back, not a URL we may open, and the platform flow
     * after a save is the Files app rather than a second sheet from us. The shared caller treats
     * false as "nothing more to do", which is correct here.
     */
    override suspend fun openSaved(saved: SavedFile): Boolean = false

    override suspend fun openExternally(name: String, mime: String, bytes: ByteArray): Boolean =
        awaitCallback { done -> bridge.openExternally(safeFileName(name), mime, bytes, done) }

    /**
     * The shared `:shared` table only.
     *
     * Desktop and Android each ask the OS first and fall back to this table; iOS has an equivalent
     * (`UTType(filenameExtension:)`), but reaching for it would pull `UniformTypeIdentifiers` in for
     * a table that already covers everything the broker sends as an attachment. If a type the
     * shared table does not know starts mattering, that is a reason to extend the shared table —
     * where all three hosts benefit — rather than to diverge here.
     */
    override fun probeMime(name: String): String =
        mimeForFileName(safeFileName(name)) ?: "application/octet-stream"

    /**
     * A real file under `NSTemporaryDirectory()`, returned as a `file://` URL string.
     *
     * The inline video player takes a URL and demuxes from disk, so [name] must already carry the
     * container extension — the caller (`attachmentTempName` in `:ui`) builds it. The temp
     * directory is the right home: iOS empties it when it needs the space, which is exactly the
     * lifetime an attachment preview wants.
     */
    override suspend fun stageTemp(name: String, bytes: ByteArray): String? =
        withContext(Dispatchers.Default) {
            val dir = NSTemporaryDirectory() + "supermux-attachments"
            NSFileManager.defaultManager.createDirectoryAtPath(
                path = dir,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
            val path = "$dir/${safeFileName(name)}"
            if (!bytes.toNSData().writeToFile(path, atomically = true)) return@withContext null
            NSURL.fileURLWithPath(path).absoluteString
        }
}

/**
 * Strip any directory part and refuse an empty name.
 *
 * The attachment name is BROKER-supplied, so it is untrusted input that ends up in a path: a name
 * of `../../Library/Preferences/x.plist` would otherwise write outside the temp directory. The
 * same guard exists on desktop (`safeFileName` there) for the same reason.
 */
internal fun safeFileName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
