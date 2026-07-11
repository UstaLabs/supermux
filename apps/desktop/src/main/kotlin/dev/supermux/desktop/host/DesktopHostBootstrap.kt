package dev.supermux.desktop.host

import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.host.PairedHostStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * Production wiring for the first-run [HostWizard] (Plan 3 Task 3): starts/adopts the local broker via
 * a [BrokerSidecar], bootstraps a local device token, mints the phone claim, and builds a
 * [HostWizardModel]. All network work is best-effort (never throws); the pieces are runtime-gated (a
 * live local broker) and so are verified via the sidecar smoke + the wizard's unit/Compose tests, not
 * a headless run of the full app.
 */
object DesktopHostBootstrap {

    private val json = Json { ignoreUnknownKeys = true }
    private const val AUTH_COOKIE = "cmux_token" // matches the broker's src/channels/web/cookies.ts

    /** macOS/Linux host natively (first-run shows the host wizard); Windows/other do not (Task 6 card). */
    fun isNativeHostPlatform(env: KeepAliveEnv = SystemKeepAliveEnv): Boolean =
        env.os == KeepAlive.Os.MAC || env.os == KeepAlive.Os.LINUX

    /**
     * Walk up from the working dir to find the dev repo root (the dir containing `src/main.ts`) so a
     * source checkout can spawn `bun src/main.ts`. Returns null in a packaged app (→ a bundled broker
     * path is Task 5; until then the sidecar simply adopts an already-running broker).
     */
    fun detectRepoDir(start: Path = Path.of(System.getProperty("user.dir") ?: ".")): Path? {
        var dir: Path? = start.toAbsolutePath()
        var hops = 0
        while (dir != null && hops < 8) {
            if (Files.exists(dir.resolve("src/main.ts")) && Files.exists(dir.resolve("package.json"))) return dir
            dir = dir.parent
            hops++
        }
        return null
    }

    /** A sidecar pointed at the local broker, dev-repo aware. Caller owns start()/stop(). */
    fun sidecar(port: Int = 9898): BrokerSidecar =
        BrokerSidecar(SidecarConfig(port = port, repoDir = detectRepoDir()))

    /**
     * Bootstrap a local device token then mint a one-time phone claim from the LOCAL broker:
     *  1. reuse [existingToken] if present, else trust-on-first-connect (secretless POST /pair/claim on
     *     a brand-new broker) and read the token off its Set-Cookie;
     *  2. POST /pair/mint-claim (authed) → the one-time claimSecret.
     * Returns null on any failure (e.g. the broker is already set up and we hold no token).
     */
    suspend fun mintLocalClaim(localUrl: String, deviceName: String, existingToken: String?): HostClaim? =
        withContext(Dispatchers.IO) {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val token = existingToken?.takeIf { it.isNotBlank() } ?: secretlessClaimToken(client, localUrl, deviceName)
            if (token.isNullOrBlank()) return@withContext null
            val secret = mintClaimSecret(client, localUrl, token) ?: return@withContext null
            HostClaim(localToken = token, claimSecret = secret)
        }

    /** POST /pair/claim with no secret (brand-new broker) → the minted token from the Set-Cookie header. */
    private fun secretlessClaimToken(client: HttpClient, localUrl: String, deviceName: String): String? = runCatching {
        val body = json.encodeToString(ClaimBody.serializer(), ClaimBody(deviceName = deviceName))
        val req = HttpRequest.newBuilder(URI.create("$localUrl/pair/claim"))
            .timeout(Duration.ofSeconds(5))
            .header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build()
        val resp = client.send(req, BodyHandlers.ofString())
        if (resp.statusCode() != 200) return null
        // The brand-new-broker path returns {paired,name} + a Set-Cookie carrying the device token.
        resp.headers().allValues("set-cookie")
            .firstNotNullOfOrNull { extractCookieToken(it) }
    }.getOrNull()

    /** POST /pair/mint-claim (Bearer) → {claimSecret}. */
    private fun mintClaimSecret(client: HttpClient, localUrl: String, token: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI.create("$localUrl/pair/mint-claim"))
            .timeout(Duration.ofSeconds(5))
            .header("authorization", "Bearer $token")
            .POST(HttpRequest.BodyPublishers.noBody()).build()
        val resp = client.send(req, BodyHandlers.ofString())
        if (resp.statusCode() != 200) return null
        json.decodeFromString(MintResult.serializer(), resp.body()).claimSecret.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Pull `<AUTH_COOKIE>=<token>` out of one Set-Cookie header value. */
    internal fun extractCookieToken(setCookie: String): String? {
        val first = setCookie.substringBefore(";").trim()
        if (!first.startsWith("$AUTH_COOKIE=")) return null
        return first.substringAfter("=").trim().takeIf { it.isNotBlank() }
    }

    /**
     * Build the production [HostWizardModel]. Starts the [sidecar] (best-effort) and awaits its hostId,
     * mints the claim, and on finish auto-pairs "This computer" into [hostStore] + installs the login
     * keep-alive when the box is checked.
     */
    fun buildModel(
        scope: CoroutineScope,
        hostStore: PairedHostStore,
        sidecar: BrokerSidecar,
        hostName: String = defaultHostName(),
        keepAliveExec: List<String> = currentAppCommand(),
        tokenStore: DesktopTokenStore = DesktopTokenStore(),
    ): HostWizardModel = HostWizardModel(
        scope = scope,
        hostName = hostName,
        provideHostId = {
            // Kick the sidecar if it hasn't run, then wait (≤60s) for it to learn a hostId.
            if (sidecar.state.value == BrokerSidecar.Phase.Idle) scope.launch { sidecar.start() }
            var id = sidecar.hostId.value
            val deadline = System.currentTimeMillis() + 60_000
            while (id.isNullOrBlank() && System.currentTimeMillis() < deadline) {
                delay(500)
                id = sidecar.hostId.value
            }
            id
        },
        provideLocalUrl = { sidecar.localBaseUrl },
        mintClaim = {
            // Reuse an existing "This computer" token if we already have one (reconnect), else bootstrap.
            val existing = hostStore.list().firstOrNull { it.hostId == sidecar.hostId.value }?.token
            mintLocalClaim(sidecar.localBaseUrl, hostName, existing)
        },
        provideRelayUrl = { null }, // hosting-remote/relay is a follow-up (spec §6 D7); direct-only for now
        onPairThisComputer = { localToken, directUrl, hostId ->
            hostStore.addOrUpdate(
                displayName = hostName,
                token = localToken,
                directUrl = directUrl,
                hostId = hostId,
                platform = System.getProperty("os.name"),
            )
        },
        onInstallKeepAlive = { keepAlive ->
            if (keepAlive) {
                runCatching {
                    KeepAlive.install(KeepAlive.Spec(exec = keepAliveExec, hostId = sidecar.hostId.value))
                }
            }
        },
    )

    /** "This computer" plus the machine's hostname when we can read it. */
    fun defaultHostName(): String {
        val h = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        return if (h != null) "This computer ($h)" else "This computer"
    }

    /** Best-effort argv for the login keep-alive to relaunch (the packaged app, or the dev java cmd). */
    fun currentAppCommand(): List<String> =
        runCatching { ProcessHandle.current().info().commandLine().orElse(null) }
            .getOrNull()?.takeIf { it.isNotBlank() }?.let { listOf(it) }
            ?: listOf(System.getProperty("java.home") + "/bin/java")

    @kotlinx.serialization.Serializable
    private data class ClaimBody(val deviceName: String)

    @kotlinx.serialization.Serializable
    private data class MintResult(val claimSecret: String = "")
}
