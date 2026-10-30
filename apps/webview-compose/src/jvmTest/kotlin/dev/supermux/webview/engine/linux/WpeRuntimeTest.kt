package dev.supermux.webview.engine.linux

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WpeRuntimeTest {
    private fun runtime(
        abi: String = WpeRuntime.ABI,
        version: String = "2.54.2",
        arch: String = "x64",
        glue: ByteArray = byteArrayOf(1, 2, 3),
        lie: Boolean = false,
        helpers: List<String> = WpeRuntime.HELPERS,
    ): File {
        val dir = Files.createTempDirectory("wpe-runtime").toFile()
        File(dir, "lib").mkdirs()
        val glueFile = File(dir, WpeRuntime.GLUE).apply { writeBytes(glue) }
        val sha = if (lie) "0".repeat(64) else WpeRuntime.sha256(glueFile)
        val manifest = StringBuilder("runtime=wpe\nversion=$version\nabi=$abi\narch=$arch\nglibc=GLIBC_2.39\nfile.${WpeRuntime.GLUE}=${glue.size} $sha\n")
        for (helper in helpers) {
            val file = File(dir, helper).apply { parentFile.mkdirs(); writeBytes(byteArrayOf(9)); setExecutable(true) }
            manifest.append("file.$helper=1 ${WpeRuntime.sha256(file)}\n")
        }
        File(dir, "manifest.properties").writeText(manifest.toString())
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
        assertTrue(WpeRuntime.verify(runtime(abi = "0"), "x64")!!.contains("ABI"))
        assertTrue(WpeRuntime.verify(runtime(version = "2.52.6"), "x64")!!.contains("2.54."))
        assertTrue(WpeRuntime.verify(runtime(arch = "arm64"), "x64")!!.contains("arm64"))
        assertTrue(WpeRuntime.verify(runtime(lie = true), "x64")!!.contains("sha256"))
        val short = runtime().also { File(it, WpeRuntime.GLUE).writeBytes(byteArrayOf(1)) }
        assertTrue(WpeRuntime.verify(short, "x64")!!.contains("bytes"))
        assertTrue(WpeRuntime.verify(Files.createTempDirectory("empty").toFile(), "x64")!!.contains("manifest"))
    }

    @Test fun aRuntimeWithoutAHelperProcessSaysWhich() {
        // WebKit can't start a process it doesn't find: it aborts the JVM (g_error), so the engine looks first.
        val missing = runtime(helpers = WpeRuntime.HELPERS - "libexec/WPEWebProcess")
        assertTrue(WpeRuntime.verify(missing, "x64")!!.contains("libexec/WPEWebProcess"))
        val notExecutable = runtime().also { File(it, "libexec/WPENetworkProcess").setExecutable(false) }
        assertTrue(WpeRuntime.verify(notExecutable, "x64")!!.contains("libexec/WPENetworkProcess"))
    }

    @Test fun thePageSandboxNeedsABubblewrapThatMakesANamespaceHere() {
        assertTrue(WpeRuntime.sandboxProblem(bwrap = false, dbusProxy = true) { null }!!.contains("bubblewrap"))
        assertEquals("no user namespaces", WpeRuntime.sandboxProblem(bwrap = true, dbusProxy = true) { "no user namespaces" })
        assertNull(WpeRuntime.sandboxProblem(bwrap = true, dbusProxy = true) { null })
    }

    @Test fun xdgDbusProxyIsNeededWhateverTheEnvironmentSays() {
        // WebKit aborts without it as soon as it finds a session or accessibility bus, which GLib finds at
        // $XDG_RUNTIME_DIR/bus even with DBUS_SESSION_BUS_ADDRESS unset (desktop, 2026-10-30).
        var probed = false
        assertTrue(WpeRuntime.sandboxProblem(bwrap = true, dbusProxy = false) { probed = true; null }!!.contains("xdg-dbus-proxy"))
        assertEquals(false, probed, "no sandbox made for nothing")
    }

    @Test fun theRuntimeIsAFolderWebKitCanMountInThePageSandbox() {
        // webkit_web_context_add_path_to_sandbox refuses these, and the web process then can't start.
        val home = File("/home/u")
        assertNull(WpeRuntime.sandboxPathProblem(File("/home/u/.local/share/app/wpe-runtime"), home))
        assertNull(WpeRuntime.sandboxPathProblem(File("/opt/app/wpe-runtime"), home))
        for (refused in listOf("/", "/home", "/home/u", "/proc/1/root", "/sys/x", "/dev/shm/r", "relative/runtime")) {
            assertTrue(WpeRuntime.sandboxPathProblem(File(refused), home) != null, refused)
        }
    }
}
