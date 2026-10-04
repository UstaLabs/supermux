// The two places a chat meets its account (slice A3b): the new-chat launcher's picker, beside the
// agent/model pills, and the running session's pill in the chat header that switches it.
package dev.supermux.ui.accounts

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.AccountDto
import dev.supermux.net.SessionAccountResult
import dev.supermux.state.AccountResult
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import kotlinx.coroutines.launch

/** "Subscription · 5h 62%" — the second line of a picker row (the busiest window only). */
internal fun accountPickerDetail(account: AccountDto): String {
    val parts = mutableListOf<String>()
    if (account.system) {
        parts += account.identity?.email?.takeIf { it.isNotBlank() } ?: "This machine's login"
    } else {
        methodLabel(account.method)?.let { parts += it }
        account.identity?.email?.takeIf { it.isNotBlank() && it != account.label }?.let { parts += it }
    }
    account.usage.maxByOrNull { it.usedPercent }?.let {
        parts += "${usageWindowName(it.name)} ${kotlin.math.round(it.usedPercent.coerceIn(0.0, 100.0)).toInt()}%"
    }
    return parts.joinToString(" · ")
}

/** One row of either menu: label, detail line, a check on the current one, a spinner while busy. */
@Composable
private fun AccountMenuRow(
    account: AccountDto,
    selected: Boolean,
    busy: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    DropdownMenuItem(
        text = {
            Column(Modifier.widthIn(min = 180.dp, max = 280.dp).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        accountShortLabel(account),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (account.isolated) {
                        Text("isolated", style = MaterialTheme.typography.labelSmall, color = LocalSemantics.current.warning)
                    }
                }
                val detail = accountPickerDetail(account)
                if (detail.isNotBlank()) {
                    Text(
                        detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        trailingIcon = {
            when {
                busy -> CircularProgressIndicator(strokeWidth = 1.5.dp, modifier = Modifier.size(14.dp), color = cs.primary)
                selected -> Icon(Icons.Filled.Check, contentDescription = "Current", modifier = Modifier.size(16.dp), tint = cs.primary)
                else -> Box(Modifier.size(16.dp))
            }
        },
        modifier = Modifier.testTag(testTag),
        onClick = onClick,
    )
}

/** The menu's small heading ("Start this chat on"). */
@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Space.md, end = Space.md, top = Space.sm, bottom = Space.xs),
    )
}

/** The pill: an account glyph, the label, a caret. [accent] tints it (a non-system account). */
@Composable
private fun AccountPill(
    label: String,
    accent: Boolean,
    testTag: String,
    maxLabelWidth: Dp,
    onClick: () -> Unit,
    /** Glyph + caret only (a phone-width launcher toolbar has no room for a label). */
    iconOnly: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.pill)
    val fg = if (accent) cs.primary else cs.onSurfaceVariant
    Row(
        Modifier
            .clip(shape)
            .then(
                if (accent) Modifier.background(cs.primary.copy(alpha = 0.12f))
                else Modifier.border(Stroke.hairline, cs.outlineVariant, shape),
            )
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = 7.dp, end = 5.dp, top = 3.dp, bottom = 3.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            Icons.Outlined.AccountCircle,
            contentDescription = if (iconOnly) "Account: $label" else null,
            tint = fg,
            modifier = Modifier.size(14.dp),
        )
        if (!iconOnly) Text(
            label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = maxLabelWidth),
        )
        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = fg.copy(alpha = 0.75f), modifier = Modifier.size(14.dp))
    }
}

/**
 * The launcher's account picker. Renders nothing unless [agent] has more than its system login;
 * [selected] null = the system account (the default — nothing is remembered).
 */
