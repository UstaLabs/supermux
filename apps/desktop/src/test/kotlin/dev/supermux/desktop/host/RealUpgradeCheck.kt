package dev.supermux.desktop.host

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.system.exitProcess

/**
 * Card #77: the update from an installed release to this build, run for real on a computer where
 * that release hosts in the background (the Windows test VM's 0.17.3). It is what the updated app
 * does at launch, over the user's real app data, state dir and service, run twice:
 *
 *   java -cp <classes + the app's jars> dev.supermux.desktop.host.RealUpgradeCheckKt <first bundled broker> <second bundled broker>
 *
 * Each run "bundles" the broker given (copied in when the update restarts the service, as the app's
 * own copy does). Afterwards the service must still be this app's, running the bundled build, with
 * hosting on, the same host, the pairing and the sessions.
 */
fun main(args: Array<String>) {
    val bundles = args.map(Path::of)
    require(bundles.size == 2 && bundles.all(Files::isRegularFile)) { "usage: RealUpgradeCheck <broker> <broker>" }
    val failures = mutableListOf<String>()
    fun expect(ok: Boolean, what: String) { println((if (ok) "ok   " else "FAIL ") + what); if (!ok) failures += what }
    runBlocking {
        val env = SystemOsEnv
        val stateDir = BrokerPaths.defaultStateDir()
        val appData = DesktopHostStores.defaultDir()
        val prefsStore = HostingPrefsStore.ofThisUser()
        val prefs0 = prefsStore.load()
        val hosts = DesktopHostStores.store().list()
        val here = hosts.firstOrNull { isLoopbackUrl(it.directUrl) }
        println("before: prefs $prefs0, ${hosts.size} paired host(s), This computer ${here?.directUrl ?: "unpaired"}")
        println("before: install id ${InstallIdentity(InstallIdentity.defaultFile()).load() ?: "none (first launch since install ids)"}")
        val port = prefs0.port
        val token = here?.token
        val before = HostProber.probe(port) as? HostProbeResult.Supermux ?: error("no broker on $port")
        val sessions0 = token?.let { sessionNames(port, it) }
        println("before: ${before.hostId} ${before.build}, sessions $sessions0")

        for ((i, bundled) in bundles.withIndex()) {
            val build = BrokerVersion.readBundledBuild(bundled) ?: error("can't read the build of $bundled")
            println("\n== update ${i + 1}: this build, bundling $build")
            val bin = stateDir.resolve(HostBinaries.BIN_SUBDIR)
            val log = mutableListOf<String>()
            val sup = HostSupervisor(
                stateDir = stateDir,
                loadPrefs = prefsStore::load,
                savePrefs = prefsStore::save,
                osEnv = env,
                materialize = {
                    // The app's own copy: the new broker over the old one, once the service let go of it.
                    val broker = bin.resolve(HostBinaries.fileName(HostBinaries.Binary.Broker, HostBinaries.Os.WINDOWS))
                    // Windows may hold the old .exe a moment after its process ended. Never leave the
                    // service down over it: after ~10 s the update restarts it on the old binary.
                    for (attempt in 1..20) {
                        if (runCatching { Files.copy(bundled, broker, StandardCopyOption.REPLACE_EXISTING) }.isSuccess) break
                        if (attempt == 20) println("   (couldn't replace the broker binary; the restart keeps the old one)") else Thread.sleep(500)
                    }
                    HostBinaries.SidecarBinaries(broker, bin, bin.resolve("mux-sessiond.exe").takeIf(Files::exists), null, null)
                },
                // The PATH the service has now (the app's own, from the desktop), not this shell's.
                existingPath = runCatching {
                    Regex("\\\$env:PATH = '(.*)'").find(Files.readString(BrokerService.windowsEnvPath(env)))?.groupValues?.get(1)?.replace("''", "'")
                }.getOrNull() ?: System.getenv("PATH"),
                packaged = { true },
                bundledBuild = { build },
                lateBundledBuild = { build },
                startChild = { error("the update must not fall back to a broker child") },
                repoDir = { null },
                log = { log += it },
                identity = InstallIdentity(InstallIdentity.defaultFile()),
                appDataPredatesIds = { Files.exists(appData.resolve(DesktopHostPersistence.META_FILE)) },
            )
            sup.ensure()
            log.forEach { println("   log: $it") }
            val after = HostProber.probe(port) as? HostProbeResult.Supermux
            expect(sup.status.value == HostingStatus.Running(port, readOnly = false), "update ${i + 1}: running, managed by this app (${sup.status.value})")
            expect(sup.mode == HostSupervisor.Mode.SERVICE, "update ${i + 1}: as the background service (${sup.mode})")
            expect(after?.build == build, "update ${i + 1}: the service restarted on the bundled build (${after?.build})")
            expect(after?.hostId == before.hostId, "update ${i + 1}: the same host")
            val id = InstallIdentity(InstallIdentity.defaultFile()).load()
            val envFile = BrokerService.windowsEnvPath(env)
            expect(id != null && id.id in Files.readString(envFile), "update ${i + 1}: its task's env names this app (${id?.id})")
            expect(sup.prefs.value.hosting && sup.prefs.value.background, "update ${i + 1}: hosting and background stay on")
            if (token != null) {
                expect(status(port, "/me", token) == 200, "update ${i + 1}: This computer's pairing still works")
                expect(sessionNames(port, token) == sessions0, "update ${i + 1}: the same sessions (${sessionNames(port, token)})")
            }
            sup.quit()
        }
    }
    println(if (failures.isEmpty()) "RESULT: OK" else "RESULT: ${failures.size} failed: $failures")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}

private val http = HttpClient.newHttpClient()

private fun status(port: Int, path: String, token: String): Int = http.send(
    HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path")).header("authorization", "Bearer $token").GET().build(),
    HttpResponse.BodyHandlers.discarding(),
).statusCode()

private fun sessionNames(port: Int, token: String): List<String> {
    val body = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/sessions")).header("authorization", "Bearer $token").GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    ).body()
    val root = Json.parseToJsonElement(body)
    val list = runCatching { root.jsonArray }.getOrElse { root.jsonObject["sessions"]?.jsonArray } ?: return emptyList()
    return list.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content }.sorted()
}
