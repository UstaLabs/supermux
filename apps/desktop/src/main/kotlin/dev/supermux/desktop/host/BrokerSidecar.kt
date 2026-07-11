package dev.supermux.desktop.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Supervises the local broker for the desktop-as-host app (Plan 3 Task 2 / spec §6, D6):
 * **adopt-don't-duplicate**. On start it probes `:9898` `GET /host` and runs the pure [decideHost]
 * policy —
 *  - a live supermux broker  ⇒ **adopt** it (ownership `external`, NEVER stop it),
 *  - nothing on the port      ⇒ **spawn** our own (ownership `managed`),
 *  - a foreign squatter       ⇒ spawn ours on a **persisted alternate port** (still managed),
 *  - a pre-Plan-1 legacy broker ⇒ surface `NeedsUpgrade` and touch nothing.
 *
 * A per-user file lock in `~/.mux/state` (shared with the keep-alive login agent) ensures only ONE
 * manager spawns a broker; an instance that can't take the lock adopts whatever the lock-holder
 * brings up instead of double-spawning. Health is a `GET /host` poll until 200; [stop] tears down
 * ONLY a broker this sidecar spawned.
 *
 * The decision / ownership / lock / port logic is pure and unit-tested ([BrokerSidecarTest]); the
 * network probe + `ProcessBuilder` spawn are injectable seams so the tests need no broker, while a
 * real headless smoke drives the true spawn→health→stop path against a source broker.
 */
