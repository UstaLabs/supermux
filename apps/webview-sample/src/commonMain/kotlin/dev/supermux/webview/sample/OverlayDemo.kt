package dev.supermux.webview.sample

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.webview.Activation
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebView
import dev.supermux.webview.rememberWebViewState
import dev.supermux.webview.webViewOverlay
import dev.supermux.webview.webViewScrim

/** The menu's items. */
internal val MENU_ITEMS = listOf("Copy link", "Open in browser", "Reload", "Inspect")

/** The demo's hint on the fake engine, which outlines the cut-outs it is given. */
internal const val FAKE_ENGINE_HINT: String = "The fake engine outlines in white each cut-out it is given."

/** The hint where the page really sits above Compose, as an iframe does on the web. */
internal const val CUTOUT_HINT: String =
    "The page sits above Compose here: the menu, the dialog and these controls are cut out of it, and it dims under the dialog's scrim."

/** The hint where Compose draws over the page, as over Android's WebView: nothing needs cutting out. */
internal const val NATIVE_VIEW_HINT: String =
    "Compose draws over the page here: the menu, the dialog and these controls cover it and take their own taps, with nothing cut out."

/** What the demo's modal draws over the page, and what the page dims by where it sits above Compose. */
private val SCRIM = Color.Black.copy(alpha = 0.32f)

/**
 * A page under Compose overlays (spec §6): a menu and a dialog marked with `webViewOverlay` in the shapes they are
 * drawn with, and the dialog's scrim marked with `webViewScrim`. A page that sits above Compose (web, macOS) cuts those
 * shapes out of itself and dims under the scrim. The fake engine draws in white the outline of each cut-out it is
 * given, so they can be seen here.
 *
 * The dialog follows the README's recipe. Material's dialog draws its scrim in its own layer, where no modifier
 * reaches it, so the dialog's own scrim is off, and its content, which fills the window, draws one behind the dialog's
 * surface, marked.
 */
@Composable
fun OverlayDemo(modifier: Modifier = Modifier, hint: String = FAKE_ENGINE_HINT) {
    val state = rememberWebViewState(OverlayPageContent)
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        // A pane, not a list item: live whenever it shows.
        WebView(state, Modifier.fillMaxSize().padding(16.dp).testTag(SampleTags.OverlayPage), activation = Activation.Eager)
        Row(Modifier.align(Alignment.TopEnd).padding(28.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                Button(
                    onClick = { menu = true },
                    modifier = Modifier.testTag(SampleTags.OpenMenu).webViewOverlay(ButtonDefaults.shape),
                ) { Text("Menu") }
                val shape = MenuDefaults.shape
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    modifier = Modifier.webViewOverlay(shape),
                    shape = shape,
                ) {
                    MENU_ITEMS.forEach { item -> DropdownMenuItem(text = { Text(item) }, onClick = { menu = false }) }
                }
            }
            Button(
                onClick = { dialog = true },
                modifier = Modifier.testTag(SampleTags.OpenDialog).webViewOverlay(ButtonDefaults.shape),
            ) { Text("Dialog") }
        }
        Text(
            hint,
            Modifier
                .align(Alignment.BottomStart)
                .padding(28.dp)
                .webViewOverlay(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
        )
        if (dialog) {
            val shape = AlertDialogDefaults.shape
            UnscrimmedDialog(onDismissRequest = { dialog = false }) {
                Box(Modifier.fillMaxSize().background(SCRIM).webViewScrim(SCRIM), contentAlignment = Alignment.Center) {
                    Surface(
                        Modifier.widthIn(max = 400.dp).padding(24.dp).webViewOverlay(shape),
                        shape = shape,
                        color = AlertDialogDefaults.containerColor,
                        tonalElevation = AlertDialogDefaults.TonalElevation,
                    ) {
                        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("A Compose dialog", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                "It draws over the page and takes the clicks; the page dims under its scrim.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(
                                onClick = { dialog = false },
                                modifier = Modifier.align(Alignment.End).testTag(SampleTags.CloseDialog),
                            ) { Text("Close") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A dialog whose content may fill the window, with no scrim of its own: the content draws one, marked. Each platform
 * turns the dialog's scrim off its own way (Compose Multiplatform's `scrimColor`, Android's window dim).
 */
@Composable
internal expect fun UnscrimmedDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit)

/** The overlay demo's page. */
internal val OverlayPageContent: WebContent.Html = WebContent.Html(OVERLAY_PAGE)

private const val OVERLAY_PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width"><title>Under the overlays</title>
<style>:root{color-scheme:light dark;font:16px/1.5 system-ui,sans-serif}body{margin:0 auto;padding:24px;max-width:720px}
a.out{display:block;margin:-24px -24px 16px;padding:14px 24px;background:rgba(79,124,255,.12)}</style>
</head><body><a class="out" href="https://example.com/">Open example.com: a link at the top of the page</a>
<h1>A page under Compose overlays</h1>
<p>Open the menu or the dialog above. Where the page sits above Compose, as an iframe on the web does, it cuts the
menu and the dialog out of itself, so they show and take the clicks, and it dims under the dialog's scrim.</p>
<p><input aria-label="Type here" placeholder="Type here" style="font:inherit;width:100%;max-width:320px"></p></body></html>"""
