package dev.supermux.desktop.ui

import java.awt.Desktop
import java.net.URI
import kotlin.concurrent.thread

/**
 * Open [url] in the system browser (desktop replacement for Android's intent launch). Shared by
 * every "open a link" call site (the timeline, the finish dialog, the session-links menu).
 *
 * `Desktop.browse` blocks the calling thread while it hands off to the OS (it can spawn and wait on
 * the browser process), so it must NEVER run on the Compose UI thread — a click would freeze the
 * frame. Off-loaded to a short-lived daemon thread and guarded by `isDesktopSupported` + the BROWSE
 * capability check; a broken handoff logs (rather than silently swallowing) so it stays diagnosable.
 *
 * Test seam: [openInBrowserOverride] captures URLs without spawning a browser. When the JVM
 * property `supermux.tests=1` is set (desktop `test` task), the system browser is never opened even
 * if the override is null — no test may launch a browser (Agent OAuth would otherwise hang the full
 * suite waiting on inherited browser pipes).
 */
@Volatile
var openInBrowserOverride: ((String) -> Unit)? = null

fun openInBrowser(url: String) {
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
            if (Desktop.isDesktopSupported()) {
                val desktop = Desktop.getDesktop()
                if (desktop.isSupported(Desktop.Action.BROWSE)) desktop.browse(URI(url))
            }
        }.onFailure { e -> println("[browser] openInBrowser failed for $url: $e") }
    }
}
