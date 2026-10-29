package dev.supermux.webview.sample

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.webview.Activation
import dev.supermux.webview.DialogResult
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.WebClient
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebDialog
import dev.supermux.webview.WebView
import dev.supermux.webview.rememberWebViewState
import dev.supermux.webview.webViewOverlay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonPrimitive
import kotlin.coroutines.resume

/** A dialog the page asked for, and how to answer it. */
class AskedDialog(val dialog: WebDialog, val answer: (DialogResult) -> Unit)

/**
 * The checks page's client: the page's dialogs become Compose dialogs, and its file input opens the platform's picker
 * where the app passes one ([pickFiles]); everything else keeps the safe defaults.
 */
class ChecksClient(
    private val show: (AskedDialog) -> Unit,
    private val pickFiles: (suspend (FileChooserRequest) -> List<String>?)?,
) : WebClient {
    override suspend fun onDialog(dialog: WebDialog): DialogResult = suspendCancellableCoroutine { continuation ->
        show(AskedDialog(dialog) { result -> if (continuation.isActive) continuation.resume(result) })
    }

    override suspend fun onFileChooser(request: FileChooserRequest): List<String>? = pickFiles?.invoke(request)
}

/**
 * Spec §11's shared test page, which every platform's sample runs on its engine: navigation and history, the bridge
 * both ways, console, alert, confirm and prompt as Compose dialogs, the file picker, Turkish and Japanese typing, a
 * native select, a permission request, and scrolling; a Compose menu and dialog over the page, each taking its own
 * taps. The page is `Eager`, as panes are.
 */
