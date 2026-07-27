package dev.supermux.desktop.host

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure decision / ownership / lock / port-selection proofs for [BrokerSidecar] (Plan 3 Task 2). The
 * network probe and process spawn are injected fakes, so these run with NO broker, NO ports, NO
 * display — the real spawn→health→stop path is covered separately by the gated source-broker smoke.
 */
class BrokerSidecarTest {

    // ── A fake Process so stop()'s ownership guard is observable without a real child ──
    private class FakeProcess : Process() {
        @Volatile var destroyed = false
        private val empty: InputStream get() = ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = ByteArrayOutputStream()
        override fun getInputStream(): InputStream = empty
        override fun getErrorStream(): InputStream = empty
        override fun waitFor(): Int = 0
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true
        override fun exitValue(): Int = if (destroyed) 0 else throw IllegalThreadStateException()
        override fun destroy() { destroyed = true }
        override fun destroyForcibly(): Process { destroyed = true; return this }
        override fun isAlive(): Boolean = !destroyed
    }

    /** A probe seam that returns [results] in order, repeating the last once exhausted. */
    private class ScriptedProbe(private val results: List<HostProbeResult>) {
        var calls = 0; private set
        val probe: suspend (Int) -> HostProbeResult = { _ ->
            val r = results[minOf(calls, results.size - 1)]
            calls++
            r
        }
    }

    private fun tmp(): Path = createTempDirectory("sidecar-test").also { it.toFile().deleteOnExit() }

    private fun config(dir: Path, port: Int = 9898) = SidecarConfig(
        port = port,
        stateDir = dir,
        repoDir = dir, // any non-null so buildSpawnCommand is valid if reached
        healthTimeoutMs = 2_000,
        healthPollMs = 1,
    )

    // ── shouldSpawn: the pure spawn guard ───────────────────────────────────────────

    @Test fun shouldSpawn_onlyWhenLockedAndOurOwnBrokerIsCalledFor() {
        // Spawn only when we hold the lock AND the decision is spawn-fresh or conflict.
        assertTrue(BrokerSidecar.shouldSpawn(HostDecision.SpawnManaged, holdsLock = true))
        assertTrue(BrokerSidecar.shouldSpawn(HostDecision.PortConflict, holdsLock = true))
        assertFalse(BrokerSidecar.shouldSpawn(HostDecision.SpawnManaged, holdsLock = false))
        assertFalse(BrokerSidecar.shouldSpawn(HostDecision.PortConflict, holdsLock = false))
        // Never spawn for adopt/upgrade regardless of the lock.
        assertFalse(BrokerSidecar.shouldSpawn(HostDecision.AdoptExternal, holdsLock = true))
        assertFalse(BrokerSidecar.shouldSpawn(HostDecision.UpgradeRequired, holdsLock = true))
    }

    // ── start(): adopt vs spawn vs conflict ownership + hostId ───────────────────────

    @Test fun adoptsAnExternalBrokerAndNeverStopsIt() = runBlocking {
        val dir = tmp()
        var spawnCalled = false
        val sidecar = BrokerSidecar(
            config = config(dir),
            probe = ScriptedProbe(listOf(HostProbeResult.SupermuxHost("hExternal"))).probe,
            spawn = { _, _ -> spawnCalled = true; FakeProcess() },
        )
        sidecar.start()
        assertEquals(BrokerSidecar.Ownership.External, sidecar.ownership.value)
        assertEquals(BrokerSidecar.Phase.Adopted, sidecar.state.value)
        assertEquals("hExternal", sidecar.hostId.value)
        assertFalse(spawnCalled, "adopting an external broker must never spawn our own")

        // stop() must NOT tear down a broker we merely adopted.
        sidecar.stop()
        assertEquals(BrokerSidecar.Phase.Stopped, sidecar.state.value)
    }

