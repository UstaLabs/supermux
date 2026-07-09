package dev.supermux.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.desktop.theme.AppearanceMode
import dev.supermux.desktop.theme.GeistFontFamily
import dev.supermux.desktop.theme.SupermuxTheme

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "supermux",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        // TODO(M4): drive from Settings/Appearance instead of a hardcoded default.
        SupermuxTheme(appearance = AppearanceMode.DARK) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Text(
                    "supermux desktop — M1 scaffold",
                    fontFamily = GeistFontFamily,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
    }
}
