package dev.supermux.desktop.platform

import dev.supermux.net.isOpenableLink
import dev.supermux.net.linkScheme
import java.awt.Desktop
import java.net.URI
import kotlin.concurrent.thread

/**
 * Open [url] in the system browser (desktop replacement for Android's intent launch). Shared by
 * every "open a link" call site (the timeline, the finish dialog, the session-links menu).
 *
 * `Desktop.browse` blocks the calling thread while it hands off to the OS (it can spawn and wait on
 * the browser process), so it must NEVER run on the Compose UI thread — a click would freeze the
 * frame. Off-loaded to a short-lived daemon thread. Where AWT cannot browse, or fails, the OS opener takes the link
 * ([openUrlWith]); a broken handoff logs (rather than silently swallowing) so it stays diagnosable.
 *
 * Test seam: [openInBrowserOverride] captures URLs without spawning a browser. When the JVM
 * property `supermux.tests=1` is set (desktop `test` task), the system browser is never opened even
 * if the override is null — no test may launch a browser (Agent OAuth would otherwise hang the full
 * suite waiting on inherited browser pipes).
 */
@Volatile
var openInBrowserOverride: ((String) -> Unit)? = null

fun openInBrowser(url: String) {
    // AWT and the OS openers would also run a `file:` program or a share's: only links that leave the app open.
    if (!isOpenableLink(url)) {
        println("[browser] not opening a ${linkScheme(url) ?: "relative"} link")
        return
    }
    val override = openInBrowserOverride
    if (override != null) {
        override(url)
        return
    }
    if (System.getProperty("supermux.tests") == "1") {
        // Visible in test logs if a code path still tries to open a browser without an override.
        println("[browser] suppressed under supermux.tests: $url")
        return
    }
    openInSystemBrowser(url)
}

private fun openInSystemBrowser(url: String) {
    thread(isDaemon = true, name = "open-browser") {
        runCatching {
            val desktop = if (Desktop.isDesktopSupported()) Desktop.getDesktop() else null
            val browse = desktop?.takeIf { it.isSupported(Desktop.Action.BROWSE) }?.let { d -> { uri: URI -> d.browse(uri) } }
            openUrlWith(url, browse) { link -> openCommand(link).start() }
        }.onFailure { e -> println("[browser] openInBrowser failed for $url: $e") }
    }
}

/**
 * Open [url]: AWT's [browse] first; where AWT cannot browse (null: no desktop integration, e.g. a Linux session
 * without GNOME's libraries) or it throws, [fallback], the OS opener ([openCommand]: xdg-open, open, ShellExecute).
 * Text that is not a URL is opened by neither.
 */
internal fun openUrlWith(url: String, browse: ((URI) -> Unit)?, fallback: (String) -> Unit) {
    val uri = runCatching { URI(url) }.getOrElse { println("[browser] not a URL, not opened: $url"); return }
    if (browse != null) {
        try {
            browse(uri)
            return
        } catch (e: Exception) {
            println("[browser] AWT could not open $url ($e); trying the OS opener")
        }
    }
    fallback(uri.toString())
}
