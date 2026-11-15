// #117: Applet info — everything about one applet that isn't the applet itself, behind its menu's "Applet info".
// Tabs: Overview (what needs him), Data (its tables, its read-only backups), Jobs (last run, output, Run now),
// Secrets (values only he sets), Settings (the applet's own options, its name, link, start command, Stop, Delete).
// Tabs with nothing in them (no jobs, no secrets) don't show. Every action says why it failed, where he clicked.
package dev.supermux.ui.applets

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.supermux.net.AppletDetailDto
import dev.supermux.net.AppletDto
import dev.supermux.net.AppletJobDto
import dev.supermux.net.AppletJobRunDto
import dev.supermux.net.AppletSecretDto
import dev.supermux.net.AppletSettingDto
import dev.supermux.net.BrokerApi
import dev.supermux.state.FleetStore
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.session.AppletIcon
import dev.supermux.ui.session.archivedRelTime
import dev.supermux.ui.theme.MonoFontFamily
import kotlinx.coroutines.launch

/** What Applet info does, over the active host. */
class AppletInfoActions(
    val load: suspend (String) -> AppletDetailDto?,
    val jobRuns: suspend (String, String) -> List<AppletJobRunDto>?,
    val act: suspend (suspend (BrokerApi) -> Unit) -> String?,
    val runJob: suspend (String, String) -> Result<AppletJobRunDto>,
    val loadIcon: suspend (AppletDto) -> ByteArray?,
    val open: (AppletDto) -> Unit,
    val hostName: String,
)

fun appletInfoActions(fleet: FleetStore, hostName: String, open: (AppletDto) -> Unit): AppletInfoActions = AppletInfoActions(
    load = { fleet.appletDetail(it) },
    jobRuns = { name, job -> fleet.appletJobRuns(name, job) },
    act = { block -> fleet.appletAction { api -> block(api) }.exceptionOrNull()?.message },
    runJob = { name, job -> fleet.appletAction { api -> api.runAppletJob(name, job) } },
    loadIcon = { fleet.appletIconBytes(it) },
    open = open,
    hostName = hostName,
)

private enum class InfoTab { Overview, Data, Jobs, Secrets, Settings }

@Composable
private fun InfoTab.label(): String = localized(
    when (this) {
        InfoTab.Overview -> Str.applet_info_tab_overview
        InfoTab.Data -> Str.applet_info_tab_data
        InfoTab.Jobs -> Str.applet_info_tab_jobs
        InfoTab.Secrets -> Str.applet_info_tab_secrets
        InfoTab.Settings -> Str.applet_info_tab_settings
    },
)

/** The detail as the list's row, for the icon and Open. */
private fun AppletDetailDto.asApplet() = AppletDto(id = id, name = name, title = title, url = url, iconUrl = iconUrl, status = status, kind = kind, updatedAt = updatedAt)

