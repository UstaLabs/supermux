package dev.supermux.desktop.host

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Take over a broker set up outside the app (spec §Takeover): find its supervisor, back up the
 * definition, carry its MUX_* settings, stop + disable it. [rollback] restores it exactly.
 * The caller then installs our service (BrokerService / child) on the same port and state dir.
 */
object Takeover {
    enum class Kind { LAUNCHD, SYSTEMD }
    data class OldService(val kind: Kind, val name: String, val file: Path)
    data class Prepared(val old: OldService, val backup: Path, val carriedEnv: Map<String, String>)

    private val MAC_LABELS = listOf("dev.supermux.broker", "dev.supermux.host")
    private val LINUX_UNITS = listOf("supermux.service", "mux.service")
    private val OWNED = setOf("MUX_WEB_PORT", "MUX_MANAGED_BY", "MUX_STATE_DIR", "MUX_HOME")

    fun findOldService(env: OsEnv = SystemOsEnv): OldService? = when (env.os) {
        OsEnv.Os.MAC -> MAC_LABELS.map { env.home.resolve("Library/LaunchAgents/$it.plist") }
            .firstOrNull { Files.exists(it) && isSupermuxPlist(read(it)) }
            ?.let { OldService(Kind.LAUNCHD, it.fileName.toString().removeSuffix(".plist"), it) }
        OsEnv.Os.LINUX -> LINUX_UNITS.map { env.home.resolve(".config/systemd/user/$it") }
            .firstOrNull { Files.exists(it) && isSupermuxUnit(read(it)) }
            ?.let { OldService(Kind.SYSTEMD, it.fileName.toString(), it) }
        else -> null
    }

    private fun read(p: Path) = String(Files.readAllBytes(p), Charsets.UTF_8)

    /** Our own current service (always carries MUX_MANAGED_BY) must never be mistaken for an old one. */
    private fun isSupermuxPlist(xml: String) =
        "supermux" in xml.substringAfter("<key>ProgramArguments</key>", "").substringBefore("</array>") &&
            "<key>MUX_MANAGED_BY</key>" !in xml

    private fun isSupermuxUnit(unit: String): Boolean {
        val exec = unit.lineSequence().firstOrNull { it.startsWith("ExecStart=") } ?: return false
        return "supermux" in exec && "src/main.ts" !in exec && "MUX_MANAGED_BY=desktop" !in unit
    }

    fun parsePlistEnv(xml: String): Map<String, String> {
        val dict = xml.substringAfter("<key>EnvironmentVariables</key>", "").substringAfter("<dict>", "").substringBefore("</dict>")
        return Regex("""<key>([^<]+)</key>\s*<string>([^<]*)</string>""").findAll(dict)
            .associate { unxml(it.groupValues[1]) to unxml(it.groupValues[2]) }
    }

    /** `Environment=` lines: one or more assignments split on unquoted spaces; quotes stripped. */
    fun parseSystemdEnv(unit: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in unit.lineSequence().map { it.trim() }.filter { it.startsWith("Environment=") }) {
            for (word in splitWords(line.removePrefix("Environment="))) {
                if ('=' in word) out[word.substringBefore('=')] = word.substringAfter('=')
            }
        }
        return out
    }

    private fun splitWords(s: String): List<String> {
        val words = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var any = false
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && quote != null -> { cur.append(s[++i]) }
                quote != null -> if (c == quote) quote = null else cur.append(c)
                c == '"' || c == '\'' -> { quote = c; any = true }
                c.isWhitespace() -> if (cur.isNotEmpty() || any) { words += cur.toString(); cur.clear(); any = false }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || any) words += cur.toString()
        return words
    }

    fun carriedEnv(all: Map<String, String>): Map<String, String> =
        all.filterKeys { it.startsWith("MUX_") && it !in OWNED }

    fun prepare(old: OldService, stateDir: Path, env: OsEnv = SystemOsEnv): Prepared {
        val dir = stateDir.resolve("takeover-backup").also { Files.createDirectories(it) }
        val backup = dir.resolve(old.file.fileName.toString())
        Files.copy(old.file, backup, StandardCopyOption.REPLACE_EXISTING)
        val text = read(old.file)
        val carried = carriedEnv(if (old.kind == Kind.LAUNCHD) parsePlistEnv(text) else parseSystemdEnv(text))
        when (old.kind) {
            Kind.LAUNCHD -> {
                if (env.uid != null) env.run(listOf("launchctl", "bootout", "gui/${env.uid}/${old.name}"))
                if (old.name != BrokerService.LAUNCHD_LABEL) Files.deleteIfExists(old.file)
            }
            Kind.SYSTEMD -> env.run(listOf("systemctl", "--user", "disable", "--now", old.name))
        }
        return Prepared(old, backup, carried)
    }

    /** Best-effort restore of the old service. Never throws; true iff the definition was restored and reloaded. */
    fun rollback(p: Prepared, env: OsEnv = SystemOsEnv): Boolean = try {
        // Same label as ours: BrokerService may have replaced the file and loaded it; unload it first.
        if (p.old.kind == Kind.LAUNCHD && p.old.name == BrokerService.LAUNCHD_LABEL) BrokerService.remove(env)
        Files.createDirectories(p.old.file.parent)
        Files.copy(p.backup, p.old.file, StandardCopyOption.REPLACE_EXISTING)
        when (p.old.kind) {
            Kind.LAUNCHD -> env.uid != null && env.run(listOf("launchctl", "bootstrap", "gui/${env.uid}", p.old.file.toString()))
            Kind.SYSTEMD -> {
                val reloaded = env.run(listOf("systemctl", "--user", "daemon-reload"))
                env.run(listOf("systemctl", "--user", "enable", "--now", p.old.name)) && reloaded
            }
        }
    } catch (_: Exception) {
        false
    }

    private fun unxml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}
