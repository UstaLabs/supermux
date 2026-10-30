package dev.supermux.webview.engine.mac

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The glue's loader (D8), with fake resources and a fake `System.load`: no native code runs here. */
class MacNativeLibraryTest {
    private val root: File = createTempDirectory("wv-glue-").toFile()
    private val loads = mutableListOf<String>()
    private val glue = "not really a dylib".encodeToByteArray()

    @AfterTest fun cleanUp() {
        root.deleteRecursively()
    }

    private fun resources(vararg files: Pair<String, ByteArray>): (String) -> InputStream? {
        val byPath = files.associate { (name, bytes) -> "${MacNativeLoader.RESOURCE_ROOT}/$name" to bytes }
        return { path -> byPath[path]?.let(::ByteArrayInputStream) }
    }

    private fun properties(abi: Int = MacGlue.ABI) =
        "library=libwebview_macos.dylib\nabi=$abi\nversion=1.0\n".encodeToByteArray()

    private fun packaged() = resources("native.properties" to properties(), "libwebview_macos.dylib" to glue)

    private fun loader(
        resource: (String) -> InputStream?,
        isMac: Boolean = true,
        override: String? = null,
        abi: Int = MacGlue.ABI,
        afterExtract: (File) -> Unit = {},
    ) = MacNativeLoader(
        resource = resource,
        cacheRoots = listOf(root),
        override = override,
        isMac = isMac,
        systemLoad = { loads += it },
        abiOfLoaded = { abi },
        afterExtract = afterExtract,
    )

    @Test fun onlyMacOsLoadsTheGlue() {
        val failure = assertFailsWith<MacGlueException> { loader(packaged(), isMac = false).load() }
        assertTrue("macOS only" in failure.message!!, failure.message)
        assertTrue(loads.isEmpty())
    }

    @Test fun aBuildWithoutTheGlueSaysSo() {
        val failure = assertFailsWith<MacGlueException> { loader(resources()).load() }
        assertTrue("has no macOS glue" in failure.message!!, failure.message)
    }

    @Test fun anotherAbiIsRefusedBeforeAnythingLoads() {
        val failure = assertFailsWith<MacGlueException> {
            loader(resources("native.properties" to properties(abi = 99), "libwebview_macos.dylib" to glue)).load()
        }
        assertTrue("ABI 99" in failure.message!!, failure.message)
        assertTrue(loads.isEmpty())
        assertTrue(!File(root, MacNativeLoader.BASE_DIR).exists(), "nothing extracted")
    }

    @Test fun theGlueIsExtractedOwnerOnlyUnderTheHashOfItsBytesAndLoaded() {
        val file = loader(packaged()).load()
        val sha = MacNativeLoader.sha256(glue)
        assertEquals(File(root, "dev.supermux.webview/1.0/$sha/libwebview_macos.dylib").canonicalPath, file.canonicalPath)
        assertTrue(file.readBytes().contentEquals(glue))
        assertEquals(listOf(file.absolutePath), loads)
        for (dir in listOf(file.parentFile, file.parentFile.parentFile, file.parentFile.parentFile.parentFile)) {
            assertEquals(
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(dir.toPath()),
                "$dir is owner-only",
            )
        }
    }

    @Test fun aFileChangedAfterItsExtractionIsNeverLoaded() {
        val failure = assertFailsWith<MacGlueException> {
            loader(packaged(), afterExtract = { it.writeText("something else") }).load()
        }
        assertTrue("changed between its extraction and its loading" in failure.message!!, failure.message)
        assertTrue(loads.isEmpty())
    }

    @Test fun aSecondLoaderReusesTheExtractedFile() {
        val first = loader(packaged()).load()
        val written = first.lastModified()
        Thread.sleep(1_100)
        val second = loader(packaged()).load()
        assertEquals(first.canonicalPath, second.canonicalPath)
        assertEquals(written, second.lastModified(), "not written again")
    }

    @Test fun aTamperedCachedFileIsReplacedBeforeItLoads() {
        val first = loader(packaged()).load()
        first.writeText("tampered")
        val second = loader(packaged()).load()
        assertTrue(second.readBytes().contentEquals(glue))
    }

    @Test fun aLinkInTheCachePathIsRefused() {
        val elsewhere = File(root, "elsewhere").apply { mkdirs() }
        Files.createSymbolicLink(File(root, MacNativeLoader.BASE_DIR).toPath(), elsewhere.toPath())
        val failure = assertFailsWith<MacGlueException> { loader(packaged()).load() }
        assertTrue("can't extract" in failure.message!!, failure.message)
        assertTrue(loads.isEmpty())
    }

    @Test fun theOverrideLoadsThatFileAndStillChecksItsAbi() {
        val file = File(root, "glue.dylib").apply { writeBytes(glue) }
        loader(resources(), override = file.path).load()
        assertEquals(listOf(file.absolutePath), loads)
        val failure = assertFailsWith<MacGlueException> { loader(resources(), override = file.path, abi = 7).load() }
        assertTrue("speaks ABI 7" in failure.message!!, failure.message)
    }

    /**
     * I5: a release build shrunk with ProGuard drops `MacGlue.onEvent`, which only the glue calls (by name): the glue
     * then can't call back at all. Its failure says so, and what to keep, rather than a bare ABI number.
     */
    @Test fun aGlueThatCantFindItsCallBackSaysWhyAndWhatToKeep() {
        val failure = assertFailsWith<MacGlueException> { loader(packaged(), abi = -1).load() }
        val message = failure.message!!
        assertTrue("MacGlue.onEvent" in message, message)
        assertTrue("-keep class dev.supermux.webview.engine.mac.MacGlue" in message, message)
    }

    @Test fun theGlueLoadsOnceAndAnswersTheSameEveryTime() {
        var calls = 0
        val failing = GlueLoading {
            calls++
            throw MacGlueException("nope")
        }
        assertEquals("nope", failing.ensureLoaded())
        assertEquals("nope", failing.ensureLoaded())
        assertEquals(1, calls)
        assertNull(GlueLoading { }.ensureLoaded())
    }

    @Test fun macOsAndItsVersionAreReadFromTheJvm() {
        assertTrue(MacPlatform.isMac("Mac OS X"))
        assertTrue(!MacPlatform.isMac("Linux"))
        assertTrue(!MacPlatform.isMac("Windows 11"))
        assertEquals(26, MacPlatform.majorVersion("26.6.2"))
        assertEquals(10, MacPlatform.majorVersion("10.15.7"))
        assertEquals(0, MacPlatform.majorVersion(""))
    }
}