class BrokerSidecar(
    private val config: SidecarConfig = SidecarConfig(),
    // Injectable seams (default: real HTTP probe + ProcessBuilder spawn). Tests replace these.
    private val probe: suspend (port: Int) -> HostProbeResult = defaultProbe(config),
    private val spawn: (SidecarConfig, port: Int) -> Process = { c, p -> defaultSpawn(c, p) },
    private val lock: SidecarLock = SidecarLock(config.stateDir.resolve(LOCK_FILE)),
    private val portStore: SidecarPortStore = SidecarPortStore(config.stateDir.resolve(PORT_FILE)),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    /** Who started the broker we're pointing at — decides whether [stop] may terminate it. */
    enum class Ownership { None, Managed, External }

    /** Lifecycle for the UI (spec §6). Starting → {Online (managed) | Adopted (external) | …}. */
    enum class Phase { Idle, Starting, Online, Adopted, Conflict, NeedsUpgrade, Failed, Stopped }

    private val _state = MutableStateFlow(Phase.Idle)
    val state: StateFlow<Phase> = _state.asStateFlow()

    private val _ownership = MutableStateFlow(Ownership.None)
    val ownership: StateFlow<Ownership> = _ownership.asStateFlow()

    private val _hostId = MutableStateFlow<String?>(null)
    val hostId: StateFlow<String?> = _hostId.asStateFlow()

    /** The port the broker we adopted/spawned is actually on (default, or the alternate on conflict). */
    @Volatile var effectivePort: Int = config.port
        private set

    /** `http://<host>:<effectivePort>` — the local base URL the wizard uses for its own claim. */
    val localBaseUrl: String get() = "http://${config.host}:$effectivePort"

    @Volatile private var managed: Process? = null
    @Volatile var holdsLock: Boolean = false
        private set

    /**
     * Probe → decide → adopt/spawn → health-poll. Idempotent-ish: safe to call once per app launch.
     * Never throws (failures land in [Phase.Failed]); never stops a broker it didn't start.
     */
    suspend fun start() {
        _state.value = Phase.Starting
        holdsLock = runCatching { lock.tryAcquire() }.getOrDefault(false)

        val decision = decideHost(probe(config.port))
        when (decision) {
            HostDecision.AdoptExternal -> adopt(config.port)
            HostDecision.UpgradeRequired -> {
                _ownership.value = Ownership.None
                _state.value = Phase.NeedsUpgrade
            }
            HostDecision.SpawnManaged ->
                if (shouldSpawn(decision, holdsLock)) spawnManaged(config.port) else adopt(config.port)
            HostDecision.PortConflict ->
                if (shouldSpawn(decision, holdsLock)) {
                    _state.value = Phase.Conflict
                    effectivePort = resolveAndPersistAlternatePort()
                    spawnManaged(effectivePort)
                } else {
                    // Not our job to spawn; wait for the lock-holder's broker to appear on :9898.
                    adopt(config.port)
                }
        }
    }

    /** Adopt an external broker: confirm it's healthy and learn its hostId; never spawn/own it. */
    private suspend fun adopt(port: Int) {
        effectivePort = port
        _ownership.value = Ownership.External
        val id = pollHealth(port)
        if (id != null) {
            _hostId.value = id
            _state.value = Phase.Adopted
        } else {
            _state.value = Phase.Failed
        }
    }

    /** Spawn OUR broker on [port] and wait for it to answer GET /host. On timeout, kill it + Failed. */
    private suspend fun spawnManaged(port: Int) {
        effectivePort = port
        _ownership.value = Ownership.Managed
        val proc = runCatching { spawn(config, port) }.getOrElse {
            _state.value = Phase.Failed
            return
        }
        managed = proc
        val id = pollHealth(port)
        if (id != null) {
            _hostId.value = id
            _state.value = Phase.Online
        } else {
            // Never came up (e.g. no broker entrypoint, or the port was actually taken) — clean up
            // our own child; we still never touch anything we didn't start.
            runCatching { proc.destroy() }
            _state.value = Phase.Failed
        }
    }

    /** Poll GET /host until it returns a SupermuxHost id or the health deadline passes. */
    private suspend fun pollHealth(port: Int): String? {
        val deadline = nowMs() + config.healthTimeoutMs
        while (nowMs() < deadline) {
            val r = runCatching { probe(port) }.getOrNull()
            if (r is HostProbeResult.SupermuxHost && r.hostId.isNotBlank()) return r.hostId
            delay(config.healthPollMs)
        }
        return null
    }

    private fun resolveAndPersistAlternatePort(): Int {
        val chosen = chooseAlternatePort(portStore.loadAlternate(), config.port)
        portStore.saveAlternate(chosen)
        return chosen
    }

    /**
     * Stop ONLY a broker this sidecar spawned ([Ownership.Managed]); an adopted external broker is
     * left running. Releases the file lock. Safe to call multiple times.
     */
    fun stop() {
        if (_ownership.value == Ownership.Managed) {
            managed?.let { p ->
                runCatching {
                    p.destroy()
                    if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
                }
            }
        }
        managed = null
        _state.value = Phase.Stopped
        runCatching { lock.release() }
    }

    companion object {
        const val LOCK_FILE = "desktop-sidecar.lock"
        const val PORT_FILE = "desktop-sidecar.json"
        const val DEFAULT_ALTERNATE_PORT = 9899

        /**
         * Pure guard: this instance spawns a broker only when it holds the manager lock AND the
         * decision calls for one of our own (spawn-fresh or conflict-onto-an-alt-port). An external
         * adopt never spawns; a lock-less instance defers to whoever holds the lock.
         */
        fun shouldSpawn(decision: HostDecision, holdsLock: Boolean): Boolean =
            holdsLock && (decision == HostDecision.SpawnManaged || decision == HostDecision.PortConflict)

        /** Persisted alternate port if we've chosen one before, else a stable fallback (default+1-ish). */
        fun chooseAlternatePort(persisted: Int?, defaultPort: Int): Int =
            persisted?.takeIf { it in 1..65535 && it != defaultPort } ?: DEFAULT_ALTERNATE_PORT

        /** dev: `bun <repo>/src/main.ts`; packaged: the bundled broker entrypoint (Task 5 follow-up). */
        fun buildSpawnCommand(config: SidecarConfig): List<String> =
            config.bundledBrokerPath?.let { listOf(it.toString()) }
                ?: config.repoDir?.let { listOf(config.bunPath, it.resolve("src/main.ts").toString()) }
                ?: error("BrokerSidecar has no broker entrypoint (set repoDir for dev or bundledBrokerPath when packaged)")

        /** Env the spawned broker runs with: its web port, an optional relay domain, then any overrides. */
        fun buildSpawnEnv(config: SidecarConfig, port: Int): Map<String, String> = buildMap {
            put("MUX_WEB_PORT", port.toString())
            config.relayDomain?.let { put("MUX_RELAY_DOMAIN", it) }
            putAll(config.extraEnv)
        }

        /** Default `~/.mux/state` — where the sidecar's lock + alternate-port file live (spec paths). */
        fun defaultStateDir(): Path {
            val home = System.getProperty("user.home") ?: "."
            val muxHome = System.getenv("MUX_HOME") ?: (home + "/.mux")
            return Path.of(System.getenv("MUX_STATE_DIR") ?: "$muxHome/state")
        }

        /** Real HTTP probe of `GET /host` (java.net.http — no ktor engine to manage). */
        fun defaultProbe(config: SidecarConfig): suspend (Int) -> HostProbeResult = { port ->
            withContext(Dispatchers.IO) { httpProbe(config.host, port) }
        }

        private val probeJson = Json { ignoreUnknownKeys = true }

        internal fun httpProbe(host: String, port: Int): HostProbeResult {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            val req = HttpRequest.newBuilder(URI.create("http://$host:$port/host"))
                .timeout(Duration.ofSeconds(3)).GET().build()
            return try {
                val resp = client.send(req, BodyHandlers.ofString())
                if (resp.statusCode() == 200) {
                    val id = parseHostId(resp.body())
                    if (!id.isNullOrBlank()) HostProbeResult.SupermuxHost(id) else HostProbeResult.LegacySupermux
                } else {
                    // The port answered HTTP but not our identity route — a foreign server.
                    HostProbeResult.ForeignProcess
                }
            } catch (_: ConnectException) {
                HostProbeResult.PortFree // refused → nothing listening
            } catch (_: IOException) {
                // No usable HTTP reply. Distinguish "port truly free" from "a non-HTTP squatter" with
                // a raw TCP connect so we never try to bind a port something else already holds.
                if (tcpConnectable(host, port)) HostProbeResult.ForeignProcess else HostProbeResult.PortFree
            } catch (_: Exception) {
                if (tcpConnectable(host, port)) HostProbeResult.ForeignProcess else HostProbeResult.PortFree
            }
        }

        private fun tcpConnectable(host: String, port: Int): Boolean =
            runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), 800); it.isConnected }
            }.getOrDefault(false)

        internal fun parseHostId(body: String): String? = runCatching {
            probeJson.decodeFromString(dev.supermux.net.HostIdentity.serializer(), body).hostId.takeIf { it.isNotBlank() }
        }.getOrNull()

        /** Real spawn: ProcessBuilder over [buildSpawnCommand], broker stdout/err → a rolling log. */
        fun defaultSpawn(config: SidecarConfig, port: Int): Process {
            val pb = ProcessBuilder(buildSpawnCommand(config))
            config.repoDir?.let { pb.directory(it.toFile()) }
            pb.environment().putAll(buildSpawnEnv(config, port))
            runCatching { Files.createDirectories(config.stateDir) }
            val log = config.stateDir.resolve("desktop-broker.log").toFile()
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            pb.redirectError(ProcessBuilder.Redirect.appendTo(log))
            return pb.start()
        }
    }
}

