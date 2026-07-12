package dev.supermux.desktop.host

import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * REAL end-to-end smoke for [BrokerSidecar] (Plan 3 Task 2 verification): drives the true
 * spawn→health→stop path against a SOURCE broker (`bun <repo>/src/main.ts`) on an isolated state
 * dir + port, then confirms the sidecar reports it online via GET /host and stops its own child.
 *
 * GATED behind `SM_SIDECAR_SMOKE=1` so the normal `:desktop:test` run neither spawns a broker nor
 * touches the live one on :9898. Run explicitly:
 *
 *   SM_SIDECAR_SMOKE=1 SM_SIDECAR_REPO=<repo-root> \
 *     MUX_STATE_DIR=/home/ahmet/.cache/x/p3sidecar SM_SIDECAR_PORT=19921 SM_SIDECAR_WA_PORT=13021 \
 *     ./gradlew :desktop:test --tests 'dev.supermux.desktop.host.BrokerSidecarSmokeTest'
 */
class BrokerSidecarSmokeTest {

    @Test fun sidecarSpawnsAndAdoptsASourceBrokerReportsHostThenStops() {
        if (System.getenv("SM_SIDECAR_SMOKE") != "1") {
            println("[sidecar-smoke] skipped (set SM_SIDECAR_SMOKE=1 to run the real spawn smoke)")
            return
        }
        val repo = System.getenv("SM_SIDECAR_REPO")?.takeIf { it.isNotBlank() }
            ?: error("SM_SIDECAR_REPO must point at the repo root (the dir containing src/main.ts)")
        val brokerStateDir = System.getenv("MUX_STATE_DIR")?.takeIf { it.isNotBlank() }
            ?: "/home/ahmet/.cache/x/p3sidecar"
        val port = System.getenv("SM_SIDECAR_PORT")?.toIntOrNull() ?: 19921
        val waPort = System.getenv("SM_SIDECAR_WA_PORT") ?: "13021"

        // Isolate the sidecar's own lock/port-store under the broker's state dir, well away from
        // ~/.mux/state and the live :9898 broker.
        val sidecarStateDir = Path.of(brokerStateDir).resolve("sidecar-super")
        Files.createDirectories(sidecarStateDir)

        val config = SidecarConfig(
            port = port,
            stateDir = sidecarStateDir,
            repoDir = Path.of(repo),
            bunPath = System.getenv("SM_SIDECAR_BUN") ?: "bun",
            extraEnv = mapOf(
                "MUX_STATE_DIR" to brokerStateDir,
                "MUX_WHATSAPP_WEBHOOK_PORT" to waPort,
            ),
            healthTimeoutMs = 120_000, // bun cold start + migrations on first boot
            healthPollMs = 750,
        )
        val sidecar = BrokerSidecar(config)
        try {
            runBlocking { sidecar.start() }

            println("[sidecar-smoke] phase=${sidecar.state.value} ownership=${sidecar.ownership.value} port=${sidecar.effectivePort} hostId=${sidecar.hostId.value}")
            assertTrue(
                sidecar.state.value == BrokerSidecar.Phase.Online || sidecar.state.value == BrokerSidecar.Phase.Adopted,
                "sidecar should report the broker online/adopted, was ${sidecar.state.value}",
            )
            val hostId = sidecar.hostId.value
            assertTrue(!hostId.isNullOrBlank(), "sidecar should have learned a hostId from GET /host")

            // Independent confirmation: hit GET /host ourselves and print exactly what it returned.
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val req = HttpRequest.newBuilder(URI.create("${sidecar.localBaseUrl}/host"))
                .timeout(Duration.ofSeconds(5)).GET().build()
            val resp = client.send(req, BodyHandlers.ofString())
            println("[sidecar-smoke] GET ${sidecar.localBaseUrl}/host -> ${resp.statusCode()} ${resp.body()}")
            assertEquals(200, resp.statusCode())
            assertTrue(resp.body().contains(hostId), "GET /host body should carry the reported hostId")
        } finally {
            sidecar.stop()
            println("[sidecar-smoke] stopped; phase=${sidecar.state.value}")
        }
    }

