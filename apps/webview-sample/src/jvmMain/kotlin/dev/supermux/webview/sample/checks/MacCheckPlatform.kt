package dev.supermux.webview.sample.checks

import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.geometry.Rect
import dev.supermux.webview.testing.Copied
import dev.supermux.webview.testing.MacEngineTesting
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import kotlin.system.exitProcess

/**
 * The engine checks' platform in the macOS desktop app (D11): the window as the window server composited it
 * (`MacEngineTesting.copy`, M12), Compose's frames for display frames, frames saved under [out]`/handoff/`, and the
 * page view's state from the glue.
 */
class MacCheckPlatform(
    /** The window the checks run in: what [MacEngineTesting] drives. */
    val window: ComposeWindow,
    private val out: File,
) : CheckPlatform {
    /** Compose's density here: the window's backing scale. */
    val density: Float get() = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()?.takeIf { it > 0f } ?: 1f
    override val name: String = "macOS"
    override val needsCutouts: Boolean = true
    override val namedProfilesSeparate: Boolean = true

    override fun exit(code: Int) {
        exitProcess(code)
    }

    override fun copy(region: Rect): Captured? =
        MacEngineTesting.copy(window, region.left, region.top, region.width, region.height)?.let(::MacCaptured)

    override suspend fun everyFrame(onFrame: () -> Boolean) {
        while (true) {
            withFrameNanos { }
            if (!onFrame()) return
        }
    }

    override fun folder(name: String, empty: Boolean): String {
        val dir = File(out, "handoff/$name")
        if (empty) dir.deleteRecursively()
        dir.mkdirs()
        return dir.path
    }

    override fun save(image: Captured, path: String) {
        val pixels = image.pixels() ?: return
        val bitmap = Bitmap()
        if (!bitmap.allocPixels(ImageInfo(pixels.width, pixels.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL))) return
        if (!bitmap.installPixels(pixels.rgba)) return
        val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG) ?: return
        File(path).writeBytes(png.bytes)
    }

    override fun describePage(): String = MacEngineTesting.describePage(window)

    companion object {
        /** Pure green: the window's backing in check runs, where neither Compose nor a page draws. No check page has it. */
        const val BACKING: Int = 0xFF00FF00.toInt()
    }
}

/** A window copy from the glue: its pixels as they came. */
class MacCaptured(private val copied: Copied) : Captured {
    override fun pixels(): Pixels = Pixels(copied.width, copied.height, copied.rgba)
}
