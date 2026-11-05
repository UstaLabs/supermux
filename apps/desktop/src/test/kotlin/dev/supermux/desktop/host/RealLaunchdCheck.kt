package dev.supermux.desktop.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.system.exitProcess

/**
 * Card #77 on a real macOS launchd, run on a Mac that has NO supermux background service (it refuses
 * otherwise). Everything lives in [root] except the one plist launchd needs; that plist and its job
 * are removed again, also on a failure or an interrupt.
 *
 *   java -cp <classes + the app's jars> dev.supermux.desktop.host.RealLaunchdCheckKt <root>
 *
 * 1. "0.17.x": a stand-in broker (python, answers /host with the build in `build.txt`) and the plist
 *    0.17.x writes (no install id), its hosting.json and paired hosts.
 * 2. The update to this build: adopted, updated (restarted on the new build), stamped.
 * 3. The next update: restarted again.
 * 4. A second copy with fresh app data: connect elsewhere, background off, launch with background
 *    off: the first copy's job stays loaded and its plist byte-identical.
 * 5. The first copy turns hosting off: its job is gone.
 */
fun main(args: Array<String>) {
    val root = Path.of(args.getOrNull(0) ?: error("usage: RealLaunchdCheck <root>"))
    val env = SystemOsEnv
    val plist = env.home.resolve("Library/LaunchAgents/${BrokerService.LAUNCHD_LABEL}.plist")
    val target = "gui/${env.uid}/${BrokerService.LAUNCHD_LABEL}"
    check(env.os == OsEnv.Os.MAC) { "macOS only" }
    check(!Files.exists(plist)) { "$plist exists: this Mac has a supermux background service; not running" }
    check(env.runResult(listOf("launchctl", "print", target)).exit != 0) { "$target is loaded; not running" }
    fun cleanUp() {
        // Only ever the job this check created: its plist names our root.
        val ours = runCatching { root.toString() in Files.readString(plist) }.getOrDefault(true)
        if (ours) {
            env.runResult(listOf("launchctl", "bootout", target))
            Files.deleteIfExists(plist)
        }
    }
    Runtime.getRuntime().addShutdownHook(Thread { cleanUp() })
    val failures = mutableListOf<String>()
    fun expect(ok: Boolean, what: String) { println((if (ok) "ok   " else "FAIL ") + what); if (!ok) failures += what }
    try {
        runBlocking { check(root, env, plist, target, ::expect) }
    } catch (e: Throwable) {
        failures += "threw: $e"
        e.printStackTrace()
    } finally {
        cleanUp()
    }
    val gone = !Files.exists(plist) && env.runResult(listOf("launchctl", "print", target)).exit != 0
    expect(gone, "afterwards: no plist and no job under ${BrokerService.LAUNCHD_LABEL}")
    println(if (failures.isEmpty()) "RESULT: OK" else "RESULT: ${failures.size} failed: $failures")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}