    /**
     * Plan 3 Task 5 verification: drive the sidecar's true spawn→health→stop path against a
     * BUNDLED **compiled** broker (a `bun build --compile src/cli.ts` single-file exec) via
     * [SidecarConfig.bundledBrokerPath] — the exact packaged-app path (no repoDir/bun) — then
     * confirm it reports Online (ownership Managed) and answers GET /host, and that [stop] tears
     * down the child it spawned.
     *
     * GATED behind `SM_BUNDLED_BROKER=<path to compiled broker>`. Run:
     *
     *   bun build --compile src/cli.ts --outfile /tmp/supermux-broker
     *   SM_BUNDLED_BROKER=/tmp/supermux-broker MUX_STATE_DIR=/home/ahmet/.cache/x/p3task5-sidecar \
     *     SM_SIDECAR_PORT=19932 SM_SIDECAR_WA_PORT=13032 \
     *     ./gradlew :desktop:test --tests 'dev.supermux.desktop.host.BrokerSidecarSmokeTest'
     */
    @Test fun sidecarSpawnsABundledCompiledBrokerReportsHostThenStops() {
        val brokerBin = System.getenv("SM_BUNDLED_BROKER")?.takeIf { it.isNotBlank() }
        if (brokerBin == null) {
            println("[bundled-smoke] skipped (set SM_BUNDLED_BROKER=<compiled broker> to run the bundled-broker spawn smoke)")
            return
        }
        val brokerStateDir = System.getenv("MUX_STATE_DIR")?.takeIf { it.isNotBlank() }
            ?: "/home/ahmet/.cache/x/p3task5-sidecar"
        val port = System.getenv("SM_SIDECAR_PORT")?.toIntOrNull() ?: 19932
        val waPort = System.getenv("SM_SIDECAR_WA_PORT") ?: "13032"

        val sidecarStateDir = Path.of(brokerStateDir).resolve("sidecar-super")
        Files.createDirectories(sidecarStateDir)

        // No repoDir/bunPath: buildSpawnCommand runs the single bundled exec, exactly as a
        // packaged app does. extraEnv isolates the child's state + whatsapp port off the live host.
        val config = SidecarConfig(
            port = port,
            stateDir = sidecarStateDir,
            bundledBrokerPath = Path.of(brokerBin),
            extraEnv = mapOf(
                "MUX_STATE_DIR" to brokerStateDir,
                "MUX_WHATSAPP_WEBHOOK_PORT" to waPort,
            ),
            healthTimeoutMs = 120_000,
            healthPollMs = 750,
        )
        val sidecar = BrokerSidecar(config)
        try {
            runBlocking { sidecar.start() }

            println("[bundled-smoke] phase=${sidecar.state.value} ownership=${sidecar.ownership.value} port=${sidecar.effectivePort} hostId=${sidecar.hostId.value}")
            assertEquals(BrokerSidecar.Ownership.Managed, sidecar.ownership.value, "a bundled broker we spawned is managed")
            assertEquals(BrokerSidecar.Phase.Online, sidecar.state.value, "sidecar should report the bundled broker online, was ${sidecar.state.value}")
            val hostId = sidecar.hostId.value
            assertTrue(!hostId.isNullOrBlank(), "sidecar should have learned a hostId from the bundled broker's GET /host")

            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val req = HttpRequest.newBuilder(URI.create("${sidecar.localBaseUrl}/host"))
                .timeout(Duration.ofSeconds(5)).GET().build()
            val resp = client.send(req, BodyHandlers.ofString())
            println("[bundled-smoke] GET ${sidecar.localBaseUrl}/host -> ${resp.statusCode()} ${resp.body()}")
            assertEquals(200, resp.statusCode())
            assertTrue(resp.body().contains(hostId), "GET /host body should carry the reported hostId")
        } finally {
            sidecar.stop()
            println("[bundled-smoke] stopped; phase=${sidecar.state.value}")
        }
    }
}
