package dev.supermux.webview.engine.linux

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WpeRuntimeTest {
    private fun runtime(abi: String = "1", version: String = "2.54.2", arch: String = "x64", glue: ByteArray = byteArrayOf(1, 2, 3), lie: Boolean = false): File {
        val dir = Files.createTempDirectory("wpe-runtime").toFile()
        File(dir, "lib").mkdirs()
        val glueFile = File(dir, WpeRuntime.GLUE).apply { writeBytes(glue) }
        val sha = if (lie) "0".repeat(64) else WpeRuntime.sha256(glueFile)
        File(dir, "manifest.properties").writeText(
            "runtime=wpe\nversion=$version\nabi=$abi\narch=$arch\nglibc=GLIBC_2.39\nfile.${WpeRuntime.GLUE}=${glue.size} $sha\n",
        )
        return dir
    }

    @Test fun candidatesComeInOrder() {
        assertEquals(
            listOf(File("/p"), File("/e"), File("/r/wpe-runtime"), File("/h/.local/share/dev.supermux.webview/wpe-runtime")),
            WpeRuntime.candidates("/p", "/e", "/r", "/h"),
        )
        assertEquals(listOf(File("/h/.local/share/dev.supermux.webview/wpe-runtime")), WpeRuntime.candidates(null, " ", null, "/h"))
    }

    @Test fun architectures() {
        assertEquals("x64", WpeRuntime.archOf("amd64"))
        assertEquals("arm64", WpeRuntime.archOf("aarch64"))
        assertNull(WpeRuntime.archOf("riscv64"))
    }

    @Test fun aWholeRuntimeOfThisLibraryPasses() = assertNull(WpeRuntime.verify(runtime(), "x64"))

    @Test fun anythingElseSaysWhy() {
        assertTrue(WpeRuntime.verify(runtime(abi = "2"), "x64")!!.contains("ABI"))
        assertTrue(WpeRuntime.verify(runtime(version = "2.52.6"), "x64")!!.contains("2.54."))
        assertTrue(WpeRuntime.verify(runtime(arch = "arm64"), "x64")!!.contains("arm64"))
        assertTrue(WpeRuntime.verify(runtime(lie = true), "x64")!!.contains("sha256"))
        val short = runtime().also { File(it, WpeRuntime.GLUE).writeBytes(byteArrayOf(1)) }
        assertTrue(WpeRuntime.verify(short, "x64")!!.contains("bytes"))
        assertTrue(WpeRuntime.verify(Files.createTempDirectory("empty").toFile(), "x64")!!.contains("manifest"))
    }
}
