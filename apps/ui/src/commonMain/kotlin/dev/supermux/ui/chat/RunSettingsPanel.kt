// The run-settings panel: everything about WHO runs the next turn and HOW, opened from the one model
// pill in the composer (and the New Session launcher). Three zones, top to bottom:
//   1. who   — the launcher's agent switcher, or the running chat's agent as a quiet title;
//   2. which — a search field (focused on open under a pointer) over a short scrolling model list,
//              starred models pinned first;
//   3. how   — settings rows that always show their current value (Effort, Fast, Account, Mode).
//              Mode and Account open their own list in place of the panel, with a back arrow. The
//              Account row is a new chat's account choice, or the account a running chat runs on
//              and the switch to another (it replaced the chat header's account pill).
// The popover is pinned by its bottom edge to the pill, so only its top edge ever moves.
// A pointer gets it as a popover above the pill; touch gets the same content in a bottom sheet.
package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.supermux.chat.DEFAULT_MODEL_ID
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.sortEffortLevelsLowToHigh
import dev.supermux.net.AccountDto
import dev.supermux.net.SessionAccountResult
import dev.supermux.state.AccountResult
import dev.supermux.ui.accounts.AccountTag
import dev.supermux.ui.accounts.accountPickerDetail
import dev.supermux.ui.accounts.accountShortLabel
import dev.supermux.ui.accounts.accountUnavailableText
import dev.supermux.ui.accounts.accountsFor
import dev.supermux.ui.accounts.agentDisplayName
import dev.supermux.ui.accounts.hasAccountChoice
import dev.supermux.ui.accounts.isSystemAccountId
import dev.supermux.ui.accounts.sessionSwitchFailureText
import dev.supermux.ui.accounts.signInExpired
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.session.hasAgentLogo
import dev.supermux.ui.theme.GeistMonoFontFamily
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.rememberHaptics
import dev.supermux.ui.prefs.LocalUiPrefsOrNull
import dev.supermux.ui.widgets.ModalHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A starred model's stored key: `"<agent>/<modelId>"` (see [dev.supermux.ui.prefs.UiPrefs.starredModels]). */
internal fun starKey(agent: String?, modelId: String): String = "${agent.orEmpty()}/$modelId"

/** Where no [LocalUiPrefsOrNull] is provided (a composer mounted bare in a test), stars live here. */
private object InMemoryStars {
    var keys by mutableStateOf(emptySet<String>())
}

/** The starred keys, persisted through UiPrefs when the host provides them. */
@Composable
private fun rememberStars(): Pair<Set<String>, (String) -> Unit> {
    val prefs = LocalUiPrefsOrNull.current
    val scope = rememberCoroutineScope()
    if (prefs == null) {
        val keys = InMemoryStars.keys
        return keys to { key -> InMemoryStars.keys = if (key in keys) keys - key else keys + key }
    }
    val keys by prefs.starredModels.collectAsState(emptySet())
    return keys to { key -> scope.launch { prefs.putStarredModels(if (key in keys) keys - key else keys + key) } }
}

/**
 * The panel's Account row and the list it opens, for both of its callers:
 *  - the launcher (a new chat): "Auto" is listed first and is the default — [selectedId] null = the
 *    account with the most room left, picked by the broker when the chat starts. A pick is instant.
 *  - a running chat ([forChat]): it runs on exactly one account, so there is no Auto; a pick is the
 *    switch, which takes a moment ([pendingId]) and can be refused ([refusal]).
 */
