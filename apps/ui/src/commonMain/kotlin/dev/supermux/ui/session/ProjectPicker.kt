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
import kotlin.time.Clock
import dev.supermux.session.ProjectActivity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.ForgeConnection
import dev.supermux.net.RemoteRepo
import dev.supermux.session.OmniOption
import dev.supermux.session.ProjectOption
import dev.supermux.session.buildOmniboxOptions
import dev.supermux.session.formatWorkdir
import dev.supermux.workspace.ProjectRef
import dev.supermux.proto.ProjectDto
import androidx.compose.material.icons.filled.Lock
import dev.supermux.session.looksLikePath
import dev.supermux.session.fuzzyMatch
import dev.supermux.session.projectFolderName
import dev.supermux.ui.adaptive.LocalPointerAvailable
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
 * Container by INPUT DEVICE, not by width: a `ModalBottomSheet` wherever no pointer is attached
 * (a phone, and equally a tablet or unfolded foldable held in the hand — what Android's
 * `ProjectPickerSheet` was), an anchored [DropdownMenu] wherever one is (desktop at any window
 * width, a docked tablet, DeX). Width was the wrong question: a dropdown must hang off the project
 * heading, and both hosts anchor it there, so a hand-held tablet would otherwise get a menu it
 * cannot comfortably drive while a narrow desktop window would lose the dropdown it always had.
 * The body, and every test tag on it, is identical either way.
 *
 * Clone/create is long-running and **not abortable** on the broker (`git clone` via
 * `execFileSync`). The progress overlay offers **Hide** (not Cancel): the host keeps working;
 * if the user hides, a notice stays visible and a successful finish surfaces a ready path to pick.
 * Search failures are distinct from empty results. Keyboard: autofocus search immediately on a
 * pointer host (never gated on forge loading), ↑/↓, Enter, Escape.
 *
 * The picker is composed whether or not it is [expanded], so a clone/create that lands AFTER a
 * dismiss is dropped rather than silently rewriting the launcher's workdir.
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
    /** Sessions per project and when one last spoke, keyed by project path — the tiles' "● 2m". */
    activity: Map<String, ProjectActivity> = emptyMap(),
    /**
     * The host's project catalog. When non-empty its projects ARE the picker's projects (plain
     * folders outside it still turn up in search); picking one calls [onCatalogProject] and leaves
     * the menu to the launcher, which either lands a location or opens [catalogLocationsFor].
     */
    catalog: List<ProjectDto> = emptyList(),
    catalogHostKey: String = "",
    loadCatalogImage: suspend (ProjectRef) -> ByteArray? = { null },
    /** A catalog project whose locations to list instead of the search. */
    catalogLocationsFor: ProjectDto? = null,
    onCatalogProject: (ProjectDto) -> Unit = {},
    onCatalogLocation: (ProjectDto, String) -> Unit = { _, _ -> },
    onCatalogLocationsBack: () -> Unit = {},
    /**
     * Production leaves this true (heading dropdown on a pointer host). UI tests that need real
     * [FocusRequester] semantics set false — headless skiko often does not report IsFocused inside
     * [DropdownMenu]. Ignored under Compact, where the container is a sheet.
     */
    useDropdownMenu: Boolean = true,
    /** Width of the heading the dropdown hangs off, so the menu can centre under it (0 = start-aligned). */
    anchorWidth: Dp = 0.dp,
) {
    val cs = MaterialTheme.colorScheme
    /** No mouse/touchpad → the sheet. Never the width class: see the container note above. */
    val touch = !LocalPointerAvailable.current
    val scope = rememberCoroutineScope()
    // Read inside the resolve coroutine, which outlives a dismiss (the picker stays composed).
    val expandedNow by rememberUpdatedState(expanded)
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
    /** "2m ago" is relative to when the picker opened — no ticking clock inside a menu. */
    val nowMs = remember(expanded) { Clock.System.now().toEpochMilliseconds() }
    var highlight by remember(expanded) { mutableStateOf(0) }

    val query = search.trim()
    // Offer a free-path pick when the typed query isn't already an exact known project path.
    // …and only when it reads as a path: a bare word is a project to find or a repo to create.
    val showTypedPath = looksLikePath(query) && projects.none { it == query }
    val pickerCatalog = remember(catalog, catalogHostKey, loadCatalogImage) {
        PickerCatalog(catalog.associateBy { it.id }, catalogHostKey, loadCatalogImage)
    }
    val projectOptions = remember(projects, home, catalog, activity) {
        if (catalog.isEmpty()) {
            projects.map { ProjectOption(it, formatWorkdir(it, home)) }
        } else {
            // Most recently active first; the stable sort keeps catalog order for the rest.
            val lastActive = { p: ProjectDto -> p.locations.mapNotNull { activity[it.path]?.lastActiveMs }.maxOrNull() ?: Long.MIN_VALUE }
            val inCatalog = catalog.flatMap { p -> p.locations.map { it.path } }.toHashSet()
            catalog.sortedByDescending(lastActive).map { p ->
                ProjectOption(
                    path = CATALOG_KEY_PREFIX + p.id,
                    label = p.locations.firstOrNull()?.let { formatWorkdir(it.path, home) }.orEmpty(),
                    name = p.name,
                    projectId = p.id,
                    locations = p.locations.map { it.path },
                )
            } + projects.filter { it !in inCatalog }.map { ProjectOption(it, formatWorkdir(it, home)) }
        }
    }

    // Autofocus immediately when the menu opens — never wait on broker forge loading.
    // searchAutofocused is set only from onFocusChanged (real focus), not after requestFocus(),
    // so tests that see the ready tag have proof the field is focused — not a side-effect flag.
    // A touch host does NOT autofocus (Android's sheet never did): it would raise the IME over the list.
    LaunchedEffect(expanded, touch) {
        if (expanded && !touch) {
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
    val options = remember(query, projectOptions, pagedCloud, connections, home) {
        buildOmniboxOptions(query, projectOptions, pagedCloud, connections, home)
    }
    // With a catalog, folders outside it are search results only — the resting picker is projects.
    val locals = options.filterIsInstance<OmniOption.Local>()
        .filter { query.isNotEmpty() || catalog.isEmpty() || it.projectId != null }
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

    /** A local option: a catalog project goes to the launcher, a plain folder is picked. */
    fun pickLocal(path: String) {
        val project = if (path.startsWith(CATALOG_KEY_PREFIX)) pickerCatalog.byId[path.removePrefix(CATALOG_KEY_PREFIX)] else null
        if (project != null) onCatalogProject(project) else pick(path)
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
                    if (!expandedNow) {
                        // The user closed the picker while the host worked. Landing the path now
                        // would rewrite the launcher's workdir out from under them — Android's
                        // sheet used to be unmounted here, and desktop (which kept the picker
                        // composed exactly as this does) really did rewrite it. Drop it.
                    } else if (resolveHid) {
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
            is OmniNav.Local -> pickLocal(target.path)
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
        catalogLocationsFor?.let { project ->
            CatalogLocations(
                project = project,
                catalog = pickerCatalog,
                current = current,
                home = home,
                activity = activity,
                nowMs = nowMs,
                onBack = onCatalogLocationsBack,
                onLocation = { onCatalogLocation(project, it) },
                modifier = if (touch) Modifier.fillMaxWidth() else Modifier.width(Size.omniboxWidth),
            )
            return
        }
        if (catalog.isNotEmpty()) Box(Modifier.size(0.dp).testTag(CatalogPickerTestIds.MENU))
        // Outer Box is ONLY for the resolving overlay (matchParentSize). Content is a Column —
        // siblings of a Box stack at TopStart (the old layout drew the list under the fields).
        Box(
            Modifier
                .then(if (touch) Modifier.fillMaxWidth() else Modifier.width(Size.omniboxWidth))
                .focusable()
                .onPreviewKeyEvent { onOmniboxKey(it) }
                .testTag("launcher_omnibox_root"),
        ) {
            Column(Modifier.padding(bottom = if (touch) Space.xl else Space.sm)) {
            // ── Single search field ──
            // 5a's field inset: 8dp from the menu edge and top.
            Column(Modifier.padding(horizontal = Space.sm, vertical = Space.sm)) {
                // Android's sheet named itself; a dropdown anchored under the heading does not.
                if (touch) {
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
                // Compact rounded field (the picker lab's 5a), not a full-height outlined one.
                BasicTextField(
                    value = search,
                    onValueChange = {
                        search = it
                        resolveError = null
                        readyPath = null
                        validationError = null
                        highlight = 0
                    },
                    singleLine = true,
                    enabled = !resolving,
                    cursorBrush = SolidColor(cs.primary),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = cs.onSurface, fontSize = 14.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocus)
                        .onFocusChanged { if (it.isFocused) searchAutofocused = true }
                        .testTag("launcher_project_search")
                        .onPreviewKeyEvent { onOmniboxKey(it) },
                    decorationBox = { field ->
                        val shape = RoundedCornerShape(Radii.sm)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                            modifier = Modifier
                                .clip(shape)
                                .background(cs.surfaceContainerLowest)
                                .border(
                                    Stroke.hairline,
                                    cs.outlineVariant,
                                    shape,
                                )
                                .padding(horizontal = Space.md, vertical = Space.sm),
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = cs.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Box(Modifier.weight(1f)) {
                                if (search.isEmpty()) {
                                    Text(
                                        "Search projects, repos, or type a path",
                                        color = cs.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                                        maxLines = 1,
                                    )
                                }
                                field()
                            }
                            if (validating) {
                                CircularProgressIndicator(
                                    Modifier.size(Space.lg),
                                    strokeWidth = Stroke.thin,
                                    color = cs.primary,
                                )
                            }
                        }
                    },
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
                        .padding(horizontal = Space.md, vertical = Space.sm)
                        .testTag("launcher_project_empty"),
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
            // The rows, keyed. A touch host scrolls them in a LazyColumn (Android's sheet did,
            // and a forge search can be hundreds of repos under a thumb); the desktop menu keeps
            // its plain scrolling Column so `performScrollTo` and the dropdown sizing are unchanged.
            val rows = buildList<Pair<String, @Composable () -> Unit>> {
                // "Use this path" — first row, and only when the query reads as a path.
                if (showTypedPath) {
                    add("typed" to {
                        PickerRow(
                            onClick = { confirmTypedPath() },
                            highlighted = highlight == 0 && navTargets.firstOrNull() is OmniNav.TypedPath,
                            enabled = !resolving && !validating,
                            modifier = Modifier.testTag("launcher_use_path"),
                        ) {
                            Icon(Icons.Filled.FolderOpen, null, tint = cs.primary, modifier = Modifier.size(Space.lg))
                            Text("Use this path", color = cs.onSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            StartEllipsizedText(
                                query,
                                style = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    })
                }

                if (locals.isNotEmpty()) {
                    // Empty search → the most recent projects as tiles (arrow keys walk them in
                    // order, they are the first nav targets), the rest as rows below.
                    val tiles = if (query.isEmpty()) locals.take(PROJECT_TILE_COUNT) else emptyList()
                    val listed = locals.drop(tiles.size)
                    if (tiles.isNotEmpty()) {
                        add("h_recent" to { PickerSectionLabel("Jump back in") })
                        tiles.chunked(PROJECT_TILE_COLUMNS).forEachIndexed { i, row ->
                            add("tiles_$i" to {
                                ProjectTileRow(
                                    row = row,
                                    current = current,
                                    home = home,
                                    catalog = pickerCatalog,
                                    activity = activity,
                                    nowMs = nowMs,
                                    highlighted = { path ->
                                        navTargets.indexOfFirst { it is OmniNav.Local && it.path == path } == highlight
                                    },
                                    enabled = !resolving,
                                    onPick = { pickLocal(it.path) },
                                )
                            })
                        }
                    }
                    if (listed.isNotEmpty()) {
                        add("h_projects" to { PickerSectionLabel(if (tiles.isEmpty()) "Projects" else "Everything else") })
                    }
                    listed.forEach { o ->
                        add("l_${o.path}" to {
                            val selected = current in o.paths(pickerCatalog)
                            // Name, the full location (trimmed from the start), then activity.
                            PickerRow(
                                onClick = { pickLocal(o.path) },
                                highlighted = navTargets.indexOfFirst { it is OmniNav.Local && it.path == o.path } == highlight,
                                enabled = !resolving,
                                modifier = Modifier.testTag(o.testTag()),
                            ) {
                                ProjectMonogram(o, pickerCatalog, 18.dp)
                                Text(
                                    highlightHits(o.displayName(), o.nameHits, cs.primary),
                                    color = if (selected) cs.primary else cs.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                )
                                StartEllipsizedText(
                                    o.locationText(pickerCatalog, home),
                                    style = MaterialTheme.typography.labelSmall.copy(color = cs.onSurfaceVariant, fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.weight(1f),
                                )
                                o.activity(pickerCatalog, activity)?.let { ActivityLine(it, nowMs) }
                                if (selected) Icon(Icons.Filled.Check, "Current project", Modifier.size(Space.lg), tint = cs.primary)
                            }
                        })
                    }
                }

                cloudGroups.forEach { (conn, repos) ->
                    add("h_${conn.id}" to {
                        PickerSectionLabel(
                            "Clone from ${conn.host} · ${conn.account.login}",
                            modifier = Modifier.testTag("forge_group_${conn.id}"),
                        )
                    })
                    repos.forEach { repo ->
                        add("c_${conn.id}_${repo.fullName}" to {
                            PickerRow(
                                onClick = {
                                    resolve("clone ${repo.fullName}") {
                                        actions.cloneForge(repo.connectionId, repo.owner, repo.name)
                                    }
                                },
                                highlighted = navTargets.indexOfFirst {
                                    it is OmniNav.Clone && it.repo.fullName == repo.fullName &&
                                        it.repo.connectionId == repo.connectionId
                                } == highlight,
                                enabled = !resolving,
                                modifier = Modifier.testTag("forge_clone_${repo.fullName}"),
                            ) {
                                Icon(Icons.Filled.Download, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(Space.lg))
                                Text(
                                    highlightHits(repo.fullName, fuzzyMatch(query, repo.fullName)?.indices.orEmpty(), cs.primary),
                                    color = cs.onSurface,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f),
                                )
                                if (repo.private) {
                                    Icon(Icons.Filled.Lock, "Private", tint = cs.onSurfaceVariant, modifier = Modifier.size(Space.md))
                                }
                                Text("Clone", color = cs.primary, style = MaterialTheme.typography.labelMedium)
                            }
                        })
                    }
                }

                if (hasMoreCloud && !searching) {
                    add("load_more" to {
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
                    })
                }

                if (searching && cloudGroups.isEmpty()) {
                    add("searching" to {
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
                    })
                }

                if (creates.isNotEmpty()) {
                    add("h_create" to { PickerSectionLabel("Create new") })
                    creates.forEach { c ->
                        add("cr_${c.createTarget}" to {
                            PickerRow(
                                onClick = {
                                    resolve("create $query") {
                                        if (c.createTarget == "local") actions.createLocalRepo(query)
                                        else actions.createForge(c.createTarget, query)
                                    }
                                },
                                highlighted = navTargets.indexOfFirst {
                                    it is OmniNav.Create && it.target == c.createTarget
                                } == highlight,
                                enabled = !resolving,
                                modifier = Modifier.testTag("forge_create_${c.createTarget}"),
                            ) {
                                Icon(Icons.Filled.Add, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(Space.lg))
                                Text(c.label, color = cs.onSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            }
                        })
                    }
                }
            }

            val listMax = if (touch) Size.omniboxSheetListMax else Size.omniboxTilesListMax
            if (touch) {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = listMax)
                        .testTag("launcher_omnibox_list"),
                ) {
                    items(rows, key = { it.first }) { it.second() }
                }
            } else {
                Column(
                    Modifier
                        .heightIn(max = listMax)
                        .verticalScroll(rememberScrollState())
                        .testTag("launcher_omnibox_list"),
                ) {
                    rows.forEach { it.second() }
                }
            } // list
            } // else: has rows to show
            // Under the list, never above real results — and only when no project matched either.
            if (searchEmpty && !searching && query.length >= 2 && connections.isNotEmpty() &&
                cloudGroups.isEmpty() && locals.isEmpty() && !showTypedPath
            ) {
                Text(
                    "No repos match \"${query}\".",
                    color = cs.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .padding(horizontal = Space.md, vertical = Space.sm)
                        .testTag("launcher_forge_empty"),
                )
            }

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

    if (touch) {
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
            // Centred under the heading, not hung off its left edge.
            offset = DpOffset((anchorWidth - Size.omniboxWidth) / 2, 0.dp),
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
