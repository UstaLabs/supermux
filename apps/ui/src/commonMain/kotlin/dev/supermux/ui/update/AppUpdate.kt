// The one "the app updates itself" screen + sidebar card for both apps (cluster G5).
//
// Base = desktop's `update/AppUpdateUi.kt` (screen + startup strip) over the G1 `AppUpdater` seam;
// Android's `AppUpdatePage`/`AppUpdateBanner` are the Compact/Touch branch of the SAME composables.
// Everything either side had is unioned rather than picked:
//
//  - release notes, Recheck, the check spinner, "You're up to date", the failure texts — both had
//    them, and the wording that survives is the one both already showed;
//  - the version block gains Android's `versionCode` line, printed only where the platform HAS a
//    version code (desktop's is null by contract);
//  - the download CTA gains Android's live "Downloading 42%…" label (desktop showed a bare
//    "Downloading…") and its "no installer file was published" branch (Android's wording named
//    an APK, which is a lie on every other host);
//  - the unknown-sources refusal becomes a VISIBLE row with its own action rather than only an
//    instant jump to Settings: the jump is kept (Android's behaviour, verbatim) and the row lets a
//    user who came back without granting it try again. It is driven by
//    [UpdateStatus.needsInstallPermission], which only a platform that has the gate ever raises.
//
// The chrome follows the cluster-E rule `(standalone || compact) && !topBarShown`: the Settings hub
// paints title + Back for its detail, desktop's `Route.AppUpdate` overlay passes `standalone = true`
// so it keeps the Back + title row it has always drawn at Expanded, and nobody paints two bars or
// registers two Back owners. Android's status-bar progress/alert notifications are NOT here — they
// live inside `AndroidAppUpdater`, on the far side of the seam.
package dev.supermux.ui.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.supermux.update.ClientUpdateStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.AppUpdater
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.UpdatePhase
import dev.supermux.ui.platform.UpdateStatus
import dev.supermux.ui.theme.IconSize
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.SettingsCaption
import dev.supermux.ui.widgets.SettingsDetailMaxWidth
import dev.supermux.ui.widgets.SettingsSectionHeader
import dev.supermux.ui.widgets.SwipeBackHandler
import kotlin.math.roundToLong
import kotlinx.coroutines.launch

// ── Pure labels ───────────────────────────────────────────────────────────────

/** `512 B` / `1.5 KB` / `2.0 MB`. Android's `AppUpdateNotifier.formatBytes`, without `java.util`. */
fun formatUpdateBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "${oneDecimal(kb)} KB"
    return "${oneDecimal(kb / 1024.0)} MB"
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return "${tenths / 10}.${tenths % 10}"
}

/**
 * The download CTA's label while bytes are moving: a percentage when the length is known, a byte
 * count when it is not, and the bare verb before the first tick. Android showed this in the button
 * AND in its status-bar notification; the notifier now delegates here so both stay one wording.
 */
fun formatUpdateProgress(bytesReceived: Long, contentLength: Long?): String {
    val pct = if (contentLength != null && contentLength > 0) {
        ((bytesReceived * 100) / contentLength).toInt().coerceIn(0, 100)
    } else {
        null
    }
    return when {
        pct != null -> "Downloading $pct%…"
        bytesReceived > 0 -> "Downloading ${formatUpdateBytes(bytesReceived)}…"
        else -> "Downloading…"
    }
}

/**
 * The installer's file kind (`deb`/`msi`/`dmg`/`apk`) read off the release's download URL.
 *
 * Desktop's caption names the file it is about to open, and the seam only reports the kind AFTER a
 * download (`DownloadedInstaller.kind`) — the caption has to say it before. The URL is the same
 * fact, already on the status, so no new seam member is needed. Null when the URL is missing or
 * carries no recognisable extension.
 */
fun installerKindFrom(downloadUrl: String?): String? {
    val path = downloadUrl?.substringBefore('?')?.substringBefore('#')?.substringAfterLast('/')
        ?: return null
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext.takeIf { it in INSTALLER_EXTENSIONS }
}

