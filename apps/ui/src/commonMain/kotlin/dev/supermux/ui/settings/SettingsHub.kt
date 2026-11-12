// The one Settings shell for every app (cluster E1; drawn by the Settings kit since #97).
//
// A wide window gets a rail (back + "Settings", the search field, a card per machine, a row per page) beside a
// detail pane; a phone (Compact) gets an index (the same search, cards and rows, with a line of description each)
// and pushes the page over it, with Back returning to the index. The identity dirty-close guard
// (`onRegisterCloseHandler` → Escape / NavDisplay back) asks before anything leaves a page with unsaved edits.
//
// Pages are NOT rendered here — they arrive through the [content] slot (each host's `*SettingsSections.kt`), so a
// page can be rebuilt without touching this file.
package dev.supermux.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.LocalHardwareKeyboard
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.retain.LocalRetainedPage
import dev.supermux.ui.retain.newRetainedPageOpening
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.KitMachine
import dev.supermux.ui.settings.kit.KitMachineCard
import dev.supermux.ui.settings.kit.KitNavRow
import dev.supermux.ui.settings.kit.KitSearchField
import dev.supermux.ui.settings.kit.KitSheet
import dev.supermux.ui.settings.kit.KitTopBar
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.SettingsMachines
import dev.supermux.ui.settings.kit.THIS_COMPUTER_ID
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.settings.kit.kitFocusRing
import dev.supermux.ui.settings.kit.kitMetrics
import dev.supermux.ui.settings.search.SettingsSearchContext
import dev.supermux.ui.settings.search.SettingsSearchEntry
import dev.supermux.ui.settings.search.SettingsSearchGroup
import dev.supermux.ui.settings.search.SettingsSearchIndex
import dev.supermux.ui.settings.search.searchSettings
import dev.supermux.ui.adaptive.LocalWindowControlsInsets
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.uppercaseInAppLanguage
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.settings_back
import dev.supermux.ui.resources.settings_desc_coding
import dev.supermux.ui.resources.settings_desc_general
import dev.supermux.ui.resources.settings_desc_machine
import dev.supermux.ui.resources.settings_desc_memory
import dev.supermux.ui.resources.settings_desc_privacy
import dev.supermux.ui.resources.settings_desc_privacy_labs
import dev.supermux.ui.resources.settings_desc_providers
import dev.supermux.ui.resources.settings_desc_shortcuts
import dev.supermux.ui.resources.settings_desc_voice
import dev.supermux.ui.resources.settings_discard_confirm
import dev.supermux.ui.resources.settings_discard_description
import dev.supermux.ui.resources.settings_discard_keep
import dev.supermux.ui.resources.settings_discard_title
import dev.supermux.ui.resources.settings_search_none
import dev.supermux.ui.resources.settings_search_page
import dev.supermux.ui.resources.settings_title
import dev.supermux.ui.settings.search.LocalizedSearchEntry
import dev.supermux.ui.settings.search.localizedSearchEntries
import dev.supermux.ui.widgets.LocalIosBackSwipe
import dev.supermux.ui.widgets.SwipeBackHandler
import dev.supermux.ui.widgets.SwipeBackPages
import dev.supermux.ui.widgets.rememberIosBackSwipe
import org.jetbrains.compose.resources.StringResource

/**
 * What a section slot is handed by the hub.
 *
 * @property onClose leave this section — back to the index under Compact, close the whole hub on a
 *   rail layout. Honors the dirty guard, so a folded screen's own Back button can call it safely.
 * @property onDirtyChange the section reports unsaved edits; while true the hub confirms before a
 *   section switch or a close (available to every section).
 * @property topBarShown true when the hub already painted a top bar for this detail — a slot
 *   that could carry its own bar (every settings screen can, because Android's
 *   standalone routes still need one) must only draw it when this is false.
 * @property onOpen move the hub to another place: a page (as a rail click or an index tap would), a sub-page
 *   of a page (a provider, the glossary, a machine screen) or a row on one. A sub-page of the page on display
 *   swaps in place and keeps what the page retains. Honors the dirty guard.
 * @property onAnchorShown the page has scrolled to and flashed the place's anchor: the hub forgets it, so a
 *   recreation or a Compact↔wide swap does not land on it again.
 * @property onOpenUsage the machine page's "Open full usage": the app's Usage screen.
 */
