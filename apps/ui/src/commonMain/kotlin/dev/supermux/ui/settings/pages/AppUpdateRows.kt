// General ▸ This app ▸ App version (#97 spec §11): the app updating ITSELF, inline — restyled from the deleted
// update page (cluster G5) over the same [AppUpdater] seam the sidebar's card reads, so what either shows is one
// check. Busy states are words in the description ("Checking…", "Downloading 42%…"), never spinners; one button at
// the row's end for the one thing to do next; an error is one line with Try again. Where the app does not update
// itself (iOS, web: `!caps.appUpdate`) the row is the version alone (inventory bug 9).
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.platform.AppUpdater
import dev.supermux.ui.platform.InFlightWork
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.UpdatePhase
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitRow
import dev.supermux.ui.settings.kit.KitActionRow
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitToggleRow
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.update.RESTART_CAPTION
import dev.supermux.ui.update.downloadUpdate
import dev.supermux.ui.update.finishingLabel
import dev.supermux.ui.update.formatUpdateProgress
import dev.supermux.ui.update.installCaption
import dev.supermux.ui.update.installerKindFrom
import dev.supermux.ui.work.rememberDownloads
import dev.supermux.ui.i18n.localized
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The App version row and what hangs under it: release notes, the highlights of a newer release, an error with
 * Try again, Android's install-permission step, and desktop's background-updates switch.
 */
