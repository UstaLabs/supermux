package dev.supermux.webview.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.awt.ComposeWindow
import dev.supermux.webview.testing.MacEngineTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

/**
 * An evidence screenshot of [window] (plan Task 18), taken from the SSH session, which may record the screen where java
 * may not (M9): after [settleMillis] for the pages to go live, the app writes its window's number to `<dir>/<name>.req`,
 * waits up to 15 s for `mac-shoot-loop.sh` to answer with `<dir>/<name>.png`, prints `SHOT`, and exits.
 */
@Composable
fun ShotRequest(window: ComposeWindow, dir: File, name: String, settleMillis: Long = 8_000) {
    LaunchedEffect(Unit) {
        delay(settleMillis)
        val number = MacEngineTesting.windowNumber(window)
        val png = File(dir, "$name.png")
        val taken = withContext(Dispatchers.IO) {
            dir.mkdirs()
            png.delete()
            File(dir, "$name.req").writeText(number.toString())
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline && !(png.isFile && png.length() > 0)) Thread.sleep(50)
            png.isFile && png.length() > 0
        }
        println(if (taken) "SHOT $name ${png.path}" else "SHOT-MISSING $name")
        exitProcess(if (taken) 0 else 1)
    }
}