/** Hoisted so the set is built once, not on every recomposition that renders the caption. */
private val INSTALLER_EXTENSIONS = setOf("deb", "msi", "dmg", "apk", "exe", "pkg", "appimage")

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * "Check for updates": the running build, what the release feed says, and one CTA that downloads
 * the installer and hands it to the OS.
 *
 * @param onBack leave the screen (the hub's `SettingsSlotScope.onClose`, or the shell's `goBack`).
 * @param topBarShown a hub above already painted a `TopAppBar` for this detail — the page then
 *   drops its own bar and its `BackHandler` and keeps Recheck as a body action.
 * @param standalone the screen is its own destination (desktop's `Route.AppUpdate` overlay) rather
 *   than a hub section, so it owns title + Back at EVERY width.
 * @param updater the seam under test; production always reads `LocalPlatform.current.updates`, and
 *   both the sidebar card and this screen read the SAME one, so what either shows is one check.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AppUpdateScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    standalone: Boolean = false,
    updater: AppUpdater = LocalPlatform.current.updates,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val update by updater.status.collectAsState()
    val loading = update.phase == UpdatePhase.Checking || update.phase == UpdatePhase.Idle
    val installing = update.busy
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    val ownChrome = (standalone || compact) && !topBarShown

    val refresh: () -> Unit = remember(scope, updater) { { scope.launch { updater.check() } } }

    // The seam no-ops a check while a download/install owns the phase, so entering the page mid
    // download neither clobbers the progress nor re-enables the CTA.
    LaunchedEffect(updater) { updater.check() }
    SwipeBackHandler(enabled = ownChrome, onBack = onBack)

    if (ownChrome) {
        Scaffold(
            modifier = modifier.testTag("app_update_overlay"),
            topBar = {
                TopAppBar(
                    title = { Text("Check for updates", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("app_update_back")) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = cs.onSurface,
                            )
                        }
                    },
                    actions = { RecheckButton(refresh, enabled = !loading && !installing) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
                )
            },
            containerColor = cs.background,
        ) { padding ->
            AppUpdateBody(Modifier.padding(padding), updater, refresh, showRecheck = false)
        }
    } else {
        Surface(
            modifier.fillMaxSize().testTag("app_update_overlay"),
            color = cs.background,
        ) {
            AppUpdateBody(Modifier, updater, refresh, showRecheck = true)
        }
    }
}

@Composable
private fun RecheckButton(onClick: () -> Unit, enabled: Boolean) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag("app_update_recheck"),
    ) {
        Text("Recheck")
    }
}

/**
 * @param showRecheck the page painted no bar of its own, so Recheck rides in the body (whoever
 *   painted the bar above has no room for this page's actions).
 */
