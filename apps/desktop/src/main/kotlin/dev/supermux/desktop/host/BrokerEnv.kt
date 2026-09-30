package dev.supermux.desktop.host

import java.nio.file.Path

const val RELAY_DOMAIN = "relay.supermux.dev"

/**
 * The broker's env for BOTH modes. [carried] (from a takeover) goes UNDER ours, so ours
 * wins. `MUX_WEB_PUBLIC_URL` is only ever set when carried. `MUX_STATE_DIR` is always the app's
 * state dir, so the broker and the app agree on it whatever the app inherited.
 */
fun brokerEnv(
    prefs: HostingPrefs,
    bins: HostBinaries.SidecarBinaries,
    carried: Map<String, String>,
    stateDir: Path,
    hostName: String = DesktopHostBootstrap.defaultHostName(),
    existingPath: String? = System.getenv("PATH"),
    home: String = System.getProperty("user.home") ?: ".",
    os: OsEnv.Os = SystemOsEnv.os,
): Map<String, String> {
    val out = LinkedHashMap(carried)
    out["MUX_WEB_PORT"] = prefs.port.toString()
    out["MUX_MANAGED_BY"] = "desktop"
    out["MUX_STATE_DIR"] = stateDir.toString()
    out["MUX_HOST_NAME"] = hostName
    out["MUX_RELAY_DOMAIN"] = if (prefs.relay) RELAY_DOMAIN else ""
    bins.zmxDir?.let { out["MUX_ZMX_BIN_DIR"] = it.toString() }
    bins.sessiondPath?.let { out["MUX_SESSIOND_PATH"] = it.toString() }
    out["PATH"] = servicePath(bins.binDir, existingPath, home, os)
    return out
}

/** bin dir + existing PATH (or /usr/bin:/bin) + the agent CLI dirs `supermux setup` adds. */
fun servicePath(binDir: Path?, existingPath: String?, home: String, os: OsEnv.Os): String {
    val windows = os == OsEnv.Os.WINDOWS
    val sep = if (windows) ";" else ":"
    val parts = mutableListOf<String>()
    binDir?.let { parts += it.toString() }
    val existing = existingPath?.takeIf { it.isNotBlank() } ?: if (windows) "" else "/usr/bin:/bin"
    parts += existing.split(sep)
    if (!windows) parts += listOf("/opt/homebrew/bin", "/usr/local/bin", "$home/.local/bin")
    return parts.filter { it.isNotBlank() }.distinct().joinToString(sep)
}
