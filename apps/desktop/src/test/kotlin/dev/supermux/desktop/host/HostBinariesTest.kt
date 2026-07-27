package dev.supermux.desktop.host

import dev.supermux.desktop.host.HostBinaries.Binary
import dev.supermux.desktop.host.HostBinaries.Os
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure path/name/policy proofs for [HostBinaries] (Plan 3 Task 5) plus a real materialize
 * round-trip on a temp dir. No display, no broker, no packaged image — the dev-vs-packaged
 * branch is driven by an injected `resourcesDir` / `$PATH` lookup so it runs anywhere.
 */
class HostBinariesTest {

    private fun tmp(): Path = createTempDirectory("hostbins-test").also { it.toFile().deleteOnExit() }

    // ── detectOs ────────────────────────────────────────────────────────────────────

    @Test fun detectOsFromOsName() {
        assertEquals(Os.LINUX, HostBinaries.detectOs("Linux"))
        assertEquals(Os.MAC, HostBinaries.detectOs("Mac OS X"))
        assertEquals(Os.MAC, HostBinaries.detectOs("Darwin"))
        assertEquals(Os.WINDOWS, HostBinaries.detectOs("Windows 11"))
        assertEquals(Os.OTHER, HostBinaries.detectOs("SunOS"))
        assertEquals(Os.OTHER, HostBinaries.detectOs(null))
    }

    // ── fileName: `.exe` on Windows for the exec'ables; tmux never on Windows ─────────

    @Test fun fileNamePerOs() {
        assertEquals("supermux-broker", HostBinaries.fileName(Binary.Broker, Os.LINUX))
        assertEquals("supermux-broker", HostBinaries.fileName(Binary.Broker, Os.MAC))
        assertEquals("supermux-broker.exe", HostBinaries.fileName(Binary.Broker, Os.WINDOWS))
        assertEquals("mux-sessiond.exe", HostBinaries.fileName(Binary.Sessiond, Os.WINDOWS))
        assertEquals("frpc", HostBinaries.fileName(Binary.Frpc, Os.LINUX))
        assertEquals("frpc.exe", HostBinaries.fileName(Binary.Frpc, Os.WINDOWS))
        assertEquals("tmux", HostBinaries.fileName(Binary.Tmux, Os.MAC))
    }

    // ── isBundled: Windows is client-only (frpc only, for when it later hosts) ────────

    @Test fun bundledSetPerOs() {
        // Linux/macOS host natively: all three ship.
        for (os in listOf(Os.LINUX, Os.MAC)) {
            assertTrue(HostBinaries.isBundled(Binary.Broker, os))
            assertTrue(HostBinaries.isBundled(Binary.Tmux, os))
            assertTrue(HostBinaries.isBundled(Binary.Frpc, os))
            assertFalse(HostBinaries.isBundled(Binary.Sessiond, os))
        }
        // Windows: the native broker uses sessiond instead of tmux.
        assertTrue(HostBinaries.isBundled(Binary.Broker, Os.WINDOWS))
        assertTrue(HostBinaries.isBundled(Binary.Sessiond, Os.WINDOWS))
        assertTrue(HostBinaries.isBundled(Binary.Frpc, Os.WINDOWS))
        assertFalse(HostBinaries.isBundled(Binary.Tmux, Os.WINDOWS))
        // Unknown OS ships nothing.
        assertFalse(HostBinaries.isBundled(Binary.Frpc, Os.OTHER))
    }

    // ── packaged detection off the Compose resources-dir system property ─────────────

    @Test fun packagedDetectionFromProperty() {
        assertNull(HostBinaries.resourcesDir(null))
        assertNull(HostBinaries.resourcesDir("  "))
        assertEquals(Path.of("/opt/supermux/app/resources"), HostBinaries.resourcesDir("/opt/supermux/app/resources"))
    }

    // ── prependPath: bundled bin dir wins over the inherited PATH ─────────────────────

    @Test fun prependPathPutsBinDirFirst() {
        val dir = Path.of("/home/u/.mux/state/desktop-assets/bin")
        val p = HostBinaries.prependPath(dir, existing = "/usr/bin:/bin")
        // binDir.toString() is OS-dependent; pathSeparator is ':' on POSIX and ';' on Windows.
        assertEquals(dir.toString() + java.io.File.pathSeparator + "/usr/bin:/bin", p)
        // No inherited PATH → just the bin dir.
        assertEquals(dir.toString(), HostBinaries.prependPath(dir, existing = null))
        assertEquals(dir.toString(), HostBinaries.prependPath(dir, existing = ""))
    }

    // ── resolve DEV: no resources dir ⇒ broker via repo+bun (null), tmux/frpc off PATH ─

