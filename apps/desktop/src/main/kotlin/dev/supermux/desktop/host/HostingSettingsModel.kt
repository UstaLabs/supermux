package dev.supermux.desktop.host

import java.io.RandomAccessFile
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Settings ▸ Hosting's status row: the dot and the words after it. */
data class HostingStatusLine(val dot: String, val text: String)

/**
 * Pure. Running: "Running · N sessions · v<version>" (the version part only when known). Read-only:
 * "Running · set up outside the app". Everything else says what the tray header says.
 */
fun hostingStatusLine(s: HostingStatus, prefs: HostingPrefs, sessions: Int, version: String?): HostingStatusLine {
    val tray = TrayModel.of(s, prefs, sessions, remoteName = null)
    val dot = dotGlyph(tray.dot)
    val text = when {
        s is HostingStatus.Running && s.readOnly -> "Running · set up outside the app"
        s is HostingStatus.Running -> buildString {
            append("Running · ")
            append(sessionCountText(sessions))
            version?.let { append(" · v").append(it) }
        }
        else -> tray.header
    }
    return HostingStatusLine(dot, text)
}

/** One network interface, as [lanIpv4] needs it (injected in tests). */
data class NetIf(val name: String, val virtual: Boolean, val addresses: List<InetAddress>)

private val VIRTUAL_PREFIXES = listOf("docker", "br-", "veth", "virbr", "vmnet", "utun", "tun", "tap")
private val PHYSICAL_PREFIXES = listOf("en", "eth", "wl")

/**
 * Pure: a site-local IPv4 (192.168/16, 10/8, 172.16/12) another device on the LAN can reach.
 * Virtual interfaces (containers, VMs, VPN tunnels) are skipped; `en*`/`eth*`/`wl*` come first.
 */
fun lanIpv4(ifaces: List<NetIf>): String? {
    fun lanAddress(i: NetIf) =
        i.addresses.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }?.hostAddress
    val real = ifaces.filter { i -> !i.virtual && VIRTUAL_PREFIXES.none { i.name.startsWith(it) } }
    val (physical, other) = real.partition { i -> PHYSICAL_PREFIXES.any { i.name.startsWith(it) } }
    return (physical + other).firstNotNullOfOrNull(::lanAddress)
}

/** The machine's up, non-loopback interfaces. Best-effort: empty on failure. */
fun systemNetIfs(): List<NetIf> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
        .map { NetIf(it.name, it.isVirtual, it.inetAddresses.toList()) }
}.getOrDefault(emptyList())

/** Pure: [localBaseUrl] with its host swapped for [lanIp], or unchanged when there is none. */
fun displayLocalUrl(localBaseUrl: String, lanIp: String?): String {
    if (lanIp == null) return localBaseUrl
    return runCatching {
        val u = URI(localBaseUrl)
        URI(u.scheme, u.userInfo, lanIp, u.port, u.path, u.query, u.fragment).toString()
    }.getOrDefault(localBaseUrl)
}

/** The last [n] lines of [file] (reads at most its last [maxBytes]). Empty when it is missing. */
fun tailLines(file: Path, n: Int = 20, maxBytes: Long = 64 * 1024): List<String> = runCatching {
    if (!Files.isRegularFile(file)) return emptyList()
    RandomAccessFile(file.toFile(), "r").use { raf ->
        val len = raf.length()
        val start = (len - maxBytes).coerceAtLeast(0)
        val buf = ByteArray((len - start).toInt())
        raf.seek(start)
        raf.readFully(buf)
        var lines = String(buf, StandardCharsets.UTF_8).lines()
        if (start > 0) lines = lines.drop(1) // the first one is cut
        lines.dropLastWhile { it.isBlank() }.takeLast(n)
    }
}.getOrDefault(emptyList())
