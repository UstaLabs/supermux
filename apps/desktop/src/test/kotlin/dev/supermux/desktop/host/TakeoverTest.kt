package dev.supermux.desktop.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TakeoverTest {
    private fun res(name: String) = String(javaClass.getResourceAsStream("/takeover/$name")!!.readAllBytes(), Charsets.UTF_8)
    private val plistText get() = res("mac-native-host.plist")

    private class Mac(val home: Path = createTempDirectory(), val state: Path = createTempDirectory()) {
        val agents: Path = home.resolve("Library/LaunchAgents").also { Files.createDirectories(it) }
        fun plist(label: String, text: String) = agents.resolve("$label.plist").also { Files.write(it, text.toByteArray(Charsets.UTF_8)) }
    }

    private fun macEnv(m: Mac, uid: Long? = 501, failing: Set<List<String>> = emptySet(), scripted: Map<List<String>, List<OsEnv.RunResult>> = emptyMap()) =
        FakeOsEnv(os = OsEnv.Os.MAC, home = m.home, uid = uid, failing = failing, scripted = scripted)

    private val printHost = listOf("launchctl", "print", "gui/501/dev.supermux.host")
    private val printBroker = listOf("launchctl", "print", "gui/501/dev.supermux.broker")

    private fun ok(r: Takeover.PrepareResult) = assertIs<Takeover.PrepareResult.Ok>(r).prepared

    @Test fun the100KeepAliveIsNotAnOldService() {
        val m = Mac(); m.plist("dev.supermux.host", LegacyKeepAlive.plist)
        assertTrue(Takeover.findOldServices(macEnv(m)).isEmpty(), "it runs the app; taking it over would boot out the app")
    }

    // ---- parsing / env ----

    @Test fun plistEnvKeepsMuxKeysExceptOnesWeOwn() {
        val env = Takeover.carriedEnv(Takeover.parsePlistEnv(plistText))
        assertEquals("Alex’s MacBook Air", env["MUX_HOST_NAME"])
        assertTrue("MUX_WEB_PORT" !in env && "MUX_STATE_DIR" !in env && "PATH" !in env)
        // loopback public URL and the relay domain are ours to decide, not carried
        assertTrue("MUX_WEB_PUBLIC_URL" !in env && "MUX_RELAY_DOMAIN" !in env)
    }

    @Test fun theOldServicesOwnUnitAndLabelAreNotCarried() {
        val env = Takeover.carriedEnv(mapOf("MUX_SERVICE_UNIT" to "supermux.service", "MUX_SERVICE_LABEL" to "dev.supermux.broker", "MUX_X" to "1"))
        assertEquals(mapOf("MUX_X" to "1"), env)
    }

    @Test fun nonLoopbackPublicUrlIsCarried() {
        assertEquals("https://me.example.com", Takeover.carriedEnv(mapOf("MUX_WEB_PUBLIC_URL" to "https://me.example.com"))["MUX_WEB_PUBLIC_URL"])
        for (u in listOf("http://127.0.0.1:9898", "http://localhost:9898", "http://[::1]:9898"))
            assertTrue("MUX_WEB_PUBLIC_URL" !in Takeover.carriedEnv(mapOf("MUX_WEB_PUBLIC_URL" to u)), u)
    }

    @Test fun plistParserIgnoresCommentsAndHandlesEmptyStrings() {
        val xml = """<?xml version="1.0"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>ProgramArguments</key><array><string>/x/supermux</string></array>
<key>EnvironmentVariables</key><dict>
<!-- <key>MUX_HIDDEN</key><string>1</string> -->
<key>MUX_A</key><string/><key>MUX_B</key><string><![CDATA[a&b]]></string></dict></dict></plist>"""
        assertEquals(mapOf("MUX_A" to "", "MUX_B" to "a&b"), Takeover.parsePlist(xml)!!.env)
    }

    @Test fun systemdEnvHandlesQuotedAndBareLines() {
        val env = Takeover.carriedEnv(Takeover.parseSystemdEnv(res("linux-supermux.service")))
        assertEquals("3001", env["MUX_WHATSAPP_WEBHOOK_PORT"])
        assertEquals("123:abc", env["MUX_TELEGRAM_BOT_TOKEN"])
        assertTrue("NODE_ENV" !in env)
    }

    @Test fun systemdEnvSplitsSeveralAssignmentsOnOneLine() {
        val all = Takeover.parseSystemdEnv("[Service]\nEnvironment=MUX_A=1 \"MUX_B=two words\" MUX_C=3\n")
        assertEquals(mapOf("MUX_A" to "1", "MUX_B" to "two words", "MUX_C" to "3"), all)
    }

    // ---- discovery ----

    @Test fun findsBothMacServicesInOrder() {
        val m = Mac()
        m.plist("dev.supermux.broker", plistText.replace("<string>dev.supermux.host</string>", "<string>dev.supermux.broker</string>"))
        m.plist("dev.supermux.host", plistText)
        assertEquals(listOf("dev.supermux.host", "dev.supermux.broker"), Takeover.findOldServices(macEnv(m)).map { it.name })
    }

    @Test fun ourOwnManagedPlistIsNotAnOldService() {
        val m = Mac()
        m.plist("dev.supermux.host", BrokerService.launchdPlist(BrokerService.Spec(Path.of("/x/supermux-broker"), emptyMap(), m.state.resolve("l"))))
        assertNull(Takeover.findOldService(macEnv(m)))
        // a MUX_MANAGED_BY key alone is enough too
        m.plist("dev.supermux.host", plistText.replace("<key>MUX_WEB_PORT</key>", "<key>MUX_MANAGED_BY</key><string>x</string><key>MUX_WEB_PORT</key>"))
        assertNull(Takeover.findOldService(macEnv(m)))
    }

    @Test fun macDevCheckoutIsRejected() {
        val m = Mac()
        m.plist("dev.supermux.broker", plistText.replace(
            "<string>/Users/alex/.mux/state/bin/supermux-broker</string>",
            "<string>/opt/homebrew/bin/bun</string><string>/Users/a/projects/supermux/src/main.ts</string>"))
        assertTrue(Takeover.findOldServices(macEnv(m)).isEmpty())
    }

    @Test fun plistWithMuxKeysOnlyInACommentProducesNoEnv() {
        val xml = plistText.replace("<key>EnvironmentVariables</key>", "<!-- <key>MUX_X</key><string>1</string> --><key>EnvironmentVariables</key>")
        assertTrue("MUX_X" !in Takeover.parsePlistEnv(xml))
    }

    @Test fun linuxFindsSupermuxServiceWhenItRunsASupermuxBinary() {
        val home = createTempDirectory()
        val dir = home.resolve(".config/systemd/user").also { Files.createDirectories(it) }
        Files.writeString(dir.resolve("supermux.service"), res("linux-supermux.service"))
        val env = FakeOsEnv(os = OsEnv.Os.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        assertEquals("supermux.service", Takeover.findOldService(env)!!.name)
    }

    @Test fun linuxDevCheckoutUnitIsRejected() {
        val home = createTempDirectory()
        val dir = home.resolve(".config/systemd/user").also { Files.createDirectories(it) }
        Files.writeString(dir.resolve("supermux.service"),
            "[Service]\nExecStart=/usr/bin/true\nExecStart=sg render -c \"exec %h/.bun/bin/bun %h/projects/supermux/src/main.ts\"\n")
        assertTrue(Takeover.findOldServices(FakeOsEnv(os = OsEnv.Os.LINUX, home = home, uid = 1000)).isEmpty())
    }

    // ---- linux env sources ----

    private fun linuxUnit(): Pair<FakeOsEnvHolder, Takeover.OldService> {
        val home = createTempDirectory()
        val dir = home.resolve(".config/systemd/user").also { Files.createDirectories(it) }
        val unit = dir.resolve("supermux.service")
        Files.writeString(unit, res("linux-supermux.service"))
        return FakeOsEnvHolder(home) to Takeover.OldService(Takeover.Kind.SYSTEMD, "supermux.service", unit.toString())
    }
    private class FakeOsEnvHolder(val home: Path)
    private val showEnv = listOf("systemctl", "--user", "show", "-p", "Environment", "--value", "supermux.service")
    private val showFiles = listOf("systemctl", "--user", "show", "-p", "EnvironmentFiles", "--value", "supermux.service")

    @Test fun linuxFallbackCarriesDropInAndEnvironmentFileWhenSystemctlFails() {
        val (h, old) = linuxUnit()
        val unit = old.path
        val envFile = h.home.resolve("mux.env").also { Files.writeString(it, "# c\n\nMUX_FROM_FILE=\"f1\"\nMUX_OTHER=2\n") }
        val d = unit.resolveSibling("supermux.service.d").also { Files.createDirectories(it) }
        Files.writeString(d.resolve("override.conf"), "[Service]\nEnvironment=MUX_WHATSAPP_WEBHOOK_SECRET=s3\nEnvironmentFile=-$envFile\n")
        val env = FakeOsEnv(os = OsEnv.Os.LINUX, home = h.home, uid = 1000, failing = setOf(showEnv))
        val carried = ok(Takeover.prepare(listOf(old), createTempDirectory(), env)).carriedEnv
        assertEquals("s3", carried["MUX_WHATSAPP_WEBHOOK_SECRET"])
        assertEquals("f1", carried["MUX_FROM_FILE"])
        assertEquals("3001", carried["MUX_WHATSAPP_WEBHOOK_PORT"])
    }

    @Test fun linuxParsesScriptedSystemctlShow() {
        val (h, old) = linuxUnit()
        val envFile = h.home.resolve("m.env").also { Files.writeString(it, "MUX_FILE=x\n") }
        val env = FakeOsEnv(os = OsEnv.Os.LINUX, home = h.home, uid = 1000, scripted = mapOf(
            showEnv to listOf(OsEnv.RunResult(0, "NODE_ENV=production \"MUX_TELEGRAM_BOT_TOKEN=1:a b\" MUX_D=4\n", "")),
            showFiles to listOf(OsEnv.RunResult(0, "-$envFile (ignore_errors=yes)\n", "")),
        ))
        val carried = ok(Takeover.prepare(listOf(old), createTempDirectory(), env)).carriedEnv
        assertEquals(mapOf("MUX_TELEGRAM_BOT_TOKEN" to "1:a b", "MUX_D" to "4", "MUX_FILE" to "x"), carried)
    }

    @Test fun linuxPrepareSequenceAndRollback() {
        val (h, old) = linuxUnit()
        val state = createTempDirectory()
        val env = FakeOsEnv(os = OsEnv.Os.LINUX, home = h.home, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        val prepared = ok(Takeover.prepare(listOf(old), state, env))
        val stopCmds = env.ran.filter { it.getOrNull(2) in setOf("disable", "is-active") }
        assertEquals(listOf(listOf("systemctl", "--user", "disable", "--now", "supermux.service"),
            listOf("systemctl", "--user", "is-active", "supermux.service")), stopCmds)
        assertTrue(Files.exists(state.resolve("takeover-backup/pending.json")))
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(state.resolve("takeover-backup/pending.json"))), "it holds the carried env")
        env.ran.clear()
        assertTrue(Takeover.rollback(prepared, env))
        assertEquals(listOf("systemctl", "--user", "enable", "--now", "supermux.service"), env.ran.last())
        assertTrue(listOf("systemctl", "--user", "daemon-reload") in env.ran)
        assertFalse(Files.exists(state.resolve("takeover-backup/pending.json")))
    }

    @Test fun linuxStillActiveFailsAndRollsBack() {
        val (h, old) = linuxUnit()
        val isActive = listOf("systemctl", "--user", "is-active", "supermux.service")
        val env = FakeOsEnv(os = OsEnv.Os.LINUX, home = h.home, uid = 1000,
            scripted = mapOf(isActive to List(20) { OsEnv.RunResult(0, "active\n", "") }))
        assertIs<Takeover.PrepareResult.Failed>(Takeover.prepare(listOf(old), createTempDirectory(), env))
        assertEquals(listOf("systemctl", "--user", "enable", "--now", "supermux.service"), env.ran.last())
    }

    // ---- mac prepare / commit / rollback ----

    @Test fun prepareBacksUpDisablesBootsOutAndDoesNotDeleteThePlist() {
        val m = Mac(); val plist = m.plist("dev.supermux.host", plistText)
        val env = macEnv(m, failing = setOf(printHost))
        val p = ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env))
        assertEquals(listOf("launchctl", "disable", "gui/501/dev.supermux.host"), env.ran[0])
        assertEquals(listOf("launchctl", "bootout", "gui/501/dev.supermux.host"), env.ran[1])
        assertEquals(printHost, env.ran[2])
        assertTrue(Files.exists(plist))
        assertEquals(plistText, String(Files.readAllBytes(Path.of(p.olds.single().backup)), Charsets.UTF_8))
        assertEquals(true, p.oldRelay)
        assertEquals("Alex’s MacBook Air", p.carriedEnv["MUX_HOST_NAME"])
    }

    @Test fun otherLabelPlistSurvivesPrepareIsDeletedByCommitAndBootstrappedByRollback() {
        val m = Mac()
        val plist = m.plist("dev.supermux.broker", plistText.replace("<string>dev.supermux.host</string>", "<string>dev.supermux.broker</string>"))
        val env = macEnv(m, failing = setOf(printBroker))
        val p = ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env))
        assertTrue(Files.exists(plist))
        env.ran.clear()
        assertTrue(Takeover.rollback(p, env))
        // ours is unloaded first (assert its bootout), then enable precedes bootstrap
        assertEquals(listOf("launchctl", "bootout", "gui/501/dev.supermux.host"), env.ran[0])
        val enable = env.ran.indexOf(listOf("launchctl", "enable", "gui/501/dev.supermux.broker"))
        val boot = env.ran.indexOf(listOf("launchctl", "bootstrap", "gui/501", plist.toString()))
        assertTrue(enable in 1 until boot)
        // a fresh prepare + commit deletes the old plist and the journal
        val p2 = ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env))
        Takeover.commit(p2, env)
        assertFalse(Files.exists(plist))
        assertFalse(Files.exists(m.state.resolve("takeover-backup/pending.json")))
    }

    @Test fun commitKeepsTheSameLabelPlist() {
        val m = Mac(); val plist = m.plist("dev.supermux.host", plistText)
        val env = macEnv(m, failing = setOf(printHost))
        Takeover.commit(ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env)), env)
        assertTrue(Files.exists(plist))
    }

    @Test fun rollbackRestoresSameLabelPlistAndBootstrapsIt() {
        val m = Mac(); val plist = m.plist("dev.supermux.host", plistText)
        val env = macEnv(m, failing = setOf(printHost))
        val p = ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env))
        Files.writeString(plist, "ours")
        assertTrue(Takeover.rollback(p, env))
        assertEquals(plistText, String(Files.readAllBytes(plist), Charsets.UTF_8))
        assertEquals(listOf("launchctl", "bootstrap", "gui/501", plist.toString()), env.ran.last())
    }

    @Test fun rollbackWaitsForOursToBeGoneBeforeBootstrappingTheSameLabel() {
        val m = Mac(); val plist = m.plist("dev.supermux.host", plistText)
        val env0 = macEnv(m, failing = setOf(printHost))
        val p = ok(Takeover.prepare(Takeover.findOldServices(env0), m.state, env0))
        val alive = OsEnv.RunResult(0, "", "")
        val gone = OsEnv.RunResult(113, "", "Could not find service")
        // remove() waits once (3 polls alive), rollback's own wait then finds it gone at once
        val env = macEnv(m, scripted = mapOf(printHost to listOf(alive, alive, alive, gone, gone)))
        assertTrue(Takeover.rollback(p, env))
        val boot = env.ran.indexOf(listOf("launchctl", "bootstrap", "gui/501", plist.toString()))
        val lastPrint = env.ran.lastIndexOf(printHost)
        assertTrue(lastPrint in 0 until boot)
    }

    @Test fun rollbackRetriesBootstrap() {
        val m = Mac(); val plist = m.plist("dev.supermux.host", plistText)
        val env0 = macEnv(m, failing = setOf(printHost))
        val p = ok(Takeover.prepare(Takeover.findOldServices(env0), m.state, env0))
        val boot = listOf("launchctl", "bootstrap", "gui/501", plist.toString())
        val env = macEnv(m, failing = setOf(printHost),
            scripted = mapOf(boot to listOf(OsEnv.RunResult(5, "", "busy"), OsEnv.RunResult(5, "", "busy"), OsEnv.RunResult(0, "", ""))))
        assertTrue(Takeover.rollback(p, env))
        assertEquals(3, env.ran.count { it == boot })
        assertEquals(listOf(1_000L, 1_000L), env.sleeps)
    }

    @Test fun rollbackReportsFalseWhenBootstrapNeverSucceedsOrBackupGone() {
        val m = Mac(); val plist = m.plist("dev.supermux.host", plistText)
        val env0 = macEnv(m, failing = setOf(printHost))
        val p = ok(Takeover.prepare(Takeover.findOldServices(env0), m.state, env0))
        val boot = listOf("launchctl", "bootstrap", "gui/501", plist.toString())
        assertFalse(Takeover.rollback(p, macEnv(m, failing = setOf(boot))))
        Files.delete(Path.of(p.olds.single().backup))
        assertFalse(Takeover.rollback(p, macEnv(m)))
    }

    @Test fun stillLoadedAfterBootoutFailsAndRollsBack() {
        val m = Mac(); m.plist("dev.supermux.host", plistText)
        val env = macEnv(m) // print always exits 0: never unloads
        assertIs<Takeover.PrepareResult.Failed>(Takeover.prepare(Takeover.findOldServices(env), m.state, env))
        assertTrue(env.ran.count { it == printHost } >= 10)
        assertTrue(env.ran.any { it.getOrNull(1) == "bootstrap" })
        assertFalse(Files.exists(m.state.resolve("takeover-backup/pending.json")))
    }

    @Test fun nullUidOnMacFailsAndTouchesNothing() {
        val m = Mac(); val plist = m.plist("dev.supermux.broker", plistText.replace("<string>dev.supermux.host</string>", "<string>dev.supermux.broker</string>"))
        val env = macEnv(m, uid = null)
        assertIs<Takeover.PrepareResult.Failed>(Takeover.prepare(Takeover.findOldServices(env), m.state, env))
        assertTrue(Files.exists(plist))
        assertTrue(env.ran.isEmpty())
    }

    @Test fun aSecondTakeoverKeepsTheFirstBackup() {
        val m = Mac(); m.plist("dev.supermux.host", plistText)
        val env = macEnv(m, failing = setOf(printHost))
        val a = ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env)).olds.single().backup
        Files.writeString(Path.of(a), "first")
        val b = ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env)).olds.single().backup
        assertTrue(a != b)
        assertEquals("first", Files.readString(Path.of(a)))
    }

    @Test fun mergesEnvAcrossOldServicesFirstWins() {
        val m = Mac()
        m.plist("dev.supermux.host", plistText)
        m.plist("dev.supermux.broker", plistText.replace("<string>dev.supermux.host</string>", "<string>dev.supermux.broker</string>")
            .replace("Alex’s MacBook Air", "other").replace("<key>MUX_RELAY_DOMAIN</key>", "<key>MUX_EXTRA</key><string>e</string><key>MUX_RELAY_DOMAIN</key>"))
        val env = macEnv(m, failing = setOf(printHost, printBroker))
        val p = ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env))
        assertEquals(2, p.olds.size)
        assertEquals("Alex’s MacBook Air", p.carriedEnv["MUX_HOST_NAME"])
        assertEquals("e", p.carriedEnv["MUX_EXTRA"])
    }

    @Test fun oldRelayIsNullWhenAbsentAndFalseWhenEmpty() {
        val m = Mac()
        m.plist("dev.supermux.host", plistText.replace("<key>MUX_RELAY_DOMAIN</key>\n    <string>relay.supermux.dev</string>", ""))
        val env = macEnv(m, failing = setOf(printHost))
        assertNull(ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env)).oldRelay)
        m.plist("dev.supermux.host", plistText.replace("<string>relay.supermux.dev</string>", "<string></string>"))
        assertEquals(false, ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env)).oldRelay)
    }

    // ---- journal ----

    @Test fun journalRoundTrips() {
        val p = Takeover.Prepared("/s", listOf(Takeover.PreparedOne(Takeover.OldService(Takeover.Kind.LAUNCHD, "dev.supermux.host", "/p"), "/b")), mapOf("MUX_A" to "’"), true)
        assertEquals(p, Takeover.decodeJournal(Takeover.encodeJournal(p)))
    }

    @Test fun recoverPendingRollsBackWhenUnhealthyAndCommitsWhenHealthy() {
        val m = Mac()
        val plist = m.plist("dev.supermux.broker", plistText.replace("<string>dev.supermux.host</string>", "<string>dev.supermux.broker</string>"))
        val env = macEnv(m, failing = setOf(printBroker))
        ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env))
        val journal = m.state.resolve("takeover-backup/pending.json")
        assertTrue(Files.exists(journal))

        env.ran.clear()
        assertTrue(Takeover.recoverPending(m.state, env, ourServiceHealthy = false))
        assertTrue(env.ran.any { it.getOrNull(1) == "bootstrap" })
        assertFalse(Files.exists(journal))
        assertTrue(Files.exists(plist))

        ok(Takeover.prepare(Takeover.findOldServices(env), m.state, env))
        assertTrue(Takeover.recoverPending(m.state, env, ourServiceHealthy = true))
        assertFalse(Files.exists(plist))
        assertFalse(Files.exists(journal))
        assertFalse(Takeover.recoverPending(m.state, env, ourServiceHealthy = true))
    }
}
