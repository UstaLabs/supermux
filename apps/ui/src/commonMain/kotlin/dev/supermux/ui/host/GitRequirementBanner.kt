// "Developer tools not installed" (spec 2026-09-30 desktop hosting lifecycle). Git is OPTIONAL:
// agents run in any folder without it, and only the git features (worktrees, diffs, change
// tracking) wait for it. So this is a calm notice, never a "you are blocked" error. One banner for
// every client — desktop, phone, PWA — rendered from the broker's own requirement (`GET /host`
// `requirements.git` / the `host_requirements` frame), so there is no client-side git check.
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The banner's copy, as constants/functions so the tests assert the exact strings. */
object GitBannerCopy {
    /** The title on a Mac, where git comes with Apple's command line tools. */
    const val TITLE = "Developer tools not installed"
    /** The title everywhere else. */
    const val GIT_TITLE = "Git not installed"
    /** Says what git adds — and that agents do not need it. */
    const val OPTIONAL_MAC =
        "Agents work without them. Install Apple's command line tools to turn on git features: worktrees, diffs and change tracking."
    const val OPTIONAL = "Agents work without git. Install it to turn on worktrees, diffs and change tracking."
    const val INSTALL = "Install…"
    const val STARTING = "Starting…"
    const val FAILED = "Couldn't start the installer."
    const val RETRY = "Retry"

    /** The host's own report that its last install ended without git. */
    fun installFailed(error: String): String = "Couldn't install git: $error"

    /** "… on <host>" when a host is named; bare for the local / only host. */
    fun title(hostName: String?, install: String = GitRequirement.INSTALL_XCODE_SELECT): String {
        val base = if (install == GitRequirement.INSTALL_XCODE_SELECT) TITLE else GIT_TITLE
        return hostName?.let { "$base on $it" } ?: base
    }

    fun optional(install: String): String =
        if (install == GitRequirement.INSTALL_XCODE_SELECT) OPTIONAL_MAC else OPTIONAL

    /** What happened on [hostName] (null: "this computer") after a successful Install…, per kind. */
    fun started(install: String, hostName: String?): String {
        val host = hostName ?: "this computer"
        return when (install) {
            GitRequirement.INSTALL_XCODE_SELECT -> "Apple's installer is open on $host. Follow it, then this clears by itself."
            GitRequirement.INSTALL_WINGET -> "Installing git on $host… this clears by itself when done."
            GitRequirement.INSTALL_MINGIT -> "Installing git on $host…"
            GitRequirement.INSTALL_BROWSER -> "The Git download page is open on $host."
            else -> "The installer started on $host."
        }
    }
}

/** Test tags, shared by every place the banner is shown. */
object GitBannerTags {
    const val BANNER = "git_required_banner"
    const val TITLE = "git_required_title"
    /** What git would turn on (and that agents run without it). */
    const val HINT = "git_required_hint"
    /** The host's own how-to (e.g. the apt command); not shown on a Mac, where [HINT] says it. */
    const val HOW = "git_required_how"
    const val INSTALL = "git_required_install"
    const val STATUS = "git_required_status"
}

/**
 * The "git is not installed" notice, or nothing when [requirement] is satisfied. Informational:
 * nothing in the app is blocked on it.
 *
 * @param requirement the host's `requirements.git`.
 * @param onInstall `POST /system/install-git` on that host; true when the installer started. Only
 *   offered when the host has a one-click install (`install != "manual"`).
 * @param hostName names the host in the title and status ("Git not installed on <host>") where more than one
 *   host could be meant; null for the local host or a single-host view ("This computer…").
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
    // The host's own report wins over what this client saw after its click: a winget run that was
    // declined or failed must not leave "Installing…" up forever.
    LaunchedEffect(requirement.installError) { if (requirement.installError != null) status = null }
    val shown = requirement.installError?.let(GitBannerCopy::installFailed)
        ?: (if (requirement.installing) requirement.installNote ?: GitBannerCopy.started(requirement.install, hostName) else null)
        ?: status

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Space.sm))
            .background(cs.surfaceContainerHighest)
            .padding(horizontal = Space.md, vertical = Space.sm)
            .testTag(GitBannerTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Info,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                GitBannerCopy.title(hostName, requirement.install),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurface,
                modifier = Modifier.testTag(GitBannerTags.TITLE),
            )
            Text(
                GitBannerCopy.optional(requirement.install),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.testTag(GitBannerTags.HINT),
            )
            if (requirement.hint.isNotBlank() && requirement.install != GitRequirement.INSTALL_XCODE_SELECT) {
                Text(
                    requirement.hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.testTag(GitBannerTags.HOW),
                )
            }
            shown?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
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
                        val ok = try {
                            onInstall()
                        } catch (c: CancellationException) {
                            busy = false
                            throw c
                        } catch (_: Throwable) {
                            false
                        }
                        status = if (ok) GitBannerCopy.started(requirement.install, hostName) else GitBannerCopy.FAILED
                        busy = false
                    }
                },
                enabled = !busy && !requirement.installing,
                modifier = Modifier.testTag(GitBannerTags.INSTALL),
            ) {
                Text(
                    when {
                        busy -> GitBannerCopy.STARTING
                        requirement.installError != null -> GitBannerCopy.RETRY
                        else -> GitBannerCopy.INSTALL
                    },
                )
            }
        }
    }
}
