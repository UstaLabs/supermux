@file:OptIn(ExperimentalForeignApi::class)

package dev.supermux.editor.syntax

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.SEEK_END
import platform.posix.access
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

// The tables sit next to the test executable, i.e. in its main bundle (build.gradle.kts
// bundleTablesFor*), exactly where Supermux.app has them: no extra directory is needed.
internal actual fun useTestAppResources() = Unit

// The simulator runs on the Mac's file system: read the staged file where Gradle put it.
actual fun testResource(path: String): ByteArray {
    val file = listOf(TEST_RESOURCE_DIR, TEST_STATIC_RESOURCE_DIR).map { "$it/$path" }.firstOrNull { access(it, F_OK) == 0 }
        ?: "$TEST_RESOURCE_DIR/$path"
    val f = fopen(file, "rb") ?: error("test resource $file is missing: run native/build.sh gen on the Mac")
    try {
        fseek(f, 0L, SEEK_END)
        val n = ftell(f).toInt()
        fseek(f, 0L, SEEK_SET)
        val bytes = ByteArray(n)
        if (n > 0) bytes.usePinned { check(fread(it.addressOf(0), 1uL, n.toULong(), f).toInt() == n) { "short read of $file" } }
        return bytes
    } finally {
        fclose(f)
    }
}

internal actual fun goldenUpdateDir(): String? = null

internal actual fun writeTextFile(path: String, text: String): Unit = error("goldens are written on the JVM only")