/**
 * Where + how the sidecar runs the broker. [stateDir] holds the manager lock + alternate-port file
 * (default `~/.mux/state`); [repoDir]/[bunPath] drive the dev `bun src/main.ts` spawn; [extraEnv]
 * lets a smoke/test isolate the child (`MUX_STATE_DIR`, an alternate whatsapp-webhook port, …).
 */
data class SidecarConfig(
    val port: Int = 9898,
    val host: String = "127.0.0.1",
    val stateDir: Path = BrokerSidecar.defaultStateDir(),
    val repoDir: Path? = null,
    val bunPath: String = "bun",
    val bundledBrokerPath: Path? = null,
    val relayDomain: String? = null,
    val extraEnv: Map<String, String> = emptyMap(),
    val healthTimeoutMs: Long = 45_000,
    val healthPollMs: Long = 500,
)

/**
 * A per-user advisory lock so only one desktop sidecar (or the keep-alive login agent) manages the
 * broker. `FileChannel.tryLock` is process-wide; within one JVM an overlapping lock throws, which we
 * map to "not acquired". Pure + unit-tested ([BrokerSidecarTest]).
 */
class SidecarLock(private val lockFile: Path) {
    private var channel: FileChannel? = null
    private var handle: FileLock? = null

    val held: Boolean get() = handle != null

    /** True if this instance now holds the lock. False if another holder has it (or on any error). */
    fun tryAcquire(): Boolean {
        if (held) return true
        return try {
            Files.createDirectories(lockFile.parent)
            val ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val l = try {
                ch.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (l == null) {
                ch.close()
                false
            } else {
                channel = ch
                handle = l
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun release() {
        runCatching { handle?.release() }
        runCatching { channel?.close() }
        handle = null
        channel = null
    }
}

/** Persists the chosen alternate port (spec §6 PortConflict) so it's stable across restarts. */
class SidecarPortStore(private val file: Path) {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadAlternate(): Int? = runCatching {
        json.decodeFromString(Blob.serializer(), Files.readString(file)).alternatePort
    }.getOrNull()?.takeIf { it in 1..65535 }

    fun saveAlternate(port: Int) {
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(Blob.serializer(), Blob(port)))
        }
    }

    @kotlinx.serialization.Serializable
    private data class Blob(val alternatePort: Int? = null)
}
