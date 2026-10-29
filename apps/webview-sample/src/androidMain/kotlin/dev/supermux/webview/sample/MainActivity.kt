package dev.supermux.webview.sample

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import dev.supermux.webview.FileChooserRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * The Android sample (dev.supermux.webview.sample): the perf lab, the overlay demo and the checks page on the system
 * WebView, edge to edge, padded for the status bar and the sides only, so the keyboard covers the page and WebView
 * shrinks its visual viewport itself.
 * - `--ez fake true`: the library's fake engine instead, to compare.
 * - `--es run scroll|reading|fling`: starts that perf-lab run 3 s after launch. Its `perf-lab:` line goes to logcat (tag
 *   System.out), where device-checks/android-sample.sh reads it.
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
        val lab = androidPerfLab(fake)
        setContent {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                SampleApp(lab, engineLabel = if (fake) FAKE_ENGINE_LABEL else ANDROID_ENGINE_LABEL, pickFiles = ::pickFiles)
            }
            if (run != null) {
                LaunchedEffect(run) {
                    delay(3_000)
                    lab.start(run)
                }
            }
        }
    }

    /** The system document picker, for the checks page's file input. Null when nothing was picked. */
    private suspend fun pickFiles(request: FileChooserRequest): List<String>? {
        val waiting = CompletableDeferred<List<Uri>>()
        picking?.complete(emptyList())
        picking = waiting
        picker.launch(request.acceptTypes.ifEmpty { listOf("*/*") }.toTypedArray())
        return waiting.await().map(Uri::toString).ifEmpty { null }
    }
}