@Composable
fun AppletInfoScreen(name: String, actions: AppletInfoActions, phone: Boolean, onBack: () -> Unit, initialTab: String? = null) {
    val cs = MaterialTheme.colorScheme
    var detail by remember(name) { mutableStateOf<AppletDetailDto?>(null) }
    var failed by remember(name) { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(name, reload) {
        val d = actions.load(name)
        if (d != null) detail = d else failed = detail == null
    }
    var tab by remember(name) { mutableStateOf(InfoTab.entries.firstOrNull { it.name.equals(initialTab, ignoreCase = true) } ?: InfoTab.Overview) }
    Column(Modifier.fillMaxSize().background(cs.surface).testTag("applet_info_$name")) {
        // The top: back on a phone; the crumb on a desktop.
        Row(Modifier.fillMaxWidth().height(if (phone) 52.dp else 40.dp).padding(horizontal = if (phone) 4.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (phone) {
                Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack).testTag("applet_info_back"), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = localized(Str.shell_back), tint = cs.onSurface)
                }
                Text(detail?.title ?: name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(localized(Str.sessions_applets_title), fontSize = 13.sp, color = cs.onSurfaceVariant, modifier = Modifier.clickable(onClick = onBack))
                Text("  ›  ", fontSize = 13.sp, color = cs.onSurfaceVariant) // i18n-ok: a crumb separator
                Text(detail?.title ?: name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                if (actions.hostName.isNotBlank()) Text(localized(Str.applets_page_host, actions.hostName), fontSize = 12.sp, color = cs.onSurfaceVariant)
            }
        }
        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                if (failed) Text(localized(Str.applet_info_load_failed), color = cs.onSurfaceVariant, fontSize = 14.sp)
            }
            return@Column
        }
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = if (phone) 14.dp else 32.dp, vertical = if (phone) 10.dp else 22.dp)) {
                // The head: icon, title, when it changed, Open.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppletIcon(d.asApplet(), if (phone) 60.dp else 72.dp, actions.loadIcon)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.title.ifBlank { d.name }, fontSize = if (phone) 20.sp else 24.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                        val ago = archivedRelTime(d.updatedAt)
                        Text(
                            listOfNotNull(
                                localized(if (d.kind == "server") Str.applet_info_kind_server else Str.applet_info_kind_static),
                                ago.takeIf { it.isNotEmpty() }?.let { localized(Str.applets_menu_updated, it) },
                                if (d.stopped) localized(Str.applet_info_stopped) else null,
                            ).joinToString(" · "),
                            fontSize = 13.sp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    if (!d.stopped) InfoButton(Icons.AutoMirrored.Outlined.OpenInNew, localized(Str.applets_menu_open), primary = true, tag = "applet_info_open") { actions.open(d.asApplet()); null }
                }
                Spacer(Modifier.height(18.dp))
                val tabs = InfoTab.entries.filter { (it != InfoTab.Jobs || d.jobs.isNotEmpty()) && (it != InfoTab.Secrets || d.secrets.isNotEmpty()) }
                val shown = if (tab in tabs) tab else InfoTab.Overview
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    tabs.forEach { t ->
                        val badge = (t == InfoTab.Secrets && d.secrets.any { it.state != "set" && it.wanted }) ||
                            (t == InfoTab.Jobs && d.jobs.any { (it.lastRun?.exitCode ?: 0) != 0 })
                        InfoTabChip(t.label(), selected = t == shown, badge = badge, tag = "applet_info_tab_${t.name.lowercase()}") { tab = t }
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
                Spacer(Modifier.height(16.dp))
                val refresh = { reload++ }
                when (shown) {
                    InfoTab.Overview -> OverviewTab(d, actions, refresh) { tab = InfoTab.Secrets }
                    InfoTab.Data -> DataTab(d, actions)
                    InfoTab.Jobs -> JobsTab(d, actions, refresh)
                    InfoTab.Secrets -> SecretsTab(d, actions, refresh)
                    InfoTab.Settings -> SettingsTab(d, actions, refresh, onDeleted = onBack)
                }
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

@Composable
private fun InfoTabChip(label: String, selected: Boolean, badge: Boolean, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .background(if (hovered && !selected) cs.onSurface.copy(alpha = 0.05f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.5.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = if (selected) cs.onSurface else cs.onSurfaceVariant)
            if (badge) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(cs.error))
            }
        }
        Box(Modifier.height(2.dp).width(28.dp).background(if (selected) cs.onSurface else Color.Transparent))
    }
}

/** A short paragraph above a tab's rows. */
@Composable
private fun Lede(text: String) {
    Text(text, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp))
}

@Composable
private fun Small(text: String) {
    Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
}

/** A bordered group of rows. */
@Composable
private fun InfoBox(content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp))
            .background(cs.surfaceContainerLow),
    ) { content() }
}

@Composable
private fun InfoRow(icon: ImageVector?, title: String, sub: String? = null, subWarn: Boolean = false, mono: Boolean = false, trailing: @Composable () -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface, fontFamily = if (mono) MonoFontFamily else null)
            if (sub != null) Text(sub, fontSize = 12.5.sp, color = if (subWarn) cs.error else cs.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)))
}

