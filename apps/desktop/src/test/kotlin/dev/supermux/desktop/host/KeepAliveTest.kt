package dev.supermux.desktop.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure plist / unit / autostart STRING-generation proofs plus OS-gated install/remove routing for
 * [KeepAlive] (Plan 3 Task 4). The real launchctl/systemctl calls are captured by a fake
 * [KeepAliveEnv] so both the macOS and Linux branches verify here on any host — the actual OS install
 * is gated and not attempted.
 */
class KeepAliveTest {

    private val spec = KeepAlive.Spec(
        exec = listOf("/opt/supermux/bin/supermux", "--keep-hosting"),
        hostId = "abcdefghijklmnopqrstuvwxyz".take(26),
        hostName = "Ahmet's MacBook Air",
    )
    private val windowsSpec = KeepAlive.Spec(
        exec = listOf(
            "C:\\Program Files\\Supermux\\Supermux.exe",
            "--keep-hosting",
            "--name=Ahmet & Windows",
        ),
    )

    // ── string generators ───────────────────────────────────────────────────────────

    @Test fun launchdPlist_hasLabelExecAndHostId() {
        val xml = KeepAlive.launchdPlist(spec)
        assertTrue(xml.contains("<string>dev.supermux.host</string>"), "plist Label")
        assertTrue(xml.contains("<string>/opt/supermux/bin/supermux</string>"), "exec arg 0")
        assertTrue(xml.contains("<string>--keep-hosting</string>"), "exec arg 1")
        assertTrue(xml.contains("<key>SUPERMUX_HOST_ID</key>"), "hostId env key")
        assertTrue(xml.contains("<string>${spec.hostId}</string>"), "hostId value")
        assertTrue(xml.contains("<key>MUX_HOST_NAME</key>"), "host name env key")
        assertTrue(xml.contains("<string>${spec.hostName}</string>"), "host name value")
        assertTrue(xml.contains("<key>RunAtLoad</key>") && xml.contains("<key>KeepAlive</key>"), "relaunch keys")
        assertTrue(xml.trimStart().startsWith("<?xml"), "well-formed plist header")
    }

    @Test fun launchdPlist_escapesXml() {
        val xml = KeepAlive.launchdPlist(spec.copy(exec = listOf("/bin/app", "--flag=a&b<c>")))
        assertTrue(xml.contains("--flag=a&amp;b&lt;c&gt;"), "special chars are XML-escaped")
        assertFalse(xml.contains("a&b<c>"), "raw unescaped value must not appear")
    }

    @Test fun systemdUnit_hasExecStartRestartAndInstall() {
        val unit = KeepAlive.systemdUnit(spec)
        assertTrue(unit.contains("ExecStart=/opt/supermux/bin/supermux --keep-hosting"), "ExecStart")
        assertTrue(unit.contains("Restart=on-failure"), "restart policy")
        assertTrue(unit.contains("Environment=SUPERMUX_HOST_ID=${spec.hostId}"), "hostId env")
        assertTrue(unit.contains("Environment=\"MUX_HOST_NAME=${spec.hostName}\""), "host name env")
        assertTrue(unit.contains("WantedBy=default.target"), "install section")
    }

    @Test fun xdgAutostart_isAValidDesktopEntry() {
        val d = KeepAlive.xdgAutostart(spec)
        assertTrue(d.contains("[Desktop Entry]"))
        assertTrue(d.contains("Exec=/opt/supermux/bin/supermux --keep-hosting"))
        assertTrue(d.contains("X-GNOME-Autostart-enabled=true"))
    }

    @Test fun windowsTaskXml_isPerUserLeastPrivilegeAndEscapesTheLauncher() {
        val xml = KeepAlive.windowsTaskXml(windowsSpec)
        assertTrue(xml.contains("<LogonTrigger>"), "starts at user logon")
        assertTrue(xml.contains("<LogonType>InteractiveToken</LogonType>"), "runs in the logged-in desktop session")
        assertTrue(xml.contains("<RunLevel>LeastPrivilege</RunLevel>"), "does not request elevation")
        assertTrue(xml.contains("<RestartOnFailure>"), "restarts a crashed host")
        assertTrue(
            xml.contains("<Command>powershell.exe</Command>"),
            "uses a fixed non-interactive PowerShell launcher for the env assignment",
        )
        assertTrue(
            xml.contains("\$env:SUPERMUX_KEEP_ALIVE = '1'"),
            "passes the keep-alive environment through the launcher command",
        )
        assertTrue(
            xml.contains("'C:\\Program Files\\Supermux\\Supermux.exe'"),
            "PowerShell-quotes the executable",
        )
        assertTrue(xml.contains("Ahmet &amp; Windows"), "XML-escapes arguments")
        assertTrue(
            xml.contains("<WorkingDirectory>C:\\Program Files\\Supermux</WorkingDirectory>"),
            "uses the executable directory",
        )
    }

