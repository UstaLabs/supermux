package dev.supermux.editor.syntax

import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Compare [actual] with src/nativeBackedTest/resources/golden/[name]. `jvmTest -PupdateGolden`
 * (re)writes the file instead; review it by eye before committing. Every platform compares with
 * the same file, so JVM, iOS and Android must agree exactly.
 */
internal fun assertGolden(name: String, actual: String) {
    goldenUpdateDir()?.let { dir ->
        writeTextFile("$dir/$name", actual)
        println("golden/$name written")
        return
    }
    val expected = runCatching { testResource("golden/$name").decodeToString() }.getOrNull()
        ?: fail("golden/$name is missing: run :editor-syntax:jvmTest -PupdateGolden and review it")
    assertEquals(expected, actual, "golden/$name")
}

/** The directory to write goldens to (JVM with -PupdateGolden), else null. */
internal expect fun goldenUpdateDir(): String?

internal expect fun writeTextFile(path: String, text: String)
