// The first-run setup wizard (Vue `SetupView.vue` + `views/setup/*`), ported into `:ui`.
//
// A LINEAR stepper, not a pager: five fixed steps, a header that names the current one, a progress
// bar, and a footer with Back / Next. Three of the five steps are screens that already exist —
// `AgentSettingsScreen` and `GitHostingScreen` render inside the body untouched, which is the whole
// point of the port: setup and Settings must never drift into two implementations of "connect an
// agent".
//
// Not [Intro]: that is the pairing story (a full-bleed animated pager for a device that has no
// broker yet). This runs AFTER pairing, against a broker that reports `onboarded=false`, and it is
// a form — so it borrows Intro's typography and the brand mark but none of its choreography.
//
// Mandatory by design: there is no Skip and no exit. Escape is deliberately NOT handled — a fresh
// broker has nothing behind this screen to escape TO, and the host renders it above the shell (the
// pairing gate's placement). "Skipping" a step is what Next does: only the Agents step gates.
package dev.supermux.ui.intro

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.net.AgentInstallStatus
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.mux_logo
import dev.supermux.ui.settings.AgentSettingsActions
import dev.supermux.ui.settings.AgentSettingsScreen
import dev.supermux.ui.settings.DevicesSettingsActions
import dev.supermux.ui.settings.GitHostingActions
import dev.supermux.ui.settings.GitHostingScreen
import dev.supermux.ui.theme.IconSize
import dev.supermux.ui.theme.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

/** The five steps, in order — Vue's `ONBOARDING_STEP_LABELS`. */
val SetupStepLabels = listOf("Welcome", "Agents", "Git Hosting", "Connect Your Phone", "Done")

private const val STEP_WELCOME = 0
private const val STEP_AGENTS = 1
private const val STEP_FORGES = 2
private const val STEP_PHONE = 3
private const val STEP_DONE = 4

/** Agent kind the broker reports as usable with no credentials at all (opencode's free tier). */
private const val FREE_TIER_KIND = "opencode"

/**
 * Can the user leave the Agents step? At least one agent must be usable.
 *
 * PARITY NOTE. Vue's rule (`AgentLoginPanel.vue:94`) is
 * `statuses.some(s => s.authed || (caps(s).usableWithoutAuth && s.installed))`, where `caps`
 * reads `capabilities.usableWithoutAuth` off `GET /agents/status` and falls back to
 * `kind === "opencode"` when the broker is older than that field. `AgentInstallStatus` decodes
 * only `kind`/`installed`/`authed` — no `capabilities` object — so this takes Vue's own fallback
 * instead of widening the DTO: an INSTALLED opencode counts, which is the only free-tier agent the
 * broker has ever reported (`src/core/agents/capabilities.ts:51` sets the flag for exactly that
 * kind, and [dev.supermux.ui.settings.statusLabel] already labels it "Ready · free tier" on the
 * same test). If the broker ever marks a second kind usable-without-auth, decode `capabilities`
 * into `AgentInstallStatus` and read it here.
 *
 * An EMPTY list is `false`, and so is a list the screen never managed to load: a broker that
 * reports no agents at all leaves Next disabled, by design and in parity with Vue — the screen
 * offers Install for every kind it knows, and there is nothing to set up past this step without
 * one. `AgentSettingsScreen` only reports lists it actually loaded, so a failed `GET
 * /agents/status` keeps the last answer (and its own auto-retry recovers) instead of un-gating.
 */
internal fun setupAgentsCanProceed(statuses: List<AgentInstallStatus>): Boolean =
    statuses.any { it.authed || (it.installed && it.kind == FREE_TIER_KIND) }

/**
 * The broker's first-run setup, as five linear steps.
 *
 * Rendered by the host ABOVE the shell (like the pairing gate) while `Caps.setupWizard` is set and
 * the broker reports `onboarded == false`; it never decides on a `null` flag.
 *
 * @param agents wiring for the Agents step — the real [AgentSettingsScreen].
 * @param forges wiring for the Git Hosting step — the real [GitHostingScreen].
 * @param devices wiring for the phone-pairing step ([SetupPhoneStep]).
 * @param onFinish flip the broker's `onboarded` flag (`PUT /settings/config`). `false` = the write
 *   failed: the wizard stays on Done and shows an inline error rather than dropping the user into a
 *   shell the route guard would bounce them out of on the next load.
 * @param onCreateFirstSession called only after [onFinish] succeeds — the host routes to its new
 *   session screen (Vue's `router.push("/new")`).
 * @param scope MUST be the app scope — a scope that outlives this whole screen. It carries
 *   [SetupPhoneStep]'s leave-the-step revoke of an unused pairing link, and that link is a
 *   host-wide bearer credential, so the revoke must survive the two things that kill a scope taken
 *   HERE: the host swapping the wizard out the instant `onboarded` flips to `true` (mid-revoke —
 *   it is two requests, a list then a DELETE), and Compose running the phone step's `onDispose`
 *   BEFORE cancelling the wizard's own scope on teardown, which would cancel the launched revoke
 *   before it ever dispatched. Deliberately has no default for that reason.
 */
