package dev.supermux.desktop.host

import dev.supermux.net.GitRequirement
import dev.supermux.net.HostIdentity
import dev.supermux.net.HostRequirements
import dev.supermux.net.KeepAwakeState
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
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
        /** false: the broker found no usable git. Legacy; [requirements] carries the full answer. */
        val gitAvailable: Boolean? = null,
        /** What the broker still needs to run agents; null from a broker older than requirements. */
        val requirements: HostRequirements? = null,
        /** "Keep this computer awake" as the broker holds it; null from a broker older than keep-awake. */
        val keepAwake: KeepAwakeState? = null,
    ) : HostProbeResult {
        /**
         * The broker's git requirement. An older broker that only said `gitAvailable: false` was a
         * Mac without the Command Line Tools (the only case it reported), so that maps to the
         * xcode-select install.
         */
        val gitRequirement: GitRequirement?
            get() = requirements?.git ?: when (gitAvailable) {
                false -> GitRequirement(ok = false, install = GitRequirement.INSTALL_XCODE_SELECT, hint = LEGACY_MAC_GIT_HINT)
                true -> GitRequirement(ok = true)
                null -> null
            }

        companion object {
            const val LEGACY_MAC_GIT_HINT = "Install Apple's Command Line Tools (xcode-select --install)"
        }
    }
    /** Something answers but is not ready (HTTP 5xx, or accepts TCP but times out): may be a broker still starting. */
    object Busy : HostProbeResult
    object ForeignProcess : HostProbeResult   // the port is held by something that is not a supermux broker
    object PortFree : HostProbeResult
}

/** What the supervisor should do. Pure output of [decideHost]. */
sealed interface HostPlan {
    data object NotHosting : HostPlan
    data object Start : HostPlan
    data object MovePort : HostPlan
    /** The port is busy (maybe a broker still starting). The supervisor (Task 8) retries for up to 30 s before treating it as [MovePort]. */
    data object Wait : HostPlan
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
        HostProbeResult.Busy -> HostPlan.Wait
        is HostProbeResult.Supermux -> when {
            probe.managedBy == "desktop" -> when {
                probe.stateDir != null && !sameDir(probe.stateDir, appStateDir) -> HostPlan.ReadOnly
                bundledBuild == null || probe.mode != "binary" -> HostPlan.UseOwn
                BrokerVersion.sameBuild(probe.build, bundledBuild) -> HostPlan.UseOwn
                else -> HostPlan.UpdateOwn
            }
            probe.hostId in prefs.leftAloneHostIds -> HostPlan.ReadOnly
            probe.mode != "binary" -> HostPlan.ReadOnly
            probe.stateDir == null || !sameDir(probe.stateDir, appStateDir) -> HostPlan.ReadOnly
            BrokerVersion.isNewer(found = probe.build, bundled = bundledBuild) -> HostPlan.AskDowngrade(probe.hostId)
            else -> HostPlan.AskTakeover(probe.hostId)
        }
    }
}

private fun canonical(p: String): String {
    val path = runCatching { Path.of(p) }.getOrNull() ?: return p
    val c = runCatching { path.toRealPath().toString() }.getOrNull()
        ?: runCatching { path.toAbsolutePath().normalize().toString() }.getOrDefault(p)
    return if (File.separatorChar == '\\') c.lowercase() else c
}

private fun sameDir(a: String, b: String): Boolean = canonical(a) == canonical(b)

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
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build() }

    fun probe(port: Int, host: String = "127.0.0.1"): HostProbeResult {
        val req = HttpRequest.newBuilder(URI.create("http://$host:$port/host")).timeout(Duration.ofSeconds(3)).GET().build()
        return try {
            val resp = client.send(req, BodyHandlers.ofString())
            when (val code = resp.statusCode()) {
                200 -> parse(resp.body())
                in 500..599 -> HostProbeResult.Busy
                else -> HostProbeResult.ForeignProcess
            }
        } catch (_: ConnectException) {
            HostProbeResult.PortFree
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw e
        } catch (_: HttpTimeoutException) {
            if (tcpConnectable(host, port)) HostProbeResult.Busy else HostProbeResult.PortFree
        } catch (_: IOException) {
            if (tcpConnectable(host, port)) HostProbeResult.ForeignProcess else HostProbeResult.PortFree
        } catch (_: Exception) {
            if (tcpConnectable(host, port)) HostProbeResult.ForeignProcess else HostProbeResult.PortFree
        }
    }

    internal fun parse(body: String): HostProbeResult {
        val id = runCatching { json.decodeFromString(HostIdentity.serializer(), body) }.getOrNull()
            ?: return HostProbeResult.ForeignProcess
        if (id.hostId.isBlank()) return HostProbeResult.ForeignProcess
        return HostProbeResult.Supermux(id.hostId, id.build, id.mode, id.managedBy, id.stateDir, id.gitAvailable, id.requirements, id.keepAwake)
    }

    private fun tcpConnectable(host: String, port: Int): Boolean =
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 800); it.isConnected } }.getOrDefault(false)
}
