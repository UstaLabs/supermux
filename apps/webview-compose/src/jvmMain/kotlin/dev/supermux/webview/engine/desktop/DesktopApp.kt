package dev.supermux.webview.engine.desktop

import java.io.File
import java.security.MessageDigest

/**
 * The app this process is, as a folder name: each app built on the library keeps its web data apart from every other's
 * (the Linux review's I8), on Linux (`WpeProfiles`) and Windows (`Wv2Profiles`) alike.
 */
internal object DesktopApp {
    /** What an app may set to name itself (`-Ddev.supermux.webview.app=<name>`), where its own name won't do. */
    const val APP_PROPERTY: String = "dev.supermux.webview.app"

    /** This process's app's folder name ([appId] of its system properties). */
    val id: String by lazy {
        appId(System.getProperty(APP_PROPERTY), System.getProperty("jpackage.app-path"), System.getProperty("sun.java.command"), System.getProperty("java.class.path"))
    }

    /**
     * The app's folder name, without the app doing anything:
     * - [property], if the app names itself;
     * - else its launcher's name ([launcher], `jpackage.app-path`: a Compose Desktop package's), without `.exe`;
     * - else its main class or jar ([command], `sun.java.command`), with a hash of where its own code lives ([classPath]'s
     *   first entry): two apps whose main is the root package's `MainKt` don't share a store.
     * Only `A-Z a-z 0-9 . _ -`, never a Windows device name, never ending in a dot: a folder's name, never a path.
     */
    fun appId(property: String?, launcher: String?, command: String?, classPath: String?): String {
        property?.takeIf { it.isNotBlank() }?.let { return sanitize(it) }
        launcher?.takeIf { it.isNotBlank() }?.let { return sanitize(File(it).name.removeSuffix(".exe")) }
        val main = command?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() } ?: return "default"
        val name = if (main.endsWith(".jar")) File(main).name.removeSuffix(".jar") else main
        val origin = classPath?.substringBefore(File.pathSeparatorChar)?.takeIf { it.isNotEmpty() } ?: main
        return sanitize(name) + "-" + sha256(origin).take(8)
    }

    private val RESERVED = setOf("con", "prn", "aux", "nul") + (1..9).flatMap { listOf("com$it", "lpt$it") }

    private fun sanitize(name: String): String {
        val cleaned = name.map { if (it.isLetterOrDigit() && it.code < 0x80 || it == '.' || it == '_' || it == '-') it else '_' }
            .joinToString("").take(64).trimStart('.').trimEnd('.').ifEmpty { "default" }
        return if (cleaned.substringBefore('.').lowercase() in RESERVED) "app-$cleaned" else cleaned
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}
