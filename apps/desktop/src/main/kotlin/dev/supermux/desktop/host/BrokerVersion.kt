package dev.supermux.desktop.host

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
        return Sem(a.toInt(), b.toInt(), c.toInt(), if (pre.isEmpty()) emptyList() else pre.split('.'))
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
            val c = when {
                p.toIntOrNull() != null && q.toIntOrNull() != null -> compareValues(p.toInt(), q.toInt())
                else -> p.compareTo(q)
            }
            if (c != 0) return c
        }
        return 0
    }

    /** `<broker> version` → "1.5.0 (abc1234)", or null. Blocking; call off the main thread. */
    fun readBundledBuild(broker: Path?): String? = broker?.let {
        runCatching {
            val p = ProcessBuilder(it.toString(), "version").redirectErrorStream(true).start()
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            p.inputStream.bufferedReader().readText().trim().lineSequence().lastOrNull()?.takeIf { l -> l.isNotBlank() }
        }.getOrNull()
    }
}