@Composable
fun LauncherAccountPicker(
    agent: String,
    accounts: List<AccountDto>?,
    selected: String?,
    onSelect: (String?) -> Unit,
    enabled: Boolean = true,
) {
    if (!hasAccountChoice(accounts, agent)) return
    val list = accountsFor(accounts.orEmpty(), agent)
    val current = list.firstOrNull { it.id == selected } ?: list.firstOrNull { it.system }
    var open by remember { mutableStateOf(false) }
    Box {
        AccountPill(
            label = current?.let { accountShortLabel(it) } ?: "System login",
            accent = current != null && !current.system,
            testTag = "account-picker",
            maxLabelWidth = 120.dp,
            onClick = { if (enabled) open = true },
            // The default (the system login) is just the glyph: the toolbar is already full, and
            // only a chat that will run on ANOTHER account needs to say which.
            iconOnly = current == null || current.system,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            MenuHeading("Start this chat on")
            list.forEach { account ->
                AccountMenuRow(
                    account = account,
                    selected = account.id == current?.id,
                    busy = false,
                    testTag = "account-picker-item:${account.id}",
                    onClick = {
                        onSelect(if (account.system) null else account.id)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * The launcher's account choice as a section of the AGENT menu — used on a compact window, whose
 * composer toolbar has no room for one more pill. Renders nothing without a choice. The caller
 * closes its menu in [onSelect].
 */
@Composable
fun LauncherAccountMenuSection(
    agent: String,
    accounts: List<AccountDto>?,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    if (!hasAccountChoice(accounts, agent)) return
    val list = accountsFor(accounts.orEmpty(), agent)
    val current = list.firstOrNull { it.id == selected } ?: list.firstOrNull { it.system }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        modifier = Modifier.padding(vertical = Space.xs).testTag("account-picker-section"),
    )
    MenuHeading("${agentDisplayName(agent)} account")
    list.forEach { account ->
        AccountMenuRow(
            account = account,
            selected = account.id == current?.id,
            busy = false,
            testTag = "account-picker-item:${account.id}",
            onClick = { onSelect(if (account.system) null else account.id) },
        )
    }
}

/**
 * The running session's account pill + switch menu, for the chat header. Shown when the session
 * runs on an added account, or when its agent has more than one to switch between. A refusal
 * (409 busy / not running) stays in the open menu as one quiet line.
 */
@Composable
fun SessionAccountPill(
    agent: String,
    accountId: String?,
    accountLabel: String?,
    accounts: List<AccountDto>?,
    onSwitch: suspend (String) -> AccountResult<SessionAccountResult>,
    narrow: Boolean = false,
) {
    val onSystem = isSystemAccountId(accountId)
    if (onSystem && !hasAccountChoice(accounts, agent)) return
    val list = accountsFor(accounts.orEmpty(), agent)
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // A switch that landed (from this menu, another device or the auto-switch) clears a stale refusal.
    androidx.compose.runtime.LaunchedEffect(accountId) { error = null }
    val currentId = if (onSystem) list.firstOrNull { it.system }?.id ?: "$agent:system" else accountId
    val label = when {
        onSystem -> "System login"
        else -> accountLabel?.takeIf { it.isNotBlank() } ?: list.firstOrNull { it.id == accountId }?.let { accountShortLabel(it) } ?: accountId.orEmpty()
    }
    Box(Modifier.padding(end = Space.xs)) {
        AccountPill(
            label = label,
            accent = !onSystem,
            testTag = "session-account-pill",
            maxLabelWidth = if (narrow) 72.dp else 140.dp,
            onClick = { error = null; open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { if (busyId == null) open = false }) {
            MenuHeading("Run this chat on")
            if (list.isEmpty()) {
                Text(
                    "Loading accounts…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                )
            }
            list.forEach { account ->
                AccountMenuRow(
                    account = account,
                    selected = account.id == currentId,
                    busy = busyId == account.id,
                    testTag = "session-account-item:${account.id}",
                    onClick = {
                        if (account.id == currentId) {
                            open = false
                        } else if (busyId == null) {
                            busyId = account.id
                            error = null
                            scope.launch {
                                when (val r = onSwitch(account.id)) {
                                    is AccountResult.Ok -> open = false
                                    is AccountResult.Failed -> error = sessionSwitchFailureText(r.code, r.message)
                                }
                                busyId = null
                            }
                        }
                    },
                )
            }
            error?.let {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), modifier = Modifier.padding(vertical = Space.xs))
                Row(
                    Modifier.widthIn(max = 300.dp).padding(horizontal = Space.md, vertical = Space.xs).testTag("session-account-error"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = LocalSemantics.current.warning, modifier = Modifier.size(14.dp))
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
