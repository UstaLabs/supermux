// OpenCode's provider page (#97 spec §2.2, §11): no sign-in of its own and no "Add a login" — its subscription
// (OpenCode Go or Zen, approved at opencode.ai with a code, #109), the OpenCode key (Zen + Go), and the model providers
// inside OpenCode (today's "Connect a provider": keys and browser sign-ins), under today's rules — `anthropic` and
// `openai` stay hidden, and the key row shows only while OpenCode itself is not among the providers.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.retain.rememberRetainedScope
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.kit.KitDot
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitHairline
import dev.supermux.ui.settings.kit.KitItem
import dev.supermux.ui.settings.kit.KitItemList
import dev.supermux.ui.settings.kit.KitItemStatus
import dev.supermux.ui.settings.kit.KitMark
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitRow
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitTextField
import dev.supermux.ui.settings.prettyProviderName
import kotlinx.coroutines.launch

/** The subscription's sign-in (the machine's OpenCode login, so every OpenCode chat gets those models), then the rest. */
@Composable
internal fun OpenCodeSection(p: ProviderState, agents: AgentSettingsActions) {
    KitItemList {
        KitItem(
            "OpenCode Go or Zen",
            subtitle = "Approve this machine at opencode.ai with a code.",
            mark = KitMark.Letters("O"),
        ) {
            if (!p.loginActive) {
                KitGhostButton("Sign in with OpenCode", onClick = p::startLogin, modifier = Modifier.testTag("agent_login_start_opencode"))
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LoginLines(p, agents)
    }
    OpenCodeProvidersSection(agents.openCodeProviders, agents.openCodeSetKey, agents.openCodeStartOAuth, agents.openCodeFinishOAuth)
}

@Composable
private fun OpenCodeProvidersSection(
    load: suspend () -> List<OpenCodeProvider>,
    setKey: suspend (providerId: String, key: String) -> Boolean,
    startOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    finishOAuth: suspend (providerId: String, method: Int, code: String) -> Boolean,
) {
    var providers by remember { mutableStateOf<List<OpenCodeProvider>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(reloadKey) {
        loading = true
        loadFailed = false
        val hidden = setOf("anthropic", "openai")
        // The callers degrade to an empty list, so only a throw is a failure; the key row shows either way.
        providers = runCatching { load().filter { it.id !in hidden } }
            .onFailure { loadFailed = true }
            .getOrDefault(emptyList())
        loading = false
    }

    Column(Modifier.fillMaxWidth().testTag("opencode_providers_section")) {
        if (!(loading && providers.isEmpty()) && providers.none { it.id == "opencode" }) {
            OpenCodeZenKeyRow(setKey = setKey, onChanged = { reloadKey++ })
        }
        KitSectionHead(
            "Model providers in OpenCode",
            noteContent = { KitTextAction("Refresh", { if (!loading) reloadKey++ }, enabled = !loading) },
        )
        KitNote("Free models work out of the box. Connect a subscription for more.")
        when {
            loading && providers.isEmpty() -> KitNote("Loading…")
            else -> {
                if (providers.isNotEmpty()) KitHairline(Modifier.padding(top = 10.dp))
                providers.forEach { provider ->
                    OpenCodeProviderRow(provider, setKey, startOAuth, finishOAuth, onChanged = { reloadKey++ })
                }
                if (loadFailed) {
                    WarnLine("Couldn't load the providers.", "opencode_providers_error")
                } else if (providers.isEmpty()) {
                    KitNote("No other providers to connect.")
                }
            }
        }
    }
}

@Composable
private fun OpenCodeZenKeyRow(setKey: suspend (String, String) -> Boolean, onChanged: () -> Unit) {
    val platform = LocalPlatform.current
    // Kept for the page on display, with its save (#89).
    val form = rememberRetainedOnPage("agents:opencode-zen") { SecretForm() }
    val saves = rememberRetainedScope()

    fun submit() {
        val k = form.value.trim()
        if (k.isEmpty() || form.saving) return
        form.save(saves, "Couldn't save the key — check the connection and try again.") { setKey("opencode", k) }
    }
    LaunchedEffect(form, form.saved) {
        if (!form.saved) return@LaunchedEffect
        form.saved = false
        onChanged()
    }

    Column(Modifier.fillMaxWidth().testTag("opencode_zen_key_row")) {
        KitRow(
            "OpenCode key (Zen + Go)",
            description = "For OpenCode's own models, on this machine's OpenCode.",
            divider = false,
        ) {
            KitTextAction("Get a key", { platform.openUrl("https://opencode.ai/auth") })
        }
        KeyField(
            value = form.value,
            onValueChange = {
                form.value = it
                form.error = null
            },
            placeholder = "OpenCode key",
            saving = form.saving,
            onSubmit = ::submit,
            fieldTag = "opencode_zen_key_field",
            saveTag = "opencode_zen_key_save",
        )
        form.error?.let { WarnLine(it, "opencode_zen_key_error") }
        KitHairline(Modifier.padding(top = 12.dp))
    }
}

/** A secret field and its Save, on one line. */
@Composable
private fun KeyField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    saving: Boolean,
    onSubmit: () -> Unit,
    fieldTag: String,
    saveTag: String,
) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KitTextField(value, onValueChange, Modifier.weight(1f), placeholder = placeholder, secret = true, onSubmit = onSubmit, fieldTag = fieldTag)
        KitGhostButton(
            if (saving) "Saving…" else "Save",
            onClick = onSubmit,
            enabled = value.isNotBlank() && !saving,
            modifier = Modifier.testTag(saveTag),
        )
    }
}