/**
 * A button that runs [run]: busy while it runs, its failure (the returned reason) shown under it. Desktop idioms:
 * click and hover; the same button is a thumb-sized target on touch through its padding.
 */
@Composable
private fun InfoButton(icon: ImageVector?, label: String, primary: Boolean = false, danger: Boolean = false, tag: String, run: suspend () -> String?) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(horizontalAlignment = Alignment.End) {
        Row(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    when {
                        primary -> if (hovered) cs.primary.copy(alpha = 0.9f) else cs.primary
                        danger -> if (hovered) cs.error.copy(alpha = 0.16f) else cs.error.copy(alpha = 0.1f)
                        else -> if (hovered) cs.onSurface.copy(alpha = 0.1f) else cs.onSurface.copy(alpha = 0.06f)
                    },
                )
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, enabled = !busy) {
                    busy = true
                    error = null
                    scope.launch {
                        error = run()
                        busy = false
                    }
                }
                .padding(horizontal = 12.dp)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fg = when {
                primary -> cs.onPrimary
                danger -> cs.error
                else -> cs.onSurface
            }
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(if (busy) localized(Str.applet_info_working) else label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg)
        }
        error?.let { Text(it, fontSize = 12.sp, color = cs.error, modifier = Modifier.padding(top = 4.dp).widthIn(max = 320.dp).testTag("${tag}_error")) }
    }
}

// ── Overview ─────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun OverviewTab(d: AppletDetailDto, actions: AppletInfoActions, refresh: () -> Unit, showSecrets: () -> Unit) {
    val needs = d.secrets.filter { it.wanted && it.state != "set" }
    if (needs.isNotEmpty()) {
        SectionTitle(localized(Str.applet_info_needs_you))
        InfoBox {
            needs.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                SecretRow(d, s, actions, refresh)
            }
        }
    }
    SectionTitle(localized(Str.applet_info_status))
    InfoBox {
        val p = d.process
        val state = when {
            d.stopped -> localized(Str.applet_info_stopped)
            d.status == "invalid" -> d.error ?: localized(Str.applet_info_invalid)
            d.status == "missing" -> localized(Str.applet_info_missing)
            d.kind != "server" -> localized(Str.applet_info_static_running)
            p?.state == "running" -> localized(Str.applet_info_server_running)
            else -> p?.error ?: localized(Str.applet_info_server_down)
        }
        val warn = !d.stopped && (d.status != "ok" || (d.kind == "server" && p?.state != "running"))
        InfoRow(Icons.Outlined.History, state, sub = d.updatedBy?.let { localized(Str.applet_info_changed_by, whoChanged(it)) }, subWarn = false)
        if (warn) {
            RowDivider()
            InfoRow(null, localized(Str.applet_info_ask_agent_hint))
        }
    }
}

/** "an agent" / "this device" from updatedBy (agent:<session>, device:<id>, host). */
@Composable
private fun whoChanged(by: String): String = when {
    by.startsWith("agent:") -> localized(Str.applet_info_by_agent)
    by.startsWith("device:") -> localized(Str.applet_info_by_you)
    else -> localized(Str.applet_info_by_machine)
}

// ── Data ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun DataTab(d: AppletDetailDto, actions: AppletInfoActions) {
    val data = d.data
    Lede(localized(Str.applet_info_data_lede, actions.hostName.ifBlank { "—" }, data?.dir ?: "${d.folder}/data")) // platform-ok: url
    SectionTitle(localized(Str.applet_info_tables))
    InfoBox {
        val tables = data?.tables.orEmpty()
        if (tables.isEmpty()) InfoRow(Icons.Outlined.Storage, localized(Str.applet_info_no_tables))
        tables.forEachIndexed { i, t ->
            if (i > 0) RowDivider()
            InfoRow(Icons.Outlined.Storage, t.name, trailing = { Text(localized(Str.applet_info_rows, t.rows.toString()), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) })
        }
        if (data != null) {
            RowDivider()
            InfoRow(null, localized(Str.applet_info_files, data.files.toString(), dev.supermux.ui.worktree.formatBytes(data.bytes)))
        }
    }
    SectionTitle(localized(Str.applet_info_backups))
    InfoBox {
        if (d.backups.isEmpty()) InfoRow(Icons.Outlined.History, localized(Str.applet_info_no_backups))
        d.backups.forEachIndexed { i, b ->
            if (i > 0) RowDivider()
            InfoRow(
                Icons.Outlined.History,
                archivedRelTime(b.createdAt).let { if (it.isEmpty()) b.id else localized(Str.applet_info_backup_ago, it) },
                sub = b.why ?: localized(if (b.kind == "nightly") Str.applet_info_backup_nightly else Str.applet_info_backup_manual),
            )
        }
    }
    Small(localized(Str.applet_info_backups_note))
}


