package dev.supermux.desktop.host

import dev.supermux.desktop.platform.DesktopOs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.StandardCopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Card #77 on a real systemd: two copies of the app share one user's service name and state dir.
 * Opt-in: a throwaway container runs systemd and a user whose home is [dir] (shared with this JVM,
 * same uid), so the unit files this test writes are the ones its `systemctl --user` reads.
 * `SUPERMUX_REAL_SYSTEMD_CONTAINER=<name> SUPERMUX_REAL_SYSTEMD_DIR=<dir>`; skipped otherwise.
 * The broker is a stand-in that answers `/host` the way a desktop-managed broker does.
 */
class RealLinuxOwnershipTest {
    private val container: String? = System.getenv("SUPERMUX_REAL_SYSTEMD_CONTAINER")
    private val dir: Path? = System.getenv("SUPERMUX_REAL_SYSTEMD_DIR")?.let(Path::of)
    /** Released brokers, `<dir>/bin-<version>/supermux-linux-x64`, for the update test. */
    private val released: Path? = System.getenv("SUPERMUX_RELEASED_BROKERS")?.let(Path::of)

    /** Every command runs as the container's user, against its systemd user manager. */
    private inner class ContainerOsEnv(override val home: Path) : OsEnv {
        override val os = DesktopOs.LINUX
        override val localAppData: Path = home.resolve(".local/share")
        override val uid: Long = 1000
        override val xdgRuntimeDir = "/run/user/1000"
        val ran: MutableList<List<String>> = java.util.Collections.synchronizedList(mutableListOf())
        private fun inside(argv: List<String>) = listOf(
            "docker", "exec", "-u", "1000", "-e", "XDG_RUNTIME_DIR=/run/user/1000",
            "-e", "DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus", container!!,
        ) + argv
        override fun hasCommand(name: String) = SystemOsEnv.runResult(inside(listOf("sh", "-c", "command -v $name"))).exit == 0
        override fun run(argv: List<String>) = runResult(argv).exit == 0
        override fun runResult(argv: List<String>): OsEnv.RunResult { ran += argv; return SystemOsEnv.runResult(inside(argv)) }
        override fun runCapture(argv: List<String>) = runResult(argv).takeIf { it.exit == 0 }?.out
        override fun sleep(ms: Long) = Thread.sleep(ms)
    }

