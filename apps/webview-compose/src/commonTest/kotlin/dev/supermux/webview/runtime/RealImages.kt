package dev.supermux.webview.runtime

/**
 * Skips the calling test where the platform can't make real images: Android's unit tests, which run on the build host's
 * JVM against android.jar's stubs (`Bitmap.createBitmap` throws "not mocked"). The test is reported as skipped there, and
 * runs everywhere else. On Android the snapshot codec runs on a device, in the instrumented tests.
 */
internal expect fun assumeRealImages()
