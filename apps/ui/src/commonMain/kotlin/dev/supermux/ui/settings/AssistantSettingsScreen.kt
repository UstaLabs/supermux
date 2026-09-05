// The one Assistant-identity settings screen for both apps (cluster E3).
//
// Base = desktop's `settings/AssistantSettingsScreen.kt`: the Loading/Ready/Error load model (a
// failed fetch is NEVER an empty editor over a real soul.md), the dirty guard the hub reads, the
// overwrite confirm dialog, Enter-to-submit and every test tag. Android's page was the same screen
// with a Boolean save, no dirty guard, no confirm and an `R.drawable` check icon — it contributes
// the Compact branch (its own `TopAppBar` when the hub did not paint one) and the keyboard options
// that keep autocorrect out of a name field and out of soul.md.
//
// Curator lives in its own hub section (`CuratorSettingsScreen`), not here.
package dev.supermux.ui.settings

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.SettingsCaption
import dev.supermux.ui.widgets.SettingsDetailMaxWidth
import dev.supermux.ui.widgets.SettingsSectionHeader
import dev.supermux.ui.widgets.settingsFieldColors
import dev.supermux.ui.widgets.submitOnEnter
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

/**
 * Every broker call the Assistant screen makes, in one holder.
 *
 * Shapes are desktop's: a null load means "the call failed" (never "an empty soul.md"), and the
 * save returns the human-readable reason it failed, or null on success. Android's `FleetStore`
 * wrapper already had that shape; only its call site flattened it to a Boolean, which is why a
 * failed PUT of the PA name used to read "Couldn't save soul.md".
 */
@Immutable
class AssistantSettingsActions(
    /** Load (paName, soul); `null` = transport/decode failure. */
    val assistantLoad: suspend () -> Pair<String, String>? = { null },
    /** Save both; returns null on success, else a human-readable error. */
    val assistantSave: suspend (paName: String, soul: String) -> String? =
        { _, _ -> "Not connected." },
)

/** [AssistantSettingsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberAssistantSettingsActions(app: HostStore): AssistantSettingsActions = remember(app) {
    AssistantSettingsActions(
        assistantLoad = { app.assistantLoad() },
        assistantSave = { paName, soul -> app.assistantSave(paName, soul) },
    )
}

/** [AssistantSettingsActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberAssistantSettingsActions(fleet: FleetStore): AssistantSettingsActions = remember(fleet) {
    AssistantSettingsActions(
        assistantLoad = { fleet.assistantLoad() },
        assistantSave = { paName, soul -> fleet.assistantSave(paName, soul) },
    )
}

/**
 * Assistant identity: PA name + soul.md, saved together behind an overwrite confirm.
 *
 * @param onDirtyChange the hub's dirty guard — while the editor differs from what was loaded, a
 *   section switch or a close asks before discarding.
 * @param onBack leave the screen; only reachable from the Compact top bar this screen paints for
 *   itself (pass the hub's `SettingsSlotScope.onClose`).
 * @param topBarShown the hub already painted a `TopAppBar` for this detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantSettingsScreen(
    actions: AssistantSettingsActions,
    modifier: Modifier = Modifier,
    onDirtyChange: (Boolean) -> Unit = {},
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    if (compact && !topBarShown) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Assistant", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("assistant_settings_back"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = cs.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = cs.surfaceContainerHigh,
                    ),
                )
            },
            containerColor = cs.background,
        ) { padding ->
            AssistantSettingsBody(actions, modifier.padding(padding), onDirtyChange)
        }
    } else {
        AssistantSettingsBody(actions, modifier, onDirtyChange)
    }
}

@Composable
private fun AssistantSettingsBody(
    actions: AssistantSettingsActions,
    modifier: Modifier = Modifier,
    onDirtyChange: (Boolean) -> Unit = {},
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
        val pair = actions.assistantLoad()
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
            val pair = actions.assistantLoad()
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
            val err = actions.assistantSave(paName, soul)
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
                    // A failed load must never present an editable soul.md with a working Save:
                    // an empty editor over a failed fetch would overwrite the real file with "".
                    SettingsCaption("soul.md was not loaded, so it can't be saved from here yet.")
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
                        // Android's: a PA name is an identifier, not prose.
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
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
                        // Markdown, not prose: no autocorrect and no sentence capitalisation.
                        keyboardOptions = KeyboardOptions(
                            autoCorrectEnabled = false,
                            capitalization = KeyboardCapitalization.None,
                        ),
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
