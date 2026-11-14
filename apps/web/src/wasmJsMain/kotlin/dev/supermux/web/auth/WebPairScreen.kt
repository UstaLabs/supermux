package dev.supermux.web.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
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
 * A pasted link may carry ANY origin: the broker mints its pairing links on the relay/public
 * origin (`${relayUrl ?? publicUrl}/pair?t=…`), so a browser reached over the LAN or a tunnel is
 * handed a link whose host is not this one. Only the TOKEN is taken out of what was pasted and it
 * is always replayed against THIS origin — never a navigation to the foreign host, which would
 * hand the token to a broker this page is not talking to. A token that is not this broker's is
 * simply refused by `/pair`, which is the only place that can judge it.
 */
private val PAIR_LINK = Regex("""^(?:[a-zA-Z][\w+.-]*://[^/]+)?/pair\?(?:.*&)?t=([^&#]+)""")

/**
 * @param state the gate's verdict. [SessionState.Unpaired] is a broker that ANSWERED and refused —
 *   the pairing copy and the paste field belong there. [SessionState.Offline] is a broker that did
 *   not answer, where "this broker is already set up" would be a lie and a pairing link cannot
 *   help; that path gets its own headline and nothing but "Check again".
 * @param checking a `probe()` is in flight — the retry button says so and refuses a second one.
 */
@Composable
fun WebPairScreen(
    state: SessionState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    checking: Boolean = false,
) {
    val offline = state is SessionState.Offline
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun go() {
        val token = PAIR_LINK.find(link.trim())?.groupValues?.get(1)
        if (token == null) {
            error = localized(Str.host_app_web_not_pair_link)
            return
        }
        window.location.href = "${window.location.origin}/pair?t=$token"
    }
    Surface(modifier.fillMaxSize().testTag("web_pair_screen")) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.widthIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    localized(if (offline) Str.host_app_web_cant_reach else Str.host_app_web_pair_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    when (state) {
                        is SessionState.Offline -> localized(Str.host_app_web_offline_text, state.error)
                        is SessionState.Unpaired -> localized(Str.host_app_web_unpaired_text, state.reason)
                        is SessionState.Paired -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                // No paste field when the broker is unreachable: a pairing link cannot be checked,
                // let alone redeemed, by a broker that is not answering.
                if (!offline) {
                    OutlinedTextField(
                        value = link,
                        onValueChange = { link = it; error = null },
                        label = { Text(localized(Str.host_pairing_link)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go() }),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!offline) {
                        Button(onClick = ::go, enabled = link.isNotBlank()) { Text(localized(Str.host_app_web_open_link)) }
                    }
                    TextButton(onClick = onRetry, enabled = !checking) {
                        Text(localized(if (checking) Str.machine_checking else Str.host_app_web_check_again))
                    }
                }
            }
        }
    }
}
