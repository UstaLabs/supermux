// The forge-aware project picker both launchers open from their project heading (cluster F5).
//
// Desktop's private `ProjectPicker` is the base and its container is unchanged: an anchored
// heading `DropdownMenu` (the desktop convention — NOT a centred dialog), so desktop behaviour
// stays byte-identical. Android's `ProjectPickerSheet` becomes the Compact branch: the same body
// inside a `ModalBottomSheet`, which is what a phone wants under a keyboard. One body, two
// containers — exactly the cluster-E add-forge form pattern.
//
// Everything additive on either side is unioned: Android's sheet title and full-width rows, and
// desktop's typed-path validation, failure-vs-empty search states, paging, keyboard navigation and
// the honest "continues on the host" progress overlay (a broker `git clone` is not abortable).
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.supermux.net.ForgeConnection
import dev.supermux.net.RemoteRepo
import dev.supermux.session.OmniOption
import dev.supermux.session.ProjectOption
import dev.supermux.session.buildOmniboxOptions
import dev.supermux.session.formatWorkdir
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Size
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Forge repos fetched (and revealed) per "Load more" page. */
const val FORGE_OMNIBOX_PAGE_SIZE = 10

/** What a key press in the project omnibox means, decided without touching composition state. */
sealed class OmniboxKeyAction {
    data object Dismiss : OmniboxKeyAction()

    /** Escape while a clone/create overlay is up: drop the overlay, never abort the host op. */
    data object HideResolve : OmniboxKeyAction()
    data class MoveHighlight(val index: Int) : OmniboxKeyAction()
    data class Activate(val index: Int) : OmniboxKeyAction()
}

fun omniboxKeyAction(
    key: Key,
    highlight: Int,
    count: Int,
    resolving: Boolean,
): OmniboxKeyAction? {
    if (resolving) {
        return if (key == Key.Escape) OmniboxKeyAction.HideResolve else null
    }
    return when (key) {
        Key.DirectionDown -> if (count > 0) OmniboxKeyAction.MoveHighlight((highlight + 1) % count) else null
        Key.DirectionUp -> if (count > 0) OmniboxKeyAction.MoveHighlight((highlight - 1 + count) % count) else null
        Key.Enter, Key.NumPadEnter -> if (count > 0) OmniboxKeyAction.Activate(highlight.coerceIn(0, count - 1)) else null
        Key.Escape -> OmniboxKeyAction.Dismiss
        else -> null
    }
}

/** Keyboard-navigable row in the forge omnibox. */
private sealed class OmniNav {
    /** Free-path row ("Use this path") — first row when the query is not an exact project. */
    data object TypedPath : OmniNav()
    data class Local(val path: String) : OmniNav()
    data class Clone(val repo: RemoteRepo) : OmniNav()
    data class Create(val target: String, val label: String) : OmniNav()
}

