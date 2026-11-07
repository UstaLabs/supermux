package dev.supermux.webview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What a web view shows before it has a snapshot: a quiet card with a subtle frame, and the page title once known.
 *
 * It is opaque, in the theme [dark] says, because it covers a page that hasn't painted: an engine's unpainted surface
 * must never show through it.
 */
@Composable
public fun DefaultPlaceholder(state: WebViewState, dark: Boolean = isSystemInDarkTheme()) {
    val fill = if (dark) Color(0xFF202124) else Color(0xFFF1F3F4)
    val frame = if (dark) Color(0x24FFFFFF) else Color(0x1F000000)
    val text = if (dark) Color(0x99FFFFFF) else Color(0x99000000)
    Box(Modifier.fillMaxSize().background(fill).border(1.dp, frame), contentAlignment = Alignment.Center) {
        val title = state.title
        if (title != null) {
            BasicText(
                title,
                Modifier.padding(horizontal = 12.dp),
                style = TextStyle(color = text),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}
