package dev.supermux.desktop.editor

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure checks for direct-JCEF argument parsing and the packaged JBR native markers. */
class JcefRuntimeTest {

    @Test
    fun null_and_blank_extra_args_yield_no_switches() {
        assertEquals(emptyList(), parseJcefExtraArgs(null))
        assertEquals(emptyList(), parseJcefExtraArgs(""))
        assertEquals(emptyList(), parseJcefExtraArgs("   "))
        assertEquals(emptyList(), parseJcefExtraArgs("\t\n "))
    }

    @Test
    fun extra_args_split_on_any_whitespace_run() {
        assertEquals(listOf("--in-process-gpu"), parseJcefExtraArgs("--in-process-gpu"))
        assertEquals(
            listOf("--in-process-gpu", "--disable-gpu-sandbox"),
            parseJcefExtraArgs("--in-process-gpu   --disable-gpu-sandbox"),
        )
        assertEquals(
            listOf("--a", "--b", "--c"),
            parseJcefExtraArgs("  --a\t--b \n --c  "),
        )
    }

    @Test
    fun spaced_extra_arg_values_are_documented_as_two_tokens() {
        assertEquals(listOf("--foo=a", "b"), parseJcefExtraArgs("--foo=a b"))
    }

    @Test
    fun mac_os_name_detection() {
        assertTrue(isMacOs("Mac OS X"))
        assertTrue(isMacOs("macOS"))
        assertTrue(isMacOs("Darwin"))
        assertFalse(isMacOs("Linux"))
        assertFalse(isMacOs("Windows 11"))
        assertFalse(isMacOs(null))
    }

    @Test
    fun linux_runtime_requires_libjcef() = withTempDir { javaHome ->
        val marker = javaHome.resolve("lib/libjcef.so")
        assertTrue(validateBundledRuntime(javaHome, "Linux").orEmpty().contains(marker.toString()))

        marker.parent.createDirectories()
        marker.writeText("native")
        assertNull(validateBundledRuntime(javaHome, "Linux"))
    }

    @Test
    fun windows_runtime_requires_jcef_dll() = withTempDir { javaHome ->
        val marker = javaHome.resolve("bin/jcef.dll")
        assertTrue(validateBundledRuntime(javaHome, "Windows 11").orEmpty().contains(marker.toString()))

        marker.parent.createDirectories()
        marker.writeText("native")
        assertNull(validateBundledRuntime(javaHome, "Windows 11"))
    }

    @Test
    fun mac_runtime_requires_the_sibling_chromium_framework() = withTempDir { image ->
        val javaHome = image.resolve("Contents/Home")
        javaHome.createDirectories()
        val marker = image.resolve(
            "Contents/Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework",
        )
        assertTrue(validateBundledRuntime(javaHome, "Mac OS X").orEmpty().contains(marker.toString()))

        marker.parent.createDirectories()
        marker.writeText("native")
        assertNull(validateBundledRuntime(javaHome, "Mac OS X"))
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("jcef-runtime-test")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