    @Test fun spec_rejectsEmptyExec() {
        var threw = false
        try { KeepAlive.Spec(exec = emptyList()) } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw, "an empty exec argv must be rejected")
    }

    // ── OS-gated install / remove via a fake env ─────────────────────────────────────

    private class FakeEnv(
        override val os: KeepAlive.Os,
        override val home: Path,
        override val localAppData: Path = home.resolve("AppData/Local"),
        override val uid: Long? = 501L,
        override val xdgRuntimeDir: String? = "/run/user/501",
        private val commands: Set<String> = setOf("launchctl", "systemctl", "loginctl"),
    ) : KeepAliveEnv {
        val ran = mutableListOf<List<String>>()
        override fun hasCommand(name: String) = name in commands
        override fun run(argv: List<String>): Boolean { ran.add(argv); return true }
    }

    @Test fun install_onLinux_writesUnitAndEnablesViaSystemctl() {
        val home = createTempDirectory("ka-linux").also { it.toFile().deleteOnExit() }
        val env = FakeEnv(KeepAlive.Os.LINUX, home)
        val result = KeepAlive.install(spec, env)
        val installed = assertIs<KeepAlive.Result.Installed>(result)
        assertEquals(home.resolve(".config/systemd/user/${KeepAlive.SYSTEMD_UNIT}"), installed.path)
        assertTrue(Files.exists(installed.path), "the unit file is written")
        assertTrue(Files.readString(installed.path).contains("ExecStart="))
        assertTrue(env.ran.any { it == listOf("systemctl", "--user", "enable", "--now", KeepAlive.SYSTEMD_NAME) }, "enabled via systemctl")
    }

    @Test fun install_onLinuxWithoutSystemctl_fallsBackToXdgAutostart() {
        val home = createTempDirectory("ka-xdg").also { it.toFile().deleteOnExit() }
        val env = FakeEnv(KeepAlive.Os.LINUX, home, commands = emptySet())
        val result = KeepAlive.install(spec, env)
        val installed = assertIs<KeepAlive.Result.Installed>(result)
        assertEquals(home.resolve(".config/autostart/${KeepAlive.XDG_AUTOSTART_FILE}"), installed.path)
        assertTrue(Files.readString(installed.path).contains("[Desktop Entry]"))
        assertTrue(env.ran.isEmpty(), "no systemctl attempted without the command")
    }

    @Test fun install_onMac_writesPlistAndBootstrapsWithGuiDomain() {
        val home = createTempDirectory("ka-mac").also { it.toFile().deleteOnExit() }
        val env = FakeEnv(KeepAlive.Os.MAC, home)
        val result = KeepAlive.install(spec, env)
        val installed = assertIs<KeepAlive.Result.Installed>(result)
        assertEquals(home.resolve("Library/LaunchAgents/${KeepAlive.LAUNCHD_LABEL}.plist"), installed.path)
        assertTrue(Files.readString(installed.path).contains("<key>Label</key>"))
        assertTrue(env.ran.any { it.contains("bootstrap") && it.any { a -> a == "gui/501" } }, "bootstrapped into gui/<uid>")
    }

    @Test fun install_onWindows_writesXmlAndCreatesTheCurrentUserTask() {
        val home = createTempDirectory("ka-win").also { it.toFile().deleteOnExit() }
        val localAppData = home.resolve("LocalAppData")
        val env = FakeEnv(KeepAlive.Os.WINDOWS, home, localAppData)
        val installed = assertIs<KeepAlive.Result.Installed>(KeepAlive.install(windowsSpec, env))
        val taskXml = localAppData.resolve("Supermux/supermux-host-task.xml")
        assertEquals(taskXml, installed.path)
        assertTrue(Files.readString(taskXml, Charsets.UTF_16).contains("<LogonTrigger>"))
        assertTrue(
            env.ran.any {
                it == listOf(
                    "schtasks.exe", "/Create",
                    "/TN", KeepAlive.WINDOWS_TASK_NAME,
                    "/XML", taskXml.toString(),
                    "/F",
                )
            },
            "creates the idempotent current-user task from the generated XML",
        )
    }

    @Test fun remove_onWindows_deletesTheTaskAndGeneratedXml() {
        val home = createTempDirectory("ka-win-rm").also { it.toFile().deleteOnExit() }
        val localAppData = home.resolve("LocalAppData")
        val env = FakeEnv(KeepAlive.Os.WINDOWS, home, localAppData)
        KeepAlive.install(windowsSpec, env)

        val removed = assertIs<KeepAlive.Result.Removed>(KeepAlive.remove(env))
        val taskXml = localAppData.resolve("Supermux/supermux-host-task.xml")
        assertEquals(taskXml, removed.path)
        assertFalse(Files.exists(taskXml))
        assertTrue(
            env.ran.any {
                it == listOf(
                    "schtasks.exe", "/Delete",
                    "/TN", KeepAlive.WINDOWS_TASK_NAME,
                    "/F",
                )
            },
            "deletes only the per-user Supermux task",
        )
    }

    @Test fun remove_onLinux_deletesUnitAndDisables() {
        val home = createTempDirectory("ka-rm").also { it.toFile().deleteOnExit() }
        val env = FakeEnv(KeepAlive.Os.LINUX, home)
        KeepAlive.install(spec, env)
        val removed = assertIs<KeepAlive.Result.Removed>(KeepAlive.remove(env))
        assertEquals(home.resolve(".config/systemd/user/${KeepAlive.SYSTEMD_UNIT}"), removed.path)
        assertFalse(Files.exists(home.resolve(".config/systemd/user/${KeepAlive.SYSTEMD_UNIT}")))
        assertTrue(env.ran.any { it == listOf("systemctl", "--user", "disable", "--now", KeepAlive.SYSTEMD_NAME) })
    }
}
