package dev.supermux.webview.sample.checks

import androidx.compose.ui.geometry.Rect

/**
 * What the engine checks need from the app they run in (D11): `IosCheckPlatform` in the iOS app, `MacCheckPlatform` in
 * the macOS desktop app. Main thread.
 */
interface CheckPlatform {
    /** "iOS" or "macOS", for messages. */
    val name: String

    /** What this platform's engine answers to `needsCutouts`. */
    val needsCutouts: Boolean

    /** Whether named profiles get data stores of their own here. */
    val namedProfilesSeparate: Boolean

    /** Ends the app with [code]: 0 when every check passed. */
    fun exit(code: Int)

    /** What the window shows now in [region] (window pixels, as Compose lays out), at the screen's scale; null if nothing. */
    fun copy(region: Rect): Captured?

    /** Calls [onFrame] once a display frame, on the main thread, until it returns false. */
    suspend fun everyFrame(onFrame: () -> Boolean)

    /** The folder one run's frames go in, emptied first unless not [empty]: its path, for messages. */
    fun folder(name: String, empty: Boolean = true): String

    /** Saves [image] as a PNG at [path]. */
    fun save(image: Captured, path: String)

    /** The page's native view now, in a few words: shown, or what hides it. */
    fun describePage(): String
}

/** A copy of part of the window; its pixels are read when asked for. */
interface Captured {
    fun pixels(): Pixels?
}

// kotlin.test's asserts, for the checks, which run in the apps themselves: each failure throws an AssertionError.

fun <T> assertEquals(expected: T, actual: T, message: String? = null) {
    if (expected != actual) throw AssertionError((message?.let { "$it: " } ?: "") + "expected <$expected>, actual <$actual>")
}

fun assertTrue(actual: Boolean, message: String? = null) {
    if (!actual) throw AssertionError(message ?: "expected true")
}

fun assertFalse(actual: Boolean, message: String? = null) {
    if (actual) throw AssertionError(message ?: "expected false")
}

fun <T : Any> assertNotNull(actual: T?, message: String? = null): T = actual ?: throw AssertionError(message ?: "expected a value, got null")

fun assertNull(actual: Any?, message: String? = null) {
    if (actual != null) throw AssertionError((message?.let { "$it: " } ?: "") + "expected null, got <$actual>")
}
