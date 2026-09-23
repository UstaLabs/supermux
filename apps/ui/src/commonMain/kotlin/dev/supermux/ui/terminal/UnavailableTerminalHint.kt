package dev.supermux.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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

/** The node a failed engine LOAD draws, for tests. */
const val TERMINAL_LOAD_FAILED_TAG = "terminal_load_failed"

/**
 * The engine did not load — a DIFFERENT thing from "this host has no terminal", and saying the
 * latter was both wrong and unactionable.
 *
 * The case that makes it matter is the browser, and it needs no caching to happen: assets are
 * content-hashed and served `immutable`, so a TAB THAT HAS BEEN OPEN SINCE BEFORE A DEPLOY is
 * holding a bundle whose wasm URL the new build no longer serves. Nobody notices until the first
 * terminal is opened in that tab, because that is when the engine is first fetched — and it 404s.
 * (A browser that is offline, or an intermediary that ignores the entry points' `no-cache`, gets
 * there the same way.) The engine is fine, the app is fine, and the ONE thing the user can do
 * about it — reload onto the current build — is the one thing "This client has no terminal" does
 * not tell them. So the reason is shown verbatim (the loader's messages name the status, the ABI
 * or the URL) with the action under it.
 */
@Composable
fun TerminalLoadFailedHint(reason: String?, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxSize().background(cs.surfaceVariant.copy(alpha = 0.4f)).testTag(TERMINAL_LOAD_FAILED_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "The terminal engine didn't load",
                color = cs.onSurfaceVariant,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            if (!reason.isNullOrBlank()) {
                Text(
                    reason,
                    color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, start = 12.dp, end = 12.dp),
                )
            }
            Text(
                "Reload to pick up the current version",
                color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
