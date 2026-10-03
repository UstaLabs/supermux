package dev.supermux.desktop.host

import dev.supermux.desktop.auth.DesktopTokenStore
import dev.supermux.host.PairedHost
import dev.supermux.host.PairedHostStore
import androidx.compose.ui.graphics.ImageBitmap
import dev.supermux.ui.widgets.qrBitmap
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
    suspend fun mintLocalClaim(
        localUrl: String,
        deviceName: String,
        existingToken: String?,
        onNewToken: (String) -> Unit = {},
    ): HostClaim? = withContext(Dispatchers.IO) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        mintClaimWith(
            existingToken = existingToken,
            secretless = { secretlessClaimToken(client, localUrl, deviceName) },
            mintSecret = { mintClaimSecret(client, localUrl, it) },
            relay = { fetchRelayUrl(client, localUrl, it) },
            onNewToken = onNewToken,
        )
    }

    /** A claim minted with [token] only: never a secretless claim ("Pair a device…"). */
    suspend fun mintClaimForToken(localUrl: String, token: String): HostClaim? = withContext(Dispatchers.IO) {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        mintClaimWith(
            existingToken = token,
            secretless = null,
            mintSecret = { mintClaimSecret(client, localUrl, it) },
            relay = { fetchRelayUrl(client, localUrl, it) },
        )
    }

    /**
     * The mint sequence with its HTTP calls injected. [existingToken] is used when present; otherwise
     * [secretless] (trust-on-first-connect) mints one, and it goes to [onNewToken] BEFORE the claim
     * secret is minted, so a failure or a closed window after that point never strands a claimed
     * broker with no stored token. [secretless] null = never make a secretless claim.
     */
    internal suspend fun mintClaimWith(
        existingToken: String?,
        secretless: (suspend () -> String?)?,
        mintSecret: suspend (token: String) -> String?,
        relay: suspend (token: String) -> String?,
        onNewToken: (String) -> Unit = {},
    ): HostClaim? {
        val token = existingToken?.takeIf { it.isNotBlank() }
            ?: secretless?.invoke()?.takeIf { it.isNotBlank() }?.also(onNewToken)
            ?: return null
        val secret = mintSecret(token)?.takeIf { it.isNotBlank() } ?: return null
        return HostClaim(localToken = token, claimSecret = secret, relayUrl = relay(token))
    }

    /** "This computer"'s record: the one with [hostId], else one whose direct URL is loopback. */
    fun thisComputerRecord(hosts: List<PairedHost>, hostId: String?): PairedHost? =
        hosts.firstOrNull { hostId != null && it.hostId == hostId } ?: hosts.firstOrNull { isLoopbackUrl(it.directUrl) }

    /** Store (or refresh) "This computer" in [hostStore], then tell the live fleet. */
    fun saveThisComputer(
        hostStore: PairedHostStore,
        hostName: String,
        token: String,
        directUrl: String?,
        hostId: String,
        onStoreChanged: () -> Unit,
    ) {
        hostStore.addOrUpdate(
            displayName = hostName,
            token = token,
            directUrl = directUrl,
            hostId = hostId,
            platform = System.getProperty("os.name"),
        )
        runCatching(onStoreChanged).onFailure { System.err.println("supermux host: fleet refresh failed: ${it.message}") }
    }

    /**
     * "Pair a device…": a model that only mints a claim with "This computer"'s [token] and shows its
     * QR. It never starts the broker, never makes a secretless claim, and its finish does nothing.
     * Null when there is no token: nothing may touch the network then.
     */
    fun pairOnlyModel(
        scope: CoroutineScope,
        hostName: String,
        token: String?,
        hostId: () -> String?,
        directUrl: () -> String?,
        mint: suspend (token: String) -> HostClaim?,
        qrOf: (String) -> ImageBitmap = { qrBitmap(it) },
    ): HostWizardModel? {
        val t = token?.takeIf { it.isNotBlank() } ?: return null
        return HostWizardModel(
            scope = scope,
            hostName = hostName,
            provideHostId = { hostId() },
            provideLocalUrl = directUrl,
            mintClaim = { mint(t) },
            onPairThisComputer = { _, _, _ -> },
            onInstallKeepAlive = {},
            qrOf = qrOf,
        )
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

    /**
     * `POST /system/install-git` on the local broker with "This computer"'s [token]. The broker
     * starts the OS installer itself; a manual host answers `error="manual"` + `hint`. Null when
     * the broker is unreachable or the body is not one.
     */
    suspend fun installGit(localUrl: String, token: String): dev.supermux.net.InstallGitResult? = withContext(Dispatchers.IO) {
        runCatching {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val req = HttpRequest.newBuilder(URI.create("$localUrl/system/install-git"))
                .timeout(Duration.ofSeconds(10))
                .header("authorization", "Bearer $token")
                .POST(HttpRequest.BodyPublishers.noBody()).build()
            val resp = client.send(req, BodyHandlers.ofString())
            val decoded = json.decodeFromString(dev.supermux.net.InstallGitResult.serializer(), resp.body())
            if (resp.statusCode() in 200..299) decoded else decoded.copy(ok = false)
        }.getOrNull()
    }

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
        onStoreChanged: () -> Unit = {},
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
            mintLocalClaim(
                supervisor.localBaseUrl, hostName, existing,
                // A trust-on-first-connect token is stored at once, not only on Done.
                onNewToken = tofuSaver(hostStore, hostName, supervisor.localBaseUrl, { supervisor.hostId.value }, onStoreChanged),
            )
        },
        onPairThisComputer = { localToken, directUrl, hostId ->
            saveThisComputer(hostStore, hostName, localToken, directUrl, hostId, onStoreChanged)
        },
        onInstallKeepAlive = { keepRunning ->
            // A broker set up outside the app is not ours to move into or out of the background.
            if (!isReadOnly(supervisor.status.value)) scope.launch { supervisor.setBackground(keepRunning) }
        },
    )

    /** What [buildModel] does with a freshly minted trust-on-first-connect token. */
    internal fun tofuSaver(
        hostStore: PairedHostStore,
        hostName: String,
        directUrl: String,
        hostId: () -> String?,
        onStoreChanged: () -> Unit,
    ): (String) -> Unit = { token ->
        val id = hostId()
        if (id != null) saveThisComputer(hostStore, hostName, token, directUrl, id, onStoreChanged)
    }

    internal fun isReadOnly(s: HostingStatus): Boolean = s is HostingStatus.Running && s.readOnly

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
