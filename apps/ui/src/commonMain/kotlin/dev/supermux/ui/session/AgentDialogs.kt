// The sidebar's Agent dialogs — New Agent, Change character, Rename, Archive — in one desktop
// style (not Material alerts). The "New Agent" modal: pick a character from the avatar gallery, name it, choose a provider.
//
// A desktop-style sheet rather than a Material alert: a live preview of the picked character that
// cycles through its states, a grid of characters, a quiet text field, a segmented provider
// control and a compact model menu; Enter creates, Esc cancels.
package dev.supermux.ui.session

import androidx.compose.foundation.BorderStroke
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.resolveReasoningLevel
import dev.supermux.net.showReasoningPicker
import dev.supermux.net.sortEffortLevelsLowToHigh
import dev.supermux.state.LauncherPrefs
import dev.supermux.ui.prefs.LocalUiPrefsOrNull
import kotlinx.coroutines.flow.first
import dev.supermux.state.AccountResult
import dev.supermux.state.agentFailureText
import dev.supermux.state.agentNameError
import dev.supermux.state.agentNameTrim
import dev.supermux.ui.accounts.agentDisplayName
import dev.supermux.ui.widgets.Dialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One gallery character: its display name, the JSON the Agent will keep, and the parsed rig. */
private class GalleryCharacter(val name: String, val json: String, val rig: AvatarRig)