class RunSettingsAccount(
    /** This agent's logins, system login first; empty while they load. */
    val accounts: List<AccountDto>,
    /** The account chosen (the launcher's null = Auto) or run on. */
    val selectedId: String?,
    val onSelect: (String?) -> Unit,
    /** List "Auto" first: a new chat. */
    val offersAuto: Boolean = true,
    /** The row's value until [selectedId] is in [accounts] (a running chat's own `accountLabel`). */
    val fallbackLabel: String? = null,
    /** The account a pick is being applied to: its row spins, and no other pick is taken meanwhile. */
    val pendingId: String? = null,
    /** Why the last pick was refused, shown under the list. */
    val refusal: String? = null,
    /** Opens Settings ▸ Accounts; offered when the current account is unavailable. */
    val onOpenAccounts: (() -> Unit)? = null,
    /** Opens the agent's own sign-in (Settings ▸ Providers); offered instead when the system login's sign-in expired. */
    val onSignIn: (() -> Unit)? = null,
) {
    /** The account chosen by hand (null while Auto is chosen) or run on. */
    val current: AccountDto? = selectedId?.let { id -> accounts.firstOrNull { it.id == id } }
    /** The account Auto would pick now (the broker marks it), when known. */
    val autoPick: AccountDto? = accounts.firstOrNull { it.auto }
    /** On an added account, not the system login (and not Auto). */
    val onAdded: Boolean = current?.let { !it.system } ?: (!offersAuto && !isSystemAccountId(selectedId))
    /** The current account is marked unavailable (its API refused it). Picking it again is the retry. */
    val unavailable: Boolean = current?.unavailable != null
    /** The row's value: the current account, or Auto and what it would pick now. */
    val label: String = when {
        current != null -> accountShortLabel(current)
        !offersAuto -> fallbackLabel?.takeIf { it.isNotBlank() }
            ?: if (isSystemAccountId(selectedId)) "System login" else selectedId.orEmpty()
        else -> autoPick?.let { "Auto · ${accountShortLabel(it)}" } ?: "Auto"
    }

    companion object {
        /**
         * A running chat's account, or null when there is nothing to say. The rule is the one the
         * header's account pill had: shown on an added account, when the agent has more than its
         * system login to switch between, or when the account the chat runs on is unavailable.
         */
        fun forChat(
            agent: String,
            /** The session's `account`; null or `<agent>:system` = the system login. */
            accountId: String?,
            accountLabel: String?,
            /** The host's accounts (every agent); null until loaded. */
            accounts: List<AccountDto>?,
            switch: SessionAccountSwitch,
            onOpenAccounts: (() -> Unit)? = null,
            onSignIn: (() -> Unit)? = null,
        ): RunSettingsAccount? {
            val list = accountsFor(accounts.orEmpty(), agent)
            val onSystem = isSystemAccountId(accountId)
            val id = if (onSystem) list.firstOrNull { it.system }?.id ?: "$agent:system" else accountId.orEmpty()
            val unavailable = list.firstOrNull { it.id == id }?.unavailable != null
            if (onSystem && !unavailable && !hasAccountChoice(accounts, agent)) return null
            return RunSettingsAccount(
                accounts = list,
                selectedId = id,
                onSelect = { picked -> if (picked != null) switch.start(picked) },
                offersAuto = false,
                fallbackLabel = accountLabel,
                pendingId = switch.pendingId,
                refusal = switch.refusal,
                onOpenAccounts = onOpenAccounts,
                onSignIn = onSignIn,
            )
        }
    }
}

/**
 * A running chat's account switch (POST /sessions/<id>/account), one at a time. It belongs to the
 * chat, not to the run-settings panel, so closing the panel mid-switch cancels nothing; while the
 * panel is open, its Account list shows [pendingId] spinning and [refusal] (busy, not running…).
 */
@Stable
class SessionAccountSwitch(
    private val scope: CoroutineScope,
    private val switch: suspend (accountId: String) -> AccountResult<SessionAccountResult>,
) {
    var pendingId: String? by mutableStateOf(null)
        private set
    var refusal: String? by mutableStateOf(null)
        private set

    fun start(accountId: String) {
        if (pendingId != null) return
        pendingId = accountId
        refusal = null
        scope.launch {
            try {
                val result = switch(accountId)
                if (result is AccountResult.Failed) refusal = sessionSwitchFailureText(result.code, result.message)
            } finally {
                pendingId = null
            }
        }
    }

    /** Nothing stale to say: the panel opened again, or the chat's account changed (from anywhere). */
    fun clearRefusal() {
        refusal = null
    }
}

/** One pickable row: a real model, or the agent's own default. */
private data class ModelRow(val id: String, val name: String, val secondary: String?)

