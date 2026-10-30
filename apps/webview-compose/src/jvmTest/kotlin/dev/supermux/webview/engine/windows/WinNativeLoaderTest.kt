package dev.supermux.webview.engine.windows

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WinNativeLoaderTest {
    private val dll = "MZ fake glue".encodeToByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(dll).joinToString("") { "%02x".format(it) }
    private val root = WinNativeLoader.RESOURCE_ROOT + "/windows-x64"

    private fun resources(properties: String, bytes: ByteArray = dll): (String) -> java.io.InputStream? = { path ->
        when (path) {
            "$root/native.properties" -> ByteArrayInputStream(properties.encodeToByteArray())
            "$root/webview_windows.dll" -> ByteArrayInputStream(bytes)
            else -> null
        }
    }

    private fun loader(
        properties: String = "library=webview_windows.dll\nabi=${WinGlue.ABI}\nversion=0.1.0\nsha256=$sha\n",
        bytes: ByteArray = dll,
        cache: File = Files.createTempDirectory("wvc-cache").toFile(),
        abi: Int = WinGlue.ABI,
        loaded: MutableList<String> = mutableListOf(),
        arch: String? = "x64",
    ) = WinNativeLoader(
        resource = resources(properties, bytes),
        cacheRoots = listOf(cache),
        override = null,
        arch = arch,
        loadJawt = { loaded += "jawt" },
        systemLoad = { loaded += it },
        abiOfLoaded = { abi },
    )

    @Test fun theGlueIsExtractedToAFolderNamedByItsHashAndLoadedAfterJawt() {
        val cache = Files.createTempDirectory("wvc-cache").toFile()
        val loaded = mutableListOf<String>()
        val file = loader(cache = cache, loaded = loaded).load()
        assertEquals(File(cache, "dev.supermux.webview/0.1.0/$sha/webview_windows.dll").canonicalPath, file.canonicalPath)
        assertEquals(listOf("jawt", file.absolutePath), loaded)
    }

    @Test fun aJarWhoseGlueDoesntMatchItsRecordedHashLoadsNothing() {
        val loaded = mutableListOf<String>()
        val error = assertFailsWith<WinGlueException> { loader(bytes = "tampered".encodeToByteArray(), loaded = loaded).load() }
        assertContains(error.message.orEmpty(), "sha256")
        assertEquals(listOf("jawt"), loaded)
    }

    @Test fun aShrunkBuildNamesTheKeepRule() {
        val error = assertFailsWith<WinGlueException> { loader(abi = WinNativeLoader.NO_UPCALL).load() }
        assertContains(error.message.orEmpty(), WinNativeLoader.KEEP_RULE)
    }

    @Test fun anotherAbiIsRefusedBeforeAnythingLoads() {
        val loaded = mutableListOf<String>()
        assertFailsWith<WinGlueException> { loader(properties = "library=webview_windows.dll\nabi=99\nversion=0.1.0\nsha256=$sha\n", loaded = loaded).load() }
        assertEquals(listOf("jawt"), loaded)
    }

    @Test fun aCopyOnDiskThatChangedIsReplacedNotTrusted() {
        val cache = Files.createTempDirectory("wvc-cache").toFile()
        val first = loader(cache = cache).load()
        first.writeBytes("changed on disk".encodeToByteArray())
        val second = loader(cache = cache).load()
        assertTrue(second.readBytes().contentEquals(dll))
    }

    @Test fun anArchitectureWithNoGlueIsSaidSo() {
        val error = assertFailsWith<WinGlueException> { loader(arch = null).load() }
        assertContains(error.message.orEmpty(), "no Windows glue")
    }
}
