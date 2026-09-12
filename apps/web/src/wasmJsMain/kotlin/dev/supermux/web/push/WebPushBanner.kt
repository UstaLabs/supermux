package dev.supermux.web.push

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.browser.localStorage
import org.w3c.dom.get
import org.w3c.dom.set
import kotlinx.coroutines.launch

/** The Vue app's key, kept byte-identical: a browser that dismissed the old banner stays dismissed. */
private const val BANNER_DISMISSED_KEY = "cmux:push:banner-dismissed"

/**
 * The one-line "turn notifications on" strip, shown at most once per browser profile.
 *
 * Why a banner at all, rather than asking on load: `Notification.requestPermission()` must be
 * driven by a user gesture to be worth spending — Chrome permanently blocks the origin after an
 * unprompted refusal, and there is no second prompt. So the app asks for a CLICK first, and the
 * real permission dialog only ever opens from "Enable".
 *
 * Three conditions gate it, and all three are cheap reads done on every composition so the strip
 * disappears the instant the user answers:
 *  - the browser HAS Web Push (no `Notification` → nothing to enable),
 *  - permission is still `"default"` (granted → already on, denied → the prompt is spent),
 *  - "Not now" was never clicked on this profile.
 *
 * Rendered by `Main.kt` as a top OVERLAY, not in the layout flow: the shell's own top bar is
 * position-sensitive (the terminal measures against it) and a strip that reflows the app on load
 * would jump every session list.
 */
@Composable
fun WebPushBanner(registrar: WebPushRegistrar, modifier: Modifier = Modifier) {
    val permission by registrar.permission.collectAsState()
    var dismissed by remember { mutableStateOf(localStorage[BANNER_DISMISSED_KEY] == "1") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (dismissed || !registrar.supported || permission != "default") return

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Enable notifications for agent replies", style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    // ONE coroutine for both halves: `requestPermissionNow()` must be awaited
                    // before `register()` reads `Notification.permission`, and two launches would
                    // race the grant.
                    scope.launch {
                        if (registrar.requestPermissionNow() == "granted") registrar.register()
                        busy = false
                    }
                },
            ) { Text("Enable") }
            TextButton(
                enabled = !busy,
                onClick = {
                    dismissed = true
                    localStorage[BANNER_DISMISSED_KEY] = "1"
                },
            ) { Text("Not now") }
        }
    }
}
