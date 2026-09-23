package dev.supermux.terminal.consumer

import dev.supermux.terminal.TerminalEngine
import dev.supermux.terminal.TerminalSize
import java.io.File
import java.security.MessageDigest
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop-JVM consumer check: a third-party build with ONE dependency
 * (`dev.supermux.terminal:terminal-core:<version>`, resolved only from the local test repository)
 * creates, drives and frees a terminal engine — and proves that the native library it ran came out
 * of the PUBLISHED jar, not out of terminal-core's build tree.
 *
 * The provenance assertions are the point of this suite: without them a green semantic fixture
 * could just as well have loaded `build/native/linux-x64/lib/libsupermux_terminal_jni.so`.
 *
 * CAVEAT on one of the four: [noPartOfTheTerminalCoreBuildTreeIsOnTheClasspath] reads
 * `java.class.path`, which Gradle may replace with a single synthetic jar whose manifest holds the
 * real Class-Path (it does that when the command line would be too long). The list would then look
 * empty of build-tree entries even if it were not, so treat that check as a tripwire, not a proof.
 * The other three do not depend on it: the code source of a loaded class, the `jar:file:` URL of
 * the packaged resource, and the mapping in `/proc/self/maps` are all read from what the JVM
 * actually loaded.
 */
class PackagedJvmEngineTest {
    private val version = System.getProperty("consumerSmoke.expectedVersion") ?: "0.1.0-dev.1"
    private val nativeCache = File(System.getProperty("consumerSmoke.nativeCache")!!).canonicalFile

    /** `<os>-<arch>` key of the packaged library this JVM must use (mirrors JvmNativeLoader). */
    private val platformKey: String = run {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        val o = when {
            os.startsWith("linux") -> "linux"
            os.startsWith("mac") || os.startsWith("darwin") -> "macos"
            os.startsWith("windows") -> "windows"
            else -> error("unsupported OS for this check: $os")
        }
        val a = when (arch) {
            "amd64", "x86_64", "x64" -> "x64"
            "aarch64", "arm64" -> "arm64"
            else -> error("unsupported CPU for this check: $arch")
        }
        "$o-$a"
    }

    /** `apps/terminal-core` — the build tree this check must NOT touch. */
    private val terminalCoreDir: File = File(System.getProperty("user.dir")).canonicalFile.parentFile

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun theApiComesFromThePublishedJar() {
        val source = TerminalEngine::class.java.protectionDomain.codeSource.location
        val jar = File(source.toURI())
        assertEquals("terminal-core-jvm-$version.jar", jar.name, "loaded from $source")
        assertTrue(jar.isFile, "$jar is not a file")
        println("consumer-smoke: API jar = $jar")
    }

    @Test
    fun noPartOfTheTerminalCoreBuildTreeIsOnTheClasspath() {
        assertEquals("terminal-core", terminalCoreDir.name, "unexpected layout: $terminalCoreDir")
        // The only paths under apps/terminal-core this build may use: the published repository, and
        // this consumer build's own directory (its sources and outputs live inside it).
        val allowed = listOf("build/test-repository", "consumer-smoke")
            .map { File(terminalCoreDir, it).canonicalPath + File.separator }
        val offenders = System.getProperty("java.class.path").split(File.pathSeparator)
            .map { File(it).canonicalPath }
            .filter { entry ->
                entry.startsWith(terminalCoreDir.canonicalPath + File.separator) && allowed.none { entry.startsWith(it) }
            }
        assertTrue(offenders.isEmpty(), "terminal-core build-tree entries on the classpath: $offenders")
        // Sources, compiled classes and the staged native libraries are all absent.
        for (dir in listOf("src", "build/gradle", "build/native", "build/wasm")) {
            val path = File(terminalCoreDir, dir).canonicalPath
            assertTrue(
                System.getProperty("java.class.path").split(File.pathSeparator).none { File(it).canonicalPath.startsWith(path) },
                "$dir is on the classpath",
            )
        }
        // And no developer override is pointing the loader at a file of our choosing.
        assertNull(System.getProperty("supermux.terminal.nativeLibrary"), "the library override is set")
    }

