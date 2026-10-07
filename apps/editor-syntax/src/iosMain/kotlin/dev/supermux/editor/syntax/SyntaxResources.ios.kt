@file:OptIn(ExperimentalForeignApi::class)

package dev.supermux.editor.syntax

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

actual object SyntaxResources {
    /**
     * Directories searched after the main bundle's resource directory, each holding the same
     * layout (`editor-syntax/tables/<lang>.sesz`). Set before the first [NativeBackend.ensureLanguage].
     */
    var extraDirectories: List<String> = emptyList()

    actual fun read(path: String): ByteArray? {
        val dirs = listOfNotNull(NSBundle.mainBundle.resourcePath) + extraDirectories
        for (d in dirs) readFile("$d/$path")?.let { return it }
        return null
    }

    private fun readFile(file: String): ByteArray? {
        val f = fopen(file, "rb") ?: return null
        try {
            fseek(f, 0L, SEEK_END)
            val n = ftell(f).toInt()
            fseek(f, 0L, SEEK_SET)
            val bytes = ByteArray(n)
            if (n > 0) bytes.usePinned { if (fread(it.addressOf(0), 1uL, n.toULong(), f).toInt() != n) return null }
            return bytes
        } finally {
            fclose(f)
        }
    }
}
