package dev.supermux.desktop.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Take over a broker set up outside the app (spec §Takeover): find its supervisor(s), back up the
 * definition (never overwriting a backup), carry its MUX_* settings, stop + disable it and VERIFY
 * it stopped. A journal (`pending.json`) is written before anything is stopped so a crash mid-way
 * can be recovered at next launch ([recoverPending]). [commit] finalises once ours is healthy;
 * [rollback] restores the old service exactly.
 */
object Takeover {
    enum class Kind { LAUNCHD, SYSTEMD }

    @Serializable
    data class OldService(val kind: Kind, val name: String, val file: String) {
        val path: Path get() = Path.of(file)
    }

    @Serializable
    data class PreparedOne(val old: OldService, val backup: String)

    @Serializable
    data class Prepared(
        val stateDir: String,
        val olds: List<PreparedOne>,
        val carriedEnv: Map<String, String>,
        /** true: an old env had a non-empty MUX_RELAY_DOMAIN; false: key present but empty; null: absent. */
        val oldRelay: Boolean?,
    )

    sealed interface PrepareResult {
        data class Ok(val prepared: Prepared) : PrepareResult
        data class Failed(val reason: String) : PrepareResult
    }

    private val MAC_LABELS = listOf("dev.supermux.host", "dev.supermux.broker")
    private val LINUX_UNITS = listOf("supermux.service", "mux.service")
    /** Ours to set, never carried. `MUX_SERVICE_*` name the OLD service: carried, the broker's update would restart it. */
    private val OWNED = setOf(
        "MUX_WEB_PORT", "MUX_MANAGED_BY", "MUX_STATE_DIR", "MUX_HOME", "MUX_RELAY_DOMAIN", "MUX_WEB_PUBLIC_URL",
        "MUX_SERVICE_UNIT", "MUX_SERVICE_LABEL",
    )
    private const val JOURNAL = "pending.json"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    // ---- discovery --------------------------------------------------------------------------

    fun findOldServices(env: OsEnv = SystemOsEnv): List<OldService> = when (env.os) {
        OsEnv.Os.MAC -> MAC_LABELS.map { env.home.resolve("Library/LaunchAgents/$it.plist") }
            .filter { Files.exists(it) && isOldPlist(read(it)) }
            .map { OldService(Kind.LAUNCHD, it.fileName.toString().removeSuffix(".plist"), it.toString()) }
        OsEnv.Os.LINUX -> LINUX_UNITS.map { env.home.resolve(".config/systemd/user/$it") }
            .filter { Files.exists(it) && isOldUnit(read(it)) }
            .map { OldService(Kind.SYSTEMD, it.fileName.toString(), it.toString()) }
        else -> emptyList()
    }

    fun findOldService(env: OsEnv = SystemOsEnv): OldService? = findOldServices(env).firstOrNull()

    private fun read(p: Path) = String(Files.readAllBytes(p), Charsets.UTF_8)

    private fun isDevCommand(firstWord: String, whole: String) =
        "src/main.ts" in whole || firstWord.endsWith("/bun") || firstWord == "bun"

    /** Our own service (marker or MUX_MANAGED_BY) and dev checkouts (bun + src/main.ts) are never old services. */
    private fun isOldPlist(xml: String): Boolean {
        if (MANAGED_MARKER in xml) return false
        val plist = parsePlist(xml) ?: return false
        if ("MUX_MANAGED_BY" in plist.env) return false
        val first = plist.args.firstOrNull() ?: return false
        if (isDevCommand(first, plist.args.joinToString(" "))) return false
        return plist.args.any { "supermux" in it }
    }

    private const val MANAGED_MARKER = BrokerService.MANAGED_MARKER

    private fun isOldUnit(unit: String): Boolean {
        if (MANAGED_MARKER in unit || "MUX_MANAGED_BY" in unit) return false
        val execs = unit.lineSequence().map { it.trim() }.filter { it.startsWith("ExecStart=") }
            .map { it.removePrefix("ExecStart=").trim() }.filter { it.isNotEmpty() }.toList()
        if (execs.isEmpty()) return false
        if (execs.any { isDevCommand(it.removePrefix("-").removePrefix("@").substringBefore(' '), it) }) return false
        return execs.any { "supermux" in it }
    }

    // ---- parsing ----------------------------------------------------------------------------

    data class Plist(val args: List<String>, val env: Map<String, String>)