@Composable
fun SetupWizardScreen(
    agents: AgentSettingsActions,
    forges: GitHostingActions,
    devices: DevicesSettingsActions,
    onFinish: suspend () -> Boolean,
    onCreateFirstSession: () -> Unit,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var step by rememberSaveable { mutableStateOf(STEP_WELCOME) }
    // Not saveable: it is re-derived from the next `onStatusesChanged` the Agents step emits, and a
    // restored `true` against a broker that has since lost its credential would un-gate wrongly.
    var agentsCanProceed by remember { mutableStateOf(false) }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("setup_wizard"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(cs.surfaceContainerHigh)
                .padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Setup",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurface,
                modifier = Modifier.weight(1f),
            )
            // ONE text node, not "Step N of 5" + a styled label: the header is what the browser
            // run (and these tests) match on, and two nodes cannot be matched as one string.
            Text(
                "Step ${step + 1} of ${SetupStepLabels.size} — ${SetupStepLabels[step]}",
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.testTag("setup_header"),
            )
        }
        LinearProgressIndicator(
            progress = { (step + 1).toFloat() / SetupStepLabels.size },
            modifier = Modifier.fillMaxWidth().height(Space.xs).testTag("setup_progress"),
            color = cs.primary,
            trackColor = cs.surfaceVariant,
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (step) {
                STEP_WELCOME -> SetupWelcomeStep()
                STEP_AGENTS -> AgentSettingsScreen(
                    actions = agents,
                    topBarShown = true,
                    onStatusesChanged = { agentsCanProceed = setupAgentsCanProceed(it) },
                )
                STEP_FORGES -> GitHostingScreen(actions = forges, topBarShown = true)
                STEP_PHONE -> SetupPhoneStep(devices, scope)
                else -> SetupDoneStep(
                    onFinish = onFinish,
                    onCreateFirstSession = onCreateFirstSession,
                    scope = scope,
                )
            }
        }

        // Done owns its own single action, so the footer disappears there (Vue's `v-if`).
        if (step < STEP_DONE) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainerHigh)
                    .padding(horizontal = Space.lg, vertical = Space.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement =
                    if (step == STEP_WELCOME) Arrangement.Center else Arrangement.SpaceBetween,
            ) {
                if (step > STEP_WELCOME) {
                    TextButton(
                        onClick = { step-- },
                        modifier = Modifier.testTag("setup_back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(IconSize.md),
                        )
                        Spacer(Modifier.size(Space.sm))
                        Text("Back")
                    }
                }
                Button(
                    onClick = { step++ },
                    enabled = step != STEP_AGENTS || agentsCanProceed,
                    modifier = Modifier.testTag("setup_next"),
                ) {
                    Text(if (step == STEP_WELCOME) "Start" else "Next")
                    if (step > STEP_WELCOME) {
                        Spacer(Modifier.size(Space.sm))
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(IconSize.md),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupWelcomeStep() {
    val cs = MaterialTheme.colorScheme
    SetupCenteredColumn {
        Icon(
            painter = painterResource(Res.drawable.mux_logo),
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            "Welcome to supermux",
            style = MaterialTheme.typography.headlineSmall,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            "supermux drives coding agents from your phone or laptop. Let's connect one and get " +
                "you set up in just a few steps.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * @param scope the app scope, for the same reason the phone step needs it: a successful [onFinish]
 *   flips `onboarded`, the host swaps the wizard out on that flip, and a `rememberCoroutineScope()`
 *   here would be cancelled between the write landing and [onCreateFirstSession] running — leaving
 *   the user in the shell with no session and no idea setup had finished.
 */
@Composable
private fun SetupDoneStep(
    onFinish: suspend () -> Boolean,
    onCreateFirstSession: () -> Unit,
    scope: CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
    var finishing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    SetupCenteredColumn {
        Icon(
            painter = painterResource(Res.drawable.mux_logo),
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            "You're all set!",
            style = MaterialTheme.typography.headlineSmall,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            "supermux is ready — create a session for the project you want to work on.",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Space.sm))
        Button(
            onClick = {
                if (finishing) return@Button
                finishing = true
                error = null
                scope.launch {
                    val ok = onFinish()
                    finishing = false
                    if (ok) {
                        onCreateFirstSession()
                    } else {
                        error = "Couldn't finish setup. Check the connection and try again."
                    }
                }
            },
            enabled = !finishing,
            modifier = Modifier.testTag("setup_done"),
        ) {
            if (finishing) {
                CircularProgressIndicator(
                    color = cs.onPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(IconSize.md),
                )
                Spacer(Modifier.size(Space.sm))
            }
            Text("Create your first session")
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = cs.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("setup_done_error"),
            )
        }
    }
}

/** The shared body frame for the two steps this file paints itself. */
@Composable
private fun SetupCenteredColumn(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = IntroPageMaxWidth)
                .padding(horizontal = Space.xl, vertical = Space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            content()
        }
    }
}