@Composable
fun ChecksScreen(pickFiles: (suspend (FileChooserRequest) -> List<String>?)? = null) {
    val content = remember { WebContent.Html(checksPage()) }
    val state = rememberWebViewState(content)
    var asking by remember { mutableStateOf<AskedDialog?>(null) }
    var menu by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pings by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val client = remember(pickFiles) { ChecksClient(show = { asking = it }, pickFiles = pickFiles) }
    LaunchedEffect(state) { state.console.collect { status = "console ${it.level}: ${it.message}" } }
    DisposableEffect(state) {
        val stop = state.bridge.handle("hostTime") { JsonPrimitive("hello from Compose, ping ${pings}") }
        onDispose { stop() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = state.navigator::back, enabled = state.canGoBack) { Text("Back") }
            OutlinedButton(onClick = state.navigator::reload) { Text("Reload") }
            OutlinedButton(
                onClick = {
                    val ping = ++pings
                    scope.launch {
                        status = try {
                            "the page answered ${state.bridge.call("ping", JsonPrimitive(ping))}"
                        } catch (e: Exception) {
                            "the ping failed: ${e.message}"
                        }
                    }
                },
                modifier = Modifier.testTag(SampleTags.ChecksPing),
            ) { Text("Ping the page") }
            Box {
                OutlinedButton(onClick = { menu = true }, modifier = Modifier.testTag(SampleTags.ChecksMenu)) { Text("Menu") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.webViewOverlay()) {
                    DropdownMenuItem(
                        text = { Text("Post “hello” to the page") },
                        onClick = {
                            state.bridge.post("hello", JsonPrimitive("from the menu"))
                            menu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Show a Compose dialog") },
                        onClick = {
                            menu = false
                            asking = AskedDialog(WebDialog.Alert("A Compose dialog over the page")) {}
                        },
                    )
                }
            }
        }
        Text(
            "${state.title ?: "…"} · ${state.phase} · ${state.url.orEmpty()}",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
        Text(status, Modifier.padding(horizontal = 16.dp).testTag(SampleTags.ChecksStatus), style = MaterialTheme.typography.labelSmall, maxLines = 2)
        WebView(state, Modifier.weight(1f).fillMaxWidth().testTag(SampleTags.ChecksPage), client = client, activation = Activation.Eager)
    }
    asking?.let { asked ->
        CheckDialog(asked) { result ->
            asking = null
            asked.answer(result)
        }
    }
}

/** A page's dialog as a Compose one, over the page. */
@Composable
private fun CheckDialog(asked: AskedDialog, answer: (DialogResult) -> Unit) {
    val dialog = asked.dialog
    var text by remember(dialog) { mutableStateOf((dialog as? WebDialog.Prompt)?.defaultValue.orEmpty()) }
    AlertDialog(
        onDismissRequest = { answer(DialogResult.Dismissed) },
        title = {
            Text(
                when (dialog) {
                    is WebDialog.Alert -> "The page says"
                    is WebDialog.Confirm -> "The page asks"
                    is WebDialog.Prompt -> "The page asks for text"
                    is WebDialog.BeforeUnload -> "Leave the page?"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(dialog.message)
                if (dialog is WebDialog.Prompt) OutlinedTextField(text, { text = it }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { answer(if (dialog is WebDialog.Prompt) DialogResult.Text(text) else DialogResult.Confirmed) }) { Text("OK") }
        },
        dismissButton = if (dialog is WebDialog.Alert) null else ({ TextButton(onClick = { answer(DialogResult.Dismissed) }) { Text("Cancel") } }),
        modifier = Modifier.webViewOverlay(),
    )
}

/** The checks page: one section per check, and a log the page writes what happened to. */
fun checksPage(): String {
    val paragraphs = (1..30).joinToString("") { "<p>Paragraph $it. Scroll the page; past its end the app takes the drag.</p>" }
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Checks</title>
<style>:root{color-scheme:light dark;font:15px/1.45 system-ui,sans-serif}body{margin:0;padding:12px 14px}
section{margin:0 0 18px}h3{margin:0 0 6px;font-size:15px}button,input,select,textarea{font:inherit;margin:2px 0}
input[type=text],textarea{width:100%;box-sizing:border-box}#log{white-space:pre-wrap;font:12px monospace}</style></head>
<body>
<section><h3>Dialogs</h3>
<button onclick="alert('Hello from the page'); say('alert closed')">alert</button>
<button onclick="say('confirm: ' + confirm('Do you agree?'))">confirm</button>
<button onclick="say('prompt: ' + prompt('Your name?', 'Ahmet'))">prompt</button></section>
<section><h3>Files</h3><input type="file" multiple onchange="say('picked: ' + Array.from(this.files).map(function (f) { return f.name; }).join(', '))"></section>
<section><h3>Typing</h3><input type="text" placeholder="Türkçe: ç ş ğ ı ö ü İ"><textarea rows="3" placeholder="日本語を入力してください"></textarea></section>
<section><h3>The app</h3>
<button onclick="hostBridge.call('hostTime').then(function (t) { say('the app says ' + t); }, function (e) { say('the call failed: ' + e.message); })">Call the app</button>
<button onclick="console.log('a console line at ' + new Date().toISOString())">Log to the console</button></section>
<section><h3>Navigation</h3>
<a href="#scrolling">Same-document link</a> ·
<button onclick="history.pushState({}, '', '#pushed'); say('pushed')">pushState</button> ·
<a href="https://example.com/">example.com, which leaves the app</a> ·
<button onclick="window.open('https://example.com/')">window.open</button></section>
<section><h3>Native controls</h3><select><option>One</option><option>Two</option><option>Three</option></select>
<button onclick="navigator.mediaDevices.getUserMedia({audio: true}).then(function () { say('microphone granted'); }, function (e) { say('microphone: ' + e.name); })">Microphone</button></section>
<section><h3>Log</h3><div id="log"></div></section>
<section id="scrolling"><h3>Scrolling</h3>$paragraphs</section>
<script>
function say(text) { var log = document.getElementById('log'); log.textContent = text + '\n' + log.textContent; }
hostBridge.handle('ping', function (n) { say('the app pinged ' + n); return 'pong ' + n; });
hostBridge.on('hello', function (d) { say('the app posted ' + JSON.stringify(d)); });
</script></body></html>"""
}
