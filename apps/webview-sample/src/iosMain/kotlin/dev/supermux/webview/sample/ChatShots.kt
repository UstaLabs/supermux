package dev.supermux.webview.sample

import androidx.compose.ui.geometry.Rect
import dev.supermux.webview.sample.chat.AgentMessage
import dev.supermux.webview.sample.chat.ChatState
import dev.supermux.webview.sample.checks.IosCheckPlatform
import kotlinx.cinterop.useContents
import kotlinx.coroutines.delay
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication

/**
 * `-chat-shots`: the chat at a few places, each copied from the window once its previews have gone live, into the app's
 * `Documents/shots/<name>.png`, where `xcrun devicectl device copy from --domain-type appDataContainer` fetches them (a
 * device has no screenshot command over the network). Prints `SHOT <path>` for each, then `SHOTS DONE`.
 */
internal object ChatShots {
    /** Each shot's name and the preview whose message is brought to the bottom of the screen first (null: the newest). */
    private val SHOTS = listOf(
        "chat-latest" to null,
        "chat-3d" to "orbit-planet",
        "chat-art" to "fireworks-night",
        "chat-ui" to "flip-card",
    )

    suspend fun take(chat: ChatState) {
        delay(FIRST_MILLIS)
        for ((name, slug) in SHOTS) {
            if (slug != null) {
                val row = chat.items.indexOfFirst { item -> (item as? AgentMessage)?.previews?.any { it.slug == slug } == true }
                if (row < 0) continue
                chat.list.scrollToItem(chat.items.size - 1 - row)
                delay(SETTLE_MILLIS)
            }
            shoot(name)
        }
        println("SHOTS DONE")
    }

    private fun shoot(name: String) {
        val window = UIApplication.sharedApplication.keyWindow ?: return
        val scale = window.screen.scale
        val (width, height) = window.bounds.useContents { size.width to size.height }
        val image = IosCheckPlatform.copy(Rect(0f, 0f, (width * scale).toFloat(), (height * scale).toFloat())) ?: return
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val dir = "$documents/shots"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
        val path = "$dir/$name.png"
        IosCheckPlatform.save(image, path)
        println("SHOT $path")
    }

    private const val FIRST_MILLIS = 9_000L
    private const val SETTLE_MILLIS = 6_000L
}
