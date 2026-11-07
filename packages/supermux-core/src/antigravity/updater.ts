/**
 * agy's background updater (`agy --bg-updater`, started detached) runs `agy --version`, and on Windows a console
 * program started from a process without a console gets a console window of its own: a black window flashed whenever
 * supermux started agy (the sign-in, the model list, sessions). AGY_CLI_DISABLE_AUTO_UPDATE=true turns the updater off
 * ("1" does not, agy 1.5.0). Windows only (Ahmet, #70): elsewhere the updater is silent and is what keeps agy current.
 */
export function antigravityUpdaterEnv(platform: NodeJS.Platform = process.platform): Record<string, string> { // platform-ok: agy's updater opens a console window only on Windows (#70)
  return platform === "win32" ? { AGY_CLI_DISABLE_AUTO_UPDATE: "true" } : {}
}