// ── Jobs ─────────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun JobsTab(d: AppletDetailDto, actions: AppletInfoActions, refresh: () -> Unit) {
    Lede(localized(Str.applet_info_jobs_lede, actions.hostName.ifBlank { "—" }))
    InfoBox {
        d.jobs.forEachIndexed { i, j ->
            if (i > 0) RowDivider()
            JobRow(d, j, actions, refresh)
        }
    }
    Small(localized(Str.applet_info_jobs_note))
}

@Composable
private fun JobRow(d: AppletDetailDto, j: AppletJobDto, actions: AppletInfoActions, refresh: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var output by remember(d.name, j.name) { mutableStateOf<String?>(null) }
    var exit by remember(d.name, j.name) { mutableStateOf(j.lastRun?.exitCode) }
    var startedAt by remember(d.name, j.name) { mutableStateOf(j.lastRun?.startedAt) }
    LaunchedEffect(d.name, j.name) {
        actions.jobRuns(d.name, j.name)?.firstOrNull()?.let { output = it.output; exit = it.exitCode; startedAt = it.startedAt }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.History, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp).size(18.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(j.name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = cs.onSurface)
            val last = startedAt?.let { archivedRelTime(it) }.orEmpty()
            val result = when {
                startedAt == null -> localized(Str.applet_info_job_never)
                exit == 0 -> localized(Str.applet_info_job_ok, last)
                else -> localized(Str.applet_info_job_failed, last, exit?.toString() ?: "?")
            }
            Text(localized(Str.applet_info_job_every, j.every) + " · " + result, fontSize = 12.5.sp, color = if (startedAt != null && exit != 0) cs.error else cs.onSurfaceVariant)
            output?.takeIf { it.isNotBlank() }?.let { out ->
                Text(
                    out.trimEnd().lines().takeLast(8).joinToString("\n"),
                    fontSize = 11.5.sp,
                    fontFamily = MonoFontFamily,
                    color = cs.onSurface.copy(alpha = 0.85f),
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.surfaceContainerHighest)
                        .padding(10.dp)
                        .testTag("applet_job_output_${j.name}"),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        InfoButton(null, localized(Str.applet_info_run_now), tag = "applet_job_run_${j.name}") {
            val r = actions.runJob(d.name, j.name)
            r.getOrNull()?.let { run -> output = run.output; exit = run.exitCode; startedAt = run.startedAt }
            r.exceptionOrNull()?.message
        }
    }
}

// ── Secrets ──────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SecretsTab(d: AppletDetailDto, actions: AppletInfoActions, refresh: () -> Unit) {
    Lede(localized(Str.applet_info_secrets_lede))
    InfoBox {
        d.secrets.forEachIndexed { i, s ->
            if (i > 0) RowDivider()
            SecretRow(d, s, actions, refresh)
        }
    }
}

@Composable
private fun SecretRow(d: AppletDetailDto, s: AppletSecretDto, actions: AppletInfoActions, refresh: () -> Unit) {
    var editing by remember(s.name) { mutableStateOf(false) }
    val sub = when (s.state) {
        "expired" -> localized(Str.applet_info_secret_expired)
        "missing" -> localized(Str.applet_info_secret_missing)
        else -> localized(Str.applet_info_secret_set)
    }
    InfoRow(Icons.Outlined.Key, s.name, sub = sub, subWarn = s.state != "set", mono = true) {
        InfoButton(null, localized(if (s.state == "set") Str.applet_info_secret_replace else Str.applet_info_secret_set_value), primary = s.state != "set", tag = "applet_secret_${s.name}") {
            editing = true
            null
        }
    }
    if (editing) SecretDialog(d, s, actions, onDone = { editing = false; refresh() }, onCancel = { editing = false })
}