    fun parsePlist(xml: String): Plist? = try {
        val f = DocumentBuilderFactory.newInstance().apply {
            isValidating = false
            isIgnoringComments = true
            isCoalescing = true
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val builder = f.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
        val root = builder.parse(InputSource(StringReader(xml))).documentElement
        val top = root.elements().firstOrNull { it.tagName == "dict" }
        if (top == null) null else {
            val d = dict(top)
            val args = d["ProgramArguments"]?.elements()?.filter { it.tagName == "string" }?.map { it.textContent } ?: emptyList()
            val env = d["EnvironmentVariables"]?.let { e -> dict(e).filterValues { it.tagName == "string" }.mapValues { it.value.textContent } } ?: emptyMap()
            Plist(args, env)
        }
    } catch (_: Exception) {
        null
    }

    private fun Element.elements(): List<Element> {
        val out = mutableListOf<Element>()
        var n: Node? = firstChild
        while (n != null) { if (n is Element) out += n; n = n.nextSibling }
        return out
    }

    private fun dict(e: Element): Map<String, Element> {
        val out = LinkedHashMap<String, Element>()
        var key: String? = null
        for (c in e.elements()) {
            if (c.tagName == "key") key = c.textContent else { key?.let { out[it] = c }; key = null }
        }
        return out
    }

    fun parsePlistEnv(xml: String): Map<String, String> = parsePlist(xml)?.env ?: emptyMap()

    /** `Environment=` lines: one or more assignments split on unquoted spaces; quotes stripped. */
    fun parseSystemdEnv(unit: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in unit.lineSequence().map { it.trim() }.filter { it.startsWith("Environment=") }) {
            out.putAll(parseAssignments(line.removePrefix("Environment=")))
        }
        return out
    }

    private fun parseAssignments(s: String): Map<String, String> =
        splitWords(s).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

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

