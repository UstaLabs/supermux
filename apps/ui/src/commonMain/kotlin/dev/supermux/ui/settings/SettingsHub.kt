// The one Settings shell for both apps (cluster E1).
//
// Base = desktop's `settings/SettingsHub.kt`: a left rail of [SettingsSection]s beside a detail
// pane, with the identity dirty-close guard (`onRegisterCloseHandler` → Escape / NavDisplay back).
// The Compact branch is Android's `MoreScreens.kt` router + index list: a `TopAppBar`, an index of
// tappable rows, and a push to the section's detail with `BackHandler` returning to the index.
//
// Sections are NOT rendered here — they arrive through the [content] slot, so E2–E6 can replace a
// host screen with the shared one a section at a time without touching this file. In E1 both hosts
// pass their existing per-section composables in.
package dev.supermux.ui.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.retain.LocalRetainedPage
import dev.supermux.ui.retain.newRetainedPageOpening
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.LocalIosBackSwipe
import dev.supermux.ui.widgets.SwipeBackHandler
import dev.supermux.ui.widgets.SwipeBackPages
import dev.supermux.ui.widgets.rememberIosBackSwipe

/**
 * Rows the hub offers beyond [SettingsSection] — host-local pages that are not broker settings and
 * therefore have no place in the shared section enum. Each is gated on a capability read from
 * `LocalPlatform.current.caps`, so a machine that cannot do the thing never shows the row.
 */
enum class SettingsExtra(val label: String, val desc: String) {
    // Hosting is the Machine page's "This computer" screen since #97 (`MachineSub.COMPUTER`).

    /** Theme / Material You / text scale. Gated on `Caps.appearanceControls`. */
    Appearance("Appearance", "Theme, Material You, and text size"),

    /** In-app updater. Gated on `Caps.appUpdate`. */
    AppUpdate("Check for updates", "App version and one-tap install"),

    /** The attachment cache's size + Clear. Gated on `Platform.mediaCache` being non-null. */
    Storage("Storage", "Cached images, videos and files"),
}

/**
 * What a section slot is handed by the hub.
 *
 * @property onClose leave this section — back to the index under Compact, close the whole hub on a
 *   rail layout. Honors the dirty guard, so a folded screen's own Back button can call it safely.
 * @property onDirtyChange the section reports unsaved edits; while true the hub confirms before a
 *   section switch or a close (available to every section).
 * @property topBarShown true when the hub already painted a `TopAppBar` for this detail — a slot
 *   that could carry its own `Scaffold`/`TopAppBar` (every settings screen can, because Android's
 *   standalone routes still need one) must only draw it when this is false.
 * @property onOpen move the hub to another place: a page (as a rail click or an index tap would), a sub-page
 *   of a page (a provider, the glossary, a machine screen) or a row on one. A sub-page of the page on display
 *   swaps in place and keeps what the page retains. Honors the dirty guard.
 * @property onAnchorShown the page has scrolled to and flashed the place's anchor: the hub forgets it, so a
 *   recreation or a Compact↔wide swap does not land on it again.
 */
@Immutable
class SettingsSlotScope internal constructor(
    val onClose: () -> Unit,
    val onDirtyChange: (Boolean) -> Unit,
    val topBarShown: Boolean,
    val onOpen: (SettingsPlace) -> Unit = {},
    val onAnchorShown: () -> Unit = {},
) {
    /** Move the hub to [section]'s page (Accounts' "Sign in again" opens Providers). */
    fun onOpenSection(section: SettingsSection) = onOpen(SettingsPlace(section))
}

/** Which detail the hub is showing. */
private sealed interface Target {
    data class Sec(val section: SettingsSection) : Target
    data class Ext(val extra: SettingsExtra) : Target
}

/** [Target]'s name in saved state and in a page key. */
private fun Target.key(): String = when (this) {
    is Target.Sec -> "s:${section.name}"
    is Target.Ext -> "e:${extra.name}"
}

private fun targetOf(key: String): Target? = when {
    key.startsWith("s:") -> SettingsSection.entries.firstOrNull { it.name == key.removePrefix("s:") }?.let(Target::Sec)
    key.startsWith("e:") -> SettingsExtra.entries.firstOrNull { it.name == key.removePrefix("e:") }?.let(Target::Ext)
    else -> null
}

/** [section] as a destination: any section; null is `Route.Settings`'s "just Settings" (#97). */
private fun deliberate(section: SettingsSection?): Target? = section?.let(Target::Sec)