/** The states the preview cycles through, with the caption shown under it. */
private val PreviewStates = listOf(
    "idle" to "Idle",
    "working" to "Working",
    "waiting" to "Has a reply",
    "listening" to "Listening",
    "speaking" to "Speaking",
    "asleep" to "Asleep",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreateAgentDialog(
    actions: AgentsActions,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val gallery = remember {
        AvatarRigExamples.mapNotNull { (n, j) -> parseAvatarRig(j)?.let { GalleryCharacter(n, j, it) } }
    }
    var picked by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf<List<String>?>(null) }
    var providersFailed by remember { mutableStateOf(false) }
    var providersAttempt by remember { mutableIntStateOf(0) }
    var provider by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var model by remember { mutableStateOf<String?>(null) }
    var efforts by remember { mutableStateOf<ReasoningResponse?>(null) }
    var effort by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(providersAttempt) {
        providersFailed = false
        val list = actions.providers()
        if (list == null) {
            providersFailed = true
            return@LaunchedEffect
        }
        providers = list
        provider = list.firstOrNull { it == "claude" } ?: list.firstOrNull()
    }
    // The composer's memory (LauncherPrefs): the last model + effort used per provider.
    val uiPrefs = LocalUiPrefsOrNull.current
    var launcherPrefs by remember { mutableStateOf<LauncherPrefs?>(null) }
    LaunchedEffect(uiPrefs) { launcherPrefs = uiPrefs?.launcherPrefs?.first() }

    // A real model is always picked (no "Default"): the composer's last one for this provider,
    // else the provider's first.
    LaunchedEffect(provider, launcherPrefs) {
        model = null
        models = provider?.let { actions.models(it) }.orEmpty()
        val remembered = provider?.let { launcherPrefs?.models?.get(it) }
        model = models.firstOrNull { it.id == remembered }?.id ?: models.firstOrNull()?.id
    }
    // Efforts depend on the model. The same rule as the composer (resolveReasoningLevel): the one
    // already picked or remembered when this model offers it, else "high", else the highest.
    LaunchedEffect(provider, model) {
        val p = provider
        val m = model
        efforts = if (p != null && m != null) actions.efforts(p, m) else null
        val levels = efforts?.levels.orEmpty()
        effort = resolveReasoningLevel(levels, effort?.takeIf { e -> levels.any { it.id == e } } ?: p?.let { launcherPrefs?.reasoningLevels?.get(it) })
    }
    val effortIds = efforts?.takeIf { it.visible }?.levels.orEmpty()
        .let { if (showReasoningPicker(it)) sortEffortLevelsLowToHigh(it) else emptyList() }
        .map { it.id }

    val character = gallery.getOrNull(picked)
    // An empty name takes the character's: picking Hoot makes an Agent called Hoot.
    val finalName = agentNameTrim(name).ifEmpty { character?.name.orEmpty() }
    val nameError = if (finalName.isEmpty()) null else agentNameError(finalName)
    val canCreate = !busy && provider != null && model != null &&
        (effortIds.isEmpty() || effort != null) && finalName.isNotEmpty() && nameError == null

    fun create() {
        if (!canCreate) return
        val p = provider ?: return
        busy = true
        error = null
        scope.launch {
            when (val r = actions.create(finalName, p, model, character?.json, effort?.takeIf { it in effortIds })) {
                is AccountResult.Ok -> onCreated()
                is AccountResult.Failed -> error = agentFailureText(r, "create the agent")
            }
            busy = false
        }
    }

    // A phone gets a bottom sheet laid out for a narrow screen; wider windows the centred dialog.
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact

    val body: @Composable () -> Unit = {
        // ── Title ──
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("New Agent", color = cs.onSurface, fontSize = if (compact) 19.sp else 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(
                    "An always-on assistant with its own memory. Pick its character.",
                    color = cs.onSurfaceVariant,
                    fontSize = if (compact) 13.sp else 12.5.sp,
                )
            }
            if (!compact) {
                GhostIconButton(onClick = { if (!busy) onDismiss() }, tag = "agents_create_cancel_x") {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.height(if (compact) 16.dp else 18.dp))

        // ── Character: live preview + gallery ──
        val grid: @Composable (Modifier) -> Unit = { m ->
            Column(m, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                gallery.withIndex().chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (i, c) ->
                            CharacterTile(c, selected = i == picked, onClick = { picked = i }, modifier = Modifier.weight(1f), compact = compact)
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (compact) {
            // Preview beside its name, then the full-width grid: nothing is squeezed.
            character?.let { CharacterPreview(it, compact = true) }
            Spacer(Modifier.height(14.dp))
            grid(Modifier.fillMaxWidth())
        } else {
            Row(Modifier.padding(end = 8.dp)) {
                character?.let { CharacterPreview(it) }
                Spacer(Modifier.width(18.dp))
                grid(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(20.dp))

        Column(Modifier.padding(end = if (compact) 0.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 16.dp else 14.dp)) {
            // ── Name ──
            Field("Name") {
                QuietTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    placeholder = character?.name ?: "Name",
                    isError = nameError != null && name.isNotEmpty(),
                    tag = "agents_create_name",
                    // On a phone the keyboard would cover the gallery before anything is picked.
                    autoFocus = !compact,
                    tall = compact,
                )
                if (nameError != null && name.isNotEmpty()) {
                    Text(nameError, color = cs.error, fontSize = 11.5.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            // ── Provider ──
            Field("Provider") {
                when {
                    providersFailed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Couldn't load providers.", color = cs.error, fontSize = 12.5.sp)
                        Spacer(Modifier.width(8.dp))
                        LinkButton("Retry", tag = "agents_create_providers_error") { providersAttempt++ }
                    }
                    providers == null -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    providers!!.isEmpty() -> Text(
                        "No provider is installed. Install one in Settings › Providers.",
                        color = cs.onSurfaceVariant,
                        fontSize = 12.5.sp,
                        modifier = Modifier.testTag("agents_create_no_provider"),
                    )
                    else -> Segmented(
                        options = providers!!,
                        selected = provider,
                        label = ::agentDisplayName,
                        onSelect = { provider = it },
                        // Six providers don't fit a phone's width: the track scrolls sideways.
                        modifier = if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
                        tall = compact,
                    )
                }
            }

            // ── Model + effort (side by side on a wide window, stacked on a phone) ──
            if (provider != null && providers?.isNotEmpty() == true) {
                val modelField: @Composable () -> Unit = {
                    Field("Model") {
                        if (models.isEmpty()) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            ModelMenu(models, model, onSelect = { model = it }, tall = compact)
                        }
                    }
                }
                val effortField: @Composable () -> Unit = {
                    if (effortIds.isNotEmpty()) {
                        Field("Effort") {
                            Segmented(
                                options = effortIds,
                                selected = effort,
                                label = ::effortLabel,
                                onSelect = { effort = it },
                                tagPrefix = "agents_create_effort_",
                                modifier = if (compact) Modifier.horizontalScroll(rememberScrollState()) else Modifier,
                                tall = compact,
                            )
                        }
                    }
                }
                if (compact) {
                    modelField()
                    effortField()
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        modelField()
                        effortField()
                    }
                }
            }
            error?.let { Text(it, color = cs.error, fontSize = 12.5.sp, modifier = Modifier.testTag("agents_create_error")) }
        }
        Spacer(Modifier.height(22.dp))

        // ── Footer ──
        val createLabel = if (busy) "Creating…" else "Create ${finalName.ifEmpty { "Agent" }}"
        if (compact) {
            // One thumb-sized action; the sheet itself is dismissed by swiping down or Back.
            PrimaryButton(createLabel, enabled = canCreate, tag = "agents_create_confirm", onClick = ::create, modifier = Modifier.fillMaxWidth(), tall = true)
        } else {
            Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("↵ to create · esc to cancel", color = cs.onSurfaceVariant.copy(alpha = 0.55f), fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                SecondaryButton("Cancel", enabled = !busy, tag = "agents_create_cancel", onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                PrimaryButton(createLabel, enabled = canCreate, tag = "agents_create_confirm", onClick = ::create)
            }
        }
    }

    if (compact) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = cs.surfaceContainerHigh,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 20.dp)
                    .navigationBarsPadding()
                    .imePadding()
                    .testTag("agents_create_dialog"),
            ) { body() }
        }
    } else {
        Dialog(
            onDismissRequest = { if (!busy) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = cs.surfaceContainerHigh,
                border = BorderStroke(1.dp, cs.onSurface.copy(alpha = 0.08f)),
                shadowElevation = 24.dp,
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .testTag("agents_create_dialog")
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Escape -> { if (!busy) onDismiss(); true }
                            Key.Enter, Key.NumPadEnter -> { create(); true }
                            else -> false
                        }
                    },
            ) {
                Column(Modifier.padding(start = 24.dp, end = 16.dp, top = 18.dp, bottom = 20.dp)) { body() }
            }
        }
    }
}