    @Test fun spawnsManagedOnAFreePortAndStopsOnlyItsOwnChild() = runBlocking {
        val dir = tmp()
        val proc = FakeProcess()
        // First probe (decision) → PortFree ⇒ SpawnManaged; health probe → SupermuxHost.
        val scripted = ScriptedProbe(listOf(HostProbeResult.PortFree, HostProbeResult.SupermuxHost("hMine")))
        val sidecar = BrokerSidecar(
            config = config(dir),
            probe = scripted.probe,
            spawn = { _, _ -> proc },
        )
        sidecar.start()
        assertEquals(BrokerSidecar.Ownership.Managed, sidecar.ownership.value)
        assertEquals(BrokerSidecar.Phase.Online, sidecar.state.value)
        assertEquals("hMine", sidecar.hostId.value)
        assertEquals(9898, sidecar.effectivePort)

        sidecar.stop()
        assertTrue(proc.destroyed, "stop() must terminate the managed broker it spawned")
        assertEquals(BrokerSidecar.Phase.Stopped, sidecar.state.value)
    }

    @Test fun portConflictSpawnsOnAPersistedAlternatePort() = runBlocking {
        val dir = tmp()
        var spawnPort = -1
        // Decision probe → ForeignProcess ⇒ PortConflict; then health on the alt port → SupermuxHost.
        val scripted = ScriptedProbe(listOf(HostProbeResult.ForeignProcess, HostProbeResult.SupermuxHost("hAlt")))
        val sidecar = BrokerSidecar(
            config = config(dir),
            probe = scripted.probe,
            spawn = { _, port -> spawnPort = port; FakeProcess() },
        )
        sidecar.start()
        assertEquals(BrokerSidecar.Ownership.Managed, sidecar.ownership.value)
        assertEquals(BrokerSidecar.Phase.Online, sidecar.state.value)
        assertEquals(BrokerSidecar.DEFAULT_ALTERNATE_PORT, spawnPort)
        assertEquals(BrokerSidecar.DEFAULT_ALTERNATE_PORT, sidecar.effectivePort)
        // The alternate port is persisted so it's stable next launch.
        assertEquals(BrokerSidecar.DEFAULT_ALTERNATE_PORT, SidecarPortStore(dir.resolve(BrokerSidecar.PORT_FILE)).loadAlternate())
    }

    @Test fun legacyBrokerNeedsUpgradeAndIsNeverTouched() = runBlocking {
        val dir = tmp()
        var spawnCalled = false
        val sidecar = BrokerSidecar(
            config = config(dir),
            probe = ScriptedProbe(listOf(HostProbeResult.LegacySupermux)).probe,
            spawn = { _, _ -> spawnCalled = true; FakeProcess() },
        )
        sidecar.start()
        assertEquals(BrokerSidecar.Ownership.None, sidecar.ownership.value)
        assertEquals(BrokerSidecar.Phase.NeedsUpgrade, sidecar.state.value)
        assertFalse(spawnCalled)
    }

    @Test fun failsGracefullyWhenTheSpawnedBrokerNeverComesUp() = runBlocking {
        val dir = tmp()
        val proc = FakeProcess()
        // Health never reports a host ⇒ timeout ⇒ Failed, and our child is cleaned up.
        val sidecar = BrokerSidecar(
            config = config(dir).copy(healthTimeoutMs = 5, healthPollMs = 1),
            probe = ScriptedProbe(listOf(HostProbeResult.PortFree)).probe,
            spawn = { _, _ -> proc },
        )
        sidecar.start()
        assertEquals(BrokerSidecar.Phase.Failed, sidecar.state.value)
        assertTrue(proc.destroyed, "a broker that never came up must be cleaned up")
    }

    @Test fun withoutTheManagerLockItAdoptsInsteadOfDoubleSpawning() = runBlocking {
        val dir = tmp()
        var spawnCalled = false
        // Pre-hold the lock from ANOTHER SidecarLock so this instance can't take it.
        val other = SidecarLock(dir.resolve(BrokerSidecar.LOCK_FILE))
        assertTrue(other.tryAcquire())
        try {
            val sidecar = BrokerSidecar(
                config = config(dir),
                // Decision says the port is free, but without the lock we must defer, not spawn.
                probe = ScriptedProbe(listOf(HostProbeResult.PortFree, HostProbeResult.SupermuxHost("hOther"))).probe,
                spawn = { _, _ -> spawnCalled = true; FakeProcess() },
                lock = SidecarLock(dir.resolve(BrokerSidecar.LOCK_FILE)),
            )
            sidecar.start()
            assertFalse(sidecar.holdsLock)
            assertFalse(spawnCalled, "a lock-less instance must not double-spawn")
            assertEquals(BrokerSidecar.Ownership.External, sidecar.ownership.value)
            assertEquals("hOther", sidecar.hostId.value)
        } finally {
            other.release()
        }
    }

