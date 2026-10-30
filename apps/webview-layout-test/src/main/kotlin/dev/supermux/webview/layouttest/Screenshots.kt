package dev.supermux.webview.layouttest

import java.io.File
import java.util.concurrent.TimeUnit

/** Captures this app's window into [dir]. Blocking: call it off the EDT. */
class Screenshots(private val mode: ShotMode, private val dir: File) {
    init {
        dir.mkdirs()
    }

    /** The window [windowNumber] as dir/[name].png, or null if no capture was made. */
    fun capture(name: String, windowNumber: Long): File? {
        val file = File(dir, "$name.png")
        return when (mode) {
            ShotMode.Off -> null
            ShotMode.App -> {
                // A child of the app, so inside the GUI session; -l is the window by number, on any display.
                val process = ProcessBuilder("/usr/sbin/screencapture", "-x", "-o", "-l", windowNumber.toString(), file.path)
                    .redirectErrorStream(true)
                    .start()
                val finished = process.waitFor(10, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                file.takeIf { finished && process.exitValue() == 0 && it.length() > 0 }
            }
            ShotMode.External -> {
                // mac/shoot-loop.sh captures it and deletes the request.
                val request = File(dir, "$name.req")
                request.writeText(windowNumber.toString())
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline) {
                    if (!request.exists() && file.length() > 0) return file
                    Thread.sleep(20)
                }
                request.delete()
                null
            }
        }
    }
}
