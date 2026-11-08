package dev.supermux.webview.engine.desktop

import org.jetbrains.skiko.OS
import org.jetbrains.skiko.hostOs

/**
 * The desktop this JVM runs on, as Skiko tells it: macOS, Windows or Linux; null on any other, where no engine runs. The
 * library asks Skiko, not `os.name` (the repository's platform guard), and never fails on a desktop Skiko doesn't know:
 * Skiko throws there, and the engines report Unavailable instead.
 */
internal val hostDesktop: OS? = runCatching { hostOs }.getOrNull()?.takeIf { it == OS.MacOS || it == OS.Windows || it == OS.Linux }
