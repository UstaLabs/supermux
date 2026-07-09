package dev.supermux.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "supermux",
        state = rememberWindowState(width = 1440.dp, height = 900.dp),
    ) {
        MaterialTheme { Surface { Text("supermux desktop — M1 scaffold") } }
    }
}