/** The rows to show for [query]: starred first (catalog order kept within each group), then the rest. */
private fun modelRows(models: List<ModelInfo>, stars: Set<String>, query: String): List<ModelRow> {
    val all = listOf(ModelRow(DEFAULT_MODEL_ID, "Default", "the agent's own choice")) +
        models.map { m ->
            val name = shortModelName(m.displayName, models)
            val plainId = m.id.lowercase().filter { it.isLetterOrDigit() }
            val plainName = m.displayName.lowercase().filter { it.isLetterOrDigit() }
            ModelRow(m.id, name, m.id.takeIf { plainId != plainName })
        }
    val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val hits = if (words.isEmpty()) all else all.filter { row ->
        val hay = (row.name + " " + row.id + " " + row.secondary.orEmpty()).lowercase()
        words.all { it in hay }
    }
    return hits.filter { it.id in stars } + hits.filterNot { it.id in stars }
}

private enum class PanelView { Models, Modes, Accounts }

/** Wide enough for a six-level effort row (Codex: low … ultra) beside its label. */
private val PANEL_WIDTH_POPOVER = 420.dp

/** The panel's one height under a pointer (capped to the room above the pill) and in a sheet. */
private val PANEL_HEIGHT_POPOVER = 500.dp
private val PANEL_HEIGHT_SHEET = 600.dp

/**
 * The panel's content. [header] null shows the agent as a title (a running chat cannot switch
 * agents); the launcher passes its agent switcher. Picking a model or an effort level closes the
 * panel; the agent row, a star, Fast and the Mode / Account lists keep it open.
 *
 * Mode and Account open their list IN PLACE at the panel's exact height (nothing resizes, nothing
 * moves), and the row that was clicked becomes "‹ Back" in the very same spot — the pointer never
 * has to travel to undo a look. They are therefore the bottom two settings rows.
 */