/**
 * Project picker with forge omnibox: **one** search field (filter locals / search a connected
 * forge / create, or type a free path → "Use this path"), not a separate path box.
 *
 * Container by width class: a `ModalBottomSheet` under [WindowWidthClass.Compact] (phones — what
 * Android's `ProjectPickerSheet` was), an anchored [DropdownMenu] otherwise (desktop's heading
 * dropdown). The body, and every test tag on it, is identical either way.
 *
 * Clone/create is long-running and **not abortable** on the broker (`git clone` via
 * `execFileSync`). The progress overlay offers **Hide** (not Cancel): the host keeps working;
 * if the user hides, a notice stays visible and a successful finish surfaces a ready path to pick.
 * Search failures are distinct from empty results. Keyboard: autofocus search immediately on a
 * pointer host (never gated on forge loading), ↑/↓, Enter, Escape.
 *
 * @param actions the launcher's broker seam — `validatePath`, `listForges`, `searchForge`,
 *   `cloneForge`, `createLocalRepo`, `createForge`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPicker(
    expanded: Boolean,
    current: String,
    projects: List<String>,
    home: String,
    actions: LauncherActions,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * Production leaves this true (heading dropdown on a pointer host). UI tests that need real
     * [FocusRequester] semantics set false — headless skiko often does not report IsFocused inside
     * [DropdownMenu]. Ignored under Compact, where the container is a sheet.
     */
    useDropdownMenu: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    var search by remember(expanded) { mutableStateOf("") }
    var validating by remember(expanded) { mutableStateOf(false) }
    var validationError by remember(expanded) { mutableStateOf<String?>(null) }
    var connections by remember(expanded) { mutableStateOf(emptyList<ForgeConnection>()) }
    var cloudRepos by remember(expanded) { mutableStateOf(emptyList<RemoteRepo>()) }
    var searching by remember(expanded) { mutableStateOf(false) }
    var searchError by remember(expanded) { mutableStateOf<String?>(null) }
    var searchEmpty by remember(expanded) { mutableStateOf(false) }
    var cloudVisible by remember(expanded) { mutableStateOf(FORGE_OMNIBOX_PAGE_SIZE) }
    var resolving by remember(expanded) { mutableStateOf(false) }
    var resolveLabel by remember(expanded) { mutableStateOf("") }
    var resolveError by remember(expanded) { mutableStateOf<String?>(null) }
    var resolveJob by remember(expanded) { mutableStateOf<Job?>(null) }
    /** User hid the progress overlay while the host op is still in flight. */
    var resolveHid by remember(expanded) { mutableStateOf(false) }
    /** Path completed after Hide — discoverable one-click use (not silent disk materialisation). */
    var readyPath by remember(expanded) { mutableStateOf<String?>(null) }
    var searchAutofocused by remember(expanded) { mutableStateOf(false) }
    var highlight by remember(expanded) { mutableStateOf(0) }

    val query = search.trim()
    // Offer a free-path pick when the typed query isn't already an exact known project path.
    val showTypedPath = query.isNotEmpty() && projects.none { it == query }
    val projectOptions = remember(projects, home) {
        projects.map { ProjectOption(it, formatWorkdir(it, home)) }
    }

    // Autofocus immediately when the menu opens — never wait on broker forge loading.
    // searchAutofocused is set only from onFocusChanged (real focus), not after requestFocus(),
    // so tests that see the ready tag have proof the field is focused — not a side-effect flag.
    // A phone does NOT autofocus (Android's sheet never did): it would raise the IME over the list.
    LaunchedEffect(expanded, compact) {
        if (expanded && !compact) {
            runCatching { searchFocus.requestFocus() }
        }
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            connections = actions.listForges()
        }
    }

    // Debounced forge search (≥2 chars, only with connections) — Android/web parity.
    // Null response → error state; empty repos → empty message (not silent Create-only).
    LaunchedEffect(query, connections, expanded) {
        if (!expanded || connections.isEmpty() || query.length < 2) {
            cloudRepos = emptyList()
            searching = false
            searchError = null
            searchEmpty = false
            cloudVisible = FORGE_OMNIBOX_PAGE_SIZE
            return@LaunchedEffect
        }
        delay(250)
        searching = true
        searchError = null
        searchEmpty = false
        val result = actions.searchForge(query)
        searching = false
        if (result == null) {
            cloudRepos = emptyList()
            searchError = "Couldn't search repositories — check the connection and try again."
            searchEmpty = false
        } else {
            cloudRepos = result.repos
            cloudVisible = FORGE_OMNIBOX_PAGE_SIZE
            val partial = result.errors
                .mapNotNull { it.message.takeIf { m -> m.isNotBlank() } }
                .distinct()
                .take(2)
            searchError = when {
                partial.isNotEmpty() && result.repos.isEmpty() ->
                    partial.joinToString(" · ")
                partial.isNotEmpty() ->
                    "Some forges failed: ${partial.joinToString(" · ")}"
                else -> null
            }
            searchEmpty = result.repos.isEmpty() && partial.isEmpty()
        }
    }

    val pagedCloud = remember(cloudRepos, cloudVisible) {
        cloudRepos.take(cloudVisible)
    }
    val options = remember(query, projectOptions, pagedCloud, connections) {
        buildOmniboxOptions(query, projectOptions, pagedCloud, connections)
    }
    val locals = options.filterIsInstance<OmniOption.Local>()
    val clouds = options.filterIsInstance<OmniOption.Cloud>()
    val creates = options.filterIsInstance<OmniOption.Create>()
    val cloudGroups = remember(clouds, connections) {
        connections.mapNotNull { c ->
            val repos = clouds.filter { it.connectionId == c.id }.map { it.repo }
            if (repos.isEmpty()) null else c to repos
        }
    }
    val hasMoreCloud = cloudRepos.size > cloudVisible

    // Flat actionable rows for keyboard navigation (typed path / local / clone / create).
    // "Use this path" leads so Enter on a free-path query activates it first (Android row order).
    val navTargets = remember(showTypedPath, locals, clouds, creates) {
        buildList {
            if (showTypedPath) add(OmniNav.TypedPath)
            locals.forEach { add(OmniNav.Local(it.path)) }
            clouds.forEach { add(OmniNav.Clone(it.repo)) }
            creates.forEach { add(OmniNav.Create(it.createTarget, it.label)) }
        }
    }
    LaunchedEffect(navTargets.size) {
        if (highlight >= navTargets.size) highlight = (navTargets.size - 1).coerceAtLeast(0)
    }

    fun pick(path: String) {
        onPick(path)
        onDismiss()
    }

    /**
     * Hide the progress overlay only. Does **not** abort the broker clone/create
     * (host `git clone` is synchronous and uncancellable from the client).
     */
    fun hideResolveProgress() {
        if (!resolving) return
        resolving = false
        resolveHid = true
        resolveError = null
        // Keep resolveLabel so the "continues on the host" banner can name the op.
    }

    fun resolve(label: String, block: suspend () -> String?) {
        // Block while overlay is up OR a hidden host op is still in flight.
        if (resolving || resolveJob?.isActive == true) return
        resolving = true
        resolveHid = false
        resolveLabel = label
        resolveError = null
        readyPath = null
        resolveJob = scope.launch {
            try {
                val path = block()
                if (!path.isNullOrBlank()) {
                    if (resolveHid) {
                        // User already returned to the picker — surface the path for one-click use.
                        readyPath = path
                    } else {
                        pick(path)
                    }
                } else {
                    resolveError = "Couldn't $label — check the connection and try again."
                }
            } catch (_: CancellationException) {
                // Scope disposed (menu closed / composition left) — not user Hide.
            } catch (_: Throwable) {
                resolveError = "Couldn't $label — check the connection and try again."
            } finally {
                resolving = false
                resolveLabel = ""
                resolveHid = false
                resolveJob = null
            }
        }
    }

    /** "Use this path" — validated through the broker (tilde expand / exists) before it lands. */
    fun confirmTypedPath() {
        val p = query
        if (p.isEmpty() || validating || resolving) return
        validating = true
        validationError = null
        scope.launch {
            val res = actions.validatePath(p)
            validating = false
            val resolved = res?.path
            if (res != null && res.ok && !resolved.isNullOrBlank()) {
                pick(resolved)
            } else {
                validationError = res?.error ?: "Invalid path"
            }
        }
    }

    fun activateNav(target: OmniNav) {
        when (target) {
            is OmniNav.TypedPath -> confirmTypedPath()
            is OmniNav.Local -> pick(target.path)
            is OmniNav.Clone -> resolve("clone ${target.repo.fullName}") {
                actions.cloneForge(target.repo.connectionId, target.repo.owner, target.repo.name)
            }
            is OmniNav.Create -> resolve("create $query") {
                if (target.target == "local") actions.createLocalRepo(query)
                else actions.createForge(target.target, query)
            }
        }
    }

    fun onOmniboxKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        return when (val action = omniboxKeyAction(e.key, highlight, navTargets.size, resolving)) {
            is OmniboxKeyAction.Dismiss -> {
                onDismiss()
                true
            }
            is OmniboxKeyAction.HideResolve -> {
                hideResolveProgress()
                true
            }
            is OmniboxKeyAction.MoveHighlight -> {
                highlight = action.index
                true
            }
            is OmniboxKeyAction.Activate -> {
                navTargets.getOrNull(action.index)?.let { activateNav(it) }
                true
            }
            null -> false
        }
    }

    // resolveHid + non-blank label: user hid while host op still in flight (label cleared in finally).
    val hostOpContinues = resolveHid && resolveLabel.isNotEmpty()
    val hostOpVerb = when {
        resolveLabel.startsWith("clone ") -> "Clone"
        resolveLabel.startsWith("create ") -> "Create"
        else -> "Operation"
    }
    val hostOpTarget = resolveLabel
        .removePrefix("clone ")
        .removePrefix("create ")
        .ifBlank { null }

    @Composable
    fun MenuBody() {
        // Outer Box is ONLY for the resolving overlay (matchParentSize). Content is a Column —
        // siblings of a Box stack at TopStart (the old layout drew the list under the fields).
        Box(
            Modifier
                .then(if (compact) Modifier.fillMaxWidth() else Modifier.width(Size.omniboxWidth))
                .focusable()
                .onPreviewKeyEvent { onOmniboxKey(it) }
                .testTag("launcher_omnibox_root"),
        ) {
            Column(Modifier.padding(bottom = if (compact) Space.xl else Space.sm)) {
            // ── Single search field ──
            Column(Modifier.padding(horizontal = Space.md, vertical = Space.xs)) {
                // Android's sheet named itself; a dropdown anchored under the heading does not.
                if (compact) {
                    Text(
                        "Project",
                        color = cs.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(horizontal = Space.sm, vertical = Space.md)
                            .testTag("project_picker_title"),
                    )
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = {
                        search = it
                        resolveError = null
                        readyPath = null
                        validationError = null
                        highlight = 0
                    },
                    placeholder = {
                        Text(
                            "Search projects, repos, or type a path",
                            color = cs.onSurfaceVariant,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = null,
                            tint = cs.onSurfaceVariant,
                            modifier = Modifier.size(Space.lg + Space.xs),
                        )
                    },
                    trailingIcon = if (validating) {
                        {
                            CircularProgressIndicator(
                                Modifier.size(Space.lg),
                                strokeWidth = Stroke.thin,
                                color = cs.primary,
                            )
                        }
                    } else null,
                    singleLine = true,
                    enabled = !resolving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocus)
                        .onFocusChanged { if (it.isFocused) searchAutofocused = true }
                        .testTag("launcher_project_search")
                        .onPreviewKeyEvent { onOmniboxKey(it) },
                )
                // Ready only after the search field actually receives focus (onFocusChanged).
                if (searchAutofocused) {
                    Text(
                        "",
                        modifier = Modifier
                            .size(1.dp)
                            .testTag("launcher_project_autofocus_ready"),
                    )
                }
                validationError?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        it,
                        color = cs.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("launcher_path_error"),
                    )
                }
                if (hostOpContinues) {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        buildString {
                            append(hostOpVerb)
                            if (hostOpTarget != null) {
                                append(' ')
                                append(hostOpTarget)
                            }
                            append(" continues on the host…")
                        },
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("launcher_forge_host_continues"),
                    )
                }
                readyPath?.let { path ->
                    Spacer(Modifier.height(Space.xs))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        modifier = Modifier.testTag("launcher_forge_ready"),
                    ) {
                        Text(
                            "Ready — ${formatWorkdir(path, home)}",
                            color = cs.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = { pick(path) },
                            modifier = Modifier.testTag("launcher_forge_use_ready"),
                        ) {
                            Text("Use", color = cs.primary)
                        }
                    }
                }
                resolveError?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        it,
                        color = cs.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("launcher_forge_error"),
                    )
                }
                searchError?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        it,
                        color = cs.error,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("launcher_forge_search_error"),
                    )
                }
            }

            // Empty only when there is truly nothing to show (incl. no typed-path row).
            val nothing = !showTypedPath && locals.isEmpty() && cloudGroups.isEmpty() &&
                creates.isEmpty() && !searching && !hasMoreCloud
            val nothingLocalMatch = nothing && connections.isEmpty() && projects.isNotEmpty() &&
                query.isNotEmpty()
            if (nothingLocalMatch) {
                Text(
                    "No projects match \"${query}\".",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(horizontal = Space.xl - Space.xs, vertical = Space.sm)
                        .testTag("launcher_project_empty"),
                )
            }
            if (searchEmpty && !searching && query.length >= 2 && connections.isNotEmpty() &&
                cloudGroups.isEmpty()
            ) {
                Text(
                    "No repos match \"${query}\".",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(horizontal = Space.xl - Space.xs, vertical = Space.sm)
                        .testTag("launcher_forge_empty"),
                )
            }

            if (nothing && !nothingLocalMatch) {
                Text(
                    "Type a path or search your projects.",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(
                        horizontal = Space.xl - Space.xs,
                        vertical = Space.lg + Space.xs,
                    ),
                )
            } else if (!nothing || showTypedPath || locals.isNotEmpty() || cloudGroups.isNotEmpty() ||
                creates.isNotEmpty() || searching || hasMoreCloud
            ) {
            Column(
                Modifier
                    .heightIn(max = if (compact) Size.omniboxSheetListMax else Size.omniboxListMax)
                    .verticalScroll(rememberScrollState())
                    .testTag("launcher_omnibox_list"),
            ) {
                // "Use this path" — first row when the query is a free path.
                if (showTypedPath) {
                    val hi = highlight == 0 && navTargets.firstOrNull() is OmniNav.TypedPath
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    "Use this path",
                                    color = if (hi) cs.primary else cs.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                Text(
                                    query,
                                    color = cs.onSurfaceVariant,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier.size(Space.lg + Space.xs),
                            )
                        },
                        enabled = !resolving && !validating,
                        modifier = Modifier
                            .testTag("launcher_use_path")
                            .then(
                                if (hi) Modifier.background(cs.primary.copy(alpha = 0.08f))
                                else Modifier,
                            ),
                        onClick = { confirmTypedPath() },
                    )
                }

                if (locals.isNotEmpty()) {
                    Text(
                        "Projects",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(
                            horizontal = Space.xl - Space.xs,
                            vertical = Space.xs,
                        ),
                    )
                    locals.forEach { o ->
                        val selected = o.path == current
                        val navIndex = navTargets.indexOfFirst {
                            it is OmniNav.Local && it.path == o.path
                        }
                        val hi = navIndex == highlight
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        o.path.trimEnd('/').substringAfterLast('/').ifEmpty { o.path },
                                        color = when {
                                            hi -> cs.primary
                                            selected -> cs.primary
                                            else -> cs.onSurface
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                    Text(
                                        o.label,
                                        color = cs.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(Space.lg + Space.xs),
                                )
                            },
                            trailingIcon = {
                                if (selected) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(Space.lg), tint = cs.primary)
                                }
                            },
                            enabled = !resolving,
                            modifier = Modifier
                                .testTag("project_row_${o.path}")
                                .then(
                                    if (hi) Modifier.background(cs.primary.copy(alpha = 0.08f))
                                    else Modifier,
                                ),
                            onClick = { pick(o.path) },
                        )
                    }
                }

                cloudGroups.forEach { (conn, repos) ->
                    Text(
                        "${conn.host} · @${conn.account.login}",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(horizontal = Space.xl - Space.xs, vertical = Space.xs)
                            .testTag("forge_group_${conn.id}"),
                    )
                    repos.forEach { repo ->
                        val navIndex = navTargets.indexOfFirst {
                            it is OmniNav.Clone && it.repo.fullName == repo.fullName &&
                                it.repo.connectionId == repo.connectionId
                        }
                        val hi = navIndex == highlight
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        repo.name,
                                        color = if (hi) cs.primary else cs.onSurface,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                    )
                                    Text(
                                        repo.fullName,
                                        color = cs.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                    )
                                }
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(Space.lg + Space.xs),
                                )
                            },
                            trailingIcon = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                                ) {
                                    Icon(
                                        Icons.Filled.Download,
                                        contentDescription = "Clone",
                                        tint = cs.onSurfaceVariant,
                                        modifier = Modifier.size(Space.md + Space.xs),
                                    )
                                    Text(
                                        "Clone",
                                        color = cs.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            },
                            enabled = !resolving,
                            modifier = Modifier
                                .testTag("forge_clone_${repo.fullName}")
                                .then(
                                    if (hi) Modifier.background(cs.primary.copy(alpha = 0.08f))
                                    else Modifier,
                                ),
                            onClick = {
                                resolve("clone ${repo.fullName}") {
                                    actions.cloneForge(repo.connectionId, repo.owner, repo.name)
                                }
                            },
                        )
                    }
                }

                if (hasMoreCloud && !searching) {
                    TextButton(
                        onClick = {
                            cloudVisible += FORGE_OMNIBOX_PAGE_SIZE
                        },
                        enabled = !resolving,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("launcher_forge_load_more"),
                    ) {
                        Text(
                            "Load more (${cloudRepos.size - cloudVisible} remaining)",
                            color = cs.primary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                if (searching && cloudGroups.isEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.xl - Space.xs, vertical = Space.md + Space.xs)
                            .testTag("launcher_forge_searching"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        CircularProgressIndicator(
                            Modifier.size(Space.lg),
                            strokeWidth = Stroke.thin,
                            color = cs.primary,
                        )
                        Text(
                            "Searching repos…",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }

                if (creates.isNotEmpty()) {
                    Text(
                        "Create",
                        color = cs.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(
                            horizontal = Space.xl - Space.xs,
                            vertical = Space.xs,
                        ),
                    )
                    creates.forEach { c ->
                        val navIndex = navTargets.indexOfFirst {
                            it is OmniNav.Create && it.target == c.createTarget
                        }
                        val hi = navIndex == highlight
                        DropdownMenuItem(
                            text = {
                                Text(
                                    c.label,
                                    color = if (hi) cs.primary else cs.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = cs.onSurfaceVariant,
                                    modifier = Modifier.size(Space.lg + Space.xs),
                                )
                            },
                            enabled = !resolving,
                            modifier = Modifier
                                .testTag("forge_create_${c.createTarget}")
                                .then(
                                    if (hi) Modifier.background(cs.primary.copy(alpha = 0.08f))
                                    else Modifier,
                                ),
                            onClick = {
                                resolve("create $query") {
                                    if (c.createTarget == "local") actions.createLocalRepo(query)
                                    else actions.createForge(c.createTarget, query)
                                }
                            },
                        )
                    }
                }
            } // list Column
            } // else: has rows to show
            } // content Column (fields + list); resolving overlay is a Box sibling

            if (resolving) {
                Box(
                    Modifier
                        .matchParentSize()
                        .background(cs.scrim.copy(alpha = 0.35f))
                        .testTag("launcher_forge_resolving"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radii.lg))
                            .background(cs.surfaceContainerHigh)
                            .padding(horizontal = Space.lg, vertical = Space.md),
                    ) {
                        CircularProgressIndicator(
                            color = cs.primary,
                            strokeWidth = Stroke.thin,
                            modifier = Modifier.size(Space.xl),
                        )
                        Text(
                            when {
                                resolveLabel.startsWith("clone ") ->
                                    "Cloning ${resolveLabel.removePrefix("clone ")}…"
                                resolveLabel.startsWith("create ") ->
                                    "Creating ${resolveLabel.removePrefix("create ")}…"
                                else -> "Working…"
                            },
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.testTag("launcher_forge_resolving_label"),
                        )
                        // Honest: broker clone/create is not abortable — Hide only drops the
                        // overlay; the host keeps working (Agents install cancel parity).
                        Text(
                            "Continues on the host if you hide.",
                            color = cs.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.testTag("launcher_forge_hide_hint"),
                        )
                        TextButton(
                            onClick = { hideResolveProgress() },
                            modifier = Modifier.testTag("launcher_forge_hide"),
                        ) {
                            Text("Hide", color = cs.primary)
                        }
                    }
                }
            }
        }
    }

    if (compact) {
        if (expanded) {
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    // A sheet is NOT a dropdown: Material 3 animates it to Hidden and only THEN
                    // calls this, so a guard that merely returns would leave the picker composed
                    // but invisible while the launcher still holds it open — the project heading
                    // would go dead. Bring the sheet back up instead, matching the dropdown's
                    // "outside click while resolving only hides the progress" rule.
                    if (resolving) {
                        hideResolveProgress()
                        scope.launch { sheetState.show() }
                    } else {
                        onDismiss()
                    }
                },
                sheetState = sheetState,
                containerColor = cs.surfaceContainerLow,
                contentColor = cs.onSurface,
                modifier = Modifier.testTag("launcher_project_menu"),
            ) {
                MenuBody()
            }
        }
    } else if (useDropdownMenu) {
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                // Escape/outside click while overlay is up only hides progress — host keeps going.
                if (resolving) hideResolveProgress()
                else onDismiss()
            },
            modifier = Modifier.testTag("launcher_project_menu"),
        ) {
            MenuBody()
        }
    } else if (expanded) {
        // Plain host for UI tests that assert real focus (DropdownMenu popup breaks IsFocused).
        Box(Modifier.testTag("launcher_project_menu")) {
            MenuBody()
        }
    }
}