/** The value goes to the broker and never comes back: the field starts empty, the value is masked. */
@Composable
private fun SecretDialog(d: AppletDetailDto, s: AppletSecretDto, actions: AppletInfoActions, onDone: () -> Unit, onCancel: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var value by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val save: () -> Unit = {
        if (!busy && value.isNotEmpty()) {
            busy = true
            error = null
            scope.launch {
                val why = actions.act { api -> api.setAppletSecret(d.name, s.name, value) }
                busy = false
                if (why == null) onDone() else error = why
            }
        }
    }
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
                .padding(20.dp)
                .testTag("applet_secret_dialog"),
        ) {
            Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, fontFamily = MonoFontFamily)
            Spacer(Modifier.height(6.dp))
            Text(localized(Str.applet_info_secret_dialog_body, d.title.ifBlank { d.name }), fontSize = 13.sp, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                textStyle = TextStyle(color = cs.onSurface, fontSize = 14.sp, fontFamily = MonoFontFamily),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, cs.outline, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("applet_secret_value"),
            )
            error?.let { Text(it, color = cs.error, fontSize = 12.5.sp, modifier = Modifier.padding(top = 8.dp).testTag("applet_secret_error")) }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InfoButton(null, localized(Str.applet_info_cancel), tag = "applet_secret_cancel") { onCancel(); null }
                Spacer(Modifier.width(8.dp))
                InfoButton(null, localized(Str.applet_info_save), primary = true, tag = "applet_secret_save") {
                    if (value.isEmpty()) localized(Str.applet_info_secret_empty) else { save(); null }
                }
            }
        }
    }
}

