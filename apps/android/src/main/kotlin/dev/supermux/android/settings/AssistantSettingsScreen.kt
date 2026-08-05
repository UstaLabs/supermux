package dev.supermux.android.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ─── Assistant settings (paName + soul.md) ──────────────────────────────────────
//
// Parity with iOS AssistantSettingsView: a PA-name field + a tall monospaced soul.md
// editor + a Save button with idle/saving/saved states. Both fields persist on one tap
// (saveConfig(paName) then putSoul) — putSoul's boolean gates the "Saved" badge.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsPage(
    onBack: () -> Unit,
    load: suspend () -> Pair<String, String>?,        // (paName, soul) or null
    save: suspend (paName: String, soul: String) -> Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var paName by remember { mutableStateOf("") }
    var soul by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A failed load must never present an editable soul.md with a working Save: an empty editor
    // over a failed fetch would overwrite the real file with "". null from load() = not loaded.
    var loadFailed by remember { mutableStateOf(false) }

    suspend fun runLoad() {
        loading = true
        loadFailed = false
        val pair = load()
        if (pair == null) {
            loadFailed = true
        } else {
            paName = pair.first
            soul = pair.second
        }
        loading = false
    }

    LaunchedEffect(Unit) { runLoad() }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Assistant", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary)
            }
        } else if (loadFailed) {
            // Error state, NOT an empty editor — Save is not composed here at all, so a failed
            // fetch cannot be turned into an overwrite of the real soul.md.
            Column(
                Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Couldn't load the assistant settings.", color = cs.error, fontSize = 14.sp)
                SettingsCaption("soul.md was not loaded, so it can't be saved from here yet.")
                Button(
                    onClick = { scope.launch { runLoad() } },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                ) { Text("Retry", color = cs.onPrimary) }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 1. PA name
                OutlinedTextField(
                    value = paName,
                    onValueChange = { paName = it },
                    label = { Text("PA name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    colors = settingsFieldColors(),
                )

                // 2. soul.md label + editor
                Text("soul.md", color = cs.onSurfaceVariant, fontSize = 13.sp)
                OutlinedTextField(
                    value = soul,
                    onValueChange = { soul = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp),
                    minLines = 12,
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    colors = settingsFieldColors(),
                )
                SettingsCaption("Personality, instructions, and persistent context prepended to every session.")

                // 3. error
                error?.let { Text(it, color = cs.error, fontSize = 12.sp) }

                // 4. Save (idle / saving / saved)
                Button(
                    onClick = {
                        scope.launch {
                            saving = true
                            saved = false
                            error = null
                            val ok = save(paName, soul)
                            saving = false
                            if (ok) {
                                saved = true
                                delay(2000)
                                saved = false
                            } else {
                                error = "Couldn't save soul.md — check connection and try again"
                            }
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                ) {
                    when {
                        saving -> {
                            CircularProgressIndicator(
                                color = cs.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Saving…", color = cs.onPrimary)
                        }
                        saved -> {
                            Icon(
                                painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = cs.onPrimary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Saved", color = cs.onPrimary)
                        }
                        else -> Text("Save", color = cs.onPrimary)
                    }
                }
            }
        }
    }
}
