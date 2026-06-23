package dev.supermux.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.Space
import dev.supermux.net.ForgeConnection
import dev.supermux.net.RemoteRepo
import dev.supermux.session.OmniOption
import dev.supermux.session.ProjectOption
import dev.supermux.session.buildOmniboxOptions
import dev.supermux.session.formatWorkdir
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Tappable project field for the launcher — shows the chosen workdir and opens [ProjectPickerSheet]. */
@Composable
internal fun ProjectField(
    workdir: String,
    home: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // A read-only OutlinedTextField look-alike: the whole row is clickable and the
    // value mirrors the other launcher fields' styling. (A real text field would
    // steal the tap to place a cursor; we want the tap to open the picker.)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { onClick() }
            .border(1.dp, cs.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .testTag("launcher_project_field"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            formatWorkdir(workdir, home),
            color = cs.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = painterResource(R.drawable.ic_folder_open),
            contentDescription = "Select project",
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Icon(
            painter = painterResource(R.drawable.ic_chevron_down),
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

private fun basename(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

/**
 * Forge-aware project picker — Android take on the web ProjectPathPicker omnibox and the
 * iOS ProjectPickerSheet: pick a known project, type an arbitrary path, clone a repo from a
 * connected GitHub/GitLab account, or create a new repo (locally or on a forge). The shared
 * [buildOmniboxOptions] decides which rows appear; this just renders + resolves them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectPickerSheet(
    current: String,
    projects: List<String>,
    home: String,
    loadForges: suspend () -> List<ForgeConnection>,
    searchForge: suspend (String) -> List<RemoteRepo>,
    cloneForge: suspend (connectionId: String, owner: String, name: String) -> String?,
    createLocalRepo: suspend (name: String) -> String?,
    createForge: suspend (connectionId: String, name: String) -> String?,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var search by remember { mutableStateOf("") }
    var connections by remember { mutableStateOf(emptyList<ForgeConnection>()) }
    var cloudRepos by remember { mutableStateOf(emptyList<RemoteRepo>()) }
    var searching by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf(false) }

    val query = search.trim()
    val projectOptions = remember(projects, home) {
        projects.map { ProjectOption(it, formatWorkdir(it, home)) }
    }

    LaunchedEffect(Unit) { connections = loadForges() }

    // Debounced forge search (≥2 chars, only with connections) — web useForgeOmnibox parity.
    LaunchedEffect(query, connections) {
        if (connections.isEmpty() || query.length < 2) {
            cloudRepos = emptyList(); searching = false; return@LaunchedEffect
        }
        delay(250)
        searching = true
        cloudRepos = searchForge(query)
        searching = false
    }

    val options = remember(query, projectOptions, cloudRepos, connections) {
        buildOmniboxOptions(query, projectOptions, cloudRepos, connections)
    }
    val locals = options.filterIsInstance<OmniOption.Local>()
    val clouds = options.filterIsInstance<OmniOption.Cloud>()
    val creates = options.filterIsInstance<OmniOption.Create>()
    val showTypedPath = query.isNotEmpty() && projects.none { it == query }
    val cloudGroups = remember(clouds, connections) {
        connections.mapNotNull { c ->
            val repos = clouds.filter { it.connectionId == c.id }.map { it.repo }
            if (repos.isEmpty()) null else c to repos
        }
    }

    fun pick(path: String) { onPick(path); onDismiss() }
    fun resolve(block: suspend () -> String?) {
        if (resolving) return
        resolving = true
        scope.launch {
            val path = runCatching { block() }.getOrNull()
            resolving = false
            if (!path.isNullOrBlank()) pick(path)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = cs.surfaceContainerLow,
        contentColor = cs.onSurface,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Text(
                    "Project",
                    color = cs.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search projects, repos, or type a path", color = cs.onSurfaceVariant) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_search),
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .testTag("project_search"),
                )
                Spacer(Modifier.height(8.dp))

                val nothing = !showTypedPath && locals.isEmpty() && cloudGroups.isEmpty() &&
                    creates.isEmpty() && !searching
                if (nothing) {
                    Text(
                        "Type a path or search your projects.",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 28.dp),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                        if (showTypedPath) {
                            item(key = "typed") {
                                OmniRow(
                                    icon = R.drawable.ic_chevron_right,
                                    title = "Use this path",
                                    subtitle = query,
                                    onClick = { pick(query) },
                                )
                            }
                        }
                        if (locals.isNotEmpty()) {
                            item(key = "h_projects") { SectionHeader("Projects") }
                            items(locals, key = { "l_${it.path}" }) { o ->
                                OmniRow(
                                    icon = R.drawable.ic_folder_open,
                                    title = basename(o.path),
                                    subtitle = o.label,
                                    checked = o.path == current,
                                    onClick = { pick(o.path) },
                                )
                            }
                        }
                        cloudGroups.forEach { (conn, repos) ->
                            item(key = "h_${conn.id}") {
                                SectionHeader("${conn.host} · @${conn.account.login}")
                            }
                            items(repos, key = { "c_${conn.id}_${it.fullName}" }) { repo ->
                                OmniRow(
                                    icon = R.drawable.ic_folder_open,
                                    title = repo.name,
                                    subtitle = repo.fullName,
                                    trailingIcon = R.drawable.ic_download,
                                    trailingLabel = "Clone",
                                    enabled = !resolving,
                                    onClick = { resolve { cloneForge(repo.connectionId, repo.owner, repo.name) } },
                                )
                            }
                        }
                        if (searching && cloudGroups.isEmpty()) {
                            item(key = "searching") {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = cs.primary)
                                    Text("Searching repos…", color = cs.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }
                        }
                        if (creates.isNotEmpty()) {
                            item(key = "h_create") { SectionHeader("Create") }
                            items(creates, key = { "cr_${it.createTarget}" }) { c ->
                                OmniRow(
                                    icon = R.drawable.ic_plus,
                                    title = c.label,
                                    enabled = !resolving,
                                    onClick = {
                                        resolve {
                                            if (c.createTarget == "local") createLocalRepo(query)
                                            else createForge(c.createTarget, query)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (resolving) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(cs.scrim.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(cs.surfaceContainerHigh)
                            .padding(22.dp),
                    ) {
                        CircularProgressIndicator(color = cs.primary)
                        Text("Cloning / creating…", color = cs.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun OmniRow(
    icon: Int,
    title: String,
    subtitle: String? = null,
    checked: Boolean = false,
    trailingIcon: Int? = null,
    trailingLabel: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .alpha(if (enabled) 1f else 0.6f)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = cs.onSurface, fontSize = 14.sp, maxLines = 1)
            if (subtitle != null) {
                Text(
                    subtitle,
                    color = cs.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
        }
        if (checked) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = cs.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (trailingIcon != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    painter = painterResource(trailingIcon),
                    contentDescription = trailingLabel,
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                if (trailingLabel != null) {
                    Text(trailingLabel, color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
    }
}
