package dev.supermux.desktop.host

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TakeoverTest {
    private fun res(name: String) = String(javaClass.getResourceAsStream("/takeover/$name")!!.readAllBytes(), Charsets.UTF_8)

    @Test fun plistEnvKeepsMuxKeysExceptOnesWeOwn() {
        val env = Takeover.carriedEnv(Takeover.parsePlistEnv(res("mac-native-host.plist")))
        assertEquals("relay.supermux.dev", env["MUX_RELAY_DOMAIN"])
        assertEquals("http://127.0.0.1:9898", env["MUX_WEB_PUBLIC_URL"])
        assertEquals("ahmet’s MacBook Air", env["MUX_HOST_NAME"])
        assertTrue("MUX_WEB_PORT" !in env && "MUX_STATE_DIR" !in env && "PATH" !in env)
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

    @Test fun findsTheOldMacServiceAndBacksItUpBeforeStopping() {
        val home = createTempDirectory()
        val state = createTempDirectory()
        val agents = home.resolve("Library/LaunchAgents").also { Files.createDirectories(it) }
        Files.write(agents.resolve("dev.supermux.host.plist"), res("mac-native-host.plist").toByteArray(Charsets.UTF_8))
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501)

        val found = Takeover.findOldService(env)!!
        val prepared = Takeover.prepare(found, stateDir = state, env = env)

        assertTrue(Files.exists(state.resolve("takeover-backup/dev.supermux.host.plist")))
        assertEquals("relay.supermux.dev", prepared.carriedEnv["MUX_RELAY_DOMAIN"])
        assertEquals(listOf("launchctl", "bootout", "gui/501/dev.supermux.host"), env.ran.last())
    }

    @Test fun ourOwnManagedPlistIsNotAnOldService() {
        val home = createTempDirectory()
        val agents = home.resolve("Library/LaunchAgents").also { Files.createDirectories(it) }
        Files.writeString(agents.resolve("dev.supermux.host.plist"),
            res("mac-native-host.plist").replace("<key>MUX_WEB_PORT</key>", "<key>MUX_MANAGED_BY</key><string>desktop</string><key>MUX_WEB_PORT</key>"))
        assertNull(Takeover.findOldService(FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501)))
    }

    @Test fun rollbackRestoresAndReloadsTheOldService() {
        val home = createTempDirectory()
        val state = createTempDirectory()
        val agents = home.resolve("Library/LaunchAgents").also { Files.createDirectories(it) }
        val plist = agents.resolve("dev.supermux.host.plist")
        Files.write(plist, res("mac-native-host.plist").toByteArray(Charsets.UTF_8))
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501)
        val prepared = Takeover.prepare(Takeover.findOldService(env)!!, stateDir = state, env = env)
        Files.writeString(plist, "ours")          // BrokerService overwrote it (same label)

        assertTrue(Takeover.rollback(prepared, env))

        assertEquals(res("mac-native-host.plist"), String(Files.readAllBytes(plist), Charsets.UTF_8))
        assertEquals(listOf("launchctl", "bootstrap", "gui/501", plist.toString()), env.ran.last())
    }

    @Test fun rollbackNeverThrowsWhenTheBackupIsGone() {
        val home = createTempDirectory()
        val state = createTempDirectory()
        val agents = home.resolve("Library/LaunchAgents").also { Files.createDirectories(it) }
        Files.write(agents.resolve("dev.supermux.host.plist"), res("mac-native-host.plist").toByteArray(Charsets.UTF_8))
        val env = FakeOsEnv(os = OsEnv.Os.MAC, home = home, uid = 501)
        val prepared = Takeover.prepare(Takeover.findOldService(env)!!, stateDir = state, env = env)
        Files.delete(prepared.backup)
        assertFalse(Takeover.rollback(prepared, env))
    }

    @Test fun linuxFindsSupermuxServiceWhenItRunsASupermuxBinary() {
        val home = createTempDirectory()
        val dir = home.resolve(".config/systemd/user").also { Files.createDirectories(it) }
        Files.writeString(dir.resolve("supermux.service"), res("linux-supermux.service"))
        val env = FakeOsEnv(os = OsEnv.Os.LINUX, home = home, uid = 1000, xdgRuntimeDir = "/run/user/1000")
        assertEquals("supermux.service", Takeover.findOldService(env)!!.name)
    }
}