@Immutable
class SettingsSlotScope internal constructor(
    val onClose: () -> Unit,
    val onDirtyChange: (Boolean) -> Unit,
    val topBarShown: Boolean,
    val onOpen: (SettingsPlace) -> Unit = {},
    val onAnchorShown: () -> Unit = {},
    val onOpenUsage: () -> Unit = {},
) {
    /** Move the hub to [section]'s page (Accounts' "Sign in again" opens Providers). */
    fun onOpenSection(section: SettingsSection) = onOpen(SettingsPlace(section))
}

/** Which detail the hub is showing. */
internal sealed interface HubTarget {
    data class Sec(val section: SettingsSection) : HubTarget
}

/** [HubTarget]'s name in saved state and in a page key. */
private fun HubTarget.key(): String = when (this) {
    is HubTarget.Sec -> "s:${section.name}"
}

private fun targetOf(key: String): HubTarget? = when {
    key.startsWith("s:") -> SettingsSection.entries.firstOrNull { it.name == key.removePrefix("s:") }?.let(HubTarget::Sec)
    // The extra pages (Appearance, Check for updates, Storage) are General's rows since #97.
    key.startsWith("e:") -> HubTarget.Sec(SettingsSection.General)
    else -> null
}

/** [section] as a destination: any section; null is `Route.Settings`'s "just Settings" (#97). */
private fun deliberate(section: SettingsSection?): HubTarget? = section?.let(HubTarget::Sec)

/** The page a wide hub shows for "just Settings". */
private val WIDE_DEFAULT = SettingsSection.General

/** A route's place as one key: a new section, sub-page or anchor is a new landing; the same one coming back is not. */
private fun routeKey(section: SettingsSection?, sub: String?, anchor: String?): String =
    "${section?.name ?: ""}|${sub ?: ""}|${anchor ?: ""}"

/**
 * Where the hub is, saved with its nav entry (#89): the phone's pushed page, the sub-page and pending anchor on it (#97), the route it last landed on, the layout it was
 * last drawn in, and the opening of the page on display that the sections keep their forms under
 * ([LocalRetainedPage]).
 */
