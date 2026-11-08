package dev.supermux.webview.sample.windows

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The checks' hands on Windows: the sample's test DLL (native/windows/testing), never the library's. Loaded from the
 * sample's jar the first time it is needed, into `%TEMP%\wvc-testing\<sha256>\`: a DLL can't be deleted while it is
 * loaded, so a temporary file per run would stay behind after every run; this one is written once and reused.
 */
object WindowsInput {
    private const val RESOURCE = "/dev/supermux/webview/sample/windows/webview_windows_testing.dll"

    private val loaded: Boolean by lazy {
        val bytes = WindowsInput::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: error("the sample has no Windows test DLL: winvm.sh build, then stage-sample")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val dir = File(System.getProperty("java.io.tmpdir"), "wvc-testing/$sha").apply { mkdirs() }
        val file = File(dir, "webview_windows_testing.dll")
        if (!file.isFile || file.length() != bytes.size.toLong()) {
            // Another process of the sample may write it at the same time: each writes its own copy and renames it in.
            val fresh = File.createTempFile("testing", ".part", dir)
            fresh.writeBytes(bytes)
            runCatching { Files.move(fresh.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE) }
            fresh.delete()
        }
        System.load(file.absolutePath)
        true
    }

    /** Loads the DLL, or fails with what is missing. */
    fun ready() = check(loaded)

    @JvmStatic external fun sendText(text: String)
    @JvmStatic external fun sendScan(scan: Int, down: Boolean, extended: Boolean)
    @JvmStatic external fun sendVk(vk: Int, down: Boolean)
    @JvmStatic external fun focusInfo(): String
    @JvmStatic external fun cursorInfo(): String
    @JvmStatic external fun topLevelWindows(pid: Long): String
    @JvmStatic external fun setInputLanguage(klid: String): String
    @JvmStatic external fun imeControl(open: Int, conversion: Int): String

    /** `ctrl+a`, `shift+Tab`, `Return`, `Down`: xdotool's names, pressed in order and released in reverse. */
    fun keys(combo: String) {
        ready()
        val vks = combo.split('+').map { VK[it.lowercase()] ?: (it.uppercase().singleOrNull()?.code ?: error("no key $it")) }
        vks.forEach { sendVk(it, true) }
        vks.reversed().forEach { sendVk(it, false) }
    }

    private val VK = mapOf(
        "ctrl" to 0x11, "shift" to 0x10, "alt" to 0x12, "tab" to 0x09, "return" to 0x0D, "escape" to 0x1B,
        "backspace" to 0x08, "delete" to 0x2E, "home" to 0x24, "end" to 0x23,
        "up" to 0x26, "down" to 0x28, "left" to 0x25, "right" to 0x27, "space" to 0x20, "f5" to 0x74, "f12" to 0x7B,
    )
}
