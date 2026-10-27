// "Choose an agent": the launcher's panel for a project with no agent to run it. One card per agent,
// installed in place through the broker's install jobs (the same ones Settings ▸ Agents runs).
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.installFailureMessage
import dev.supermux.ui.settings.normalizeInstallState
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How each agent introduces itself on its card; an agent the broker adds later still gets a card. */
private val AGENT_CARDS = linkedMapOf(
    "claude" to ("Claude Code" to "Anthropic's coding agent"),
    "codex" to ("Codex" to "OpenAI's coding agent"),
    "opencode" to ("OpenCode" to "Open source, works with many models"),
    "cursor" to ("Cursor" to "Cursor's agent for the terminal"),
    "grok" to ("Grok" to "xAI's coding agent"),
    "antigravity" to ("Antigravity" to "Google's coding agent"),
)

private const val INSTALL_POLL_MS = 1_000L
/** An install is given ten minutes (a slow network, a big download) before the card gives up. */
private const val INSTALL_MAX_POLLS = 600
private const val INSTALL_NULL_LIMIT = 8

/** One card's install, as the card shows it. */
private sealed interface CardInstall {
    data object Idle : CardInstall
    data object Running : CardInstall
    data object Done : CardInstall
    data class Failed(val message: String) : CardInstall
}

/**
 * @param actions the host's agent actions (statuses + install jobs).
 * @param onStatusesChanged every fresh status list, so the launcher sees an install land and swaps
 *   back to the composer by itself.
 * @param message the broker's own refusal when a spawn just came back `agent_not_installed`.
 */
@Composable
fun InstallAgentPanel(
    actions: AgentSettingsActions,
    onStatusesChanged: (List<AgentInstallStatus>) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    stacked: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var statuses by remember { mutableStateOf<List<AgentInstallStatus>?>(null) }
    val installs = remember { mutableStateMapOf<String, CardInstall>() }

    suspend fun refresh() {
        actions.agentStatuses()?.let { statuses = it; onStatusesChanged(it) }
    }

    suspend fun watch(kind: String, first: AgentInstallJob?) {
        var job = first
        var polls = 0
        var nulls = 0
        while (true) {
            when (normalizeInstallState(job?.state)) {
                "done" -> {
                    installs[kind] = CardInstall.Done
                    refresh()
                    return
                }
                "failed" -> {
                    installs[kind] = CardInstall.Failed(installFailureMessage(job))
                    return
                }
            }
            if (polls++ >= INSTALL_MAX_POLLS) {
                installs[kind] = CardInstall.Failed("This is taking longer than expected. Check Settings ▸ Agents for progress.")
                return
            }
            delay(INSTALL_POLL_MS)
            val next = actions.agentPollInstall(kind)
            if (next == null) {
                if (++nulls >= INSTALL_NULL_LIMIT) {
                    installs[kind] = CardInstall.Failed("Lost touch with the host. Check the connection and try again.")
                    return
                }
                continue
            }
            nulls = 0
            job = next
        }
    }

    fun install(kind: String) {
        if (installs[kind] == CardInstall.Running) return
        installs[kind] = CardInstall.Running
        scope.launch {
            val started = actions.agentStartInstall(kind)
            if (started == null) installs[kind] = CardInstall.Failed("Couldn't start the install. Check the connection and try again.")
            else watch(kind, started)
        }
    }

    LaunchedEffect(actions) {
        refresh()
        // An install already running on the host (started here before, or from Settings) is picked up.
        statuses.orEmpty().filter { !it.installed }.forEach { s ->
            val job = actions.agentPollInstall(s.kind)
            if (job != null && normalizeInstallState(job.state) == "running") {
                installs[s.kind] = CardInstall.Running
                launch { watch(s.kind, job) }
            }
        }
    }

    Column(
        modifier.fillMaxWidth().testTag(LauncherFirstRunTags.INSTALL_AGENT),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                "Choose an agent",
                color = cs.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                message ?: "Supermux runs your chats on a coding agent. Pick one to install — it takes about a minute.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag(LauncherFirstRunTags.INSTALL_AGENT_MESSAGE),
            )
            Spacer(Modifier.height(Space.lg))
            val list = statuses
            if (list == null) {
                CircularProgressIndicator(Modifier.padding(Space.xl).size(28.dp), strokeWidth = Stroke.thin)
            } else {
                val kinds = (AGENT_CARDS.keys.filter { k -> list.any { it.kind == k } } + list.map { it.kind }).distinct()
                val busy = installs.values.any { it == CardInstall.Running }
                val rows = kinds.chunked(if (stacked) 1 else 2)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    for (row in rows) {
                        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                            for (kind in row) {
                                val state = installs[kind] ?: CardInstall.Idle
                                AgentCard(
                                    kind = kind,
                                    installed = list.firstOrNull { it.kind == kind }?.installed == true,
                                    state = state,
                                    dimmed = busy && state != CardInstall.Running,
                                    onInstall = { install(kind) },
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                            if (row.size == 1 && !stacked) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                Spacer(Modifier.height(Space.sm))
                Text(
                    "You can add or sign in to agents later in Settings ▸ Agents.",
                    color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun AgentCard(
    kind: String,
    installed: Boolean,
    state: CardInstall,
    dimmed: Boolean,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.lg + 4.dp)
    val (name, tagline) = AGENT_CARDS[kind] ?: (kind.replaceFirstChar { it.uppercase() } to "Coding agent")
    val running = state == CardInstall.Running
    Column(
        modifier
            .alpha(if (dimmed) 0.55f else 1f)
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(Stroke.hairline, if (running) cs.primary.copy(alpha = 0.6f) else cs.outlineVariant.copy(alpha = 0.6f), shape)
            .testTag(LauncherFirstRunTags.agentCard(kind)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(Space.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(Radii.md)).background(cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { AgentLogo(kind, 24.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, color = cs.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        running -> "Installing $name…"
                        else -> tagline
                    },
                    color = if (running) cs.primary else cs.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            when {
                installed || state == CardInstall.Done -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Installed",
                    tint = cs.primary,
                    modifier = Modifier.size(24.dp),
                )
                running -> Unit
                state is CardInstall.Failed -> TextButton(onClick = onInstall, modifier = Modifier.testTag(LauncherFirstRunTags.agentInstall(kind))) {
                    Text("Try again")
                }
                else -> FilledTonalButton(onClick = onInstall, modifier = Modifier.testTag(LauncherFirstRunTags.agentInstall(kind))) {
                    Text("Install")
                }
            }
        }
        if (running) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().height(3.dp).testTag(LauncherFirstRunTags.agentProgress(kind)),
                color = cs.primary,
                trackColor = cs.primary.copy(alpha = 0.12f),
            )
        }
        if (state is CardInstall.Failed) {
            Box(Modifier.padding(start = Space.lg, end = Space.lg, bottom = Space.md)) {
                ErrorLine(state.message, tag = LauncherFirstRunTags.agentError(kind))
            }
        }
    }
}
