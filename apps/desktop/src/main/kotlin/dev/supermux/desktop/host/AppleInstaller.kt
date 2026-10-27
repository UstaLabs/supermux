package dev.supermux.desktop.host

import kotlinx.coroutines.delay

/**
 * Apple's "install the command line developer tools" dialog, brought to the front.
 *
 * `POST /system/install-git` makes the BROKER run `xcode-select --install`, and the broker is a
 * launchd job (or the app's child) with no window: macOS opens Apple's installer without
 * activating it, so its dialog lands BEHIND the app window the user just clicked Install in, and
 * reads as nothing happened (or, later, as a dialog that appeared on its own). The installer is
 * `/System/Library/CoreServices/Install Command Line Developer Tools.app`, spawned by launchd a
 * moment after the request; once it runs, `open -a` on it simply activates it (LaunchServices, no
 * Automation or Accessibility permission). It is only ever opened when it is ALREADY running, so
 * this can never start an install by itself.
 */
object AppleInstaller {
    const val APP_PATH = "/System/Library/CoreServices/Install Command Line Developer Tools.app"

    /**
     * Matched against the full command line: macOS truncates a process NAME to 16 characters
     * ("Install Command "), so `pgrep -x` with the app's name never matches.
     */
    internal val runningArgv = listOf("/usr/bin/pgrep", "-f", "$APP_PATH/Contents/MacOS/")

    internal val frontArgv = listOf("/usr/bin/open", "-a", APP_PATH)

    /**
     * macOS only: wait for the installer to come up (up to [attempts] × [intervalMs]), then activate
     * it once. True iff it was found and `open` succeeded. Never throws; nothing elsewhere.
     */
    suspend fun bringToFront(
        env: OsEnv = SystemOsEnv,
        attempts: Int = 8,
        intervalMs: Long = 500,
        pause: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        if (env.os != OsEnv.Os.MAC) return false
        repeat(attempts) {
            pause(intervalMs)
            if (env.runResult(runningArgv).exit == 0) return env.run(frontArgv)
        }
        return false
    }
}
