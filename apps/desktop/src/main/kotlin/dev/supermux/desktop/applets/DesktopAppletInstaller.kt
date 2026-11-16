// #117 S1: "Install" for an applet on a desktop — an app of its own in the OS's launcher, which opens the applet's
// chrome-less window (handing over to the running supermux, AppletHandOff). The shortcut holds no secret: just the
// applet's address, supermux://applet/<host>/<name>.
//   Linux:   ~/.local/share/applications/supermux-applet-<id>.desktop (+ its icon), found by every app grid.
//   macOS:   ~/Applications/Supermux Applets/<Title>.app, a stub whose executable opens the applet's address
//            (supermux.app owns the scheme), ad-hoc signed here; Spotlight and Launchpad find it.
//   Windows: a Start-menu link, Programs\Supermux Applets\<Title>.lnk, to supermux.exe --applet=<address>.
package dev.supermux.desktop.applets

import dev.supermux.desktop.host.OsEnv
import dev.supermux.desktop.host.SystemOsEnv
import dev.supermux.desktop.platform.DesktopOs
import dev.supermux.net.AppletDto
import dev.supermux.ui.applets.appletUri
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.session.AppletInstaller
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DesktopAppletInstaller(
    /** The OS seam: its folders, its variables and the commands it runs (sips, codesign, PowerShell). */
    private val sys: OsEnv = SystemOsEnv,
    /** The app's own launcher (jpackage's), which the Linux and Windows shortcuts start; null in a dev run. */
    private val appPath: String? = System.getProperty("jpackage.app-path"),
) : AppletInstaller {

    private val os: DesktopOs get() = sys.os
    private val home: Path get() = sys.home
    private fun env(name: String): String? = sys.getenv(name)

    override val phone: Boolean = false

    override val where: String
        get() = localized(
            when (os) {
                DesktopOs.MACOS -> Str.applets_where_applications
                DesktopOs.WINDOWS -> Str.applets_where_start_menu
                DesktopOs.LINUX -> Str.applets_where_app_menu
            },
        )

    /** "Install on this Mac / PC / computer". */
    override val label: String
        get() = localized(
            when (os) {
                DesktopOs.MACOS -> Str.applets_menu_install_mac
                DesktopOs.WINDOWS -> Str.applets_menu_install_pc
                DesktopOs.LINUX -> Str.applets_menu_install
            },
        )

    /** A short stable id for file names: the same applet of the same host always gets the same one. */
    private fun id(hostId: String, name: String): String =
        MessageDigest.getInstance("SHA-256").digest("$hostId/$name".toByteArray()).take(6).joinToString("") { "%02x".format(it) }

    /** A title that is safe as a file name on every OS. */
    private fun fileTitle(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ").trim().take(60).ifBlank { "Applet" }

    private fun xdgDataHome(): Path = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of) ?: home.resolve(".local/share")

    private fun linuxDesktopFile(hostId: String, name: String): Path =
        xdgDataHome().resolve("applications").resolve("supermux-applet-${id(hostId, name)}.desktop")

    private fun macApp(applet: AppletDto): Path =
        home.resolve("Applications").resolve("Supermux Applets").resolve(fileTitle(applet.title.ifBlank { applet.name }) + ".app")

    private fun windowsLink(applet: AppletDto): Path =
        Path.of(env("APPDATA") ?: home.resolve("AppData/Roaming").toString())
            .resolve("Microsoft/Windows/Start Menu/Programs/Supermux Applets")
            .resolve(fileTitle(applet.title.ifBlank { applet.name }) + ".lnk")

    override fun isInstalled(hostId: String, applet: AppletDto): Boolean = when (os) {
        DesktopOs.LINUX -> Files.exists(linuxDesktopFile(hostId, applet.name))
        DesktopOs.MACOS -> Files.exists(macApp(applet).resolve("Contents/Info.plist"))
        DesktopOs.WINDOWS -> Files.exists(windowsLink(applet))
    }

    override suspend fun install(hostId: String, applet: AppletDto, icon: ByteArray?): String? = withContext(Dispatchers.IO) {
        val title = applet.title.ifBlank { applet.name }
        val png = iconPng(icon, title)
        runCatching {
            when (os) {
                DesktopOs.LINUX -> installLinux(hostId, applet, title, png)
                DesktopOs.MACOS -> installMac(hostId, applet, title, png)
                DesktopOs.WINDOWS -> installWindows(hostId, applet, title, png)
            }
        }.fold(onSuccess = { it }, onFailure = { it.message ?: localized(Str.applets_install_failed) })
    }

    // ── Linux ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun installLinux(hostId: String, applet: AppletDto, title: String, png: ByteArray): String? {
        val exe = appPath ?: return localized(Str.applets_install_dev_build)
        val iconFile = xdgDataHome().resolve("icons/hicolor/256x256/apps/supermux-applet-${id(hostId, applet.name)}.png")
        Files.createDirectories(iconFile.parent)
        Files.write(iconFile, png)
        val file = linuxDesktopFile(hostId, applet.name)
        Files.createDirectories(file.parent)
        val escaped = exe.replace("\\", "\\\\").replace("\"", "\\\"")
        Files.writeString(
            file,
            """
            [Desktop Entry]
            Type=Application
            Name=${title.replace("\n", " ")}
            Comment=${localized(Str.applets_install_comment)}
            Exec="$escaped" --applet=${appletUri(hostId, applet.name)}
            Icon=${iconFile}
            Terminal=false
            Categories=Utility;
            StartupWMClass=supermux
            """.trimIndent() + "\n",
        )
        file.toFile().setExecutable(true)
        // Menus re-read their entries when this database changes; harmless where the tool is missing.
        if (sys.hasCommand("update-desktop-database")) sys.runResult(listOf("update-desktop-database", file.parent.toString()), 5_000)
        return null
    }

    // ── macOS ────────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun installMac(hostId: String, applet: AppletDto, title: String, png: ByteArray): String? {
        val app = macApp(applet)
        val contents = app.resolve("Contents")
        Files.createDirectories(contents.resolve("MacOS"))
        Files.createDirectories(contents.resolve("Resources"))
        val uri = appletUri(hostId, applet.name)
        // The stub: opening the address hands it to supermux (it owns the scheme), which opens or fronts the window.
        val exe = contents.resolve("MacOS/applet")
        Files.writeString(exe, "#!/bin/sh\nexec /usr/bin/open '${uri.replace("'", "")}'\n")
        exe.toFile().setExecutable(true)
        // The icon: the PNG made into an .icns with macOS's own sips.
        val pngFile = Files.createTempFile("applet-icon", ".png")
        Files.write(pngFile, png)
        val icns = contents.resolve("Resources/applet.icns")
        sys.runResult(listOf("/usr/bin/sips", "-s", "format", "icns", pngFile.toString(), "--out", icns.toString()), 20_000)
        Files.deleteIfExists(pngFile)
        fun x(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        Files.writeString(
            contents.resolve("Info.plist"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>CFBundleName</key><string>${x(title)}</string>
              <key>CFBundleDisplayName</key><string>${x(title)}</string>
              <key>CFBundleIdentifier</key><string>dev.supermux.applet.${id(hostId, applet.name)}</string>
              <key>CFBundleExecutable</key><string>applet</string>
              <key>CFBundleIconFile</key><string>applet</string>
              <key>CFBundlePackageType</key><string>APPL</string>
              <key>CFBundleShortVersionString</key><string>1.0</string>
              <key>LSUIElement</key><true/>
            </dict>
            </plist>
            """.trimIndent() + "\n",
        )
        // Ad-hoc signed here, on his own Mac: Gatekeeper lets a locally made app run, and Spotlight indexes it.
        val sign = sys.runResult(listOf("/usr/bin/codesign", "--force", "--deep", "-s", "-", app.toString()), 30_000)
        if (sign.exit != 0) {
            return localized(Str.applets_install_failed) + " (codesign: " + (sign.err + sign.out).trim().take(160) + ")"
        }
        sys.runResult(listOf("/usr/bin/touch", app.toString()), 5_000)
        return null
    }

    // ── Windows ──────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun installWindows(hostId: String, applet: AppletDto, title: String, png: ByteArray): String? {
        val exe = appPath ?: return localized(Str.applets_install_dev_build)
        val link = windowsLink(applet)
        Files.createDirectories(link.parent)
        val ico = sys.localAppData.resolve("supermux/applet-icons/${id(hostId, applet.name)}.ico")
        Files.createDirectories(ico.parent)
        Files.write(ico, pngToIco(png))
        fun ps(s: String) = s.replace("'", "''")
        val script = "\$s = (New-Object -ComObject WScript.Shell).CreateShortcut('${ps(link.toString())}'); " +
            "\$s.TargetPath = '${ps(exe)}'; \$s.Arguments = '--applet=${ps(appletUri(hostId, applet.name))}'; " +
            "\$s.IconLocation = '${ps(ico.toString())}'; \$s.Description = '${ps(title)}'; \$s.Save()"
        val p = sys.runResult(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script), 30_000)
        if (p.exit != 0) {
            return localized(Str.applets_install_failed) + " (" + (p.err + p.out).trim().take(160) + ")"
        }
        return null
    }

    /** An .ico holding one 256 px PNG (Vista and later read PNG entries). */
    private fun pngToIco(png: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun le16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        fun le32(v: Int) { le16(v and 0xffff); le16((v ushr 16) and 0xffff) }
        le16(0); le16(1); le16(1) // reserved, type icon, one image
        out.write(0); out.write(0) // 256 × 256
        out.write(0); out.write(0) // no palette, reserved
        le16(1); le16(32) // planes, bits per pixel
        le32(png.size); le32(6 + 16)
        out.write(png)
        return out.toByteArray()
    }

    // ── The icon ────────────────────────────────────────────────────────────────────────────────────────────────────

    /** The applet's icon as a 256 px PNG: its own image (the broker already rasterizes SVGs), or the lettered tile. */
    private fun iconPng(bytes: ByteArray?, title: String): ByteArray {
        val size = 256
        val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val src = bytes?.let { runCatching { ImageIO.read(ByteArrayInputStream(it)) }.getOrNull() }
        // The rounded square every launcher shows (macOS masks nothing itself), with a margin as app icons have.
        val inset = if (os == DesktopOs.MACOS) 20 else 0
        val box = size - 2 * inset
        val radius = (box * 0.45).toInt()
        g.clip = java.awt.geom.RoundRectangle2D.Float(inset.toFloat(), inset.toFloat(), box.toFloat(), box.toFloat(), radius.toFloat(), radius.toFloat())
        if (src != null) {
            g.drawImage(src, inset, inset, box, box, null)
        } else {
            val hue = ((title.hashCode() ushr 1) % 360) / 360f
            g.color = hslColor(hue, 0.45f, 0.42f)
            g.fillRect(inset, inset, box, box)
            g.color = Color.WHITE
            g.font = Font(Font.SANS_SERIF, Font.BOLD, (box * 0.46).toInt())
            val letter = title.first().uppercaseChar().toString()
            val fm = g.fontMetrics
            g.drawString(letter, inset + (box - fm.stringWidth(letter)) / 2, inset + (box - fm.height) / 2 + fm.ascent)
        }
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** The project tiles' colour, hsl(h, s, l), as AWT draws it. */
    private fun hslColor(h: Float, s: Float, l: Float): Color {
        val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun hue(t0: Float): Float {
            var t = t0
            if (t < 0) t += 1f
            if (t > 1) t -= 1f
            return when {
                t < 1f / 6 -> p + (q - p) * 6 * t
                t < 1f / 2 -> q
                t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                else -> p
            }
        }
        return Color(hue(h + 1f / 3), hue(h), hue(h - 1f / 3))
    }
}
