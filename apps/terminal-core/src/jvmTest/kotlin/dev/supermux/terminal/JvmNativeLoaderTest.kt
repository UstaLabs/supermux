package dev.supermux.terminal

import dev.supermux.terminal.TerminalEngineUnavailableException.Reason
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assume
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Startup failures of the desktop loader surface through the PUBLIC factory as typed
 * [TerminalEngineUnavailableException]s, and nothing is left half-created.
 */
class JvmNativeLoaderTest {
    private val original = JvmNativeLibrary.loader
    private val size = TerminalSize(80, 24, 8, 16)

    @AfterTest fun restore() {
        JvmNativeLibrary.loader = original
    }

    private fun factoryFailure(loader: JvmNativeLoader): TerminalEngineUnavailableException {
        JvmNativeLibrary.loader = loader
        val e = assertFailsWith<TerminalEngineUnavailableException> { createTerminalEngine(size, TerminalLimits()) }
        // Sticky: the same loader fails the same way without re-running.
        val again = assertFailsWith<TerminalEngineUnavailableException> { createTerminalEngine(size, TerminalLimits()) }
        assertEquals(e.reason, again.reason)
        return e
    }

    /** (device, inode) where the OS has one; null on Windows. */
    private fun fileKey(f: File): Any? = Files.readAttributes(f.toPath(), BasicFileAttributes::class.java).fileKey()

    private fun tempCache(): List<File> = listOf(Files.createTempDirectory("st-cache").toFile())

    @Test fun missingArtifactIsTyped() {
        val e = factoryFailure(JvmNativeLoader(libraryOverride = null, resourceRoot = "/dev/supermux/terminal/nonexistent", cacheRoots = tempCache()))
        assertEquals(Reason.MISSING_BINARY, e.reason)
        assertTrue("linux-x64" in e.message!! || "macos" in e.message!! || "windows" in e.message!!, e.message)
    }

    @Test fun unsupportedArchitectureIsTyped() {
        val e = factoryFailure(JvmNativeLoader(libraryOverride = null, osName = "Linux", osArch = "riscv64", cacheRoots = tempCache()))
        assertEquals(Reason.UNSUPPORTED_PLATFORM, e.reason)
        val os = factoryFailure(JvmNativeLoader(libraryOverride = null, osName = "FreeBSD", osArch = "amd64", cacheRoots = tempCache()))
        assertEquals(Reason.UNSUPPORTED_PLATFORM, os.reason)
    }

    @Test fun manifestAbiMismatchIsTypedBeforeExtraction() {
        val cache = tempCache()
        val e = factoryFailure(AbiFixtureLoader.abi2(cache))
        assertEquals(Reason.ABI_MISMATCH, e.reason)
        assertTrue(cache.single().walk().none { it.isFile }, "nothing extracted for a mismatched ABI")
    }

    @Test fun loadedLibraryAbiMismatchIsTyped() {
        // A binding expecting ABI 2 against the real packaged library (manifest abi=1,
        // st_abi_version() == 1): rejected by the manifest check through the factory, and by the
        // post-load st_abi_version() check when asked directly.
        val manifest = factoryFailure(JvmNativeLoader(libraryOverride = null, expectedAbi = 2, cacheRoots = tempCache()))
        assertEquals(Reason.ABI_MISMATCH, manifest.reason)
        JvmNativeLibrary.loader = original
        JvmNativeLibrary.ensureLoaded()
        val loaded = assertFailsWith<TerminalEngineUnavailableException> { NativeTerminal.verifyAbi(2, "libsupermux_terminal_jni") }
        assertEquals(Reason.ABI_MISMATCH, loaded.reason)
        assertTrue("implements st_* ABI 1" in loaded.message!!, loaded.message)
    }

    @Test fun corruptArtifactIsTyped() {
        val cache = tempCache()
        val e = factoryFailure(AbiFixtureLoader.corrupt(cache))
        assertEquals(Reason.CORRUPT_BINARY, e.reason)
        assertTrue(cache.single().walk().none { it.isFile }, "corrupt bytes are never extracted")
    }