private suspend fun CoroutineScope.check(root: Path, env: OsEnv, plist: Path, target: String, expect: (Boolean, String) -> Unit) {
    val state = root.resolve("state")
    val bin = state.resolve("desktop-assets/bin").also { Files.createDirectories(it) }
    val broker = bin.resolve("supermux-broker")
    Files.writeString(broker, """#!/usr/bin/env python3
import http.server, json, os
state = os.environ["MUX_STATE_DIR"]
here = os.path.dirname(os.path.abspath(__file__))
build = open(os.path.join(here, "build.txt")).read().strip()
open(os.path.join(state, "broker.pid"), "w").write(str(os.getpid()))
body = json.dumps({"hostId": "hmacstandin", "protocolVersion": 1, "build": build, "mode": "binary",
    "managedBy": os.environ.get("MUX_MANAGED_BY"), "stateDir": state}).encode()
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.send_header("content-type", "application/json"); self.end_headers(); self.wfile.write(body)
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", int(os.environ["MUX_WEB_PORT"])), H).serve_forever()
""")
    Files.setPosixFilePermissions(broker, PosixFilePermissions.fromString("rwxr-xr-x"))
    Files.writeString(bin.resolve("build.txt"), "0.17.4 (standin)")
    val port = ServerSocket(0).use { it.localPort }
    val log = mutableListOf<String>()

    // 1. A 0.17.x user: what 0.17.x leaves on disk and in launchd.
    val appData = root.resolve("appdata-a").also { Files.createDirectories(it) }
    val oldPrefs = root.resolve("home-config/supermux-desktop/hosting.json")
    val prefs = HostingPrefs(port = port, relay = false)
    Files.createDirectories(oldPrefs.parent)
    Files.writeString(oldPrefs, kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(HostingPrefs.serializer(), prefs))
    Files.writeString(appData.resolve(DesktopHostPersistence.META_FILE), "[]") // paired hosts (This computer unpaired since)
    val bins = HostBinaries.SidecarBinaries(broker, bin, null, null, null)
    val spec = BrokerService.Spec(broker, brokerEnv(prefs, bins, emptyMap(), state, "mac-user", "/usr/bin:/bin", env.home.toString(), OsEnv.Os.MAC), state.resolve("desktop-broker.log"))
    BrokerService.writePrivate(plist, BrokerService.launchdPlist(spec))
    env.runResult(listOf("launchctl", "enable", target))
    expect(BrokerService.bootstrapWithRetry("gui/${env.uid}", plist, env).exit == 0, "0.17.x: its job loads")
    val first = awaitBuild(port, "0.17.4 (standin)")
    expect(first, "0.17.x: its broker answers")

    fun app(name: String, data: Path, bundled: String, prefsFile: Path, oldFile: Path?): HostSupervisor {
        val store = HostingPrefsStore(prefsFile, legacyPortFile = root.resolve("none.json"), oldFile = oldFile)
        return HostSupervisor(
            stateDir = state,
            loadPrefs = store::load,
            savePrefs = store::save,
            osEnv = env,
            materialize = { Files.writeString(bin.resolve("build.txt"), bundled); bins },
            packaged = { true },
            bundledBuild = { bundled },
            lateBundledBuild = { bundled },
            startChild = { error("no broker child here") },
            repoDir = { null },
            hostName = "mac-$name",
            existingPath = "/usr/bin:/bin",
            log = { log += "$name: $it"; println("  log: $name: $it") },
            identity = InstallIdentity(data.resolve("install.json")),
            appDataPredatesIds = { Files.exists(data.resolve(DesktopHostPersistence.META_FILE)) },
        )
    }

    // 2. The update to this build.
    val a = app("update", appData, "0.17.5 (standin)", appData.resolve("hosting.json"), oldPrefs)
    a.ensure()
    expect(a.status.value == HostingStatus.Running(port, readOnly = false) && a.mode == HostSupervisor.Mode.SERVICE, "update: our service, running (${a.status.value})")
    expect(awaitBuild(port, "0.17.5 (standin)"), "update: restarted on the new build")
    val id = InstallIdentity(appData.resolve("install.json")).load()
    expect(id != null && id.id in Files.readString(plist), "update: adopted, and its plist now carries the install id")
    expect(a.prefs.value.hosting && a.prefs.value.background, "update: hosting stays on")
    a.quit()

    // 3. The next update.
    val a2 = app("next", appData, "0.17.6 (standin)", appData.resolve("hosting.json"), oldPrefs)
    a2.ensure()
    expect(awaitBuild(port, "0.17.6 (standin)"), "next update: restarted again")
    a2.quit()
    val theirs = Files.readString(plist)

    // 4. A second copy with fresh app data, on the same state dir and the same port (both copies'
    //    default; this check's first copy runs on a free port instead of 9898).
    val bData = root.resolve("appdata-b").also { Files.createDirectories(it) }
    Files.writeString(bData.resolve("hosting.json"), kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(HostingPrefs.serializer(), prefs))
    val b = app("second", bData, "0.17.6 (standin)", bData.resolve("hosting.json"), null)
    val wizard = launch { b.ensure() }
    withTimeout(60_000) { b.status.first { it is HostingStatus.AskTakeover } }
    b.setHosting(false) // the wizard's "Connect to a different broker instead"
    wizard.join()
    expect(b.status.value == HostingStatus.NotHosting, "second copy: connect elsewhere ends in Not hosting")
    expect(Files.readString(plist) == theirs, "second copy: the first copy's plist is untouched")
    expect(env.runResult(listOf("launchctl", "print", target)).exit == 0, "second copy: the first copy's job is still loaded")
    expect(awaitBuild(port, "0.17.6 (standin)"), "second copy: the first copy's broker still answers")
    b.quit()
    val b2 = app("second-again", bData, "0.17.6 (standin)", bData.resolve("hosting.json"), null)
    val turnOn = launch { b2.setHosting(true) }
    withTimeout(60_000) { b2.status.first { it is HostingStatus.AskTakeover } }
    b2.answerTakeover(false) // "Leave it alone"
    turnOn.join()
    b2.setBackground(false)
    b2.ensure()
    expect(b2.status.value == HostingStatus.Running(port, readOnly = true), "second copy: left alone, read-only")
    expect(Files.readString(plist) == theirs && env.runResult(listOf("launchctl", "print", target)).exit == 0, "second copy: still untouched after background off")
    b2.quit()

    // 5. The first copy turns hosting off: its own job goes.
    val a3 = app("off", appData, "0.17.6 (standin)", appData.resolve("hosting.json"), oldPrefs)
    a3.ensure()
    a3.setHosting(false)
    expect(!Files.exists(plist) && env.runResult(listOf("launchctl", "print", target)).exit != 0, "first copy: hosting off removes its own job")
    expect(HostProber.probe(port) == HostProbeResult.PortFree, "first copy: nothing answers on its port")
    a3.quit()
}

private suspend fun awaitBuild(port: Int, build: String, ms: Long = 60_000): Boolean {
    val deadline = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < deadline) {
        val r = HostProber.probe(port)
        if (r is HostProbeResult.Supermux && r.build == build) return true
        delay(500)
    }
    return false
}