@Composable
fun AppUpdateRows(
    modifier: Modifier = Modifier,
    updater: AppUpdater = LocalPlatform.current.updates,
) {
    val canUpdate = LocalPlatform.current.caps.appUpdate
    val update by updater.status.collectAsState()
    val s = update.release
    val version = (s?.currentVersion ?: updater.currentVersion).takeIf { it.isNotBlank() }
    val code = s?.currentVersionCode ?: updater.currentVersionCode
    val product = listOfNotNull("supermux", version).joinToString(" ") // i18n-ok: the brand and a version number
    val versionText = if (code != null) localized(Str.settings_update_version_build, product, code.toString()) else product
    val appVersion = localized(Str.settings_update_app_version)
    val rowModifier = modifier.kitAnchor("app-version").testTag("general_app-version")

    if (!canUpdate) {
        KitRow(appVersion, rowModifier, description = versionText, descriptionTag = "app_update_version")
        return
    }

    val c = kitColors()
    val scope = rememberCoroutineScope()
    val downloads = rememberDownloads()
    val loading = update.phase == UpdatePhase.Checking || update.phase == UpdatePhase.Idle
    val installing = update.busy
    // #113: downloaded in the background, waiting to be installed.
    val ready = update.ready
    val restarts = ready != null && updater.restartsToUpdate
    // "Install and restart" pressed while the app's own work runs: it waits, saying what for.
    var waiting by remember { mutableStateOf(false) }
    val work by InFlightWork.active.collectAsState()
    val checkFailed = s == null && !loading
    val feedError = s?.lastError?.takeIf { s.latestVersion == null }
    val installable = s != null && s.updateAvailable && s.canInstall && s.downloadUrl != null

    // The shell checks once per launch, and General is the page Settings opens on: only a device that has not
    // checked yet checks here. The seam itself no-ops a check while a download owns the phase.
    LaunchedEffect(updater) { if (updater.status.value.phase == UpdatePhase.Idle) updater.check() }

    fun check() {
        scope.launch { updater.check() }
    }

    fun download() {
        val release = updater.status.value.release ?: return
        scope.launch {
            // One of the fleet's downloads (#95): leaving the page does not cancel it. A refused install (Android's
            // unknown sources) raises needsInstallPermission and goes straight to the system page.
            downloadUpdate(downloads, updater, release) {
                if (updater.status.value.needsInstallPermission) updater.openInstallPermissionSettings()
            }
        }
    }

    fun installReady() {
        val installer = ready ?: return
        scope.launch {
            // The app's own work first (#113): an upload or a recording finishes, then the install goes ahead.
            waiting = true
            try {
                InFlightWork.active.first { it.isEmpty() }
            } finally {
                waiting = false
            }
            updater.install(installer)
        }
    }

    val state: String? = when {
        installing -> formatUpdateProgress(update.bytesReceived, update.contentLength)
        waiting -> finishingLabel(work)
        loading -> localized(Str.settings_update_checking)
        s == null || feedError != null -> null
        ready != null -> localized(Str.settings_update_downloaded, s.latestVersion.toString())
        s.updateAvailable -> localized(Str.settings_update_ready, s.latestVersion.toString())
        else -> localized(Str.settings_update_up_to_date)
    }
    val busy = installing || waiting || loading
    KitRow(
        appVersion,
        rowModifier,
        description = versionText + (state?.let { " · $it" } ?: ""),
        descriptionTag = "app_update_version",
        divider = false,
    ) {
        when {
            busy -> Unit
            ready != null -> KitGhostButton(
                localized(if (restarts) Str.settings_update_install_restart else Str.settings_update_install),
                onClick = { installReady() },
                modifier = Modifier.testTag("app_update_install"),
            )
            installable -> KitGhostButton(localized(Str.settings_update_download), onClick = { download() }, modifier = Modifier.testTag("app_update_install"))
            s == null || feedError != null || s.updateAvailable -> Unit
            else -> KitGhostButton(localized(Str.settings_update_check), onClick = { check() }, modifier = Modifier.testTag("general_check_updates"))
        }
    }

    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            checkFailed || feedError != null -> ErrorLine(
                feedError ?: update.error ?: localized(Str.settings_update_check_failed),
                tag = "app_update_message",
                onRetry = { check() },
            )
            s != null && s.updateAvailable -> {
                when {
                    ready != null -> if (restarts) KitNote(RESTART_CAPTION)
                    installable -> KitNote(installCaption(installerKindFrom(s.downloadUrl)))
                    // Deliberately installer-agnostic: this fires on every host, and only one of them ships an APK.
                    else -> KitNote(localized(Str.settings_update_no_installer))
                }
                if (s.highlights.isNotEmpty()) {
                    Column(Modifier.padding(top = 6.dp).testTag("app_update_whats_new")) {
                        for (line in s.highlights) {
                            Row {
                                Text("·", fontSize = 12.sp, lineHeight = 18.sp, color = c.faint, modifier = Modifier.width(12.dp))
                                Text(line, fontSize = 12.sp, lineHeight = 18.sp, color = c.dim)
                            }
                        }
                    }
                }
                if (s.notesUrl != null) {
                    KitTextAction(
                        localized(Str.settings_update_release_notes),
                        onClick = { updater.openReleaseNotes() },
                        modifier = Modifier.padding(top = 4.dp).testTag("app_update_notes"),
                    )
                }
            }
        }
        // The permission row below already IS the error text, and a failed check said it above: never twice.
        val error = update.error
        if (error != null && !update.needsInstallPermission && !checkFailed && feedError == null) {
            ErrorLine(error, tag = "app_update_error", onRetry = { if (installable) download() else check() })
        }
    }

    if (update.needsInstallPermission) {
        // Only a platform with an unknown-sources gate raises this (Android). Download already jumped to the system
        // page once; this row is what the user comes back to.
        KitActionRow(
            localized(Str.settings_update_allow_installs),
            button = localized(Str.settings_update_open_settings),
            onClick = { updater.openInstallPermissionSettings() },
            modifier = Modifier.testTag("app_update_permission"),
            description = update.error ?: localized(Str.settings_update_android_asks),
            buttonTag = "app_update_permission_action",
        )
    }
    if (updater.offersBackgroundUpdates) {
        KitToggleRow(
            localized(Str.settings_update_background),
            checked = update.background,
            onCheckedChange = { updater.setBackgroundUpdates(it) },
            modifier = Modifier.testTag("app_update_background"),
            description = localized(Str.settings_update_background_description),
        )
    }
}

/** A failure as one line in the error tone, with Try again under it. */
@Composable
private fun ErrorLine(text: String, tag: String, onRetry: () -> Unit) {
    val c = kitColors()
    Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = c.danger, modifier = Modifier.padding(top = 2.dp).testTag(tag))
    KitTextAction(localized(Str.settings_pages_try_again), onClick = onRetry, modifier = Modifier.testTag("app_update_retry"))
}