    /**
     * Extraction-only loader: the real library is loaded once by [JvmNativeLibrary]; these tests
     * must not map further copies (two copies = two st_* handle tables in one process) nor ever
     * modify a mapped file (SIGBUS).
     */
    private fun extractingLoader(cache: List<File>, loaded: MutableList<String> = mutableListOf()) =
        JvmNativeLoader(libraryOverride = null, cacheRoots = cache, systemLoad = { loaded += it })

    @Test fun realLibraryIsExtractedToVersionedCacheAndReused() {
        JvmNativeLibrary.ensureLoaded()
        val cache = tempCache()
        val loaded = mutableListOf<String>()
        val first = extractingLoader(cache, loaded).load()
        assertTrue(first.isFile)
        assertEquals(listOf(first.absolutePath), loaded)
        val rel = first.relativeTo(cache.single()).invariantSeparatorsPath
        assertTrue(Regex("supermux-terminal/[^/]+/[0-9a-f]{64}/[^/]+").matches(rel), rel)
        val inode = fileKey(first)
        assertEquals(first, extractingLoader(cache).load())
        assertEquals(inode, fileKey(first), "a verified cached copy is reused")
        // A tampered cached copy is replaced (atomic rename: new inode) by the verified bytes.
        first.writeText("tampered")
        assertEquals(first, extractingLoader(cache).load())
        assertTrue(first.length() > 1000)
        if (inode != null) assertTrue(inode != fileKey(first))
    }

    @Test fun unwritableCacheFallsBackToNextRoot() {
        JvmNativeLibrary.ensureLoaded()
        val blocked = Files.createTempFile("st-cache-file", ".tmp").toFile() // a FILE: cannot hold directories
        val good = Files.createTempDirectory("st-cache").toFile()
        val loaded = extractingLoader(listOf(blocked, good)).load()
        assertTrue(loaded.startsWith(good), loaded.path)
        val none = assertFailsWith<TerminalEngineUnavailableException> { extractingLoader(listOf(blocked)).load() }
        assertEquals(Reason.INITIALIZATION_FAILED, none.reason)
    }

