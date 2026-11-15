package dev.supermux.desktop.host

import dev.supermux.desktop.platform.DesktopOs
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #133: the name a launch shows comes from the OS, and nothing the UI calls ever waits for it. */
class HostNameTest {
    @Test
    fun `now never waits, even while the OS read hangs`() {
        val release = CountDownLatch(1)
        val name = HostName(read = { release.await(30, TimeUnit.SECONDS); "late" }, cacheFile = null, fallback = "Desktop host")
        name.refresh()
        val started = System.nanoTime()
        val now = name.now()
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("Desktop host", now)
        assertTrue(tookMs < 100, "now() took $tookMs ms while the read hung")
        release.countDown()
        assertEquals("late", name.awaitRead(5_000))
    }

    @Test
    fun `the read name replaces the fallback and is cached for the next launch`() {
        val dir = Files.createTempDirectory("hostname")
        val cache = dir.resolve("this-computer-name")
        val first = HostName(read = { "Ahmet's MacBook Air\n" }, cacheFile = cache, fallback = "Desktop host")
        assertEquals("Desktop host", first.now())
        assertEquals("Ahmet's MacBook Air", first.awaitRead(5_000))
        assertEquals("Ahmet's MacBook Air", Files.readString(cache))

        // Next launch: the cached name is there before any read, even if the read never answers.
        val next = HostName(read = { CountDownLatch(1).await(30, TimeUnit.SECONDS); null }, cacheFile = cache, fallback = "Desktop host")
        assertEquals("Ahmet's MacBook Air", next.now())
    }

    @Test
    fun `a failed or blank read keeps the best name known`() {
        val dir = Files.createTempDirectory("hostname")
        val cache = dir.resolve("this-computer-name").also { Files.writeString(it, "studio") }
        assertEquals("studio", HostName(read = { error("no scutil") }, cacheFile = cache, fallback = "Desktop host").awaitRead(5_000))
        assertEquals("Desktop host", HostName(read = { "  " }, cacheFile = null, fallback = "Desktop host").awaitRead(5_000))
    }

    @Test
    fun `macOS asks scutil for the Computer Name, then the LocalHostName, and never DNS`() {
        val asked = mutableListOf<List<String>>()
        val name = HostName.osName(DesktopOs.MACOS, env = { null }, command = { argv ->
            asked += argv
            if (argv.last() == "ComputerName") "Ahmet's MacBook Air\n" else "ahmets-MacBook-Air\n"
        }, file = { null })
        assertEquals("Ahmet's MacBook Air", name)
        assertEquals(listOf(listOf("/usr/sbin/scutil", "--get", "ComputerName")), asked)

        val local = HostName.osName(DesktopOs.MACOS, env = { null }, command = { argv ->
            if (argv.last() == "ComputerName") null else "ahmets-MacBook-Air\n"
        }, file = { null })
        assertEquals("ahmets-MacBook-Air", local)
    }

    @Test
    fun `Windows reads COMPUTERNAME and Linux reads etc hostname, then hostname`() {
        assertEquals("DESK-01", HostName.osName(DesktopOs.WINDOWS, env = { if (it == "COMPUTERNAME") "DESK-01" else null }, command = { null }, file = { null }))
        assertEquals("box", HostName.osName(DesktopOs.LINUX, env = { null }, command = { "other" }, file = { "box\n" }))
        assertEquals("other", HostName.osName(DesktopOs.LINUX, env = { null }, command = { "other\n" }, file = { null }))
        assertNull(HostName.osName(DesktopOs.LINUX, env = { null }, command = { null }, file = { "" }))
    }

    @Test
    fun `no desktop code resolves this computer's name through DNS`() {
        // The lookup that froze the first composition for 10 s (#133). Tests run with the module dir as working dir.
        val hits = java.io.File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }
            .filter { f -> f.readLines().any { "InetAddress.getLocalHost(" in it || "getCanonicalHostName(" in it } }
            .map { it.path }.toList()
        assertEquals(emptyList<String>(), hits)
    }
}
