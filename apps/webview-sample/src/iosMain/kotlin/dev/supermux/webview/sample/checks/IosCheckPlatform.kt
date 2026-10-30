package dev.supermux.webview.sample.checks

import androidx.compose.ui.geometry.Rect
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSUserDomainMask
import platform.Foundation.writeToFile
import platform.QuartzCore.CADisplayLink
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsImageRenderer
import platform.UIKit.UIGraphicsImageRendererFormat
import platform.UIKit.UIGraphicsImageRendererFormatRangeStandard
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIView
import platform.UIKit.UIWindow
import platform.WebKit.WKWebView
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * The engine checks' platform in the iOS app (D11): copies of the key window without screen updates
 * (`drawViewHierarchyInRect`), the display link, the app's `Documents/handoff/`, and iOS's version.
 */
object IosCheckPlatform : CheckPlatform {
    override val name: String = "iOS"
    override val needsCutouts: Boolean = false
    /** On every iOS: from 17 each named profile's store persists; before, each has one of its own until the app quits. */
    override val namedProfilesSeparate: Boolean = true

    override fun exit(code: Int) {
        platform.posix.exit(code)
    }

    override fun copy(region: Rect): Captured? {
        val window = UIApplication.sharedApplication.keyWindow ?: return null
        val scale = window.screen.scale
        val points = CGRectMake(region.left / scale, region.top / scale, region.width / scale, region.height / scale)
        return UIKitCaptured(copy(window, points, scale))
    }

    override suspend fun everyFrame(onFrame: () -> Boolean) = suspendCancellableCoroutine<Unit> { continuation ->
        var link: CADisplayLink? = null
        val ticker = Ticker {
            if (!onFrame()) {
                link?.invalidate()
                link = null
                if (continuation.isActive) continuation.resume(Unit)
            }
        }
        val made = CADisplayLink.displayLinkWithTarget(ticker, NSSelectorFromString("tick"))
        made.addToRunLoop(NSRunLoop.mainRunLoop, NSRunLoopCommonModes)
        link = made
        continuation.invokeOnCancellation { made.invalidate() }
    }

    override fun folder(name: String, empty: Boolean): String {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val dir = "$documents/handoff/$name"
        if (empty) NSFileManager.defaultManager.removeItemAtPath(dir, null)
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        return dir
    }

    override fun save(image: Captured, path: String) {
        val ui = (image as? UIKitCaptured)?.image ?: return
        UIImagePNGRepresentation(ui)?.writeToFile(path, atomically = true)
    }

    override fun describePage(): String = describe(shownWebView())

    /** What [window] shows now in [region] (window points), at [scale] pixels a point, in standard colour. */
    private fun copy(window: UIWindow, region: CValue<CGRect>, scale: Double): UIImage {
        val format = UIGraphicsImageRendererFormat.preferredFormat()
        format.preferredRange = UIGraphicsImageRendererFormatRangeStandard
        format.scale = scale
        val (x, y) = region.useContents { origin.x to origin.y }
        val (width, height) = region.useContents { size.width to size.height }
        val (windowWidth, windowHeight) = window.bounds.useContents { size.width to size.height }
        return UIGraphicsImageRenderer(size = CGSizeMake(width, height), format = format).imageWithActions { _ ->
            window.drawViewHierarchyInRect(CGRectMake(-x, -y, windowWidth, windowHeight), afterScreenUpdates = false)
        }
    }

    /** "shown", or what hides the web view: itself or a view it is in. */
    private fun describe(view: WKWebView?): String {
        if (view == null) return "none in the window"
        var hiddenBy: String? = null
        var at: UIView? = view
        while (at != null && hiddenBy == null) {
            if (at.hidden) {
                hiddenBy = "${at::class.simpleName} hidden"
            } else if (at.alpha < 0.01) {
                hiddenBy = "${at::class.simpleName} at alpha ${at.alpha}"
            }
            at = at.superview
        }
        return if (hiddenBy == null) "shown" else "hidden ($hiddenBy)"
    }

    /** The web view the screen shows now: the one `WKWebView` in the app's window. */
    private fun shownWebView(): WKWebView? = UIApplication.sharedApplication.keyWindow?.let(::findWebView)

    private fun findWebView(view: UIView): WKWebView? {
        if (view is WKWebView) return view
        for (child in view.subviews) findWebView(child as UIView)?.let { return it }
        return null
    }
}

/** A UIKit copy of the window; its pixels are drawn into RGBA when asked for. */
class UIKitCaptured(val image: UIImage) : Captured {
    override fun pixels(): Pixels? = pixelsOf(image)
}

/** [image]'s pixels, as CoreGraphics draws them into an RGBA bitmap, as the engine reads its snapshots. */
fun pixelsOf(image: UIImage): Pixels? {
    val cg = image.CGImage ?: return null
    val width = CGImageGetWidth(cg).toInt()
    val height = CGImageGetHeight(cg).toInt()
    if (width <= 0 || height <= 0) return null
    val rgba = ByteArray(width * height * 4)
    val drawn = rgba.usePinned { pinned ->
        val space = CGColorSpaceCreateDeviceRGB()
        val context = CGBitmapContextCreate(
            pinned.addressOf(0), width.toULong(), height.toULong(), 8u, (width * 4).toULong(), space,
            CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
        )
        CGColorSpaceRelease(space)
        if (context == null) return@usePinned false
        CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cg)
        CGContextRelease(context)
        true
    }
    return if (drawn) Pixels(width, height, rgba) else null
}

/** A display link's target: runs [onTick] at every tick. */
@OptIn(BetaInteropApi::class)
private class Ticker(private val onTick: () -> Unit) : NSObject() {
    @ObjCAction
    fun tick() = onTick()
}
