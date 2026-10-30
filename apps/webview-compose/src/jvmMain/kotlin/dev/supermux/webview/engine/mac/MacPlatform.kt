package dev.supermux.webview.engine.mac

/** The desktop this JVM runs on: whether it is macOS, and which major version. */
internal object MacPlatform {
    /** The oldest macOS the engine runs on (D12): named data stores and a view's display link need 14. */
    const val MIN_MAJOR: Int = 14

    val isMac: Boolean = isMac(System.getProperty("os.name").orEmpty())

    /** 26 on macOS 26.6.2; 0 where this isn't macOS or the version can't be read. */
    val majorVersion: Int = if (isMac) majorVersion(System.getProperty("os.version").orEmpty()) else 0

    fun isMac(osName: String): Boolean =
        osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true)

    fun majorVersion(osVersion: String): Int = osVersion.substringBefore('.').trim().toIntOrNull() ?: 0
}