// ── Settings ─────────────────────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SettingsTab(d: AppletDetailDto, actions: AppletInfoActions, refresh: () -> Unit, onDeleted: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (d.settings.isNotEmpty()) {
        SectionTitle(localized(Str.applet_info_own_settings, d.title.ifBlank { d.name }))
        InfoBox {
            d.settings.forEachIndexed { i, s ->
                if (i > 0) RowDivider()
                SettingRow(d, s, actions, refresh)
            }
        }
    }
    SectionTitle(localized(Str.applet_info_applet))
    InfoBox {
        InfoRow(null, localized(Str.applet_info_name)) {
            EditableText(d.title.ifBlank { d.name }, tag = "applet_info_title") { t -> actions.act { api -> api.setAppletTitle(d.name, t) }.also { if (it == null) refresh() } }
        }
        RowDivider()
        InfoRow(null, localized(Str.applet_info_link), sub = localized(Str.applet_info_link_sub)) {
            Text(d.url, fontSize = 12.5.sp, fontFamily = MonoFontFamily, color = cs.onSurfaceVariant)
        }
        d.run?.let { run ->
            RowDivider()
            InfoRow(null, localized(Str.applet_info_starts_with), sub = localized(Str.applet_info_starts_with_sub)) {
                Text(run, fontSize = 12.5.sp, fontFamily = MonoFontFamily, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 300.dp))
            }
        }
        RowDivider()
        InfoRow(null, localized(Str.applet_info_folder)) {
            Text(d.folder, fontSize = 12.5.sp, fontFamily = MonoFontFamily, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
        }
    }
    SectionTitle(localized(Str.applet_info_danger))
    var confirmDelete by remember { mutableStateOf(false) }
    InfoBox {
        if (d.stopped) {
            InfoRow(null, localized(Str.applet_info_start), sub = localized(Str.applet_info_start_sub)) {
                InfoButton(null, localized(Str.applet_info_start_button), tag = "applet_info_start") { actions.act { api -> api.setAppletStopped(d.name, false) }.also { if (it == null) refresh() } }
            }
        } else {
            InfoRow(null, localized(Str.applet_info_stop), sub = localized(Str.applet_info_stop_sub)) {
                InfoButton(null, localized(Str.applet_info_stop_button), tag = "applet_info_stop") { actions.act { api -> api.setAppletStopped(d.name, true) }.also { if (it == null) refresh() } }
            }
        }
        RowDivider()
        InfoRow(null, localized(Str.applet_info_delete, d.title.ifBlank { d.name }), sub = localized(Str.applet_info_delete_sub)) {
            InfoButton(Icons.Outlined.Delete, localized(Str.applet_info_delete_button), danger = true, tag = "applet_info_delete") { confirmDelete = true; null }
        }
    }
    if (confirmDelete) {
        Dialog(onDismissRequest = { confirmDelete = false }) {
            Column(
                Modifier
                    .widthIn(max = 420.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surfaceContainer)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
                    .padding(20.dp)
                    .testTag("applet_delete_dialog"),
            ) {
                Text(localized(Str.applet_info_delete_title, d.title.ifBlank { d.name }), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Spacer(Modifier.height(8.dp))
                Text(localized(Str.applet_info_delete_body), fontSize = 13.sp, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    InfoButton(null, localized(Str.applet_info_cancel), tag = "applet_delete_cancel") { confirmDelete = false; null }
                    Spacer(Modifier.width(8.dp))
                    InfoButton(Icons.Outlined.Delete, localized(Str.applet_info_delete_button), danger = true, tag = "applet_delete_confirm") {
                        actions.act { api -> api.deleteApplet(d.name) }.also { if (it == null) { confirmDelete = false; onDeleted() } }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(d: AppletDetailDto, s: AppletSettingDto, actions: AppletInfoActions, refresh: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var error by remember(s.key) { mutableStateOf<String?>(null) }
    val chosen = remember(s.key) { mutableStateMapOf<String, String>() }
    val current = chosen[s.key] ?: s.value
    Column(Modifier.fillMaxWidth()) {
        InfoRow(null, s.label.ifBlank { s.key }) {
            val options = s.options
            if (s.type == "select" && !options.isNullOrEmpty()) {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp))) {
                    options.forEach { o ->
                        val on = o == current
                        Text(
                            o,
                            fontSize = 12.5.sp,
                            color = if (on) cs.onSurface else cs.onSurfaceVariant,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier
                                .background(if (on) cs.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                                .clickable {
                                    error = null
                                    scope.launch {
                                        val why = actions.act { api -> api.setAppletSetting(d.name, s.key, o) }
                                        if (why == null) chosen[s.key] = o else error = why
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                .testTag("applet_setting_${s.key}_$o"),
                        )
                    }
                }
            } else {
                EditableText(current.orEmpty(), tag = "applet_setting_${s.key}") { v ->
                    actions.act { api -> api.setAppletSetting(d.name, s.key, v) }.also { if (it == null) chosen[s.key] = v }
                }
            }
        }
        error?.let { Text(it, fontSize = 12.sp, color = cs.error, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp)) }
    }
}

/** A value edited in place: saved on Enter or when the field loses focus; the reason shows under it when saving fails. */
@Composable
private fun EditableText(initial: String, tag: String, save: suspend (String) -> String?) {
    val cs = MaterialTheme.colorScheme
    var value by remember(initial) { mutableStateOf(initial) }
    var error by remember(initial) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val commit: () -> Unit = {
        if (value != initial) scope.launch { error = save(value) }
    }
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End) {
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            textStyle = TextStyle(color = cs.onSurface, fontSize = 13.sp),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier
                .widthIn(min = 160.dp, max = 280.dp)
                .clip(RoundedCornerShape(7.dp))
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(7.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp)
                .onFocusChanged { st ->
                    if (focused && !st.isFocused) commit()
                    focused = st.isFocused
                }
                .testTag(tag),
        )
        error?.let { Text(it, fontSize = 12.sp, color = cs.error, modifier = Modifier.padding(top = 4.dp)) }
    }
}