    /** `KEY=VALUE` lines, `#`/`;` comments and blanks skipped, optional surrounding quotes stripped. */
    fun parseEnvFile(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";") || '=' !in line) continue
            val v = line.substringAfter('=').trim()
            val unq = if (v.length >= 2 && (v.first() == '"' || v.first() == '\'') && v.last() == v.first()) v.substring(1, v.length - 1) else v
            out[line.substringBefore('=').trim().removePrefix("export ").trim()] = unq
        }
        return out
    }

    private fun readEnvFiles(paths: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (p in paths) {
            val f = Path.of(p.removePrefix("-"))
            if (Files.isRegularFile(f)) runCatching { out.putAll(parseEnvFile(read(f))) }
        }
        return out
    }

    /** Merged env of a systemd unit: `systemctl show` (includes drop-ins), else unit file + drop-ins. */
    private fun systemdEnv(old: OldService, env: OsEnv): Map<String, String> {
        if (env.hasCommand("systemctl")) {
            val e = env.runResult(listOf("systemctl", "--user", "show", "-p", "Environment", "--value", old.name))
            if (e.exit == 0) {
                val f = env.runResult(listOf("systemctl", "--user", "show", "-p", "EnvironmentFiles", "--value", old.name))
                val files = if (f.exit == 0) f.out.replace(Regex("""\(ignore_errors=[^)]*\)"""), " ").split(Regex("\\s+")).filter { it.isNotBlank() } else emptyList()
                return parseAssignments(e.out.trim()) + readEnvFiles(files)
            }
        }
        val unitFile = old.path
        val dropIns = unitFile.resolveSibling(unitFile.fileName.toString() + ".d").let { d ->
            if (Files.isDirectory(d)) Files.list(d).use { s -> s.filter { it.toString().endsWith(".conf") }.sorted().toList() } else emptyList()
        }
        val texts = (listOf(unitFile) + dropIns).map { read(it) }
        val direct = texts.fold(emptyMap<String, String>()) { acc, t -> acc + parseSystemdEnv(t) }
        val files = texts.flatMap { t ->
            t.lineSequence().map { it.trim() }.filter { it.startsWith("EnvironmentFile=") }
                .map { it.removePrefix("EnvironmentFile=").trim().removeSurrounding("\"") }.filter { it.isNotEmpty() }
        }
        return direct + readEnvFiles(files)
    }

    fun carriedEnv(all: Map<String, String>): Map<String, String> =
        all.filter { (k, v) -> k.startsWith("MUX_") && (k !in OWNED || (k == "MUX_WEB_PUBLIC_URL" && isRealPublicUrl(v))) }

    /** A tunnel or domain worth carrying: it parses, has a host, and isn't loopback. */
    private fun isRealPublicUrl(url: String): Boolean = urlHost(url) != null && !isLoopbackUrl(url)

    // ---- prepare / commit / rollback / recover ----------------------------------------------

    private fun journal(stateDir: Path) = stateDir.resolve("takeover-backup/$JOURNAL")

    fun prepare(olds: List<OldService>, stateDir: Path, env: OsEnv = SystemOsEnv): PrepareResult {
        if (olds.isEmpty()) return PrepareResult.Failed("no old service to take over")
        if (olds.any { it.kind == Kind.LAUNCHD } && env.uid == null) return PrepareResult.Failed("cannot control launchd without a user id")
        return try {
            val dir = stateDir.resolve("takeover-backup").also { Files.createDirectories(it) }
            val stamp = System.currentTimeMillis()
            val ones = olds.map { old ->
                var n = stamp
                var backup = dir.resolve("${old.path.fileName}.$n")
                while (Files.exists(backup)) backup = dir.resolve("${old.path.fileName}.${++n}")
                Files.copy(old.path, backup) // never REPLACE_EXISTING
                PreparedOne(old, backup.toString())
            }
            val envs = olds.map { old ->
                val text = read(old.path)
                if (old.kind == Kind.LAUNCHD) parsePlistEnv(text) else systemdEnv(old, env)
            }
            val carried = LinkedHashMap<String, String>()
            for (e in envs) for ((k, v) in carriedEnv(e)) carried.putIfAbsent(k, v)
            val relays = envs.mapNotNull { it["MUX_RELAY_DOMAIN"] }
            val oldRelay = if (relays.isEmpty()) null else relays.any { it.isNotEmpty() }
            val prepared = Prepared(stateDir.toString(), ones, carried, oldRelay)
            Files.writeString(journal(stateDir), json.encodeToString(prepared))

            for (old in olds) {
                val stopped = stop(old, env)
                if (!stopped) {
                    rollback(prepared, env)
                    return PrepareResult.Failed("${old.name} did not stop")
                }
            }
            PrepareResult.Ok(prepared)
        } catch (e: Exception) {
            PrepareResult.Failed("takeover preparation failed: ${e.message}")
        }
    }

    private fun stop(old: OldService, env: OsEnv): Boolean = when (old.kind) {
        Kind.LAUNCHD -> {
            val target = "gui/${env.uid}/${old.name}"
            env.run(listOf("launchctl", "disable", target))
            env.run(listOf("launchctl", "bootout", target))
            poll(env) { env.runResult(listOf("launchctl", "print", target)).exit != 0 }
        }
        Kind.SYSTEMD -> {
            env.run(listOf("systemctl", "--user", "disable", "--now", old.name))
            poll(env) { env.runResult(listOf("systemctl", "--user", "is-active", old.name)).out.trim() != "active" }
        }
    }

    private fun poll(env: OsEnv, done: () -> Boolean): Boolean {
        for (i in 1..10) {
            if (done()) return true
            env.sleep(500)
        }
        return done()
    }

    /** Called once our service is healthy: retire the old definitions and drop the journal. */
    fun commit(p: Prepared, env: OsEnv = SystemOsEnv) {
        for (one in p.olds) {
            if (one.old.kind == Kind.LAUNCHD && one.old.name != BrokerService.LAUNCHD_LABEL) {
                runCatching { Files.deleteIfExists(one.old.path) } // `launchctl disable` stays in place
            }
        }
        runCatching { Files.deleteIfExists(journal(Path.of(p.stateDir))) }
    }

    /** Best-effort restore of every old service. Never throws; true iff all were restored and reloaded. */
    fun rollback(p: Prepared, env: OsEnv = SystemOsEnv): Boolean {
        var ok = true
        try { BrokerService.remove(env) } catch (_: Exception) { ok = false }
        for (one in p.olds) {
            ok = try {
                val old = one.old
                Files.createDirectories(old.path.parent)
                Files.copy(Path.of(one.backup), old.path, StandardCopyOption.REPLACE_EXISTING)
                val restored = when (old.kind) {
                    Kind.LAUNCHD -> env.uid != null && run {
                        val domain = "gui/${env.uid}"
                        // Ours was just booted out (and may share this label): wait until launchd dropped it.
                        BrokerService.awaitLaunchdGone("$domain/${old.name}", env)
                        env.run(listOf("launchctl", "enable", "$domain/${old.name}"))
                        BrokerService.bootstrapWithRetry(domain, old.path, env).exit == 0
                    }
                    Kind.SYSTEMD -> {
                        val reloaded = env.run(listOf("systemctl", "--user", "daemon-reload"))
                        env.run(listOf("systemctl", "--user", "enable", "--now", old.name)) && reloaded
                    }
                }
                ok && restored
            } catch (_: Exception) {
                false
            }
        }
        runCatching { Files.deleteIfExists(journal(Path.of(p.stateDir))) }
        return ok
    }

    /** At app launch: finish (healthy) or undo (not healthy) a takeover interrupted mid-way. True iff it did anything. */
    fun recoverPending(stateDir: Path, env: OsEnv = SystemOsEnv, ourServiceHealthy: Boolean): Boolean {
        val j = journal(stateDir)
        if (!Files.exists(j)) return false
        val p = runCatching { json.decodeFromString<Prepared>(read(j)) }.getOrNull() ?: return false
        if (ourServiceHealthy) commit(p, env) else rollback(p, env)
        return true
    }

    fun encodeJournal(p: Prepared): String = json.encodeToString(p)
    fun decodeJournal(s: String): Prepared = json.decodeFromString(s)
}
