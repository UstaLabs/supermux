package dev.supermux.terminal

import org.junit.Assume

/**
 * JNI test hooks that exist ONLY in the `-DST_JNI_TEST_HOOKS` build of the JNI library
 * (`build/native/<host>/test-lib/libsupermux_terminal_jni_test.*`, made by `native/build.sh <host>
 * --test`), which Gradle's jvmTest loads through `-Dsupermux.terminal.nativeLibrary`. The packaged
 * release library does not export them.
 */
internal object NativeTerminalTestHooks {
    /** [buffers taken, buffers freed, array elements acquired, array elements released]. */
    @JvmStatic external fun debugCounters(): LongArray

    /** The next output byte[] allocation fails with OutOfMemoryError. */
    @JvmStatic external fun debugFailNextArray(fail: Boolean)

    /** Skip (not fail) the calling test when the loaded library is the release build. */
    fun assumeAvailable() {
        JvmNativeLibrary.ensureLoaded()
        val available = try {
            debugCounters()
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
        Assume.assumeTrue(
            "JNI test hooks unavailable: run native/build.sh <host> --test (jvmTest then loads test-lib/)",
            available,
        )
    }
}
