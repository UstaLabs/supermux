package dev.supermux.webview.sample

import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.MimeTypeMap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.LaunchedEffect
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.sample.chat.ChatRun
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * The Android sample (dev.supermux.webview.sample): the chat demo, the perf lab, the overlay demo and the checks page on
 * the system WebView, edge to edge. It opens on the chat, which draws its bars under the system's and lifts its composer
 * over the keyboard; the lab's screens are padded for the status bar and the sides only, so the keyboard covers their
 * pages and WebView shrinks its visual viewport itself.
 * - `--ez fake true`: the library's fake engine instead, to compare.
 * - `--es run scroll|reading|fling`: opens the perf lab and starts that run 3 s after launch. Its `perf-lab:` line goes to
 *   logcat (tag System.out), where device-checks/android-sample.sh reads it.
 * - `--es chatrun read|scroll|fling`: starts that run of the chat 3 s after launch; its `chat-perf:` line goes there too.
 */
class MainActivity : ComponentActivity() {
    private var picking: CompletableDeferred<List<Uri>>? = null
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        picking?.complete(uris)
        picking = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val fake = intent?.getBooleanExtra("fake", false) == true
        val run = intent?.getStringExtra("run")?.let { name -> LabRun.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val chatRun = intent?.getStringExtra("chatrun")?.let { name -> ChatRun.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        // A scripted run takes minutes and nobody touches the phone meanwhile: the screen stays on while this window
        // shows, so the screen timeout doesn't stop the measurement. A window flag, gone with the window.
        if (run != null || chatRun != null) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val lab = androidPerfLab(fake)
        setContent {
            SampleApp(
                lab,
                engineLabel = if (fake) FAKE_ENGINE_LABEL else ANDROID_ENGINE_LABEL,
                initialScreen = if (run != null) Screen.PerfLab else Screen.Chat,
                pickFiles = ::pickFiles,
                safeArea = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                chatRun = chatRun,
            )
            if (run != null) {
                LaunchedEffect(run) {
                    delay(3_000)
                    lab.start(run)
                }
            }
        }
    }

    /**
     * The system document picker, for the checks page's file input. Null when nothing was picked. It takes MIME types
     * only: an input's extensions (`accept=".pdf"`) go to it as theirs ([pickerTypes]).
     */
    private suspend fun pickFiles(request: FileChooserRequest): List<String>? {
        val waiting = CompletableDeferred<List<Uri>>()
        picking?.complete(emptyList())
        picking = waiting
        val types = pickerTypes(request.acceptTypes) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        picker.launch(types.toTypedArray())
        return waiting.await().map(Uri::toString).ifEmpty { null }
    }
}
