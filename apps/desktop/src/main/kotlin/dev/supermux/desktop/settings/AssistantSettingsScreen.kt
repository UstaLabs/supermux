// Identity settings: PA name + soul.md. Curator lives in [CuratorSettingsScreen] (own hub section).
package dev.supermux.desktop.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import dev.supermux.desktop.ui.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.theme.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val ERROR_AUTO_RETRY_MS = 3_000L

/** Load model for assistant identity (PA name + soul). */
internal sealed class AssistantLoadState {
    data object Loading : AssistantLoadState()
    data class Ready(val paName: String, val soul: String) : AssistantLoadState()
    data class Error(val message: String) : AssistantLoadState()
}

@Composable
fun AssistantSettingsScreen(
    /** Load (paName, soul) or null on failure. */
    assistantLoad: suspend () -> Pair<String, String>?,
    /**
     * Save paName + soul.
     * Returns null on success; a human-readable error when either write fails.
     */
    assistantSave: suspend (paName: String, soul: String) -> String?,
    /** Report whether the identity editor has unsaved edits (hub Escape/Back dirty guard). */
    onDirtyChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    var loadState by remember { mutableStateOf<AssistantLoadState>(AssistantLoadState.Loading) }
    var reloadKey by remember { mutableStateOf(0) }
    var loadedPaName by remember { mutableStateOf("") }
    var loadedSoul by remember { mutableStateOf("") }
    var paName by remember { mutableStateOf("") }
    var soul by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var showSaveConfirm by remember { mutableStateOf(false) }

    val isDirty = loadState is AssistantLoadState.Ready &&
        (paName != loadedPaName || soul != loadedSoul)
    LaunchedEffect(isDirty) { onDirtyChange(isDirty) }

    suspend fun loadIdentityOnce() {
        val previous = loadState
        if (previous !is AssistantLoadState.Ready) {
            loadState = AssistantLoadState.Loading
        }
        val pair = assistantLoad()
        if (pair == null) {
            loadState = AssistantLoadState.Error("Couldn't load assistant settings.")
        } else {
            loadedPaName = pair.first
            loadedSoul = pair.second
            paName = pair.first
            soul = pair.second
            saved = false
            saveError = null
            loadState = AssistantLoadState.Ready(pair.first, pair.second)
        }
    }

    LaunchedEffect(reloadKey) { loadIdentityOnce() }

    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is AssistantLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val pair = assistantLoad()
            if (pair != null) {
                loadedPaName = pair.first
                loadedSoul = pair.second
                paName = pair.first
                soul = pair.second
                loadState = AssistantLoadState.Ready(pair.first, pair.second)
                break
            }
        }
    }

    fun doSave() {
        scope.launch {
            saving = true
            saved = false
            saveError = null
            val err = assistantSave(paName, soul)
            saving = false
            if (err == null) {
                loadedPaName = paName
                loadedSoul = soul
                saved = true
                delay(2000)
                saved = false
            } else {
                saveError = err
            }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("assistant_settings_screen"),
        contentAlignment = Alignment.TopCenter,
    ) {
        when (val state = loadState) {
            is AssistantLoadState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        color = cs.primary,
                        modifier = Modifier.testTag("assistant_settings_loading"),
                    )
                }
            }
            is AssistantLoadState.Error -> {
                Column(
                    Modifier
                        .widthIn(max = SettingsDetailMaxWidth)
                        .fillMaxWidth()
                        .padding(Space.xl)
                        .testTag("assistant_settings_error"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    Text(
                        state.message,
                        color = cs.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { reloadKey++ },
                        modifier = Modifier.testTag("assistant_settings_retry"),
                    ) { Text("Retry") }
                }
            }
            is AssistantLoadState.Ready -> {
                Column(
                    Modifier
                        .widthIn(max = SettingsDetailMaxWidth)
                        .fillMaxWidth()
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(Space.lg)
                        .testTag("assistant_settings_content"),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    SettingsSectionHeader(title = "Identity")
                    OutlinedTextField(
                        value = paName,
                        onValueChange = { paName = it; saved = false; saveError = null },
                        label = { Text("PA name") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .submitOnEnter(paName.isNotBlank() && !saving) {
                                if (isDirty) showSaveConfirm = true
                            }
                            .testTag("assistant_pa_name"),
                        colors = settingsFieldColors(),
                    )
                    Text(
                        "soul.md",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutlinedTextField(
                        value = soul,
                        onValueChange = { soul = it; saved = false; saveError = null },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 320.dp)
                            .testTag("assistant_soul"),
                        minLines = 6,
                        maxLines = 16,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
                        colors = settingsFieldColors(),
                    )
                    SettingsCaption(
                        "Personality, instructions, and persistent context prepended to every session.",
                    )
                    saveError?.let {
                        Text(
                            it,
                            color = cs.error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("assistant_save_error"),
                        )
                    }
                    Button(
                        onClick = { showSaveConfirm = true },
                        enabled = !saving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("assistant_save"),
                        colors = ButtonDefaults.buttonColors(containerColor = cs.primary),
                    ) {
                        when {
                            saving -> {
                                CircularProgressIndicator(
                                    color = cs.onPrimary,
                                    strokeWidth = Stroke.md,
                                    modifier = Modifier.size(Space.lg),
                                )
                                Spacer(Modifier.width(Space.sm))
                                Text("Saving…", color = cs.onPrimary)
                            }
                            saved -> {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = cs.onPrimary,
                                    modifier = Modifier.size(Space.lg),
                                )
                                Spacer(Modifier.width(Space.sm))
                                Text("Saved", color = cs.onPrimary)
                            }
                            else -> Text("Save", color = cs.onPrimary)
                        }
                    }
                }
            }
        }
    }

    if (showSaveConfirm) {
        AlertDialog(
            onDismissRequest = { if (!saving) showSaveConfirm = false },
            title = { Text("Overwrite soul.md?") },
            text = {
                Text(
                    "This replaces the assistant identity on the broker " +
                        "(PA name and soul.md). Continue?",
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        showSaveConfirm = false
                        doSave()
                    },
                    modifier = Modifier.testTag("assistant_save_confirm"),
                ) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(
                    enabled = !saving,
                    onClick = { showSaveConfirm = false },
                    modifier = Modifier.testTag("assistant_save_cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("assistant_save_dialog"),
        )
    }
}
