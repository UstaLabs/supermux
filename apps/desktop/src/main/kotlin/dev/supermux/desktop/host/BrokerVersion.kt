package dev.supermux.desktop.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Version helpers for deciding use / update / downgrade (spec §Launch flow). */
object BrokerVersion {
    /** Same build ⇔ identical `versionString()` ("1.5.0 (abc1234)"); two "dev" builds differ by commit. */
    fun sameBuild(a: String?, b: String?): Boolean = a != null && b != null && a == b

    /** "1.5.0-alpha.3 (abc)" → "1.5.0-alpha.3". */
    fun versionOf(build: String?): String? = build?.substringBefore(" (")?.trim()?.takeIf { it.isNotEmpty() }

    /** True only when both parse as semver and [found] > [bundled]. "dev"/unparseable ⇒ false (never block). */
    fun isNewer(found: String?, bundled: String?): Boolean {
        val f = parse(versionOf(found) ?: return false) ?: return false
        val b = parse(versionOf(bundled) ?: return false) ?: return false
        return compare(f, b) > 0
    }

    private data class Sem(val major: Int, val minor: Int, val patch: Int, val pre: List<String>)

    private fun parse(v: String): Sem? {
        val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$""").matchEntire(v) ?: return null
        val (a, b, c, pre) = m.destructured
        return Sem(a.toIntOrNull() ?: return null, b.toIntOrNull() ?: return null, c.toIntOrNull() ?: return null, if (pre.isEmpty()) emptyList() else pre.split('.'))
    }

    private fun compare(x: Sem, y: Sem): Int {
        compareValues(x.major, y.major).let { if (it != 0) return it }
        compareValues(x.minor, y.minor).let { if (it != 0) return it }
        compareValues(x.patch, y.patch).let { if (it != 0) return it }
        if (x.pre.isEmpty() && y.pre.isEmpty()) return 0
        if (x.pre.isEmpty()) return 1   // release > prerelease
        if (y.pre.isEmpty()) return -1
        for (i in 0 until maxOf(x.pre.size, y.pre.size)) {
            val p = x.pre.getOrNull(i) ?: return -1
            val q = y.pre.getOrNull(i) ?: return 1
            val pn = p.toBigIntegerOrNull()
            val qn = q.toBigIntegerOrNull()
            val c = when {
                pn != null && qn != null -> pn.compareTo(qn)
                pn != null -> -1   // numeric identifiers rank below alphanumeric ones
                qn != null -> 1
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return 0
    }

    private val BUILD_LINE = Regex("""^\S+ \([^)]+\)$""")

    /** `<broker> version` → "1.5.0 (abc1234)", or null. Blocking; call off the main thread. */
    fun readBundledBuild(broker: Path?): String? {
        if (broker == null) return null
        var p: Process? = null
        var watchdog: Thread? = null
        return try {
            p = ProcessBuilder(broker.toString(), "version")
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            // A child that hangs without closing stdout would block the read below forever:
            // kill it after 10 s so the read sees EOF.
            val proc = p
            watchdog = Thread({
                try {
                    if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly()
                } catch (_: InterruptedException) {
                }
            }, "broker-version-watchdog").apply { isDaemon = true; start() }
            // Read on this thread before waiting so a hung child can't be missed; cap the read at 4 KB.
            val buf = ByteArray(4096)
            var n = 0
            p.inputStream.use { ins ->
                while (n < buf.size) {
                    val r = ins.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
            }
            if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0) return null
            String(buf, 0, n, Charsets.UTF_8).lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
                ?.takeIf { BUILD_LINE.matches(it) }
        } catch (_: Exception) {
            null
        } finally {
            watchdog?.interrupt()
            if (p?.isAlive == true) p.destroyForcibly()
        }
    }

    /**
     * The bundled broker's build, read from the app image without touching the running copy
     * (Windows can't overwrite a running .exe). If packaging dropped the exec bit, a probe copy
     * under `desktop-assets/probe` is used. Blocking work runs on IO with a 15 s cap; the
     * reader itself kills a hung child after 10 s.
     */
    suspend fun defaultBundledBuild(stateDir: Path): String? = withContext(Dispatchers.IO) {
        val res = HostBinaries.resourcesDir() ?: return@withContext null
        val os = HostBinaries.detectOs()
        val name = HostBinaries.fileName(HostBinaries.Binary.Broker, os)
        val src = res.resolve(name)
        if (!Files.exists(src)) return@withContext null
        withTimeoutOrNull(15_000) {
            runInterruptible {
                val exe = if (os == HostBinaries.Os.WINDOWS || Files.isExecutable(src)) src
                else HostBinaries.materialize(src, stateDir.resolve("desktop-assets/probe"), name, executable = true)
                BrokerVersion.readBundledBuild(exe)
            }
        }
    }
}
