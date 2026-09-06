package dev.supermux.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp

/** Quiet degrade for a host with no terminal engine — never a crash (the `UnknownViewHint` rule). */
@Composable
fun UnavailableTerminalHint(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxSize().background(cs.surfaceVariant.copy(alpha = 0.4f)).testTag("terminal_unavailable"),
        contentAlignment = Alignment.Center,
    ) {
        Text("This client has no terminal", color = cs.onSurfaceVariant, fontSize = 13.sp)
    }
}
