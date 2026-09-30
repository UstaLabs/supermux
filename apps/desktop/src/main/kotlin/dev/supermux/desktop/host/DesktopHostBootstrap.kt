package dev.supermux.desktop.host

import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 * the [HostSupervisor], bootstraps a local device token, mints the phone claim, and builds a
 * [HostWizardModel]. All network work is best-effort (never throws); the pieces are runtime-gated (a
 * live local broker) and so are verified via the supervisor's unit tests + the wizard's unit/Compose tests, not
 * a headless run of the full app.
 */
object DesktopHostBootstrap {

    private val json = Json { ignoreUnknownKeys = true }
    private const val AUTH_COOKIE = "cmux_token" // matches the broker's src/channels/web/cookies.ts

    /** Every supported desktop OS hosts natively; unknown platforms fall back to client onboarding. */
    fun isNativeHostPlatform(env: OsEnv = SystemOsEnv): Boolean =
        env.os == OsEnv.Os.MAC || env.os == OsEnv.Os.LINUX || env.os == OsEnv.Os.WINDOWS

    /**
     * Walk up from the working dir to find the dev repo root (the dir containing `src/main.ts`) so a
     * source checkout can spawn `bun src/main.ts`. Returns null in a packaged app, where the supervisor
     * instead runs the bundled broker binary ([HostBinaries.resolve] → [supervisor]).
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

    /**
     * The production [HostSupervisor]: `hosting.json` prefs, the materialized bundled binaries
     * ([HostBinaries.resolve]) under the broker's state dir, and the real OS ([SystemOsEnv]).
     */
    fun supervisor(): HostSupervisor = sharedSupervisor.value

    /**
     * Every exit path (window quit, `finally`, shutdown hook) stops a child broker. Idempotent; a
     * no-op when the supervisor was never built (a client-only platform).
     */
    fun quitIfStarted() {
        if (sharedSupervisor.isInitialized()) sharedSupervisor.value.quit()
    }

    /**
     * Before the supervisor exists: write the first `hosting.json` when [initialHostingPrefs] says
     * so (an upgrade paired only to other computers starts with hosting OFF). Best-effort.
     */
    fun seedHostingPrefs(hosts: List<PairedHost>, store: HostingPrefsStore = HostingPrefsStore()) {
        runCatching {
            initialHostingPrefs(hosts, store.exists(), store.load())?.let(store::save)
        }.onFailure { System.err.println("supermux host: seeding hosting prefs failed: ${it.message}") }
    }

    /** One supervisor per process: two would each think they own the broker. */
    private val sharedSupervisor: Lazy<HostSupervisor> = lazy {
        val stateDir = BrokerPaths.defaultStateDir()
        val prefsStore = HostingPrefsStore()
        HostSupervisor(
            stateDir = stateDir,
            loadPrefs = prefsStore::load,
            savePrefs = prefsStore::save,
            osEnv = SystemOsEnv,
            materialize = { HostBinaries.resolve(stateDir) },
        )
    }

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
            HostClaim(localToken = token, claimSecret = secret, relayUrl = fetchRelayUrl(client, localUrl, token))
        }

    /** The local broker's relay URL from `/me` (Settings ▸ Hosting's Remote row). Null when off or unknown. */
    suspend fun localRelayUrl(localUrl: String, token: String): String? = withContext(Dispatchers.IO) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        fetchRelayUrl(client, localUrl, token)
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

    private fun fetchRelayUrl(client: HttpClient, localUrl: String, token: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI.create("$localUrl/me"))
            .timeout(Duration.ofSeconds(5))
            .header("authorization", "Bearer $token")
            .GET().build()
        val resp = client.send(req, BodyHandlers.ofString())
        if (resp.statusCode() != 200) return null
        json.decodeFromString(MeResult.serializer(), resp.body()).relayUrl?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Pull `<AUTH_COOKIE>=<token>` out of one Set-Cookie header value. */
    internal fun extractCookieToken(setCookie: String): String? {
        val first = setCookie.substringBefore(";").trim()
        if (!first.startsWith("$AUTH_COOKIE=")) return null
        return first.substringAfter("=").trim().takeIf { it.isNotBlank() }
    }

    /**
     * Build the production [HostWizardModel]. Runs [HostSupervisor.ensure] and awaits its hostId,
     * mints the claim, and on finish auto-pairs "This computer" into [hostStore] and applies the
     * "keep running in the background" box through [HostSupervisor.setBackground].
     */
    fun buildModel(
        scope: CoroutineScope,
        hostStore: PairedHostStore,
        supervisor: HostSupervisor,
        hostName: String = defaultHostName(),
        tokenStore: DesktopTokenStore = DesktopTokenStore(),
    ): HostWizardModel = HostWizardModel(
        scope = scope,
        hostName = hostName,
        provideHostId = {
            // Already running: done. Otherwise run the launch decision and report what it ended in
            // (no waiting on a hostId that a CantStart will never produce).
            supervisor.hostId.value?.takeIf { supervisor.status.value is HostingStatus.Running } ?: run {
                supervisor.ensure()
                supervisor.hostId.value?.takeIf { supervisor.status.value is HostingStatus.Running }
            }
        },
        provideLocalUrl = { supervisor.localBaseUrl },
        mintClaim = {
            // Reuse an existing "This computer" token if we already have one (reconnect), else bootstrap.
            val existing = hostStore.list().firstOrNull { it.hostId == supervisor.hostId.value }?.token
            mintLocalClaim(supervisor.localBaseUrl, hostName, existing)
        },
        onPairThisComputer = { localToken, directUrl, hostId ->
            hostStore.addOrUpdate(
                displayName = hostName,
                token = localToken,
                directUrl = directUrl,
                hostId = hostId,
                platform = System.getProperty("os.name"),
            )
        },
        onInstallKeepAlive = { keepRunning -> scope.launch { supervisor.setBackground(keepRunning) } },
    )

    /** The machine's actual hostname; fleet clients must not see every host as "This". */
    fun defaultHostName(): String {
        val h = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() }
        return h ?: "Desktop host"
    }

    @kotlinx.serialization.Serializable
    private data class ClaimBody(val deviceName: String)

    @kotlinx.serialization.Serializable
    private data class MintResult(val claimSecret: String = "")

    @kotlinx.serialization.Serializable
    private data class MeResult(val relayUrl: String? = null)
}