@Composable
private fun OpenCodeProviderRow(
    provider: OpenCodeProvider,
    setKey: suspend (providerId: String, key: String) -> Boolean,
    startOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    finishOAuth: suspend (providerId: String, method: Int, code: String) -> Boolean,
    onChanged: () -> Unit,
) {
    val platform = LocalPlatform.current
    // The browser sign-in's "paste the code" step, a key being pasted, and the calls behind them, kept for the page on
    // display (#89): the code only finishes the sign-in it was started for. The calls run on the fleet's scope and
    // land here; the page on display opens the browser and reloads.
    val form = rememberRetainedOnPage("agents:opencode:${provider.id}") { OpenCodeProviderForm() }
    val scope = rememberRetainedScope()
    val oauthMethod = provider.methods.firstOrNull { it.type == "oauth" }
    val apiMethod = provider.methods.firstOrNull { it.type == "api" }

    fun submitKey() {
        val k = form.key.value.trim()
        if (k.isEmpty() || form.key.saving) return
        form.key.save(scope, "Couldn't save the key — check the connection and try again.") { setKey(provider.id, k) }
    }

    fun finishOauth() {
        val c = form.oauthCode.trim()
        val mi = form.oauthMethodIndex
        if (c.isEmpty() || mi == null || form.finishing) return
        form.finishing = true
        form.oauthError = null
        scope.launch {
            val ok = finishOAuth(provider.id, mi, c)
            form.finishing = false
            if (ok) {
                form.oauthUrl = null
                form.oauthMethodIndex = null
                form.oauthCode = ""
                form.authorized = true
            } else {
                form.oauthError = "Couldn't finish the sign-in — check the code and try again."
            }
        }
    }
    LaunchedEffect(form, form.key.saved, form.authorized) {
        if (!form.key.saved && !form.authorized) return@LaunchedEffect
        form.key.saved = false
        form.authorized = false
        onChanged()
    }
    LaunchedEffect(form, form.openUrl) {
        val url = form.openUrl ?: return@LaunchedEffect
        form.openUrl = null
        platform.openUrl(url)
    }

    Column(Modifier.fillMaxWidth().testTag("opencode_provider_${provider.id}")) {
        KitRow(prettyProviderName(provider.id), divider = false) {
            if (provider.configured) KitItemStatus("Connected", dot = KitDot.Ok)
        }
        val url = form.oauthUrl
        if (url != null) {
            KitNote("A browser tab opened. Approve there, then paste the code:")
            KitTextAction("Reopen sign-in", { platform.openUrl(url) })
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KitTextField(
                    form.oauthCode,
                    {
                        form.oauthCode = it
                        form.oauthError = null
                    },
                    Modifier.weight(1f),
                    placeholder = "Paste the code",
                    mono = true,
                    onSubmit = ::finishOauth,
                    fieldTag = "opencode_oauth_code_${provider.id}",
                )
                KitGhostButton(
                    if (form.finishing) "Finishing…" else "Finish",
                    onClick = ::finishOauth,
                    enabled = form.oauthCode.isNotBlank() && !form.finishing,
                    modifier = Modifier.testTag("opencode_oauth_finish_${provider.id}"),
                )
            }
            form.oauthError?.let { WarnLine(it, "opencode_oauth_error_${provider.id}") }
        } else {
            if (oauthMethod != null) {
                KitGhostButton(
                    "Login via browser",
                    onClick = {
                        form.oauthCode = ""
                        form.oauthError = null
                        scope.launch {
                            val started = startOAuth(provider.id, oauthMethod.index)?.url
                            if (!started.isNullOrEmpty()) {
                                form.oauthMethodIndex = oauthMethod.index
                                form.oauthUrl = started
                                form.openUrl = started
                            } else {
                                form.oauthError = "Couldn't start the browser sign-in."
                            }
                        }
                    },
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (apiMethod != null) {
                KeyField(
                    value = form.key.value,
                    onValueChange = {
                        form.key.value = it
                        form.key.error = null
                    },
                    placeholder = apiMethod.label.ifEmpty { "API key" },
                    saving = form.key.saving,
                    onSubmit = ::submitKey,
                    fieldTag = "opencode_provider_key_${provider.id}",
                    saveTag = "opencode_provider_save_${provider.id}",
                )
                form.key.error?.let { WarnLine(it, "opencode_provider_error_${provider.id}") }
            }
            form.oauthError?.let { WarnLine(it) }
        }
        KitHairline(Modifier.padding(top = 8.dp))
    }
}

/** One OpenCode provider's sign-in, kept for the page on display (`rememberRetainedOnPage`, #89). */
private class OpenCodeProviderForm {
    val key = SecretForm()
    /** The browser sign-in under way: its link, and the method it was started for. */
    var oauthUrl by mutableStateOf<String?>(null)
    var oauthMethodIndex by mutableStateOf<Int?>(null)
    var oauthCode by mutableStateOf("")
    var finishing by mutableStateOf(false)
    var oauthError by mutableStateOf<String?>(null)
    /** A link just minted, for the page on display to open in the browser. */
    var openUrl by mutableStateOf<String?>(null)
    /** The code finished the sign-in: the page on display reloads the providers. */
    var authorized by mutableStateOf(false)
}