/** The page a wide hub shows for "just Settings". */
private val WIDE_DEFAULT = SettingsSection.General

/** A route's place as one key: a new section, sub-page or anchor is a new landing; the same one coming back is not. */
private fun routeKey(section: SettingsSection?, sub: String?, anchor: String?): String =
    "${section?.name ?: ""}|${sub ?: ""}|${anchor ?: ""}"

/**
 * Where the hub is, saved with its nav entry (#89): the phone's pushed page, the wide hub's extra
 * page, the sub-page and pending anchor on it (#97), the route it last landed on, the layout it was
 * last drawn in, and the opening of the page on display that the sections keep their forms under
 * ([LocalRetainedPage]).
 */
private class HubPlace(opened: Target?, landedFor: String, drawnCompact: Boolean) {
    /** The page the user opened: the phone's pushed page (null = the index), or the wide rail's last pick. */
    var opened by mutableStateOf(opened)
    var extra by mutableStateOf<SettingsExtra?>(null)
    /** The second-level page on the page on display (null = the page itself). */
    var sub by mutableStateOf<String?>(null)
    /** A row to scroll to and flash, until the page reports it shown. */
    var anchor by mutableStateOf<String?>(null)
    /** A recreation or a width change brings this route back ([routeKey]); only another one is a new landing. */
    var landedFor by mutableStateOf(landedFor)
    /** Differs from the window's width class while the Compact↔wide swap is being applied. */
    var drawnCompact by mutableStateOf(drawnCompact)
    var opening by mutableStateOf(newRetainedPageOpening())

    /** The user opened a page: what its sections keep starts empty, as a new composition did. */
    fun reopen() {
        opening = newRetainedPageOpening()
    }

    companion object {
        // Nulls are saved as "". A section or extra name an older build saved restores as nothing (`targetOf`).
        val Saver: Saver<HubPlace, Any> = listSaver(
            save = {
                listOf(
                    it.opened?.key() ?: "", it.extra?.name ?: "", it.landedFor, it.drawnCompact, it.opening,
                    it.sub ?: "", it.anchor ?: "",
                )
            },
            restore = { parts ->
                HubPlace(targetOf(parts[0] as String), parts[2] as String, parts[3] as Boolean).apply {
                    extra = SettingsExtra.entries.firstOrNull { it.name == parts[1] }
                    opening = parts[4] as String
                    // A save from before #97 has neither: the page itself.
                    sub = (parts.getOrNull(5) as? String)?.takeIf { it.isNotEmpty() }
                    anchor = (parts.getOrNull(6) as? String)?.takeIf { it.isNotEmpty() }
                }
            },
        )
    }
}

