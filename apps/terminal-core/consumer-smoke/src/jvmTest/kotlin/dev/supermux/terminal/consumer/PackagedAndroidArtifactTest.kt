package dev.supermux.terminal.consumer

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The Android consumer check — PACKAGING ONLY.
 *
 * It resolves `dev.supermux.terminal:terminal-core-android:<version>@aar` from the same local test
 * repository (so the Android publication really is consumable by coordinates) and inspects what an
 * Android app would get: the two packaged ABIs, the compiled Android binding and the licence files.
 *
 * It does NOT prove the library loads: that needs a device or emulator (none attached here). Until
 * an instrumented run happens, android-arm64 / android-x64 stay "built, not runtime-tested" in
 * VERIFICATION.md.
 */
class PackagedAndroidArtifactTest {
    private val version = System.getProperty("consumerSmoke.expectedVersion") ?: "0.1.0-dev.1"
    private val aar = File(System.getProperty("consumerSmoke.androidAar")!!)

    @Test
    fun theAarResolvesFromTheLocalTestRepositoryAndCarriesBothAbis() {
        assertEquals("terminal-core-android-$version.aar", aar.name)
        assertTrue(aar.isFile, "$aar is not a file")
        println("consumer-smoke(android): aar = $aar (${aar.length()} bytes)")

        ZipFile(aar).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            for (abi in listOf("arm64-v8a", "x86_64")) {
                val entry = zip.getEntry("jni/$abi/libsupermux_terminal_jni.so")
                assertNotNull(entry, "the aar has no jni/$abi library (entries: ${names.sorted()})")
                assertTrue(entry.size > 1_000_000, "jni/$abi library is only ${entry.size} bytes")
                println("consumer-smoke(android): jni/$abi/libsupermux_terminal_jni.so = ${entry.size} bytes")
            }
            assertTrue("classes.jar" in names, "the aar has no classes.jar")
            assertTrue("AndroidManifest.xml" in names)
            for (notice in listOf("LICENSE", "THIRD-PARTY-NOTICES.md")) {
                assertTrue("META-INF/dev.supermux.terminal/$notice" in names, "the aar is missing $notice")
            }
            // The Android actual of createTerminalEngine must be in the compiled classes.
            val classes = zip.getInputStream(zip.getEntry("classes.jar")).use { it.readBytes() }
            val tmp = File.createTempFile("terminal-core-classes", ".jar").apply { deleteOnExit() }
            tmp.writeBytes(classes)
            ZipFile(tmp).use { jar ->
                val entries = jar.entries().asSequence().map { it.name }.toSet()
                assertTrue(
                    "dev/supermux/terminal/PlatformTerminalEngine_androidKt.class" in entries ||
                        entries.any { it.startsWith("dev/supermux/terminal/") && it.endsWith(".class") },
                    "classes.jar holds no dev.supermux.terminal classes: ${entries.sorted().take(20)}",
                )
                assertTrue("dev/supermux/terminal/NativeTerminal.class" in entries, "no NativeTerminal in classes.jar")
            }
        }
    }
}