internal class HubPlace(opened: HubTarget?, landedFor: String, drawnCompact: Boolean) {
    /** The page the user opened: the phone's pushed page (null = the index), or the wide rail's last pick. */
    var opened by mutableStateOf(opened)
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
        // Nulls are saved as "". A section an older build saved restores as nothing (`targetOf`); slot 1 held the
        // wide hub's extra page until #97 and is kept empty so older saves still line up.
        val Saver: Saver<HubPlace, Any> = listSaver(
            save = {
                listOf(
                    it.opened?.key() ?: "", "", it.landedFor, it.drawnCompact, it.opening,
                    it.sub ?: "", it.anchor ?: "",
                )
            },
            restore = { parts ->
                HubPlace(targetOf(parts[0] as String), parts[2] as String, parts[3] as Boolean).apply {
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
 * `LocalWindowWidthClass == Compact` renders the phone's index + push; anything wider renders the
 * rail + detail. [section] is the rail selection (the shell reads and writes it through
 * `Route.Settings.section`); null is "just Settings" — the index on a phone, General on a wide
 * window. Any section, [sub] or [anchor] is a deliberate destination and lands there (#97).
 *
 * @param onBack close the hub entirely (the host pops its route).
 * @param onRegisterCloseHandler the host's Escape / system-back path — the hub keeps a closure here
 *   that honors the dirty guard (and clears a search being typed first), and restores a plain [onBack] on dispose.
 * @param hostKey a key the compact push stack is scoped to: a new one returns to the index. The shell passes none
 *   since #97 — a machine pick keeps the hub on its page and reloads only the page ([machines]).
 * @param machines every machine Settings knows, the one its machine pages are about, and how to switch: the cards
 *   at the top of the rail / index, and [LocalSettingsMachines] for the pages' scope lines. The page is keyed by
 *   the current machine, so a pick reloads its data and forms; the hub stays where it is.
 * @param searchShortcut the shortcut's text in the search field ("⌘F"); when set, that key focuses the search.
 * @param onPlaceChange a wide hub moved to another page or sub-page (a rail click, a sub-page, back from one): the
 *   shell writes it into the route, so a web address bar and its Back follow sub-pages too. Without it only a new
 *   section is reported, through [onSectionChange]. A phone reports nothing (see [SettingsHub]'s `open`).
 * @param searchIndex what the search looks through (tests hand in their own).
 * @param sub a second-level page of [section] to land on (a provider, the glossary, a machine screen).
 * @param anchor a row of the landed page to scroll to and flash once.
 * @param content renders one place — each host's `*SettingsSections.kt` wiring.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsHub(
    section: SettingsSection?,
    onSectionChange: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    sub: String? = null,
    anchor: String? = null,
    onRegisterCloseHandler: ((() -> Unit) -> Unit)? = null,
    hostKey: String? = null,
    machines: SettingsMachines? = null,
    searchShortcut: String? = null,
    onPlaceChange: ((SettingsSection, String?) -> Unit)? = null,
    searchIndex: List<SettingsSearchEntry> = SettingsSearchIndex,
    /** The machine page's "Open full usage". */
    onOpenUsage: () -> Unit = {},
    content: @Composable (SettingsPlace, SettingsSlotScope) -> Unit,
) {
    val c = kitColors()
    val platform = LocalPlatform.current
    val caps = platform.caps
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    // A route's sub-page or anchor only means something with a section.
    val routeSub = sub.takeIf { section != null }
    val routeAnchor = anchor.takeIf { section != null }
    val routeKey = routeKey(section, routeSub, routeAnchor)
    /** Privacy & labs is just "Privacy" where there are no labs. */
    val labs = caps.assistantOverlay
    // The Keyboard shortcuts page is listed only with a hardware keyboard (#96): its row and its search results.
    val keyboard = LocalHardwareKeyboard.current
    val searchCtx = SettingsSearchContext(
        caps,
        phone = compact,
        liveTranscript = platform.mic.liveTranscript != null,
        hardwareKeyboard = keyboard,
        mediaCache = platform.mediaCache != null,
    )
    val railSections = railSections(keyboard)
    // Each page's name in the app's language, looked up here so the rows and search groups below can read it.
    val pageTitles = SettingsSection.entries.associateWith { localizedSettingsTitle(SettingsPlace(it), labs = labs) }
    val searchWords = localizedSearchEntries(searchIndex, pageTitles)

    var identityDirty by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    /** When non-null, discard-confirm runs this instead of closing the hub. */
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Search (spec §2.5): the query outlives a page opened from its results, so a phone's Back returns to them.
    var query by rememberSaveable { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    val groups = remember(query, searchCtx, searchIndex, searchWords) {
        searchSettings(query, searchCtx, searchIndex, translations = searchWords)
    }

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
    fun landing(): HubTarget? = if (compact) deliberate(section) else null

    // Compact push stack (null = index), reset with [hostKey]. Saved
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
    /** The section the wide hub shows. */
    val wideSection = section ?: WIDE_DEFAULT

    // The swap: until the effect below applies it, each layout already draws the page the other
    // one had on display, so the page (and the forms kept for it) stays the same across it.
    val swapping = place.drawnCompact != compact

    /** The page a narrowing window opens on the phone: the wide hub's, unless it showed only the default. */
    fun narrowed(): HubTarget? = opened?.takeIf { it is HubTarget.Sec && it.section == section }
        ?: deliberate(section)

    val phonePage: HubTarget? = if (swapping) narrowed() else opened
    val widePage: HubTarget = opened?.takeIf { swapping }
        ?: HubTarget.Sec(wideSection)
    // What a section keeps on its page (rememberRetainedOnPage) belongs to this opening of it, on this machine.
    /** The machine a page's data and forms belong to: hosting is this computer's, whichever one Settings is about. */
    val pageMachine = if (place.sub == MachineSub.COMPUTER) THIS_COMPUTER_ID else machines?.currentId
    val pageKey = (if (compact) phonePage else widePage)?.let { "${place.opening}:$pageMachine:${it.key()}" }

    /** "This computer" is a card of its own: no local host is paired, so its hosting page is not any machine's. */
    val computerCard = machines?.all?.any { it.id == THIS_COMPUTER_ID } == true
    /**
     * This computer's hosting is not a sub-page of another machine (one picked on the scope line, a stale route), and
     * a device that cannot host has none at all: a `Machine/computer` link there would be an invisible sub-page that
     * eats a Back.
     */
    val dropComputer = !caps.localBroker || (machines != null && machines.current?.local == false && !computerCard)

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
     * A wide hub tells the shell where it is now, so the route (and a web address bar) has its page and sub-page.
     * Not a new landing: the hub already shows it.
     */
    fun report(s: SettingsSection, newSub: String?) {
        if (s == section && newSub == routeSub) return
        val p = onPlaceChange
        if (p != null) {
            place.landedFor = routeKey(s, newSub, null)
            p(s, newSub)
        } else if (s != section) {
            // The shell writes `Route.Settings(section)` — no sub-page, no anchor.
            place.landedFor = routeKey(s, null, null)
            onSectionChange(s)
        }
    }

    /**
     * Shows [target] with [sub] and [anchor] on it. A sub-page or anchor on the page already on display swaps in
     * place: what that page retains belongs to the page, not to its sub-page (#97).
     */
    fun open(target: HubTarget, sub: String? = null, anchor: String? = null) {
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
            is HubTarget.Sec -> {
                val samePage = target.section == wideSection
                // A pending anchor is not "already there": the rail row takes the page back to its top (the interim
                // Git hosting screen behind Coding's `github` anchor never reports it shown).
                if (samePage && sub == place.sub && anchor == place.anchor) return
                guard {
                    opened = target
                    place.sub = sub
                    place.anchor = anchor
                    if (!samePage) place.reopen()
                    report(target.section, sub)
                }
            }
        }
    }

    /**
     * Back from wherever we are: a sub-page → its page; a page → the index (Compact) or close the hub;
     * the index → close the hub. This computer's hosting opened from its own card is not a sub-page of the
     * current machine: it leaves to the index (phone) or General (wide).
     */
    fun leave() {
        val fromComputerCard = computerCard && place.sub == MachineSub.COMPUTER
        if (place.sub != null && !fromComputerCard) {
            // The page's forms belong to the page, so going back to it is not a new opening.
            guard {
                toPage()
                if (!compact) (widePage as? HubTarget.Sec)?.let { report(it.section, null) }
            }
        } else if (compact && opened != null) {
            guard {
                opened = null
                toPage()
                place.reopen()
            }
        } else if (fromComputerCard) {
            open(HubTarget.Sec(WIDE_DEFAULT))
        } else {
            tryClose()
        }
    }

    /**
     * The shell's Escape and system back: a search clears first — on a wide rail whenever there is one (its results
     * stand in for the rows), on a phone while it is being typed (a page opened from the results goes back to them).
     */
    fun closeRequest() {
        if (query.isNotEmpty() && (!compact || searchFocused)) query = "" else leave()
    }

    /** A machine card: that machine's page (switching to it first), or this computer's hosting. */
    fun pickMachine(m: KitMachine) {
        val ms = machines ?: return
        if (m.id == THIS_COMPUTER_ID) {
            open(HubTarget.Sec(SettingsSection.Machine), sub = MachineSub.COMPUTER)
            return
        }
        guard {
            if (m.id != ms.currentId) ms.onPick(m.id)
            open(HubTarget.Sec(SettingsSection.Machine))
        }
    }

    /** A search result: its page at its row. A wide rail goes back to its rows; a phone keeps the results for Back. */
    fun pickResult(e: SettingsSearchEntry) {
        if (compact) {
            open(HubTarget.Sec(e.section), sub = e.sub, anchor = e.anchor.takeIf { e.sub == null })
            return
        }
        // Only once the page is really left: "Keep editing" stays on the results.
        guard {
            open(HubTarget.Sec(e.section), sub = e.sub, anchor = e.anchor.takeIf { e.sub == null })
            query = ""
        }
    }

    fun confirmDiscard() {
        showDiscardDialog = false
        identityDirty = false
        val next = pending
        pending = null
        if (next != null) next() else onBack()
    }

    fun cancelDiscard() {
        showDiscardDialog = false
        pending = null
    }

    LaunchedEffect(compact, routeKey) {
        if (swapping) {
            // The Compact↔wide swap: the layout now drawn takes over the page the other one had
            // on display (#89), with its sub-page and pending anchor (#97). The rail selection is
            // the shell's, so a widening window reports the phone's page to it, as a rail
            // click would.
            if (compact) {
                opened = narrowed()
                if (opened == null) toPage()
            } else {
                when (val t = opened) {
                    is HubTarget.Sec -> report(t.section, place.sub)
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
        } else {
            // The wide hub shows the new place: a new section is a page of its own, as a rail
            // click opens one; a sub-page or anchor on the page on display keeps the page's.
            place.sub = routeSub
            place.anchor = routeAnchor
            if (before != (section?.name ?: "")) place.reopen()
        }
    }

    // Hosting opened from the "This computer" card pairs a local host when it starts, and the card gives way to that
    // machine's: the page follows it there, still on hosting, instead of dropping to the machine Settings was about.
    // Another machine has no "This computer" screen: its page itself shows instead.
    val hadComputerCard = remember { booleanArrayOf(computerCard) }
    LaunchedEffect(computerCard, dropComputer, place.sub) {
        val cardLeft = hadComputerCard[0] && !computerCard
        hadComputerCard[0] = computerCard
        if (place.sub != MachineSub.COMPUTER) return@LaunchedEffect
        val local = machines?.all?.firstOrNull { it.local }
        if (cardLeft && caps.localBroker && machines != null && local != null) {
            if (local.id != machines.currentId) machines.onPick(local.id)
            return@LaunchedEffect
        }
        if (dropComputer) {
            place.sub = null
            if (!compact && widePage == HubTarget.Sec(SettingsSection.Machine)) report(SettingsSection.Machine, null)
        }
    }

    // Keep the registered close handler current for Escape / system back on the outer host.
    val closeNow = rememberUpdatedState<() -> Unit> { closeRequest() }
    DisposableEffect(identityDirty, opened, compact) {
        onRegisterCloseHandler?.invoke { closeNow.value() }
        onDispose { onRegisterCloseHandler?.invoke { onBack() } }
    }

    // The slot scope is `@Immutable`, so it must not be a fresh object every recomposition —
    // that would defeat skipping in every section body underneath. The callbacks are held in
    // `rememberUpdatedState` so the remembered scope always runs the CURRENT `leave()`.
    val leaveNow = rememberUpdatedState<() -> Unit> { leave() }
    val dirtyNow = rememberUpdatedState<(Boolean) -> Unit> { identityDirty = it }
    val openNow = rememberUpdatedState<(SettingsPlace) -> Unit> { open(HubTarget.Sec(it.section), it.sub, it.anchor) }
    val anchorShownNow = rememberUpdatedState<() -> Unit> { place.anchor = null }
    val usageNow = rememberUpdatedState(onOpenUsage)
    val scope = remember(compact) {
        SettingsSlotScope(
            onClose = { leaveNow.value() },
            onDirtyChange = { dirtyNow.value(it) },
            topBarShown = compact,
            onOpen = { openNow.value(it) },
            onAnchorShown = { anchorShownNow.value() },
            onOpenUsage = { usageNow.value() },
        )
    }
    // The pages' scope lines switch machines through the same guard as the cards.
    val guardNow = rememberUpdatedState<(() -> Unit) -> Unit> { guard(it) }
    val pageMachines = remember(machines) {
        machines?.let { ms -> SettingsMachines(ms.all, ms.currentId) { id -> guardNow.value { ms.onPick(id) } } }
    }

    /** What a section's slot is handed: its page, with the sub-page and anchor on it. */
    fun placeOf(target: HubTarget.Sec) = SettingsPlace(
        target.section,
        place.sub.takeUnless { target.section == SettingsSection.Machine && it == MachineSub.COMPUTER && dropComputer },
        place.anchor,
    )

    /** The search group's name: the Machine page is the current machine's. */
    fun groupTitle(s: SettingsSection): String =
        machines?.current?.name?.takeIf { s == SettingsSection.Machine } ?: pageTitles.getValue(s)

    @Composable
    fun Slot(target: HubTarget) {
        when (target) {
            is HubTarget.Sec -> key(pageMachine) { content(placeOf(target), scope) }
        }
    }

    @Composable
    fun Search(modifier: Modifier) {
        KitSearchField(
            query = query,
            onQueryChange = { query = it },
            modifier = modifier.onFocusChanged { searchFocused = it.isFocused },
            shortcut = searchShortcut,
            focusRequester = searchFocus,
        )
    }

    // The search shortcut: the hub holds the keyboard focus (under the shell's Escape box, above the pages), so the
    // key reaches it wherever focus is inside Settings.
    // The root takes the focus only while nothing inside has it (on opening, or after a page let go of it), so it is
    // never a Tab stop of its own beside the rail and the page.
    val rootFocus = remember { FocusRequester() }
    var focusInside by remember { mutableStateOf(false) }
    val shortcutKeys = if (searchShortcut == null) {
        Modifier
    } else {
        Modifier
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.F && (e.isCtrlPressed || e.isMetaPressed)) {
                    runCatching { searchFocus.requestFocus() }
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { focusInside = it.hasFocus && !it.isFocused }
            .focusProperties { canFocus = !focusInside }
            .focusable()
    }
    LaunchedEffect(searchShortcut != null) {
        if (searchShortcut != null && !focusInside) runCatching { rootFocus.requestFocus() }
    }

    CompositionLocalProvider(LocalSettingsMachines provides pageMachines) {
        if (compact) {
            val target = phonePage
            val detailSwipe = rememberIosBackSwipe()
            // The index leaves the hub, so its swipe drags the whole Settings page off (the route's
            // swipe). A section reporting unsaved edits asks first, so neither back may slide a page
            // away underneath the discard dialog.
            SwipeBackHandler(interactive = !identityDirty) { closeRequest() }
            // An open section goes back to the index. Registered second, so it runs first.
            SwipeBackHandler(enabled = target != null, swipe = detailSwipe, interactive = !identityDirty) { leave() }
            SwipeBackPages(
                pushed = target != null,
                swipe = detailSwipe,
                pageBackground = c.pane,
                modifier = Modifier.background(c.pane).then(shortcutKeys).testTag("settings_hub"),
                under = {
                    Column(Modifier.fillMaxSize().background(c.pane).testTag("settings_index")) {
                        KitTopBar(
                            localized(Res.string.settings_title),
                            onBack = { leave() },
                            backTag = "settings_hub_back",
                            titleTag = "settings_index_title",
                        )
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Search(Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                            if (query.isBlank()) {
                                if (machines != null && machines.all.isNotEmpty()) {
                                    Column(
                                        Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        for (m in machines.all) {
                                            KitMachineCard(m, selected = false, onClick = { pickMachine(m) })
                                        }
                                    }
                                }
                                for (s in railSections) {
                                    KitNavRow(
                                        label = pageTitles.getValue(s),
                                        glyph = s.glyph(),
                                        selected = false,
                                        onClick = { open(HubTarget.Sec(s)) },
                                        modifier = Modifier.testTag("settings_row_${s.name.lowercase()}"),
                                        description = localized(s.descRes(labs)),
                                    )
                                }
                            } else {
                                SearchResults(query, groups, ::groupTitle, searchWords) { pickResult(it) }
                            }
                        }
                    }
                },
            ) {
                if (target != null) {
                    CompositionLocalProvider(LocalIosBackSwipe provides detailSwipe, LocalRetainedPage provides pageKey) {
                        // The hub ALWAYS paints the pushed page's bar: every page reads `topBarShown` and draws none,
                        // so a phone gets exactly one bar per page and the hub's back handlers own Back.
                        Column(Modifier.fillMaxSize().background(c.pane)) {
                            KitTopBar(
                                title = when (target) {
                                    is HubTarget.Sec -> localizedSettingsTitle(placeOf(target), machines?.current?.name, labs)
                                },
                                onBack = { leave() },
                            )
                            Box(Modifier.weight(1f).fillMaxWidth().testTag("settings_hub_detail")) { Slot(target) }
                        }
                    }
                }
            }
        } else {
            val onMachinePage = widePage == HubTarget.Sec(SettingsSection.Machine)
            fun cardSelected(m: KitMachine): Boolean = onMachinePage && when {
                m.id == THIS_COMPUTER_ID -> place.sub == MachineSub.COMPUTER
                computerCard && place.sub == MachineSub.COMPUTER -> false
                else -> m.id == machines?.current?.id
            }
            // The OS's own window controls over the top band (macOS traffic lights at the start, Windows' caption
            // buttons at the end): the rail's head starts below the first, the detail pane below the second.
            val controls = LocalWindowControlsInsets.current
            Row(
                Modifier
                    .fillMaxSize()
                    .background(c.chrome)
                    .then(shortcutKeys)
                    .testTag("settings_hub"),
            ) {
                // proto.css: the window body's 6 dp inset, the head (10 dp in, 10 dp gaps), the search field (2/6/8 dp
                // margins) and a list scrolled on its own, 2 dp in from the rail's sides.
                Column(
                    Modifier
                        .padding(top = if (controls.start > 0.dp) controls.band else 0.dp)
                        .padding(start = 6.dp)
                        .width(252.dp)
                        .fillMaxHeight()
                        .testTag("settings_hub_rail"),
                ) {
                    RailHeader(onBack = { leave() })
                    Search(Modifier.padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 8.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 2.dp, end = 2.dp, bottom = 10.dp),
                    ) {
                    if (query.isBlank()) {
                        if (machines != null && machines.all.isNotEmpty()) {
                            Column(
                                Modifier.padding(bottom = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (m in machines.all) {
                                    KitMachineCard(m, selected = cardSelected(m), onClick = { pickMachine(m) })
                                }
                            }
                        }
                        for (s in railSections) {
                            KitNavRow(
                                label = pageTitles.getValue(s),
                                glyph = s.glyph(),
                                selected = widePage == HubTarget.Sec(s),
                                onClick = { open(HubTarget.Sec(s)) },
                                modifier = Modifier.testTag("settings_section_${s.name.lowercase()}"),
                            )
                        }
                    } else {
                        SearchResults(query, groups, ::groupTitle, searchWords) { pickResult(it) }
                    }
                    }
                }
                // The detail pane: the page on the pane tone, inset from the chrome. The rail owns navigation, so
                // the page draws no back of its own.
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = if (controls.end > 0.dp) controls.band else 0.dp)
                        .padding(start = 6.dp, end = 6.dp, bottom = 6.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(c.pane)
                        .testTag("settings_hub_detail"),
                ) {
                    CompositionLocalProvider(LocalRetainedPage provides pageKey) { Slot(widePage) }
                }
            }
        }
    }

    // A sub-page goes back to its page, instantly: no slide, and not the route's swipe (which would drag the whole
    // Settings page off). Registered last, so it runs before the handlers above — and registered again after a
    // Compact↔wide swap, which registers the layout's own handlers anew.
    key(compact) {
        SwipeBackHandler(enabled = place.sub != null, swipe = null) { leave() }
    }

    if (showDiscardDialog) {
        KitSheet(
            localized(Res.string.settings_discard_title),
            onDismiss = { cancelDiscard() },
            description = localized(Res.string.settings_discard_description),
            tag = "settings_discard_dialog",
            buttons = {
                KitGhostButton(localized(Res.string.settings_discard_keep), onClick = { cancelDiscard() }, modifier = Modifier.testTag("settings_discard_cancel"))
                KitGhostButton(
                    localized(Res.string.settings_discard_confirm),
                    onClick = { confirmDiscard() },
                    modifier = Modifier.testTag("settings_discard_confirm"),
                    destructive = true,
                )
            },
        )
    }
}

/** The wide rail's head: a back box and "Settings". */
@Composable
private fun RailHeader(onBack: () -> Unit) {
    val c = kitColors()
    val src = remember { MutableInteractionSource() }
    val hovered by src.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    val back = localized(Res.string.settings_back)
    Row(
        Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(shape)
                // proto.css `.bk:hover`: the selection wash.
                .background(if (hovered) c.wash else Color.Transparent)
                .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onBack)
                .kitFocusRing(src, shape)
                .semantics { contentDescription = back }
                .testTag("settings_hub_back"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(KitGlyphs.Back, contentDescription = null, tint = c.dim, modifier = Modifier.size(16.dp))
        }
        Text(
            localized(Res.string.settings_title),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.text,
            modifier = Modifier.semantics { heading() },
        )
    }
}

/** Search hits grouped by page, the query's words in the accent; "No settings match" when there are none. */
@Composable
private fun SearchResults(
    query: String,
    groups: List<SettingsSearchGroup>,
    titleOf: (SettingsSection) -> String,
    words: Map<SettingsSearchEntry, LocalizedSearchEntry>,
    onPick: (SettingsSearchEntry) -> Unit,
) {
    val c = kitColors()
    val m = kitMetrics()
    if (groups.isEmpty()) {
        Text(
            localized(Res.string.settings_search_none, query),
            fontSize = 12.5.sp,
            color = c.faint,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp).testTag("settings_search_empty"),
        )
        return
    }
    Column(Modifier.padding(horizontal = 4.dp).testTag("settings_search_results")) {
        for (g in groups) {
            Text(
                titleOf(g.section).uppercaseInAppLanguage(),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.63.sp,
                color = c.faint,
                modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
            )
            for (e in g.hits) {
                val src = remember { MutableInteractionSource() }
                val hovered by src.collectIsHoveredAsState()
                val shape = RoundedCornerShape(7.dp)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (hovered) c.hover else Color.Transparent)
                        .clickable(interactionSource = src, indication = null, role = Role.Button) { onPick(e) }
                        .kitFocusRing(src, shape)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("settings_result_${g.section.name.lowercase()}_${e.anchor ?: "page"}"),
                ) {
                    Text(highlight(words[e]?.label ?: e.label, query, c.accent), fontSize = if (m.phone) 15.sp else 13.sp, color = c.text)
                    if (e.anchor == null) Text(localized(Res.string.settings_search_page), fontSize = 11.5.sp, color = c.faint)
                }
            }
        }
    }
}

private fun highlight(text: String, query: String, accent: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val lower = text.lowercase()
    for (w in query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
        var i = lower.indexOf(w)
        while (i >= 0) {
            addStyle(SpanStyle(color = accent), i, i + w.length)
            i = lower.indexOf(w, i + w.length)
        }
    }
}

/**
 * The rail's and the index's rows: every page but [SettingsSection.Machine], which a machine card opens, and Keyboard
 * shortcuts only with a [hardwareKeyboard] to use them on.
 */
private fun railSections(hardwareKeyboard: Boolean): List<SettingsSection> = SettingsSection.entries.filter {
    it != SettingsSection.Machine && (hardwareKeyboard || it != SettingsSection.KeyboardShortcuts)
}

internal fun SettingsSection.glyph(): ImageVector = when (this) {
    SettingsSection.General -> KitGlyphs.Sliders
    SettingsSection.KeyboardShortcuts -> KitGlyphs.Keyboard
    SettingsSection.Providers -> KitGlyphs.Bot
    SettingsSection.Voice -> KitGlyphs.Mic
    SettingsSection.Coding -> KitGlyphs.Code
    SettingsSection.Memory -> KitGlyphs.Memory
    SettingsSection.PrivacyLabs -> KitGlyphs.Shield
    SettingsSection.Machine -> KitGlyphs.Server
}

/** One-line "what is behind this row" on the phone's index (#97 spec §2.1). [labs]: this device has lab features. */
internal fun SettingsSection.descRes(labs: Boolean): StringResource = when (this) {
    SettingsSection.General -> Res.string.settings_desc_general
    SettingsSection.KeyboardShortcuts -> Res.string.settings_desc_shortcuts
    SettingsSection.Providers -> Res.string.settings_desc_providers
    SettingsSection.Voice -> Res.string.settings_desc_voice
    SettingsSection.Coding -> Res.string.settings_desc_coding
    SettingsSection.Memory -> Res.string.settings_desc_memory
    SettingsSection.PrivacyLabs -> if (labs) Res.string.settings_desc_privacy_labs else Res.string.settings_desc_privacy
    SettingsSection.Machine -> Res.string.settings_desc_machine
}
