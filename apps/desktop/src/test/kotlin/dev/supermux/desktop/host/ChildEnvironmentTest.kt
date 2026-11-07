package dev.supermux.desktop.host

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The broker the app starts as its child gets the app's environment minus the service keys, plus the broker's own
 * (#29 step 9). On Windows a name ignores case: the app's `Path` and the broker's `PATH` are one variable, and Java's
 * ProcessBuilder hands the child both when both are in the map, so which one the broker read was undefined.
 */
class ChildEnvironmentTest {
    @Test fun onWindowsOneVariableStaysWithTheAppsSpellingAndTheBrokersValue() {
        val env = childEnvironment(
            base = mapOf("Path" to "C:\\Windows", "SystemRoot" to "C:\\Windows", "MUX_WEB_PORT" to "1111", "TEMP" to "C:\\t"),
            ours = mapOf("PATH" to "C:\\bin;C:\\Windows", "MUX_WEB_PORT" to "9898", "systemroot" to "C:\\Windows"),
            os = OsEnv.Os.WINDOWS,
        )
        assertEquals(
            mapOf("Path" to "C:\\bin;C:\\Windows", "SystemRoot" to "C:\\Windows", "TEMP" to "C:\\t", "MUX_WEB_PORT" to "9898"),
            env,
        )
        assertEquals(1, env.keys.count { it.equals("PATH", ignoreCase = true) })
    }

    @Test fun elsewhereNamesThatDifferInCaseAreDifferentVariables() {
        val env = childEnvironment(
            base = mapOf("Path" to "app's own", "PATH" to "/usr/bin", "HOME" to "/home/u"),
            ours = mapOf("PATH" to "/opt/supermux/bin:/usr/bin"),
            os = OsEnv.Os.LINUX,
        )
        assertEquals(mapOf("Path" to "app's own", "PATH" to "/opt/supermux/bin:/usr/bin", "HOME" to "/home/u"), env)
    }

    @Test fun theServiceKeysTheAppInheritedNeverReachTheChild() {
        val env = childEnvironment(
            base = mapOf("MUX_HOME" to "/x", "INVOCATION_ID" to "abc", "xpc_service_name" to "app", "KEEP" to "1"),
            ours = emptyMap(),
            os = OsEnv.Os.MAC,
        )
        assertEquals(mapOf("KEEP" to "1"), env)
    }
}