@Composable
internal fun RunSettingsPanel(
    sheet: Boolean,
    agent: String?,
    header: (@Composable (sheet: Boolean) -> Unit)?,
    models: List<ModelInfo>?,
    modelCurrent: String?,
    onPickModel: (String) -> Unit,
    reasoning: ReasoningResponse?,
    reasoningCurrent: String?,
    onPickReasoning: (String) -> Unit,
    fastMode: Boolean?,
    onToggleFast: (Boolean) -> Unit,
    permission: RunSettingsPermission?,
    onDismiss: () -> Unit,
    account: RunSettingsAccount? = null,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    var view by remember { mutableStateOf(PanelView.Models) }
    var query by remember { mutableStateOf("") }
    var highlight by remember { mutableIntStateOf(-1) }
    val searchFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val (starKeys, toggleStar) = rememberStars()
    val stars = remember(starKeys, agent) {
        val prefix = starKey(agent, "")
        starKeys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()
    }
    val rows = models?.let { modelRows(it, stars, query) }.orEmpty()
    val selectedId = composerModelSelectedId(modelCurrent)
    val padH = if (sheet) 16.dp else 10.dp
    val listPad = if (sheet) 8.dp else 6.dp
    val drilled = view != PanelView.Models
    val back = { view = PanelView.Models }

    fun pick(row: ModelRow) {
        haptic.perform(HapticKind.Tick)
        onDismiss()
        onPickModel(if (row.id == DEFAULT_MODEL_ID) "" else row.id)
    }

    // A drilled-in view has no search field to hold the keyboard: the panel takes it, so Esc goes back.
    LaunchedEffect(view) { if (drilled && !sheet) runCatching { panelFocus.requestFocus() } }
    // A pick that lands shows the panel again, the new account on its row: at once in the launcher,
    // a moment later for a running chat's switch (or one made elsewhere meanwhile).
    LaunchedEffect(account?.selectedId) { if (view == PanelView.Accounts) back() }

    // ONE height, whatever is inside: switching agent, filtering, a model with or without Fast, Mode
    // and Account — none of them resize the panel. The model list takes whatever the rest leaves.
    // (The popover still caps it to the room above the pill.)
    Column(
        Modifier
            .then(if (sheet) Modifier.fillMaxWidth() else Modifier.width(PANEL_WIDTH_POPOVER))
            .height(if (sheet) PANEL_HEIGHT_SHEET else PANEL_HEIGHT_POPOVER)
            .focusRequester(panelFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (drilled && e.type == KeyEventType.KeyDown && e.key == Key.Escape) { back(); true } else false
            }
            .testTag("run_settings_panel"),
    ) {
        when (view) {
            PanelView.Models -> {
                // ── 1 · who ───────────────────────────────────────────────────────────────────
                if (header != null) {
                    Column(Modifier.fillMaxWidth().padding(top = if (sheet) 0.dp else 8.dp)) { header(sheet) }
                } else if (agent != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = padH + 6.dp, end = padH, top = if (sheet) 4.dp else 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (hasAgentLogo(agent)) AgentLogo(agent, size = 16.dp)
                        Text(agentDisplayName(agent), color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        Text("this chat", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    }
                }

                // ── 2 · which ─────────────────────────────────────────────────────────────────
                if (models != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = padH, vertical = 6.dp)
                            .height(if (sheet) 44.dp else 36.dp)
                            .clip(RoundedCornerShape(Radii.pill))
                            .background(cs.onSurface.copy(alpha = 0.05f))
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Search models…", color = cs.onSurfaceVariant.copy(alpha = 0.8f), fontSize = 14.sp)
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it; highlight = if (it.isBlank()) -1 else 0 },
                                singleLine = true,
                                textStyle = TextStyle(color = cs.onSurface, fontSize = 14.sp),
                                cursorBrush = SolidColor(cs.primary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(searchFocus)
                                    .testTag("run_settings_search")
                                    .onPreviewKeyEvent { e ->
                                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (e.key) {
                                            Key.DirectionDown -> { highlight = (highlight + 1).coerceAtMost(rows.lastIndex); true }
                                            Key.DirectionUp -> { highlight = (highlight - 1).coerceAtLeast(0); true }
                                            Key.Enter, Key.NumPadEnter -> {
                                                rows.getOrNull(highlight.coerceAtLeast(0))?.let { pick(it) }
                                                true
                                            }
                                            Key.Escape -> {
                                                if (query.isNotEmpty()) { query = ""; highlight = -1 } else onDismiss()
                                                true
                                            }
                                            else -> false
                                        }
                                    },
                            )
                        }
                    }
                    // A pointer types straight away; a phone does not pop its keyboard over the list.
                    if (!sheet) LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = listPad),
                    ) {
                        if (rows.isEmpty()) {
                            Text(
                                "No model matches “${query.trim()}”",
                                color = cs.onSurfaceVariant,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                            )
                        }
                        rows.forEachIndexed { i, row ->
                            ModelRowItem(
                                row = row,
                                agent = agent,
                                sheet = sheet,
                                selected = row.id == selectedId,
                                highlighted = i == highlight,
                                starred = row.id in stars,
                                onHover = { highlight = i },
                                onStar = if (row.id == DEFAULT_MODEL_ID) null else ({ toggleStar(starKey(agent, row.id)) }),
                                onClick = { pick(row) },
                            )
                        }
                    }
                }

                // No catalog: the settings rows still sit at the bottom of the same-size panel.
                if (models == null) Spacer(Modifier.weight(1f))

                // ── 3 · how ── each row shows its current value; the two that open a list sit last.
                val mode = permission?.current
                val hasSettings = reasoning != null || fastMode != null || mode != null || account != null
                if (hasSettings) {
                    HorizontalDivider(Modifier.padding(top = 4.dp), color = cs.onSurface.copy(alpha = 0.07f))
                    Column(Modifier.padding(horizontal = listPad, vertical = 6.dp)) {
                        if (reasoning != null) {
                            SettingRow(sheet, icon = { EffortGlyph() }, label = "Effort") {
                                EffortSegments(reasoning, reasoningCurrent, sheet) { level ->
                                    haptic.perform(HapticKind.Tick)
                                    onDismiss()
                                    onPickReasoning(level)
                                }
                            }
                        }
                        if (fastMode != null) {
                            SettingRow(
                                sheet,
                                icon = { Icon(Icons.Filled.Bolt, null, tint = if (fastMode) cs.tertiary else cs.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
                                label = "Fast mode",
                                onClick = { onToggleFast(!fastMode) },
                                tag = "composer-fast-toggle",
                                toggled = fastMode,
                            ) {
                                Switch(checked = fastMode, onCheckedChange = null, modifier = Modifier.scale(if (sheet) 0.85f else 0.7f))
                            }
                        }
                        if (account != null) {
                            val ink = accountInk(account)
                            SettingRow(
                                sheet,
                                icon = {
                                    Icon(
                                        if (account.unavailable) Icons.Outlined.WarningAmber else Icons.Outlined.AccountCircle,
                                        null,
                                        tint = ink ?: cs.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = "Account",
                                onClick = { view = PanelView.Accounts },
                                tag = "run_settings_account",
                            ) {
                                if (account.pendingId != null) Spinner()
                                Text(
                                    account.label,
                                    color = ink ?: cs.onSurface.copy(alpha = 0.8f),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 170.dp),
                                )
                                DrillChevron()
                            }
                        }
                        if (permission != null && mode != null) {
                            val ink = if (mode.default) cs.onSurfaceVariant else permissionInk(mode.id)
                            SettingRow(
                                sheet,
                                icon = {
                                    Icon(
                                        if (mode.id == "plan") Icons.Outlined.Visibility else Icons.Outlined.Shield,
                                        null,
                                        tint = ink,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = "Mode",
                                onClick = { view = PanelView.Modes },
                                tag = "run_settings_mode",
                            ) {
                                Text(
                                    mode.label,
                                    color = if (mode.default) cs.onSurface.copy(alpha = 0.8f) else ink,
                                    fontSize = 13.sp,
                                    fontWeight = if (mode.default) FontWeight.Normal else FontWeight.Medium,
                                    maxLines = 1,
                                )
                                DrillChevron()
                            }
                        }
                    }
                }
            }

            PanelView.Accounts -> DrillView(
                sheet = sheet,
                title = if (account?.offersAuto == false) "Run this chat on" else "Start this chat on",
                // Account is the second-to-last settings row: its Back takes the last row's place,
                // and a spacer row keeps it level with where Account itself was.
                rowsBelowBack = if (permission?.current != null) 1 else 0,
                onBack = back,
            ) {
                if (account != null) AccountOptions(sheet, account, onBack = back, onDismiss = onDismiss)
            }

            PanelView.Modes -> DrillView(
                sheet = sheet,
                title = "What can the agent do without asking?",
                rowsBelowBack = 0,
                onBack = back,
            ) {
                val p = permission
                if (p != null) p.modes.forEach { m ->
                    DrillOption(
                        sheet = sheet,
                        selected = m.id == p.current?.id,
                        icon = {
                            Icon(
                                if (m.id == "plan") Icons.Outlined.Visibility else Icons.Outlined.Shield,
                                null,
                                tint = if (m.default) cs.onSurfaceVariant else permissionInk(m.id),
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        title = m.label + if (m.default) "  · default" else "",
                        detail = m.description,
                        tag = "permissions-${m.id}",
                    ) {
                        haptic.perform(HapticKind.Tick)
                        p.onPick(m.id)
                        back()
                    }
                }
            }
        }
    }
}

/**
 * A drilled-in list at the panel's held height: a caption, the options filling the space (they
 * scroll only if they truly do not fit), and "‹ Back" on the bottom row — the row that was clicked
 * to get here. [rowsBelowBack] blank rows under it keep it level with that row.
 */
@Composable
private fun ColumnScope.DrillView(
    sheet: Boolean,
    title: String,
    rowsBelowBack: Int,
    onBack: () -> Unit,
    options: @Composable ColumnScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val listPad = if (sheet) 8.dp else 6.dp
    Text(
        title,
        color = cs.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = listPad + 10.dp, end = listPad, top = if (sheet) 4.dp else 12.dp, bottom = 6.dp),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = listPad),
        content = options,
    )
    HorizontalDivider(Modifier.padding(top = 4.dp), color = cs.onSurface.copy(alpha = 0.07f))
    Column(Modifier.padding(horizontal = listPad, vertical = 6.dp)) {
        SettingRow(
            sheet,
            icon = { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
            label = "Back",
            onClick = onBack,
            tag = "run_settings_back",
        ) {}
        repeat(rowsBelowBack) { Spacer(Modifier.height(if (sheet) 48.dp else 36.dp)) }
    }
}

/**
 * The Account list. A new chat lists Auto first (the default); a running chat lists the accounts it
 * can move to. Each row says how it signs in and how full its busiest window is, tagged when it keeps
 * its own history or its API refused it; the current one is checked, and picking it just steps back.
 * A running chat's switch spins on its row until it lands (the panel then steps back by itself) or is
 * refused, and the reason shows under the rows.
 */
@Composable
private fun AccountOptions(sheet: Boolean, account: RunSettingsAccount, onBack: () -> Unit, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    val haptic = rememberHaptics()
    val pick: (String?) -> Unit = { id ->
        if (account.pendingId == null) {
            haptic.perform(HapticKind.Tick)
            // The system login is a choice of its own: its id, which the broker resolves.
            if (id == account.selectedId) onBack() else account.onSelect(id)
        }
    }
    val current = account.current
    if (current != null && account.unavailable) {
        // An expired system sign-in is fixed by signing in again, not by anything under Accounts.
        val signIn = account.onSignIn?.takeIf { current.system && signInExpired(current) }
        UnavailableNotice(
            current,
            account.onOpenAccounts?.takeIf { signIn == null }?.let { open -> { onDismiss(); open() } },
            signIn?.let { open -> { onDismiss(); open() } },
        )
    }
    if (account.offersAuto) {
        // Auto first, and the default: the account with the most room left when the chat starts.
        // A chosen account this agent no longer has falls back to Auto, so Auto shows checked.
        DrillOption(
            sheet = sheet,
            selected = current == null,
            icon = { Icon(Icons.Outlined.AccountCircle, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
            title = "Auto",
            detail = account.autoPick?.let { "Most room left · now ${accountShortLabel(it)}" } ?: "The account with the most room left",
            tag = "account-picker-item:auto",
        ) { pick(null) }
    }
    if (account.accounts.isEmpty()) {
        Text(
            "Loading accounts…",
            color = cs.onSurfaceVariant,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
    account.accounts.forEach { a ->
        DrillOption(
            sheet = sheet,
            selected = a.id == current?.id,
            busy = a.id == account.pendingId,
            icon = { Icon(Icons.Outlined.AccountCircle, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)) },
            title = accountShortLabel(a),
            badges = {
                if (a.isolated) AccountTag("Isolated", sem.warning)
                // A tag, not a block: picking it is how the user retries it.
                if (a.unavailable != null) AccountTag("Unavailable", sem.danger, Modifier.testTag("account-unavailable-tag:${a.id}"))
            },
            detail = accountPickerDetail(a).takeIf { it.isNotBlank() },
            tag = "account-picker-item:${a.id}",
        ) { pick(a.id) }
    }
    account.refusal?.let { RefusalLine(it) }
}

/** Atop the Account list while the current account is unavailable: why, and where to log in again. */
@Composable
private fun UnavailableNotice(account: AccountDto, onOpenAccounts: (() -> Unit)?, onSignIn: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val danger = LocalSemantics.current.danger
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp)
            .testTag("run_settings_account_unavailable"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.WarningAmber, null, tint = danger, modifier = Modifier.size(16.dp))
            Text(
                "${accountShortLabel(account)} is unavailable",
                color = cs.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(Modifier.padding(start = 26.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            accountUnavailableText(account)?.let {
                Text(it, color = cs.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (onOpenAccounts != null) NoticeLink("Open Accounts", onOpenAccounts, "run_settings_open_accounts")
            if (onSignIn != null) NoticeLink("Sign in again", onSignIn, "run_settings_sign_in")
        }
    }
    HorizontalDivider(Modifier.padding(bottom = 4.dp), color = cs.onSurface.copy(alpha = 0.07f))
}

@Composable
private fun NoticeLink(label: String, onClick: () -> Unit, tag: String) {
    Text(
        label,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(vertical = 2.dp)
            .testTag(tag),
    )
}

/** Why the last pick was refused (busy, not running…): one quiet line under the rows. */
@Composable
private fun RefusalLine(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("run_settings_account_refusal"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = LocalSemantics.current.warning, modifier = Modifier.size(16.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
    }
}

/** The Account row's tone, and the pill mark's: danger when refused, primary on an added account. */
@Composable
internal fun accountInk(account: RunSettingsAccount): Color? = when {
    account.unavailable -> LocalSemantics.current.danger
    account.onAdded -> MaterialTheme.colorScheme.primary
    else -> null
}

/** A pick being applied. */
@Composable
private fun Spinner() {
    CircularProgressIndicator(strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
}

/**
 * One option of a drilled-in list: icon · title (and its [badges]) over a one-line detail · a check
 * when current, a spinner while [busy].
 */
@Composable
private fun DrillOption(
    sheet: Boolean,
    selected: Boolean,
    icon: @Composable () -> Unit,
    title: String,
    detail: String?,
    tag: String,
    busy: Boolean = false,
    badges: (@Composable RowScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(if (sheet) 14.dp else 10.dp))
            .background(if (selected) cs.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (sheet) 10.dp else 7.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    color = cs.onSurface,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                badges?.invoke(this)
            }
            if (detail != null) {
                Text(detail, color = cs.onSurfaceVariant, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        when {
            busy -> Box(Modifier.padding(start = 8.dp).size(16.dp), contentAlignment = Alignment.Center) { Spinner() }
            selected -> Icon(Icons.Filled.Check, null, tint = cs.primary, modifier = Modifier.padding(start = 8.dp).size(16.dp))
        }
    }
}

/** A drill-in row's chevron: this row opens its own list. */
@Composable
private fun DrillChevron() {
    Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
}

@Composable
private fun ModelRowItem(
    row: ModelRow,
    agent: String?,
    sheet: Boolean,
    selected: Boolean,
    highlighted: Boolean,
    starred: Boolean,
    onHover: () -> Unit,
    onStar: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (sheet) 50.dp else 36.dp)
            .clip(RoundedCornerShape(if (sheet) 14.dp else 10.dp))
            .background(
                when {
                    selected -> cs.onSurface.copy(alpha = 0.08f)
                    highlighted -> cs.onSurface.copy(alpha = 0.05f)
                    else -> Color.Transparent
                },
            )
            .hoverable(hover)
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 6.dp)
            .testTag("composer-model-${row.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.id != DEFAULT_MODEL_ID && agent != null && hasAgentLogo(agent)) {
            AgentLogo(agent, size = 15.dp)
        } else {
            Icon(Icons.Filled.AutoAwesome, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(
            row.name,
            color = cs.onSurface,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
        if (row.secondary != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                row.secondary,
                color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                fontSize = 11.5.sp,
                fontFamily = if (row.id == DEFAULT_MODEL_ID) null else GeistMonoFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (selected) {
            Icon(Icons.Filled.Check, null, tint = cs.primary, modifier = Modifier.padding(start = 6.dp).size(16.dp))
        }
        // The star shows where it matters: on a starred row, on the row under the pointer, always on touch.
        if (onStar != null && (starred || hovered || highlighted || sheet)) {
            Box(
                Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(Radii.pill))
                    .clickable(onClick = onStar)
                    .testTag("composer-model-star-${row.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (starred) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    if (starred) "Unstar" else "Star",
                    tint = if (starred) cs.tertiary else cs.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(15.dp),
                )
            }
        } else if (onStar != null) {
            Spacer(Modifier.width(28.dp))
        }
    }
}

/**
 * The launcher's agent switcher: the chosen agent as logo + name, every other agent as just its
 * logo — so five or six agents fit one row with no scrolling and nothing cut off at the edge.
 */
@Composable
internal fun AgentSwitcher(
    agents: List<String>,
    selected: String,
    enabled: Boolean,
    sheet: Boolean,
    onSelect: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (sheet) 16.dp else 10.dp, vertical = 2.dp)
            .testTag("launcher_agent_row"),
        horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        agents.forEach { a ->
            val on = a == selected
            Row(
                Modifier
                    .testTag("agent_$a")
                    .height(if (sheet) 40.dp else 32.dp)
                    .clip(RoundedCornerShape(Radii.pill))
                    .background(if (on) cs.onSurface.copy(alpha = 0.09f) else Color.Transparent)
                    .clickable(enabled = enabled) { onSelect(a) }
                    .then(if (on) Modifier.padding(start = 10.dp, end = 12.dp) else Modifier.width(if (sheet) 40.dp else 32.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
            ) {
                AgentLogo(a, size = if (sheet) 18.dp else 15.dp)
                if (on) {
                    Text(agentDisplayName(a), color = cs.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

/** A settings row: icon · label on the left, its control (or current value) on the right. */
@Composable
private fun SettingRow(
    sheet: Boolean,
    icon: @Composable () -> Unit,
    label: String,
    onClick: (() -> Unit)? = null,
    tag: String? = null,
    /** Its list is the one open in the panel's middle. */
    active: Boolean = false,
    /** Non-null makes the whole row a switch (on/off semantics) rather than a button. */
    toggled: Boolean? = null,
    control: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (sheet) 48.dp else 36.dp)
            .clip(RoundedCornerShape(if (sheet) 14.dp else 10.dp))
            .background(if (active) cs.onSurface.copy(alpha = 0.07f) else Color.Transparent)
            .then(
                when {
                    toggled != null -> Modifier.toggleable(value = toggled, role = Role.Switch, onValueChange = { onClick?.invoke() })
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                },
            )
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(start = 10.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.width(10.dp))
        Text(label, color = cs.onSurface, fontSize = 13.sp, maxLines = 1, softWrap = false, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { control() }
    }
}

/** A small three-bar meter — the effort row's icon. */
@Composable
private fun EffortGlyph() {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(1.5.dp), verticalAlignment = Alignment.Bottom) {
        listOf(5.dp, 9.dp, 13.dp).forEach { h ->
            Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(1.dp)).background(cs.onSurfaceVariant))
        }
    }
}

/** Effort levels low → high as one pill-shaped segmented control. */
@Composable
private fun EffortSegments(reasoning: ReasoningResponse, current: String?, sheet: Boolean, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(Radii.pill))
            .background(cs.onSurface.copy(alpha = 0.06f))
            .padding(2.dp),
    ) {
        sortEffortLevelsLowToHigh(reasoning.levels).forEach { level ->
            val selected = level.id == current
            Text(
                text = composerReasoningLevelLabel(level),
                color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                fontSize = if (sheet) 13.sp else 12.sp,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                modifier = Modifier
                    .testTag("composer-reasoning-${level.id}")
                    .clip(RoundedCornerShape(Radii.pill))
                    .background(if (selected) cs.onSurface.copy(alpha = 0.12f) else Color.Transparent)
                    .clickable { onPick(level.id) }
                    .padding(horizontal = if (sheet) 10.dp else 7.dp, vertical = if (sheet) 6.dp else 3.dp),
            )
        }
    }
}

/**
 * The panel as a popover ABOVE its anchor (the composer sits at the bottom of the window), its left
 * edge on the anchor's, kept inside the window. Registers with [ModalHost] like every menu, so no
 * heavyweight native view can paint over it.
 */
@Composable
internal fun RunSettingsPopover(
    onDismiss: () -> Unit,
    /** The roomier side of the anchor (above or below); the panel never grows past it. */
    maxHeight: Dp = Dp.Unspecified,
    content: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    // Dark surfaces need a whisper of an edge; light ones separate on the shadow alone.
    val dark = cs.surface.luminance() < 0.5f
    ModalHost {}
    Popup(
        popupPositionProvider = remember { AboveAnchor(gapPx = 18) },
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Box(
            Modifier
                .then(if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier)
                .padding(8.dp)
                .shadow(18.dp, shape, clip = false)
                .clip(shape)
                .background(cs.surfaceContainerHigh)
                .then(if (dark) Modifier.border(1.dp, cs.onSurface.copy(alpha = 0.06f), shape) else Modifier),
        ) { content() }
    }
}

private class AboveAnchor(private val gapPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Left edge on the anchor's. Above the anchor whenever it fits (the composer sits at the
        // bottom of the window); below it only when there is no room above but room below. The
        // panel has ONE fixed height, so this choice never changes while it is open — it cannot jump.
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.left - gapPx / 2).coerceIn(0, maxX)
        val h = popupContentSize.height
        val roomAbove = anchorBounds.top
        val roomBelow = windowSize.height - anchorBounds.bottom
        val y = when {
            h <= roomAbove -> anchorBounds.top - h
            h <= roomBelow -> anchorBounds.bottom
            roomAbove >= roomBelow -> (anchorBounds.top - h).coerceAtLeast(0)
            else -> anchorBounds.bottom.coerceAtMost((windowSize.height - h).coerceAtLeast(0))
        }
        return IntOffset(x, y)
    }
}
