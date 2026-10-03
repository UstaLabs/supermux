// "This computer needs git to run agents" (spec 2026-09-30 desktop hosting lifecycle, "Git is
// required for hosting agents"). One banner for every client — desktop, phone, PWA — rendered
// from the broker's own requirement (`GET /host` `requirements.git` / the `host_requirements`
// frame), so there is no client-side git check anywhere.
package dev.supermux.ui.host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.net.GitRequirement
import dev.supermux.ui.theme.Space
import kotlinx.coroutines.launch

/** The banner's copy, as constants so the tests assert the exact strings. */
object GitBannerCopy {
    const val TITLE = "This computer needs git to run agents"
    const val INSTALL = "Install…"
    const val STARTING = "Starting…"
    const val STARTED = "The installer is open on that computer. This clears once git is found."
    const val FAILED = "Couldn't start the installer."
}

/** Test tags, shared by every place the banner is shown. */
object GitBannerTags {
    const val BANNER = "git_required_banner"
    const val HINT = "git_required_hint"
    const val HOST = "git_required_host"
    const val INSTALL = "git_required_install"
    const val STATUS = "git_required_status"
}

/**
 * The "needs git" banner, or nothing when [requirement] is satisfied.
 *
 * @param requirement the host's `requirements.git`.
 * @param onInstall `POST /system/install-git` on that host; true when the installer started. Only
 *   offered when the host has a one-click install (`install != "manual"`).
 * @param hostName shown under the title when more than one host could be meant (multi-host lists).
 */
@Composable
fun GitRequirementBanner(
    requirement: GitRequirement?,
    onInstall: suspend () -> Boolean,
    modifier: Modifier = Modifier,
    hostName: String? = null,
) {
    if (requirement == null || requirement.ok) return
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember(requirement.install) { mutableStateOf<String?>(null) }

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.sm))
            .background(cs.errorContainer)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .testTag(GitBannerTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Warning,
            contentDescription = null,
            tint = cs.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(GitBannerCopy.TITLE, style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer)
            hostName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onErrorContainer,
                    modifier = Modifier.testTag(GitBannerTags.HOST),
                )
            }
            if (requirement.hint.isNotBlank()) {
                Text(
                    requirement.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onErrorContainer,
                    modifier = Modifier.testTag(GitBannerTags.HINT),
                )
            }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onErrorContainer,
                    modifier = Modifier.testTag(GitBannerTags.STATUS),
                )
            }
        }
        if (requirement.installable) {
            Spacer(Modifier.width(Space.sm))
            OutlinedButton(
                onClick = {
                    busy = true
                    scope.launch {
                        val ok = runCatching { onInstall() }.getOrDefault(false)
                        status = if (ok) GitBannerCopy.STARTED else GitBannerCopy.FAILED
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.testTag(GitBannerTags.INSTALL),
            ) { Text(if (busy) GitBannerCopy.STARTING else GitBannerCopy.INSTALL) }
        }
    }
}