@Composable
private fun AppUpdateBody(
    modifier: Modifier,
    updater: AppUpdater,
    refresh: () -> Unit,
    showRecheck: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val update by updater.status.collectAsState()
    val status = update.release
    val loading = update.phase == UpdatePhase.Checking || update.phase == UpdatePhase.Idle
    val installing = update.busy
    val downloadLabel =
        if (installing) formatUpdateProgress(update.bytesReceived, update.contentLength) else null

    if (loading && status == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = cs.primary)
        }
        return
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("app_update_screen"),
    ) {
        Column(
            Modifier.widthIn(max = SettingsDetailMaxWidth).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (showRecheck) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    RecheckButton(refresh, enabled = !loading && !installing)
                }
            }
            SettingsSectionHeader("APP")
            val s = status
            Column {
                Text(
                    "supermux ${s?.currentVersion ?: updater.currentVersion}",
                    color = cs.onSurface,
                    fontSize = 14.sp,
                    modifier = Modifier.testTag("app_update_version"),
                )
                // Desktop packages carry no version code; only a platform that has one prints it.
                (s?.currentVersionCode ?: updater.currentVersionCode)?.let {
                    Text("versionCode $it", color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
            }

            when {
                s == null -> Text(
                    "Couldn't check for updates.",
                    color = cs.error,
                    fontSize = 13.sp,
                    modifier = Modifier.testTag("app_update_message"),
                )

                s.lastError != null && s.latestVersion == null -> Text(
                    s.lastError!!,
                    color = cs.error,
                    fontSize = 12.sp,
                    modifier = Modifier.testTag("app_update_message"),
                )

                s.updateAvailable -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = cs.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            "Update available: ${s.latestVersion}",
                            color = cs.onSurface,
                            fontSize = 14.sp,
                            modifier = Modifier.testTag("app_update_message"),
                        )
                    }
                    if (s.highlights.isNotEmpty()) {
                        Column(
                            Modifier.testTag("app_update_whats_new"),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SettingsSectionHeader("WHAT'S NEW")
                            s.highlights.forEach { line ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Text("•", color = cs.primary, fontSize = 14.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(line, color = cs.onSurface, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    s.notesUrl?.let {
                        Row(
                            Modifier
                                .clickable { updater.openReleaseNotes() }
                                .testTag("app_update_notes"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("Release notes", color = cs.primary, fontSize = 14.sp)
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    if (s.canInstall && s.downloadUrl != null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    // The seam publishes progress and the failure text on its own
                                    // status; a refused install (unknown sources off) also raises
                                    // needsInstallPermission, which jumps to Settings exactly as
                                    // Android's "need-permission" return used to.
                                    val installer = updater.download { _, _ -> }
                                    if (installer != null) {
                                        updater.install(installer)
                                    } else if (updater.status.value.needsInstallPermission) {
                                        updater.openInstallPermissionSettings()
                                    }
                                }
                            },
                            enabled = !installing,
                            modifier = Modifier.fillMaxWidth().testTag("app_update_install"),
                        ) {
                            if (installing) {
                                CircularProgressIndicator(
                                    color = cs.onPrimary,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(downloadLabel ?: "Downloading…")
                            } else {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Download & install")
                            }
                        }
                        SettingsCaption(installCaption(installerKindFrom(s.downloadUrl)))
                    } else {
                        Text(
                            // Deliberately installer-agnostic: this branch fires on every host,
                            // and only one of them ships an APK.
                            "Update is available but no installer file was published for this release.",
                            color = cs.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }

                else -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = cs.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "You're up to date",
                        color = cs.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier.testTag("app_update_message"),
                    )
                }
            }

            if (update.needsInstallPermission) {
                // Only a platform with an unknown-sources gate ever raises this (Android). The CTA
                // already jumped to Settings once; this row is what the user comes back to.
                Row(
                    Modifier.fillMaxWidth().testTag("app_update_permission"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        update.error ?: "Allow installing apps from this source, then try again.",
                        color = cs.error,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { updater.openInstallPermissionSettings() },
                        modifier = Modifier.testTag("app_update_permission_action"),
                    ) {
                        Text("Allow")
                    }
                }
            } else {
                // The permission row above already IS the error text; printing it twice was the one
                // thing neither original had to worry about (neither drew the row).
                update.error?.let {
                    Text(
                        it,
                        color = cs.error,
                        fontSize = 12.sp,
                        modifier = Modifier.testTag("app_update_error"),
                    )
                }
            }
        }
    }
}

fun installCaption(kind: String?): String = when (kind) {
    // Android's wording — the notifier that posts this progress lives behind the seam.
    "apk" -> "One-tap installs the latest release APK over this build. " +
        "Progress also appears in the notification bar."
    null -> "Downloads the latest installer and opens it."
    else -> "Downloads the latest .$kind and opens it."
}

// ── Sidebar card ──────────────────────────────────────────────────────────────

/** Whether [AppUpdateCard] draws for this status: a check found a newer, undismissed release. */
val UpdateStatus.showsUpdateCard: Boolean
    get() = release?.updateAvailable == true && !dismissed

/** How many "What's new" lines the card lists before it says "+N more" and leaves the rest to the page. */
internal const val UPDATE_CARD_HIGHLIGHTS = 3

/**
 * "A new version is here": a small card at the foot of the sidebar, just above its footer rail —
 * the version, the release's "What's new" (its first [UPDATE_CARD_HIGHLIGHTS] lines), one Update
 * button and a dismiss. It replaced the strip that used to run across the top of the whole app.
 *
 * Tapping the card opens the full page; the seam is the one [AppUpdateScreen] reads, so dismissing
 * here settles there. The card never checks on its own — the shell does, once per launch — because
 * on a phone the sidebar is recomposed every time the list comes back, and each of those would be
 * another poll (and another GitHub API read for the notes).
 */
@Composable
fun AppUpdateCard(
    onOpenPage: () -> Unit = {},
    modifier: Modifier = Modifier,
    updater: AppUpdater = LocalPlatform.current.updates,
) {
    val update by updater.status.collectAsState()
    AnimatedVisibility(
        visible = update.showsUpdateCard,
        modifier = modifier,
        enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
    ) {
        // A dismissal keeps the release, so the exit animation still has a card to draw.
        val release = update.release ?: return@AnimatedVisibility
        UpdateCardBody(update, release, onOpenPage, updater)
    }
}

@Composable
private fun UpdateCardBody(
    update: UpdateStatus,
    release: ClientUpdateStatus,
    onOpenPage: () -> Unit,
    updater: AppUpdater,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val installing = update.busy
    // The sidebar's own card language (the new-session row): a quiet tonal panel one step off the
    // rail, a primary-tinted icon tile as the only accent, onSurface / onSurfaceVariant text.
    val shape = RoundedCornerShape(Radii.md)
    val more = release.highlights.size - UPDATE_CARD_HIGHLIGHTS
    val canUpdate = release.canInstall && release.downloadUrl != null
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.sm, vertical = Space.sm)
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(Stroke.hairline, cs.onSurface.copy(alpha = 0.08f), shape)
            .clickable(onClick = onOpenPage)
            .padding(start = Space.md, end = Space.xs, top = Space.sm, bottom = Space.md)
            .testTag("app_update_card"),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(UPDATE_CARD_TILE)
                    .clip(RoundedCornerShape(Radii.sm))
                    .background(cs.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = cs.primary,
                    modifier = Modifier.size(IconSize.sm),
                )
            }
            Spacer(Modifier.width(UPDATE_CARD_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    "supermux ${release.latestVersion}",
                    color = cs.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("app_update_card_version"),
                )
                Text(
                    "New version available",
                    color = cs.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The footer rail's icon-button size, so the ✕ reads as part of the same chrome.
            IconButton(
                onClick = { updater.dismiss() },
                modifier = Modifier.size(28.dp).testTag("app_update_card_dismiss"),
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.sm),
                )
            }
        }

        if (release.highlights.isNotEmpty()) {
            Column(
                Modifier.padding(end = Space.sm).testTag("app_update_card_notes"),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                release.highlights.take(UPDATE_CARD_HIGHLIGHTS).forEach { HighlightLine(it) }
                if (more > 0) {
                    Text(
                        "+$more more",
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                        // Under the lines' text, past the bullet.
                        modifier = Modifier.padding(start = Space.md),
                    )
                }
            }
        }

        if (canUpdate) {
            // The bar rides beside the pill, so starting a download doesn't make the card jump.
            Row(
                Modifier.padding(end = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val installer = updater.download { _, _ -> }
                            val after = updater.status.value
                            when {
                                installer != null -> updater.install(installer)
                                after.needsInstallPermission -> updater.openInstallPermissionSettings()
                                // The failure text is on the status (and in the status bar on
                                // Android); the card has no room for it, so open the page.
                                else -> onOpenPage()
                            }
                        }
                    },
                    enabled = !installing,
                    shape = RoundedCornerShape(Radii.pill),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = cs.primary.copy(alpha = 0.12f),
                        contentColor = cs.primary,
                        disabledContainerColor = cs.onSurface.copy(alpha = 0.06f),
                        disabledContentColor = cs.onSurfaceVariant,
                    ),
                    elevation = null,
                    contentPadding = PaddingValues(horizontal = Space.md),
                    modifier = Modifier.height(28.dp).testTag("app_update_card_update"),
                ) {
                    if (!installing) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(IconSize.sm))
                        Spacer(Modifier.width(Space.xs))
                    }
                    Text(
                        if (installing) formatUpdateProgress(update.bytesReceived, update.contentLength) else "Update",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
                if (installing) {
                    val pct = update.percent
                    val bar = Modifier.weight(1f).height(Stroke.thin).clip(CircleShape)
                    val track = cs.onSurface.copy(alpha = 0.08f)
                    if (pct != null) {
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = bar,
                            color = cs.primary,
                            trackColor = track,
                            drawStopIndicator = {},
                            gapSize = 0.dp,
                        )
                    } else {
                        LinearProgressIndicator(modifier = bar, color = cs.primary, trackColor = track, gapSize = 0.dp)
                    }
                }
            }
        }
    }
}

/** The card's icon tile, and the gap between it and the title. */
private val UPDATE_CARD_TILE = 28.dp
private val UPDATE_CARD_GAP = 10.dp

@Composable
private fun HighlightLine(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(4.dp)
                .clip(CircleShape)
                .background(cs.onSurfaceVariant.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.width(Space.sm))
        Text(
            text,
            color = cs.onSurface.copy(alpha = 0.85f),
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