    @Test
    fun theNativeLibraryIsAPackagedResource() {
        val resource = "/dev/supermux/terminal/native/$platformKey/native.properties"
        val url = TerminalEngine::class.java.getResource(resource)
            ?: error("$resource is not packaged")
        val text = url.toString()
        assertTrue(text.startsWith("jar:file:"), "native manifest is not inside a jar: $text")
        assertTrue(text.contains("terminal-core-jvm-$version.jar!$resource"), "unexpected manifest URL: $text")

        val props = Properties().apply { TerminalEngine::class.java.getResourceAsStream(resource)!!.use { load(it) } }
        assertEquals(platformKey, props.getProperty("target"))
        assertEquals(version, props.getProperty("version"))
        // The ABI is checked against the package's OWN abi-manifest.json, not against a number
        // written here. A literal rotted exactly as you would expect: the engine moved to ABI 2
        // and this file still said 1, and nothing noticed because nothing ran this build. What
        // matters to a consumer is that the two things the artifact says about itself agree.
        val abiManifest = TerminalEngine::class.java
            .getResourceAsStream("/dev/supermux/terminal/abi-manifest.json")
            ?.use { String(it.readBytes()) }
            ?: error("dev/supermux/terminal/abi-manifest.json is not packaged")
        val declaredAbi = Regex("\"abi_version\"\\s*:\\s*(\\d+)").find(abiManifest)?.groupValues?.get(1)
            ?: error("abi-manifest.json has no abi_version: $abiManifest")
        assertEquals(declaredAbi, props.getProperty("abi"), "native.properties and abi-manifest.json disagree about the ABI")
        val libraryName = props.getProperty("library")!!
        val libraryBytes = TerminalEngine::class.java
            .getResourceAsStream("/dev/supermux/terminal/native/$platformKey/$libraryName")!!
            .use { it.readBytes() }
        assertEquals(props.getProperty("sha256"), sha256(libraryBytes), "packaged library does not match its manifest")
        assertEquals(props.getProperty("size")!!.toLong(), libraryBytes.size.toLong())
        println("consumer-smoke: packaged $platformKey/$libraryName = ${libraryBytes.size} bytes, sha256 ${props.getProperty("sha256")}")
    }

    @Test
    fun engineRunsTheSemanticFixtureFromTheExtractedPackagedLibrary() {
        // Starting an engine is what loads the library.
        val result = ConsumerFixture.run()
        println("consumer-smoke: $result")

        val props = Properties().apply {
            TerminalEngine::class.java.getResourceAsStream("/dev/supermux/terminal/native/$platformKey/native.properties")!!
                .use { load(it) }
        }
        val libraryName = props.getProperty("library")!!
        val sha = props.getProperty("sha256")!!

        // The loader extracts to <cacheDir>/supermux-terminal/<version>/<sha256>/<library>; that
        // directory is inside THIS build, so the file can only have come from the jar.
        val expected = nativeCache.resolve("supermux-terminal").resolve(version).resolve(sha).resolve(libraryName)
        assertTrue(expected.isFile, "no extracted library at $expected (cache holds: ${nativeCache.walkTopDown().filter { it.isFile }.toList()})")
        assertEquals(sha, sha256(expected.readBytes()), "extracted library does not match the packaged one")

        // On Linux the definitive check: the mapping the process actually loaded.
        val maps = File("/proc/self/maps")
        if (maps.canRead()) {
            // "address perms offset dev inode   pathname" — the 6th field is the file, if any.
            val mapped = maps.readLines()
                .mapNotNull { line -> line.split(Regex("\\s+"), limit = 6).getOrNull(5)?.trim() }
                .filter { it.contains("supermux_terminal") }
                .map { File(it).canonicalPath }
                .toSortedSet()
            assertEquals(setOf(expected.canonicalPath), mapped.toSet(), "unexpected supermux_terminal mappings: $mapped")
            println("consumer-smoke: mapped native library = ${mapped.first()}")
        } else {
            println("consumer-smoke: /proc/self/maps unavailable; verified the extracted file only")
        }

        // A second engine on the already-loaded library still works (the load is process-wide).
        val engine = dev.supermux.terminal.createTerminalEngine(TerminalSize(20, 5, 8, 16), dev.supermux.terminal.TerminalLimits())
        try {
            engine.feed("hi".encodeToByteArray(), dev.supermux.terminal.OutputOrigin.LIVE)
            val cells = engine.viewport(forceFull = true).rows.first { it.index == 0 }.cells
            assertEquals(listOf("h", "i"), cells.take(2).map { it.text })
        } finally {
            engine.close()
        }
    }
}