/** The picked character, large, cycling through its states so you see it come alive. */
@Composable
private fun CharacterPreview(c: GalleryCharacter, compact: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    var step by remember(c) { mutableIntStateOf(0) }
    LaunchedEffect(c) {
        while (true) {
            delay(2400)
            step = (step + 1) % PreviewStates.size
        }
    }
    val (state, caption) = PreviewStates[step]
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val box: @Composable () -> Unit = {
        Box(
            Modifier
                .size(if (compact) 96.dp else 136.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(cs.onSurface.copy(alpha = 0.045f))
                .hoverable(interaction)
                .testTag("agents_create_preview"),
            contentAlignment = Alignment.Center,
        ) {
            AvatarRigView(
                c.rig,
                state = state,
                hovered = hovered,
                level = if (state == "listening" || state == "speaking") 0.55f else 0f,
                size = if (compact) 80 else 112,
            )
        }
    }
    if (compact) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            box()
            Spacer(Modifier.width(16.dp))
            Column {
                Text(c.name, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Text(caption, color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 12.5.sp)
            }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            box()
            Spacer(Modifier.height(8.dp))
            Text(c.name, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(caption, color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun CharacterTile(c: GalleryCharacter, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    selected -> cs.primary.copy(alpha = 0.12f)
                    hovered -> cs.onSurface.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .border(
                if (selected) 1.5.dp else 1.dp,
                if (selected) cs.primary.copy(alpha = 0.7f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(top = 6.dp, bottom = 5.dp)
            .testTag("agents_create_character_${c.name}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AvatarRigView(c.rig, state = "idle", hovered = hovered || selected, seed = c.name, size = if (compact) 44 else 40)
        Spacer(Modifier.height(2.dp))
        Text(
            c.name,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun Field(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun QuietTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    isError: Boolean,
    tag: String,
    autoFocus: Boolean = true,
    /** Thumb-sized (touch). */
    tall: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autoFocus) runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(color = cs.onSurface, fontSize = if (tall) 15.sp else 13.5.sp),
        cursorBrush = SolidColor(cs.primary),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .testTag(tag),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (tall) 46.dp else 36.dp)
                    .clip(RoundedCornerShape(if (tall) 10.dp else 8.dp))
                    .background(cs.onSurface.copy(alpha = 0.05f))
                    .border(
                        1.dp,
                        when {
                            isError -> cs.error
                            focused -> cs.primary.copy(alpha = 0.6f)
                            else -> cs.onSurface.copy(alpha = 0.08f)
                        },
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) Text(placeholder, color = cs.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 13.5.sp)
                inner()
            }
        },
    )
}

/** A macOS-style segmented control: one track, the selected segment raised. */
@Composable
private fun Segmented(
    options: List<String>,
    selected: String?,
    label: (String) -> String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    tagPrefix: String = "agents_create_provider_",
    tall: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(cs.onSurface.copy(alpha = 0.06f))
            .padding(2.dp),
    ) {
        options.forEach { o ->
            val on = o == selected
            Box(
                Modifier
                    .height(if (tall) 38.dp else 28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (on) cs.surfaceBright else Color.Transparent)
                    .then(if (on) Modifier.border(0.5.dp, cs.onSurface.copy(alpha = 0.1f), RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { onSelect(o) }
                    .padding(horizontal = if (tall) 14.dp else 11.dp)
                    .testTag("$tagPrefix$o"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(o),
                    color = if (on) cs.onSurface else cs.onSurfaceVariant,
                    fontSize = if (tall) 14.sp else 12.5.sp,
                    fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ModelMenu(models: List<ModelInfo>, selected: String?, onSelect: (String) -> Unit, tall: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val label = models.firstOrNull { it.id == selected }?.displayName ?: "Choose a model"
    Box {
        Row(
            Modifier
                .height(if (tall) 42.dp else 32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(cs.onSurface.copy(alpha = 0.06f))
                .clickable { open = true }
                .padding(start = 11.dp, end = 6.dp)
                .testTag("agents_create_model"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = cs.onSurface, fontSize = if (tall) 14.sp else 12.5.sp, maxLines = 1)
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            models.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.displayName) },
                    onClick = { open = false; onSelect(m.id) },
                    modifier = Modifier.testTag("agents_create_model_${m.id}"),
                )
            }
        }
    }
}

/** "xhigh" → "Extra high"; the rest capitalised ("medium" → "Medium"). */
private fun effortLabel(id: String): String = when (id) {
    "xhigh" -> "Extra high"
    else -> id.replaceFirstChar { it.uppercase() }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, tall: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .height(if (tall) 48.dp else 32.dp)
            .clip(RoundedCornerShape(if (tall) 12.dp else 8.dp))
            .background(if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.08f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) cs.onPrimary else cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = if (tall) 15.sp else 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.onSurface.copy(alpha = 0.06f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = cs.onSurface, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun LinkButton(text: String, tag: String, onClick: () -> Unit) {
    Text(
        text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp).testTag(tag),
    )
}

@Composable
private fun GhostIconButton(onClick: () -> Unit, tag: String, content: @Composable () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { content() }
}

// ── The smaller Agent dialogs (sidebar menu) ─────────────────────────────────────────────────

/** The shared frame of the small Agent dialogs: title, body, footer; Enter submits, Esc cancels. */
@Composable
private fun AgentSheet(
    title: String,
    subtitle: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    tag: String,
    maxWidth: Int = 440,
    footer: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cs.surfaceContainerHigh,
            border = BorderStroke(1.dp, cs.onSurface.copy(alpha = 0.08f)),
            shadowElevation = 24.dp,
            modifier = Modifier
                .padding(24.dp)
                .widthIn(max = maxWidth.dp)
                .fillMaxWidth()
                .testTag(tag)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.Escape -> { if (!busy) onDismiss(); true }
                        Key.Enter, Key.NumPadEnter -> { onSubmit(); true }
                        else -> false
                    }
                },
        ) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 20.dp)) {
                Text(title, color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                subtitle?.let {
                    Spacer(Modifier.height(3.dp))
                    Text(it, color = cs.onSurfaceVariant, fontSize = 12.5.sp)
                }
                Spacer(Modifier.height(16.dp))
                content()
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    footer()
                }
            }
        }
    }
}

/** "Change character…": the gallery with a live preview; saves the Agent's avatar. */
@Composable
internal fun ChangeCharacterDialog(
    agentId: String,
    agentName: String,
    current: AvatarRig?,
    setAvatar: suspend (id: String, json: String) -> AccountResult<Unit>,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val gallery = remember {
        AvatarRigExamples.mapNotNull { (n, j) -> parseAvatarRig(j)?.let { GalleryCharacter(n, j, it) } }
    }
    var picked by remember { mutableIntStateOf(-1) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val character = gallery.getOrNull(picked)
    fun save() {
        val c = character ?: return
        if (busy) return
        busy = true
        scope.launch {
            when (val r = setAvatar(agentId, c.json)) {
                is AccountResult.Ok -> onDismiss()
                is AccountResult.Failed -> error = agentFailureText(r, "change $agentName's character")
            }
            busy = false
        }
    }
    AgentSheet(
        title = "Change $agentName's character",
        subtitle = "Its name, memory and history stay the same.",
        busy = busy,
        onDismiss = onDismiss,
        onSubmit = ::save,
        tag = "agents_character_dialog",
        maxWidth = 600,
        footer = {
            SecondaryButton("Cancel", enabled = !busy, tag = "agents_character_cancel", onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryButton(if (busy) "Saving…" else "Use ${character?.name ?: "character"}", enabled = character != null && !busy, tag = "agents_character_save", onClick = ::save)
        },
    ) {
        Row {
            if (character != null) {
                CharacterPreview(character)
            } else if (current != null) {
                // Nothing picked yet: show the Agent as it looks today.
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(136.dp).clip(RoundedCornerShape(14.dp)).background(cs.onSurface.copy(alpha = 0.045f)),
                        contentAlignment = Alignment.Center,
                    ) { AvatarRigView(current, state = "idle", size = 112) }
                    Spacer(Modifier.height(8.dp))
                    Text(agentName, color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Current", color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 11.sp)
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                gallery.withIndex().chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (i, c) ->
                            CharacterTile(c, selected = i == picked, onClick = { picked = i }, modifier = Modifier.weight(1f))
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        error?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = cs.error, fontSize = 12.5.sp)
        }
    }
}

/** Rename an Agent; its memory follows its id, so nothing else changes. */
@Composable
internal fun RenameAgentDialog(
    agentId: String,
    agentName: String,
    rename: suspend (id: String, name: String) -> AccountResult<Unit>,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember(agentId) { mutableStateOf(agentName) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val trimmed = agentNameTrim(name)
    val nameError = if (name.isEmpty()) null else agentNameError(name)
    val canRename = !busy && trimmed.isNotEmpty() && nameError == null && trimmed != agentName
    fun submit() {
        if (!canRename) return
        busy = true
        error = null
        scope.launch {
            when (val r = rename(agentId, trimmed)) {
                is AccountResult.Ok -> onDismiss()
                is AccountResult.Failed -> error = agentFailureText(r, "rename $agentName")
            }
            busy = false
        }
    }
    AgentSheet(
        title = "Rename $agentName",
        subtitle = "Its memory and history stay with it.",
        busy = busy,
        onDismiss = onDismiss,
        onSubmit = ::submit,
        tag = "agents_rename_dialog",
        footer = {
            SecondaryButton("Cancel", enabled = !busy, tag = "agents_rename_cancel", onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            PrimaryButton("Rename", enabled = canRename, tag = "agents_rename_confirm", onClick = ::submit)
        },
    ) {
        QuietTextField(
            value = name,
            onValueChange = { name = it; error = null },
            placeholder = agentName,
            isError = nameError != null,
            tag = "agents_rename_name",
        )
        (nameError ?: error)?.let { Text(it, color = cs.error, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** Archive (kill) an Agent, after a confirmation. */
@Composable
internal fun ArchiveAgentDialog(
    agentId: String,
    agentName: String,
    kill: suspend (id: String) -> AccountResult<Unit>,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (busy) return
        busy = true
        scope.launch {
            when (val r = kill(agentId)) {
                is AccountResult.Ok -> onDismiss()
                is AccountResult.Failed -> error = agentFailureText(r.copy(message = null), "archive $agentName")
            }
            busy = false
        }
    }
    AgentSheet(
        title = "Archive $agentName?",
        subtitle = "It stops running and leaves the sidebar. Its memory is kept, and you can restore it from the archive.",
        busy = busy,
        onDismiss = onDismiss,
        onSubmit = ::submit,
        tag = "agents_kill_dialog",
        footer = {
            SecondaryButton("Cancel", enabled = !busy, tag = "agents_kill_cancel", onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            DangerButton(if (busy) "Archiving…" else "Archive", enabled = !busy, tag = "agents_kill_confirm", onClick = ::submit)
        },
    ) {
        error?.let { Text(it, color = cs.error, fontSize = 12.5.sp) }
    }
}

@Composable
private fun DangerButton(text: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(cs.error.copy(alpha = if (enabled) 0.9f else 0.3f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = cs.onError, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