/**
 * Full-pane Settings.
 *
 * `LocalWindowWidthClass == Compact` renders Android's index + push; anything wider renders
 * desktop's rail + detail. [section] is the rail selection (desktop reads and writes it through
 * `Route.Settings.section`); null is "just Settings" — the index on a phone, General on a wide
 * window. Any section, [sub] or [anchor] is a deliberate destination and lands there (#97).
 *
 * @param onBack close the hub entirely (the host pops its route).
 * @param onRegisterCloseHandler the host's Escape / system-back path — the hub keeps a closure here
 *   that honors the dirty guard, and restores a plain [onBack] on dispose.
 * @param hostKey the active host id. The compact push stack is scoped to it: switching hosts
 *   returns to the index rather than leaving a stale detail of the previous host on screen.
 * @param extraContent renders a [SettingsExtra] page. Only reached for extras the caps allow.
 * @param sub a second-level page of [section] to land on (a provider, the glossary, a machine screen).
 * @param anchor a row of the landed page to scroll to and flash once.
 * @param content renders one place — each host's `*SettingsSections.kt` wiring.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun SettingsHub(
    section: SettingsSection?,
    onSectionChange: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    sub: String? = null,
    anchor: String? = null,
    onRegisterCloseHandler: ((() -> Unit) -> Unit)? = null,
    hostKey: String? = null,
    extraContent: @Composable (SettingsExtra, SettingsSlotScope) -> Unit = { _, _ -> },
    content: @Composable (SettingsPlace, SettingsSlotScope) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val caps = LocalPlatform.current.caps
    val hasMediaCache = LocalPlatform.current.mediaCache != null
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    // A route's sub-page or anchor only means something with a section.
    val routeSub = sub.takeIf { section != null }
    val routeAnchor = anchor.takeIf { section != null }
    val routeKey = routeKey(section, routeSub, routeAnchor)
    /** Privacy & labs is just "Privacy" where there are no labs. */
    val labs = caps.assistantOverlay
    val extras = remember(caps, hasMediaCache) {
        SettingsExtra.entries.filter {
            when (it) {
                SettingsExtra.Appearance -> caps.appearanceControls
                SettingsExtra.AppUpdate -> caps.appUpdate
                SettingsExtra.Storage -> hasMediaCache
            }
        }
    }

    var identityDirty by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    /** When non-null, discard-confirm runs this instead of closing the hub. */
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    /**
     * The compact hub's landing target: no section is `Route.Settings`'s default and means "just
     * Settings", so it lands on the index; any section is a DELIBERATE destination
     * (`openLspSettings`, a `settings/coding` deep link) and lands there.
     *
     * Before this, `opened` always started at null, so a phone deep link showed the index while
     * `ShellUiState.lspSettingsOpen` reported the section open — the state lied. It is safe to seed
     * now only because the shell gives the settings entry a stable content key: while `section`
     * was the entry key, seeding from it would have reopened the section every time the route was
     * rewritten.
     */
    fun landing(): Target? = if (compact) deliberate(section) else null

    // Compact push stack (null = index) and wide extra selection, both reset with the host. Saved
    // with the hub's nav entry (#89): an Android recreation rebuilt the phone hub on its index, and
    // a width change, which keeps this hub but swaps its layout, showed the wide rail's section
    // instead of the page the phone had open. A new opening of Settings is a new entry, so it
    // still starts at the landing.
    val place = rememberSaveable(hostKey, saver = HubPlace.Saver) {
        HubPlace(opened = landing(), landedFor = routeKey, drawnCompact = compact).apply {
            // The route's sub-page and anchor land with its page, on either layout.
            this.sub = routeSub
            this.anchor = routeAnchor
        }
    }
    var opened by place::opened
    var extraSelected by place::extra
    /** The section the wide hub shows when no rail pick or extra overrides it. */
    val wideSection = section ?: WIDE_DEFAULT

    // The swap: until the effect below applies it, each layout already draws the page the other
    // one had on display, so the page (and the forms kept for it) stays the same across it.
    val swapping = place.drawnCompact != compact

    /** The page a narrowing window opens on the phone: the wide hub's, unless it showed only the default. */
    fun narrowed(): Target? = extraSelected?.let(Target::Ext)
        ?: opened?.takeIf { it is Target.Sec && it.section == section }
        ?: deliberate(section)

    val phonePage: Target? = if (swapping) narrowed() else opened
    val widePage: Target = opened?.takeIf { swapping }
        ?: extraSelected?.let(Target::Ext)
        ?: Target.Sec(wideSection)
    // What a section keeps on its page (rememberRetainedOnPage) belongs to this opening of it.
    val pageKey = (if (compact) phonePage else widePage)?.let { "${place.opening}:${it.key()}" }

    /** Runs [action], or asks to discard first when a section reported unsaved edits. */
    fun guard(action: () -> Unit) {
        if (identityDirty) {
            pending = action
            showDiscardDialog = true
        } else {
            action()
        }
    }

    fun tryClose() = guard { onBack() }

    /** Forgets the sub-page and the anchor: the page itself is what shows next. */
    fun toPage() {
        place.sub = null
        place.anchor = null
    }

    /**
     * Back from wherever we are: a sub-page → its page; a page → the index (Compact) or close the hub;
     * the index → close the hub.
     */
    fun leave() {
        if (place.sub != null) {
            // The page's forms belong to the page, so going back to it is not a new opening.
            guard { toPage() }
        } else if (compact && opened != null) {
            guard {
                opened = null
                toPage()
                place.reopen()
            }
        } else {
            tryClose()
        }
    }

    /**
     * Shows [target] with [sub] and [anchor] on it. A sub-page or anchor on the page already on display swaps in
     * place: what that page retains belongs to the page, not to its sub-page (#97).
     */
    fun open(target: Target, sub: String? = null, anchor: String? = null) {
        if (compact) {
            // No `onSectionChange` here, and that omission is the whole point.
            //
            // The compact hub owns its own destination in [opened]; `section` is the WIDE rail's
            // selection and nothing on a phone reads it. Reporting it back anyway was not merely
            // redundant, it undid the push: the shell writes it into the back stack as a NEW
            // `Route.Settings(section)`, that value IS NavDisplay's content key for the entry, and
            // a changed key disposes the entry and composes a fresh one — a fresh hub, whose
            // `opened` starts at null. So the tap opened a section and the rewrite closed it in
            // the same frame, and every settings section on a phone was a row that did nothing.
            //
            // The `key(activeHostId)` in `SupermuxApp` is the same rule already learned once, one
            // level down: `route.section` must not be allowed to remount this hub.
            guard {
                val samePage = opened == target
                opened = target
                place.sub = sub
                place.anchor = anchor
                if (!samePage) place.reopen()
            }
            return
        }
        // The wide rail's pick is also what a narrowing window opens on the phone.
        when (target) {
            is Target.Sec -> {
                val samePage = extraSelected == null && target.section == wideSection
                if (samePage && sub == place.sub && anchor == null) return
                guard {
                    extraSelected = null
                    opened = target
                    place.sub = sub
                    place.anchor = anchor
                    if (!samePage) place.reopen()
                    if (target.section != section) {
                        // The shell writes `Route.Settings(section)` — no sub-page, no anchor — and that is
                        // not a new landing: the hub already shows it.
                        place.landedFor = routeKey(target.section, null, null)
                        onSectionChange(target.section)
                    }
                }
            }
            is Target.Ext -> {
                if (extraSelected == target.extra) return
                guard {
                    extraSelected = target.extra
                    opened = target
                    toPage()
                    place.reopen()
                }
            }
        }
    }

    fun confirmDiscard() {
        showDiscardDialog = false
        identityDirty = false
        val next = pending
        pending = null
        if (next != null) next() else onBack()
    }

    LaunchedEffect(compact, routeKey) {
        if (swapping) {
            // The Compact↔wide swap: the layout now drawn takes over the page the other one had
            // on display (#89), with its sub-page and pending anchor (#97). The rail selection is
            // the shell's, so a widening window reports the phone's section to it, as a rail
            // click would.
            if (compact) {
                opened = narrowed()
                if (opened == null) toPage()
            } else {
                when (val t = opened) {
                    is Target.Sec -> {
                        extraSelected = null
                        if (t.section != section) {
                            place.landedFor = routeKey(t.section, null, null)
                            onSectionChange(t.section)
                        }
                    }
                    is Target.Ext -> extraSelected = t.extra
                    null -> Unit
                }
            }
            place.drawnCompact = compact
            return@LaunchedEffect
        }
        // A deep link that arrives while this hub is already composed (the stable content key
        // keeps the entry alive across a `Route.Settings` rewrite) still has to land on its
        // place. Only a NEW place lands — a new section, sub-page or anchor: a recreation
        // brings the same one back, and landing on it again would take the user off the page
        // they moved to since.
        if (routeKey == place.landedFor) return@LaunchedEffect
        val before = place.landedFor.substringBefore('|')
        place.landedFor = routeKey
        if (compact) {
            landing()?.let {
                val samePage = opened == it
                opened = it
                place.sub = routeSub
                place.anchor = routeAnchor
                if (!samePage) place.reopen()
            }
        } else if (extraSelected == null) {
            // The wide hub shows the new place: a new section is a page of its own, as a rail
            // click opens one; a sub-page or anchor on the page on display keeps the page's.
            place.sub = routeSub
            place.anchor = routeAnchor
            if (before != (section?.name ?: "")) place.reopen()
        }
    }

    // Keep the registered close handler current for Escape / system back on the outer host.
    DisposableEffect(identityDirty, opened, compact) {
        onRegisterCloseHandler?.invoke { leave() }
        onDispose { onRegisterCloseHandler?.invoke { onBack() } }
    }

    // The slot scope is `@Immutable`, so it must not be a fresh object every recomposition —
    // that would defeat skipping in every section body underneath. The two callbacks are held in
    // `rememberUpdatedState` so the remembered scope always runs the CURRENT `leave()`.
    val leaveNow = rememberUpdatedState<() -> Unit> { leave() }
    val dirtyNow = rememberUpdatedState<(Boolean) -> Unit> { identityDirty = it }
    val openNow = rememberUpdatedState<(SettingsPlace) -> Unit> { open(Target.Sec(it.section), it.sub, it.anchor) }
    val anchorShownNow = rememberUpdatedState<() -> Unit> { place.anchor = null }
    val scope = remember(compact) {
        SettingsSlotScope(
            onClose = { leaveNow.value() },
            onDirtyChange = { dirtyNow.value(it) },
            topBarShown = compact,
            onOpen = { openNow.value(it) },
            onAnchorShown = { anchorShownNow.value() },
        )
    }

    /** What a section's slot is handed: its page, with the sub-page and anchor on it. */
    fun placeOf(target: Target.Sec) = SettingsPlace(target.section, place.sub, place.anchor)

    if (compact) {
        val target = phonePage
        val detailSwipe = rememberIosBackSwipe()
        // The index leaves the hub, so its swipe drags the whole Settings page off (the route's
        // swipe). A section reporting unsaved edits asks first, so neither back may slide a page
        // away underneath the discard dialog.
        SwipeBackHandler(interactive = !identityDirty) { leave() }
        // An open section goes back to the index. Registered second, so it runs first.
        SwipeBackHandler(enabled = target != null, swipe = detailSwipe, interactive = !identityDirty) { leave() }
        SwipeBackPages(
            pushed = target != null,
            swipe = detailSwipe,
            pageBackground = cs.background,
            modifier = Modifier.background(cs.background).testTag("settings_hub"),
            under = {
                SettingsIndex(
                    extras = extras,
                    labs = labs,
                    onBack = { leave() },
                    onOpen = { open(it) },
                )
            },
        ) {
            if (target != null) {
                CompositionLocalProvider(LocalIosBackSwipe provides detailSwipe, LocalRetainedPage provides pageKey) {
                    CompactDetail(
                        title = when (target) {
                            // Task 10 names the Machine page after the active machine.
                            is Target.Sec -> settingsTitle(placeOf(target), machineName = null, labs = labs)
                            is Target.Ext -> target.extra.label
                        },
                        onBack = { leave() },
                    ) {
                        Box(Modifier.fillMaxSize().testTag("settings_hub_detail")) {
                            when (target) {
                                is Target.Sec -> content(placeOf(target), scope)
                                is Target.Ext -> extraContent(target.extra, scope)
                            }
                        }
                    }
                }
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .background(cs.background)
                .testTag("settings_hub"),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                // Back pops a sub-page first, then closes (Escape and system back do the same).
                IconButton(onClick = { leave() }, modifier = Modifier.testTag("settings_hub_back")) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = cs.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    "Settings",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider(color = cs.outlineVariant)

            Row(Modifier.fillMaxSize()) {
                // Left rail
                Column(
                    Modifier
                        .width(200.dp)
                        .fillMaxHeight()
                        .background(cs.surfaceContainerLow)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = Space.sm)
                        .testTag("settings_hub_rail"),
                ) {
                    railSections.forEach { s ->
                        RailRow(
                            label = settingsTitle(SettingsPlace(s), labs = labs),
                            selected = widePage == Target.Sec(s),
                            onClick = { open(Target.Sec(s)) },
                            testTag = "settings_section_${s.name.lowercase()}",
                        )
                    }
                    extras.forEach { e ->
                        RailRow(
                            label = e.label,
                            selected = widePage == Target.Ext(e),
                            onClick = { open(Target.Ext(e)) },
                            testTag = "settings_section_${e.name.lowercase()}",
                        )
                    }
                }
                VerticalDivider(color = cs.outlineVariant)
                // Detail pane — no nested Back on the folded screens (hub owns navigation)
                Box(Modifier.weight(1f).fillMaxHeight().testTag("settings_hub_detail")) {
                    CompositionLocalProvider(LocalRetainedPage provides pageKey) {
                        when (val p = widePage) {
                            is Target.Ext -> extraContent(p.extra, scope)
                            is Target.Sec -> content(placeOf(p), scope)
                        }
                    }
                }
            }
        }
    }

    // A sub-page goes back to its page, instantly: no slide, and not the route's swipe (which would drag the whole
    // Settings page off). Registered last, so it runs before the handlers above.
    SwipeBackHandler(enabled = place.sub != null, swipe = null) { leave() }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = {
                showDiscardDialog = false
                pending = null
            },
            title = { Text("Discard unsaved changes?") },
            text = {
                Text("You have unsaved edits. Leave without saving?")
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDiscard() },
                    modifier = Modifier.testTag("settings_discard_confirm"),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        pending = null
                    },
                    modifier = Modifier.testTag("settings_discard_cancel"),
                ) { Text("Keep editing") }
            },
            modifier = Modifier.testTag("settings_discard_dialog"),
        )
    }
}