    @Test fun resolveDevUsesPathAndNoBundledBroker() {
        val fakePath = mapOf("frpc" to Path.of("/usr/local/bin/frpc"), "tmux" to Path.of("/usr/bin/tmux"))
        val bins = HostBinaries.resolve(
            stateDir = tmp(),
            os = Os.LINUX,
            resourcesDir = null,
            onPath = { fakePath[it] },
        )
        assertNull(bins.brokerPath, "dev broker runs from the repo via bun, not a bundled binary")
        assertNull(bins.binDir, "dev needs no materialized bin dir")
        assertEquals(Path.of("/usr/local/bin/frpc"), bins.frpcPath)
        assertEquals(Path.of("/usr/bin/tmux"), bins.tmuxPath)
    }

    // ── resolve PACKAGED (Linux): materialize all three into one exec bin dir ─────────

    @Test fun resolvePackagedMaterializesAllThreeExecutable() {
        val resDir = tmp()
        Files.writeString(resDir.resolve("supermux-broker"), "#!broker\n")
        Files.writeString(resDir.resolve("frpc"), "frpc-bytes")
        Files.writeString(resDir.resolve("tmux"), "tmux-bytes")
        val stateDir = tmp()

        val bins = HostBinaries.resolve(stateDir = stateDir, os = Os.LINUX, resourcesDir = resDir, onPath = { null })

        assertNotNull(bins.binDir)
        assertNotNull(bins.brokerPath)
        assertNotNull(bins.frpcPath)
        assertNotNull(bins.tmuxPath)
        // All three land in the SAME bin dir (so it can be prepended to the broker's PATH).
        assertEquals(bins.binDir, bins.brokerPath.parent)
        assertEquals(bins.binDir, bins.frpcPath.parent)
        assertEquals(bins.binDir, bins.tmuxPath.parent)
        // Content copied verbatim, and marked executable (POSIX host).
        assertEquals("frpc-bytes", Files.readString(bins.frpcPath))
        assertTrue(Files.isExecutable(bins.brokerPath), "materialized broker must be executable")
        assertTrue(Files.isExecutable(bins.frpcPath), "materialized frpc must be executable")
    }

    // ── resolve PACKAGED (Windows): broker + sessiond + frpc, no tmux ────────────────

    @Test fun resolvePackagedWindowsMaterializesNativeHostHelpers() {
        val resDir = tmp()
        Files.writeString(resDir.resolve("frpc.exe"), "frpc-win")
        Files.writeString(resDir.resolve("supermux-broker.exe"), "broker-win")
        Files.writeString(resDir.resolve("mux-sessiond.exe"), "sessiond-win")
        Files.writeString(resDir.resolve("tmux"), "tmux-win")
        val bins = HostBinaries.resolve(stateDir = tmp(), os = Os.WINDOWS, resourcesDir = resDir, onPath = { null })

        assertNotNull(bins.brokerPath)
        assertNotNull(bins.sessiondPath)
        assertNotNull(bins.frpcPath)
        assertEquals("supermux-broker.exe", bins.brokerPath.fileName.toString())
        assertEquals("mux-sessiond.exe", bins.sessiondPath.fileName.toString())
        assertEquals("frpc.exe", bins.frpcPath.fileName.toString())
        assertEquals(bins.binDir, bins.brokerPath.parent)
        assertEquals(bins.binDir, bins.sessiondPath.parent)
        assertEquals(bins.binDir, bins.frpcPath.parent)
        assertNull(bins.tmuxPath, "native Windows uses sessiond, never tmux")
    }

    // ── resolve PACKAGED tolerates an unfilled slot (e.g. tmux the packager didn't stage) ─

    @Test fun resolvePackagedToleratesMissingSlot() {
        val resDir = tmp()
        Files.writeString(resDir.resolve("supermux-broker"), "broker")
        Files.writeString(resDir.resolve("frpc"), "frpc")
        // tmux intentionally absent from the image.
        val bins = HostBinaries.resolve(stateDir = tmp(), os = Os.LINUX, resourcesDir = resDir, onPath = { null })
        assertNotNull(bins.brokerPath)
        assertNotNull(bins.frpcPath)
        assertNull(bins.tmuxPath, "a missing tmux slot resolves to null, not a crash")
        assertNotNull(bins.binDir, "binDir still exists (broker + frpc materialized)")
    }

    // ── materialize: atomic copy + exec perm + a sig-keyed idempotent fast path ────────

    @Test fun materializeIsIdempotentAndRefreshesOnChange() {
        val src = tmp().resolve("bin")
        Files.writeString(src, "v1")
        val destDir = tmp()

        val a = HostBinaries.materialize(src, destDir, "bin", executable = true)
        assertEquals("v1", Files.readString(a))
        assertTrue(Files.isExecutable(a))
        val firstMtime = Files.getLastModifiedTime(a)

        // Same source ⇒ fast path, no re-copy (dest untouched).
        val b = HostBinaries.materialize(src, destDir, "bin", executable = true)
        assertEquals(a, b)
        assertEquals(firstMtime, Files.getLastModifiedTime(b), "unchanged source must not re-copy")

        // Changed source (new size) ⇒ re-materialized with the new content.
        Files.writeString(src, "v2-longer")
        Thread.sleep(5)
        val c = HostBinaries.materialize(src, destDir, "bin", executable = true)
        assertEquals("v2-longer", Files.readString(c))
    }
}