    /** A stand-in broker: `/host` names the copy whose definition started it, and it writes broker.pid. */
    private fun writeBroker(state: Path) {
        val bin = state.resolve("desktop-assets/bin").also { Files.createDirectories(it) }
        val broker = bin.resolve("supermux-broker")
        Files.writeString(broker, """#!/usr/bin/env python3
import http.server, json, os
state = os.environ["MUX_STATE_DIR"]
open(os.path.join(state, "broker.pid"), "w").write(str(os.getpid()))
body = json.dumps({"hostId": "h" + os.environ.get("MUX_DESKTOP_INSTALL", "none")[:8], "protocolVersion": 1,
    "build": "1.0.0 (real)", "mode": "binary", "managedBy": os.environ.get("MUX_MANAGED_BY"), "stateDir": state}).encode()
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.send_header("content-type", "application/json"); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", int(os.environ["MUX_WEB_PORT"])), H).serve_forever()
""")
        Files.setPosixFilePermissions(broker, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun copyOfTheApp(name: String, root: Path, env: OsEnv, prefs: HostingPrefs, log: MutableList<String>): HostSupervisor {
        var saved = prefs
        val state = root.resolve("state")
        return HostSupervisor(
            stateDir = state,
            loadPrefs = { saved },
            savePrefs = { saved = it },
            osEnv = env,
            materialize = {
                HostBinaries.SidecarBinaries(state.resolve("desktop-assets/bin/supermux-broker"), state.resolve("desktop-assets/bin"), null, null, null)
            },
            packaged = { true },
            bundledBuild = { "1.0.0 (real)" },
            lateBundledBuild = { "1.0.0 (real)" },
            startChild = { error("no broker child in this test") },
            repoDir = { null },
            baseEnv = { emptyMap() },
            hostName = "copy-$name",
            existingPath = "/usr/bin:/bin",
            userHome = env.home.toString(),
            log = { log += "$name: $it" },
            identity = InstallIdentity(root.resolve("appdata-$name/install.json")),
        )
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }

    @Test fun aSecondCopyLeavesTheFirstCopysRunningUnitAlone() = runBlocking {
        assumeTrue("needs SUPERMUX_REAL_SYSTEMD_CONTAINER and SUPERMUX_REAL_SYSTEMD_DIR", container != null && dir != null)
        val root = dir!!
        val home = root.resolve("home")
        val env = ContainerOsEnv(home)
        val unit = home.resolve(".config/systemd/user/supermux-host.service")
        fun active() = env.runResult(listOf("systemctl", "--user", "is-active", BrokerService.SYSTEMD_NAME)).out.trim()
        writeBroker(root.resolve("state"))
        val port = freePort()
        val log = mutableListOf<String>()
        val a = copyOfTheApp("a", root, env, HostingPrefs(port = port, relay = false), log)
        val b = copyOfTheApp("b", root, env, HostingPrefs(port = port, relay = false), log)
        try {
            // The first copy hosts in the background: a real unit, running the broker.
            a.ensure()
            assertEquals(HostingStatus.Running(port, readOnly = false), a.status.value, log.joinToString("\n"))
            assertEquals("active", active())
            val first = Files.readString(unit)
            assertTrue(BrokerService.INSTALL_ENV in first)

            // The second copy's first run: it asks; then its wizard's "Connect to a different broker instead".
            val wizard = launch { b.ensure() }
            withTimeout(60_000) { b.status.first { it is HostingStatus.AskTakeover } }
            env.ran.clear()
            b.setHosting(false)
            wizard.join()
            assertEquals(HostingStatus.NotHosting, b.status.value)
            assertEquals(first, Files.readString(unit), "the first copy's unit is unchanged")
            assertEquals("active", active(), "and still running")
            assertIs<HostProbeResult.Supermux>(HostProber.probe(port))
            assertTrue(env.ran.none { it.getOrNull(2) in setOf("disable", "stop", "restart") }, env.ran.toString())

            // Its tray: background off. Its next launch with background off. Still nothing.
            val turnOn = launch { b.setHosting(true) }
            withTimeout(60_000) { b.status.first { it is HostingStatus.AskTakeover } }
            b.answerTakeover(false) // "Leave it alone"
            turnOn.join()
            assertEquals(HostingStatus.Running(port, readOnly = true), b.status.value)
            b.setBackground(false)
            b.ensure()
            assertEquals(first, Files.readString(unit))
            assertEquals("active", active())

            // Control: the first copy turning hosting off does remove its own unit.
            a.setHosting(false)
            assertFalse(Files.exists(unit))
            assertTrue(active() != "active")
        } finally {
            a.quit(); b.quit()
            env.run(listOf("systemctl", "--user", "disable", "--now", BrokerService.SYSTEMD_NAME))
            Files.deleteIfExists(unit)
            env.run(listOf("systemctl", "--user", "daemon-reload"))
        }
    }

    // ── an update from the released 0.17.4, on a real systemd with the real released broker ──

    private val http = HttpClient.newHttpClient()
    private fun call(method: String, url: String, token: String? = null, body: String? = null): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(10))
        token?.let { b.header("authorization", "Bearer $it") }
        if (body != null) b.header("content-type", "application/json")
        b.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString())
    }

    private suspend fun awaitHost(port: Int, ms: Long = 90_000): HostProbeResult.Supermux {
        val deadline = System.currentTimeMillis() + ms
        while (true) {
            val r = HostProber.probe(port)
            if (r is HostProbeResult.Supermux) return r
            check(System.currentTimeMillis() < deadline) { "no broker on $port" }
            kotlinx.coroutines.delay(1_000)
        }
    }

    /** How the 0.17.4 user left things: [hostingJson] the wizard's Done saved it; [thisComputer] still paired. */
    private enum class User(val hostingJson: Boolean, val thisComputer: Boolean) {
        PAIRED(true, true),
        UNPAIRED_BUT_HOSTING(true, false),
        WIZARD_NEVER_FINISHED(false, true),
    }

    @Test fun anUpdateKeepsAPairedUsersServicePairingAndSessions() = update(User.PAIRED)

    @Test fun anUpdateKeepsTheServiceOfAUserWhoUnpairedThisComputer() = update(User.UNPAIRED_BUT_HOSTING)

    @Test fun anUpdateKeepsTheServiceOfAUserWhoNeverFinishedTheWizard() = update(User.WIZARD_NEVER_FINISHED)

    private fun update(user: User) = runBlocking {
        assumeTrue("needs the container and SUPERMUX_RELEASED_BROKERS", container != null && dir != null && released != null)
        // A fresh 0.17.4 user every run: a broker from an earlier run was claimed already (TOFU).
        val root = dir!!.resolve("upgrade-${user.name.lowercase()}").also { it.toFile().deleteRecursively(); Files.createDirectories(it) }
        val home = dir.resolve("home")
        val env = ContainerOsEnv(home)
        val state = root.resolve("state")
        val bin = state.resolve("desktop-assets/bin").also { Files.createDirectories(it) }
        val appData = root.resolve("appdata").also { Files.createDirectories(it) }
        val r0174 = released!!.resolve("bin-0.17.4/supermux-linux-x64")
        val r0173 = released.resolve("bin-0.17.3/supermux-linux-x64")
        val unit = home.resolve(".config/systemd/user/supermux-host.service")
        val oldPrefs = root.resolve("home-config/supermux-desktop/hosting.json")
        fun active() = env.runResult(listOf("systemctl", "--user", "is-active", BrokerService.SYSTEMD_NAME)).out.trim()
        val log = mutableListOf<String>()
        val port = freePort()
        fun app(tag: String, bundled: Path): HostSupervisor {
            val store = HostingPrefsStore(appData.resolve("hosting.json"), legacyPortFile = root.resolve("none.json"), oldFile = oldPrefs)
            return HostSupervisor(
                stateDir = state,
                // A first run that never saved hosting.json starts from the defaults on its saved port.
                loadPrefs = { store.load().let { if (store.exists()) it else it.copy(port = port, relay = false) } },
                savePrefs = store::save,
                osEnv = env,
                materialize = {
                    Files.copy(bundled, bin.resolve("supermux-broker"), StandardCopyOption.REPLACE_EXISTING)
                    HostBinaries.SidecarBinaries(bin.resolve("supermux-broker"), bin, null, null, null)
                },
                packaged = { true },
                bundledBuild = { BrokerVersion.readBundledBuild(bundled) },
                lateBundledBuild = { BrokerVersion.readBundledBuild(bundled) },
                startChild = { error("no broker child in this test") },
                repoDir = { null },
                baseEnv = { emptyMap() },
                hostName = "linux-user",
                existingPath = "/usr/bin:/bin",
                userHome = env.home.toString(),
                log = { log += "$tag: $it" },
                identity = InstallIdentity(appData.resolve("install.json")),
                appDataPredatesIds = { Files.exists(appData.resolve(DesktopHostPersistence.META_FILE)) },
            )
        }
        try {
            // 0. The 0.17.4 user, as 0.17.4 leaves them: its broker and unit (no install id), its
            //    hosting.json where 0.17.4 keeps it, "This computer" (the wizard pairs it at once), sessions.
            Files.copy(r0174, bin.resolve("supermux-broker"), StandardCopyOption.REPLACE_EXISTING)
            Files.setPosixFilePermissions(bin.resolve("supermux-broker"), PosixFilePermissions.fromString("rwxr-xr-x"))
            val prefs = HostingPrefs(port = port, relay = false)
            if (user.hostingJson) {
                Files.createDirectories(oldPrefs.parent)
                Files.writeString(oldPrefs, kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(HostingPrefs.serializer(), prefs))
            }
            val bins = HostBinaries.SidecarBinaries(bin.resolve("supermux-broker"), bin, null, null, null)
            val spec = BrokerService.Spec(
                bins.brokerPath!!, brokerEnv(prefs, bins, emptyMap(), state, "linux-user", "/usr/bin:/bin", home.toString(), DesktopOs.LINUX),
                state.resolve("desktop-broker.log"),
            )
            BrokerService.writePrivate(unit, BrokerService.systemdUnit(spec))
            env.run(listOf("systemctl", "--user", "daemon-reload"))
            assertTrue(env.run(listOf("systemctl", "--user", "enable", "--now", BrokerService.SYSTEMD_NAME)))
            val before = awaitHost(port)
            println("${user.name}: 0.17.4 user on ${before.hostId}, ${before.build}")
            // A broker that sees /.dockerenv says "docker", and the app rightly never updates that one.
            assertEquals("binary", before.mode, "the container must look like a computer: remove its /.dockerenv")
            val claim = call("POST", "http://127.0.0.1:$port/pair/claim", body = """{"deviceName":"linux-user"}""")
            val token = claim.headers().allValues("set-cookie").firstNotNullOf { DesktopHostBootstrap.extractCookieToken(it) }
            val hosts = DesktopHostStores.store(appData)
            val rec = hosts.addOrUpdate("linux-user", token, directUrl = "http://127.0.0.1:$port", hostId = before.hostId, platform = "Linux")
            if (!user.thisComputer) hosts.remove(rec.recordId)
            for (name in listOf("one", "two")) {
                val r = call("POST", "http://127.0.0.1:$port/sessions", token, """{"workdir":"/tmp","name":"$name","userStatus":"draft"}""")
                assertEquals(200, r.statusCode(), r.body())
            }
            fun sessions() = call("GET", "http://127.0.0.1:$port/sessions", token).body()
            assertTrue("\"one\"" in sessions() && "\"two\"" in sessions(), sessions())

            // 1. The update to this build, which bundles another broker build: adopted, updated, restarted.
            val app = app("update", r0173)
            app.ensure()
            val trail = log.joinToString("\n", prefix = "\n")
            assertEquals(HostingStatus.Running(port, readOnly = false), app.status.value, trail)
            assertEquals(HostSupervisor.Mode.SERVICE, app.mode, trail)
            val after = awaitHost(port)
            assertEquals(before.hostId, after.hostId, "the same broker state$trail")
            assertEquals(BrokerVersion.readBundledBuild(r0173), after.build, "running the bundled build$trail")
            assertEquals("active", active())
            val id = InstallIdentity(appData.resolve("install.json")).load()!!
            assertTrue(id.id in Files.readString(unit), "adopted: the unit carries this app's id")
            assertEquals(200, call("GET", "http://127.0.0.1:$port/me", token).statusCode(), "the pairing survived")
            assertTrue("\"one\"" in sessions() && "\"two\"" in sessions(), sessions())
            assertTrue(app.prefs.value.hosting && app.prefs.value.background, "hosting stays on")
            app.quit()

            // 2. The next update: still this app's, restarted on the new bundled build.
            val next = app("next", r0174)
            next.ensure()
            assertEquals(HostingStatus.Running(port, readOnly = false), next.status.value, log.joinToString("\n"))
            val again = awaitHost(port)
            assertEquals(before.hostId, again.hostId)
            assertEquals(before.build, again.build)
            assertEquals(200, call("GET", "http://127.0.0.1:$port/me", token).statusCode())
            assertTrue("\"one\"" in sessions() && "\"two\"" in sessions(), sessions())
            next.quit()
            log.forEach(::println)
        } finally {
            env.run(listOf("systemctl", "--user", "disable", "--now", BrokerService.SYSTEMD_NAME))
            Files.deleteIfExists(unit)
            env.run(listOf("systemctl", "--user", "daemon-reload"))
        }
    }
}
