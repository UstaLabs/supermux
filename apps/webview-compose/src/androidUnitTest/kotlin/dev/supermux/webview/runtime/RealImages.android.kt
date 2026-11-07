package dev.supermux.webview.runtime

import org.junit.Assume

/** Android's unit tests run on the build host's JVM, where android.jar is stubs: no real Bitmap. */
internal actual fun assumeRealImages() {
    Assume.assumeTrue("needs Android's real Bitmap: the host JVM has android.jar's stubs (the instrumented tests run it)", false)
}