/** The Compact index: Android's `SettingsIndexPage`, on shared icons and one row per target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsIndex(
    extras: List<SettingsExtra>,
    labs: Boolean,
    onBack: () -> Unit,
    onOpen: (Target) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", color = cs.onSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_hub_back")) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.background,
        modifier = Modifier.testTag("settings_index"),
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            railSections.forEachIndexed { i, s ->
                if (i > 0) HorizontalDivider(color = cs.outlineVariant)
                IndexRow(
                    icon = s.icon(),
                    label = settingsTitle(SettingsPlace(s), labs = labs),
                    desc = s.desc(),
                    testTag = "settings_row_${s.name.lowercase()}",
                    onClick = { onOpen(Target.Sec(s)) },
                )
            }
            extras.forEach { e ->
                HorizontalDivider(color = cs.outlineVariant)
                IndexRow(
                    icon = e.icon(),
                    label = e.label,
                    desc = e.desc,
                    testTag = "settings_row_${e.name.lowercase()}",
                    onClick = { onOpen(Target.Ext(e)) },
                )
            }
        }
    }
}

/**
 * The pushed detail's chrome. The hub ALWAYS paints it: since cluster E7 every section and extra
 * is a screen that reads `SettingsSlotScope.topBarShown` and draws no bar of its own, so a phone
 * gets exactly one bar per page and the hub's `BackHandler` is the only Back owner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactDetail(
    title: String,
    onBack: () -> Unit,
    body: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        title,
                        color = cs.onSurface,
                        modifier = Modifier.testTag("settings_detail_title"),
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_detail_back"),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = cs.onSurface,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
            )
        },
        containerColor = cs.background,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { body() }
    }
}

/** Tappable index row: icon box + label/desc + trailing chevron (Android's `SettingsNavRow`). */
@Composable
private fun IndexRow(
    icon: ImageVector,
    label: String,
    desc: String,
    testTag: String,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(cs.surfaceContainer)
                .border(1.dp, cs.outline, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(label, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 11.sp)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun RailRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    // Hover + keyboard focus are both first-class: hover lifts surface; focus draws a border
    // (including when the row is already selected). Click keeps LocalIndication for press/ripple.
    val bg = when {
        selected -> cs.surfaceContainerHighest
        focused || hovered -> cs.surfaceContainerHigh
        else -> cs.surfaceContainerLow
    }
    Text(
        label,
        color = if (selected) cs.primary else cs.onSurface,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource = interaction)
            .focusable(interactionSource = interaction)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .background(bg)
            .then(
                if (focused) {
                    Modifier.border(width = 1.dp, color = cs.primary)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Space.lg, vertical = Space.md)
            .testTag(testTag),
    )
}

/** The rail's and the index's rows: every page but [SettingsSection.Machine], which a machine card opens. */
private val railSections = SettingsSection.entries.filter { it != SettingsSection.Machine }

/** One-line "what is behind this row" on the phone's index (#97 spec §2.1). */
internal fun SettingsSection.desc(): String = when (this) {
    SettingsSection.General -> "Theme, text size, live replies, updates"
    SettingsSection.Providers -> "Sign in, extra logins, switch at a limit"
    SettingsSection.Voice -> "Dictation, languages, read aloud"
    SettingsSection.Coding -> "GitHub & GitLab, editor, code intelligence"
    SettingsSection.Memory -> "Nightly tidy-up of what agents learn"
    SettingsSection.PrivacyLabs -> "Usage statistics, experiments"
    SettingsSection.Machine -> "Status, devices, shared links and worktrees"
}

/** Material icons stand in until the kit's glyphs take over (Task 10). */
private fun SettingsSection.icon(): ImageVector = when (this) {
    SettingsSection.General -> Icons.Filled.Palette
    SettingsSection.Providers -> Icons.Filled.SmartToy
    SettingsSection.Voice -> Icons.Filled.Mic
    SettingsSection.Coding -> Icons.Filled.Code
    SettingsSection.Memory -> Icons.Filled.AutoAwesome
    SettingsSection.PrivacyLabs -> Icons.Filled.Lock
    SettingsSection.Machine -> Icons.Filled.Computer
}

private fun SettingsExtra.icon(): ImageVector = when (this) {
    SettingsExtra.Appearance -> Icons.Filled.Palette
    SettingsExtra.AppUpdate -> Icons.Filled.SystemUpdate
    SettingsExtra.Storage -> Icons.Filled.Storage
}
