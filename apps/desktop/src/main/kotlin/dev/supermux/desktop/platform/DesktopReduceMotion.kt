package dev.supermux.desktop.platform

import java.util.concurrent.TimeUnit

/** What one settings query's [output] says about reduced motion on [osName]; false when it cannot tell. */
internal fun reduceMotionFrom(osName: String, output: String?): Boolean {
    val value = output?.trim() ?: return false
    return when {
        osName.startsWith("Mac", ignoreCase = true) -> value == "1"
        osName.startsWith("Linux", ignoreCase = true) -> value == "false"
        else -> false
    }
}

/** Asks the OS once: macOS `defaults`, GNOME `gsettings`. Windows and anything unknown read as false. Blocking. */
internal fun probeReduceMotion(osName: String = System.getProperty("os.name").orEmpty()): Boolean {
    val command = when {
        osName.startsWith("Mac", ignoreCase = true) -> listOf("defaults", "read", "com.apple.universalaccess", "reduceMotion")
        osName.startsWith("Linux", ignoreCase = true) -> listOf("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")
        else -> return false
    }
    return runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else {
            reduceMotionFrom(osName, process.inputStream.bufferedReader().readText())
        }
    }.getOrDefault(false)
}