    // ── SidecarLock: mutual exclusion within/across holders ──────────────────────────

    @Test fun fileLockIsExclusiveAndReleasable() {
        val dir = tmp()
        val file = dir.resolve(BrokerSidecar.LOCK_FILE)
        val a = SidecarLock(file)
        val b = SidecarLock(file)
        assertTrue(a.tryAcquire(), "first holder acquires")
        assertTrue(a.held)
        assertFalse(b.tryAcquire(), "a second holder on the same file is refused")
        a.release()
        assertFalse(a.held)
        assertTrue(b.tryAcquire(), "after release the lock is available again")
        b.release()
    }

    // ── SidecarPortStore + chooseAlternatePort ───────────────────────────────────────

    @Test fun portStorePersistsAndChoosesAlternate() {
        val dir = tmp()
        val store = SidecarPortStore(dir.resolve(BrokerSidecar.PORT_FILE))
        assertNull(store.loadAlternate())
        store.saveAlternate(9911)
        assertEquals(9911, store.loadAlternate())
        // Persisted value wins; a valid one is reused, an invalid/absent one falls back.
        assertEquals(9911, BrokerSidecar.chooseAlternatePort(9911, defaultPort = 9898))
        assertEquals(BrokerSidecar.DEFAULT_ALTERNATE_PORT, BrokerSidecar.chooseAlternatePort(null, defaultPort = 9898))
        assertEquals(BrokerSidecar.DEFAULT_ALTERNATE_PORT, BrokerSidecar.chooseAlternatePort(9898, defaultPort = 9898))
    }

    // ── buildSpawnCommand / buildSpawnEnv ────────────────────────────────────────────

    @Test fun devSpawnCommandRunsBunOnRepoMain() {
        val repo = Path.of("/repo")
        val cmd = BrokerSidecar.buildSpawnCommand(SidecarConfig(repoDir = repo, bunPath = "bun"))
        // Path string form is OS-dependent (Windows: \repo\src\main.ts).
        assertEquals(listOf("bun", repo.resolve("src/main.ts").toString()), cmd)
    }

    @Test fun bundledBrokerPathWinsOverDevRepo() {
        val broker = Path.of("/opt/app/broker")
        val cmd = BrokerSidecar.buildSpawnCommand(
            SidecarConfig(repoDir = Path.of("/repo"), bundledBrokerPath = broker),
        )
        assertEquals(listOf(broker.toString()), cmd)
    }

    @Test fun spawnEnvCarriesPortRelayAndOverrides() {
        val env = BrokerSidecar.buildSpawnEnv(
            SidecarConfig(relayDomain = "relay.supermux.dev", extraEnv = mapOf("MUX_STATE_DIR" to "/tmp/x", "MUX_WHATSAPP_WEBHOOK_PORT" to "13021")),
            port = 19921,
        )
        assertEquals("19921", env["MUX_WEB_PORT"])
        assertEquals("relay.supermux.dev", env["MUX_RELAY_DOMAIN"])
        assertEquals("/tmp/x", env["MUX_STATE_DIR"])
        assertEquals("13021", env["MUX_WHATSAPP_WEBHOOK_PORT"])
    }

    @Test fun relayIsEnabledByDefaultForPackagedHosts() {
        val env = BrokerSidecar.buildSpawnEnv(SidecarConfig(), port = 9898)
        assertEquals("relay.supermux.dev", env["MUX_RELAY_DOMAIN"])
    }

    @Test fun parseHostIdReadsGetHostBody() {
        assertEquals("habc", BrokerSidecar.parseHostId("""{"hostId":"habc","name":"x","protocolVersion":1}"""))
        assertNull(BrokerSidecar.parseHostId("""{"name":"no id"}"""))
        assertNull(BrokerSidecar.parseHostId("not json"))
    }
}
