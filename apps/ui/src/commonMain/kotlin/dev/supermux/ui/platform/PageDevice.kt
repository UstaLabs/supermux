package dev.supermux.ui.platform

/**
 * What a web page in a Browser tab or an applet may reach on this device (#157): where its downloads land, the
 * system picker for its file inputs, and the OS's own grant behind a permission the user gave it.
 */
interface PageDevice {
    /**
     * A folder this app owns where a page's download is saved, before the downloads bar hands it over (#95's Open,
     * Save, Share). Null where pages don't download through the app (the web: the browser saves them itself).
     */
    val downloadsDir: String? get() = null

    /**
     * The system picker for a page's `<input type=file>`: the chosen files as URIs the web engine reads (`file:` on
     * desktop, `content:` on Android), or null when the user cancelled or this host has none (iOS and the web, whose
     * engines show their own). [acceptTypes] are the input's `accept` values: MIME types (`image/png`, or a family:
     * `image/` and a star) and extensions (`.pdf`).
     */
    suspend fun pickForPage(acceptTypes: List<String>, multiple: Boolean, title: String): List<String>? = null

    /**
     * The OS's grant for [what], asked for now if the app doesn't hold it (Android's runtime permissions). True where
     * the OS asks by itself when the engine first uses it (iOS, macOS), or needs nothing.
     */
    suspend fun grantOs(what: DeviceGrant): Boolean = true

    companion object {
        val None: PageDevice = object : PageDevice {}
    }
}

/** What a page may use that the OS itself guards for the app. */
enum class DeviceGrant { Camera, Microphone, Location }

/**
 * Whether a file named [name], of type [mime], is one a file input's [accept] list takes: an extension (`.pdf`), a
 * MIME type (`application/pdf`) or a family (`image/` and a star). An empty list takes any file, as a browser's picker
 * does.
 */
fun pageAccepts(accept: List<String>, name: String, mime: String): Boolean {
    val wanted = accept.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    if (wanted.isEmpty()) return true
    val lowerName = name.lowercase()
    val lowerMime = mime.lowercase()
    return wanted.any { a ->
        when {
            a.startsWith(".") -> lowerName.endsWith(a)
            a.endsWith("/*") -> lowerMime.startsWith(a.dropLast(1))
            else -> lowerMime == a
        }
    }
}
