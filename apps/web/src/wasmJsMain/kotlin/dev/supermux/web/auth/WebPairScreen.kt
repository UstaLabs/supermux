package dev.supermux.web.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.browser.window

/**
 * Shown when the cookie session is missing and the broker refused the secretless claim. A browser
 * pairs by NAVIGATING to a `/pair?t=<token>` link on this origin: that request is handled by the
 * BROKER, not by this app — it sets the cookie and redirects back to `/`, so the wasm bundle is
 * torn down and reloaded and never sees the token. That is why the only affordance here is "open
 * that link here" (a full navigation), and why `WebAppState.pendingPairLink` is NOT involved: it
 * exists for a pairing link pasted while the browser is ALREADY paired ("add another host", the
 * same door `Platform.pendingScans()` opens on iOS), not for this screen.
 *
 * Links for another origin are refused with a notice rather than silently sending the token
 * elsewhere.
 */
@Composable
fun WebPairScreen(reason: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun go() {
        val origin = window.location.origin
        val trimmed = link.trim()
        when {
            trimmed.startsWith("$origin/pair?t=") -> window.location.href = trimmed
            trimmed.startsWith("/pair?t=") -> window.location.href = origin + trimmed
            else -> error =
                "That link is for a different host. Open it in this browser, or paste the link this broker generated."
        }
    }
    Surface(modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp).widthIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Pair this browser", style = MaterialTheme.typography.headlineSmall)
            Text(
                "This broker is already set up ($reason). From a paired device open Settings › Devices, " +
                    "add a device, and open the link it shows here — or paste it below.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; error = null },
                label = { Text("Pairing link") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::go, enabled = link.isNotBlank()) { Text("Open link") }
                TextButton(onClick = onRetry) { Text("Check again") }
            }
        }
    }
}
