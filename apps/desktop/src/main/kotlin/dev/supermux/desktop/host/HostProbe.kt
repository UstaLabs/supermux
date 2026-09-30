package dev.supermux.desktop.host

import dev.supermux.net.HostIdentity
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Path
import java.time.Duration

/** What answers on the saved host port (spec §Launch flow). */
sealed interface HostProbeResult {
    data class Supermux(
        val hostId: String,
        val build: String?,
        val mode: String?,
        val managedBy: String?,
        val stateDir: String?,
    ) : HostProbeResult
    object LegacySupermux : HostProbeResult   // answers HTTP 200 on /host but with no hostId (pre-/host broker)
    object ForeignProcess : HostProbeResult   // the port is held by something else
    object PortFree : HostProbeResult
}

/** What the supervisor should do. Pure output of [decideHost]. */
sealed interface HostPlan {
    data object NotHosting : HostPlan
    data object Start : HostPlan
    data object MovePort : HostPlan
    data object UseOwn : HostPlan
    data object UpdateOwn : HostPlan
    data object ReadOnly : HostPlan
    /** One-time confirm "Let the app manage and update it?". [hostId] null for a pre-/host broker. */
    data class AskTakeover(val hostId: String?) : HostPlan
    /** The outside broker is newer than the bundled one: keep it, or downgrade and take over? */
    data class AskDowngrade(val hostId: String) : HostPlan
}

/**
 * The launch decision. Never returns a plan that stops or edits a broker the user didn't agree to
 * hand over. [appStateDir] is the state dir the app's broker would use; a broker on another state
 * dir is somebody else's installation.
 */
fun decideHost(probe: HostProbeResult, prefs: HostingPrefs, bundledBuild: String?, appStateDir: String): HostPlan {
    if (!prefs.hosting) return HostPlan.NotHosting
    return when (probe) {
        HostProbeResult.PortFree -> HostPlan.Start
        HostProbeResult.ForeignProcess -> HostPlan.MovePort
        HostProbeResult.LegacySupermux -> HostPlan.AskTakeover(null)
        is HostProbeResult.Supermux -> when {
            probe.managedBy == "desktop" ->
                if (BrokerVersion.sameBuild(probe.build, bundledBuild)) HostPlan.UseOwn else HostPlan.UpdateOwn
            probe.hostId in prefs.leftAloneHostIds -> HostPlan.ReadOnly
            probe.mode != "binary" -> HostPlan.ReadOnly
            probe.stateDir == null || normalize(probe.stateDir) != normalize(appStateDir) -> HostPlan.ReadOnly
            BrokerVersion.isNewer(found = probe.build, bundled = bundledBuild) -> HostPlan.AskDowngrade(probe.hostId)
            else -> HostPlan.AskTakeover(probe.hostId)
        }
    }
}

private fun normalize(p: String): String = runCatching { Path.of(p).toAbsolutePath().normalize().toString() }.getOrDefault(p)

/** Where the broker's state lives (mirrors src/shared/paths.ts). */
object BrokerPaths {
    fun defaultStateDir(): Path {
        val home = System.getProperty("user.home") ?: "."
        val muxHome = System.getenv("MUX_HOME") ?: "$home/.mux"
        return Path.of(System.getenv("MUX_STATE_DIR") ?: "$muxHome/state")
    }
}

/** Real HTTP probe of `GET /host` from loopback (so the local-only fields are included). */
object HostProber {
    private val json = Json { ignoreUnknownKeys = true }

    fun probe(port: Int, host: String = "127.0.0.1"): HostProbeResult {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
        val req = HttpRequest.newBuilder(URI.create("http://$host:$port/host")).timeout(Duration.ofSeconds(3)).GET().build()
        return try {
            val resp = client.send(req, BodyHandlers.ofString())
            if (resp.statusCode() == 200) parse(resp.body()) else HostProbeResult.ForeignProcess
        } catch (_: ConnectException) {
            HostProbeResult.PortFree
        } catch (_: IOException) {
            if (tcpConnectable(host, port)) HostProbeResult.ForeignProcess else HostProbeResult.PortFree
        } catch (_: Exception) {
            if (tcpConnectable(host, port)) HostProbeResult.ForeignProcess else HostProbeResult.PortFree
        }
    }

    internal fun parse(body: String): HostProbeResult {
        val id = runCatching { json.decodeFromString(HostIdentity.serializer(), body) }.getOrNull()
            ?: return HostProbeResult.ForeignProcess
        if (id.hostId.isBlank()) return HostProbeResult.LegacySupermux
        return HostProbeResult.Supermux(id.hostId, id.build, id.mode, id.managedBy, id.stateDir)
    }

    private fun tcpConnectable(host: String, port: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 800); it.isConnected } }.getOrDefault(false)
}