    @Test fun dlopenFailureIsTyped() {
        val e = assertFailsWith<TerminalEngineUnavailableException> {
            JvmNativeLoader(libraryOverride = null, cacheRoots = tempCache(), systemLoad = { throw UnsatisfiedLinkError("simulated dlopen failure") }).load()
        }
        assertEquals(Reason.INITIALIZATION_FAILED, e.reason)
    }

    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    private fun perms(f: File) = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath(), LinkOption.NOFOLLOW_LINKS))

    @Test fun cacheDirectoriesAndFileAreOwnerOnly() {
        JvmNativeLibrary.ensureLoaded()
        Assume.assumeTrue("POSIX permissions", posix)
        val root = tempCache().single()
        val file = extractingLoader(listOf(root)).load()
        val shaDir = file.parentFile
        val versionDir = shaDir.parentFile
        val base = versionDir.parentFile
        assertEquals(File(root, "supermux-terminal"), base)
        for (d in listOf(base, versionDir, shaDir)) assertEquals("rwx------", perms(d), d.path)
        assertEquals("rw-------", perms(file))
    }

    @Test fun looseOrForeignCacheDirIsNeverUsed() {
        JvmNativeLibrary.ensureLoaded()
        Assume.assumeTrue("POSIX permissions", posix)
        // A pre-existing group/world-writable base (e.g. planted in a shared tmp) is refused ...
        val root = tempCache().single()
        val planted = File(root, "supermux-terminal").apply { mkdirs() }
        Files.setPosixFilePermissions(planted.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"))
        val file = extractingLoader(listOf(root)).load()
        assertTrue(!file.startsWith(planted), file.path)
        val fresh = file.parentFile.parentFile.parentFile
        assertTrue(fresh.name.startsWith("supermux-terminal-") && fresh.parentFile == root, fresh.path)
        assertEquals("rwx------", perms(fresh))
        assertTrue(planted.listFiles()!!.isEmpty(), "nothing written into the loose directory")
        // ... and so is a base that is a symlink to somewhere else.
        val root2 = tempCache().single()
        val elsewhere = Files.createTempDirectory("st-elsewhere").toFile()
        Files.createSymbolicLink(File(root2, "supermux-terminal").toPath(), elsewhere.toPath())
        val file2 = extractingLoader(listOf(root2)).load()
        assertTrue(!file2.canonicalPath.startsWith(elsewhere.canonicalPath), file2.path)
        assertTrue(elsewhere.listFiles()!!.isEmpty())
        // A loose version directory inside our own base fails that root; the next root is used.
        val root3 = tempCache().single()
        val first = extractingLoader(listOf(root3)).load()
        Files.setPosixFilePermissions(first.parentFile.parentFile.toPath(), PosixFilePermissions.fromString("rwxrwxrwx"))
        val good = tempCache().single()
        assertTrue(extractingLoader(listOf(root3, good)).load().startsWith(good))
    }

    @Test fun fileChangedAfterExtractionIsNeverLoaded() {
        val loaded = mutableListOf<String>()
        val e = assertFailsWith<TerminalEngineUnavailableException> {
            JvmNativeLoader(
                libraryOverride = null, cacheRoots = tempCache(), systemLoad = { loaded += it },
                afterExtract = { it.writeText("swapped") }, // not mapped: systemLoad is fake
            ).load()
        }
        assertEquals(Reason.CORRUPT_BINARY, e.reason)
        assertTrue(loaded.isEmpty(), "System.load must not run on a changed file")
    }

    @Test fun developerOverrideLoadsGivenFileOnly() {
        val missing = assertFailsWith<TerminalEngineUnavailableException> {
            JvmNativeLoader(libraryOverride = "/nonexistent/libx.so", cacheRoots = tempCache()).load()
        }
        assertEquals(Reason.MISSING_BINARY, missing.reason)
        JvmNativeLibrary.ensureLoaded()
        val lib = Files.createTempFile("st-override", ".so").toFile()
        val loaded = mutableListOf<String>()
        val cache = tempCache()
        assertEquals(lib, JvmNativeLoader(libraryOverride = lib.path, cacheRoots = cache, systemLoad = { loaded += it }).load())
        assertEquals(listOf(lib.absolutePath), loaded)
        assertTrue(cache.single().walk().none { it.isFile }, "the override skips extraction")
    }

    @Test fun startupFailureLeavesNoSession() {
        // Fill the process-wide st_* table (1024 terminals), so st_create itself fails: the factory
        // must throw a typed startup error and not leak a slot; closing one frees exactly one.
        JvmNativeLibrary.ensureLoaded()
        val tiny = TerminalSize(2, 1, 1, 1)
        val limits = TerminalLimits(historyLines = 0, historyBytes = 0)
        val engines = mutableListOf<TerminalEngine>()
        try {
            while (engines.size < 2048) {
                engines += try { createTerminalEngine(tiny, limits) } catch (e: TerminalEngineUnavailableException) {
                    assertEquals(Reason.INITIALIZATION_FAILED, e.reason)
                    assertTrue("ST_ERR_LIMIT" in e.message!!, e.message)
                    break
                }
            }
            assertTrue(engines.size in 1..1024, "table limit reached after ${engines.size} engines")
            val n = engines.size
            repeat(3) { assertFailsWith<TerminalEngineUnavailableException> { createTerminalEngine(tiny, limits) } }
            engines.removeLast().close()
            engines += createTerminalEngine(tiny, limits) // exactly the freed slot, nothing leaked
            assertFailsWith<TerminalEngineUnavailableException> { createTerminalEngine(tiny, limits) }
            assertEquals(n, engines.size)
        } finally {
            engines.forEach { it.close() }
        }
        createTerminalEngine(size, TerminalLimits()).close()
    }
}

/** Loaders over the jvmTest fixtures in resources/test-native (host-independent: os forced to linux-x64). */
internal object AbiFixtureLoader {
    fun abi2(cache: List<File>) = JvmNativeLoader(
        libraryOverride = null,
        resourceRoot = "/test-native/abi2", osName = "Linux", osArch = "amd64", cacheRoots = cache,
    )

    fun corrupt(cache: List<File>) = JvmNativeLoader(
        libraryOverride = null,
        resourceRoot = "/test-native/corrupt", osName = "Linux", osArch = "amd64", cacheRoots = cache,
    )
}
