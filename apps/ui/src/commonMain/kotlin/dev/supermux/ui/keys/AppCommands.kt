package dev.supermux.ui.keys

import androidx.compose.runtime.Composable
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.StringKey
import dev.supermux.ui.i18n.localized

/** Where a command sits in the shortcuts list. */
enum class CommandGroup(val title: String) {
    General("General"),  // i18n-ok: English title; UI shows titleRes
    Sessions("Sessions"),  // i18n-ok: English title; UI shows titleRes
    Workspace("Workspace"),  // i18n-ok: English title; UI shows titleRes
    Chat("Chat"),  // i18n-ok: English title; UI shows titleRes
    Files("Files"),  // i18n-ok: English title; UI shows titleRes
    Browser("Browser"),  // i18n-ok: English title; UI shows titleRes
}

/**
 * Something the keyboard can do: a stable [id] that keys and handlers refer to, and a [title] for menus and the
 * shortcuts list. Spec: docs/superpowers/specs/2026-11-07-central-shortcuts-design.md.
 */
data class AppCommand(val id: String, val title: String, val group: CommandGroup)

/** Every app command. A handler is registered with [RegisterCommand] by whatever owns the state it acts on. */
object AppCommands {
    val NewSession = AppCommand("session.new", "New session", CommandGroup.Sessions)  // i18n-ok: English title, search matches it; UI shows titleRes
    val MoveToNewWindow = AppCommand("window.moveToNew", "Move to a new window", CommandGroup.Workspace)  // i18n-ok: English title, search matches it; UI shows titleRes
    val NewTerminal = AppCommand("workspace.newTerminal", "New terminal tab", CommandGroup.Workspace)  // i18n-ok: English title, search matches it; UI shows titleRes
    val ToggleSidebar = AppCommand("sidebar.toggle", "Show or hide the sidebar", CommandGroup.General)  // i18n-ok: English title, search matches it; UI shows titleRes
    val GoToFile = AppCommand("files.goTo", "Go to file", CommandGroup.Files)  // i18n-ok: English title, search matches it; UI shows titleRes
    /** Find in the focused thing: a chat registers its find bar, and any other view (a browser page) its own. */
    val Find = AppCommand("find", "Find", CommandGroup.General)  // i18n-ok: English title, search matches it; UI shows titleRes
    /** The next and previous match of the open find (#122): what owns the find registers them. */
    val FindNext = AppCommand("find.next", "Find next", CommandGroup.General)  // i18n-ok: English title, search matches it; UI shows titleRes
    val FindPrevious = AppCommand("find.previous", "Find previous", CommandGroup.General)  // i18n-ok: English title, search matches it; UI shows titleRes
    val OpenSettings = AppCommand("settings.open", "Settings", CommandGroup.General)  // i18n-ok: English title, search matches it; UI shows titleRes
    val ShowShortcuts = AppCommand("shortcuts.show", "Keyboard shortcuts", CommandGroup.General)  // i18n-ok: English title, search matches it; UI shows titleRes
    val PasteImage = AppCommand("composer.pasteImage", "Paste an image into the message", CommandGroup.Chat)  // i18n-ok: English title, search matches it; UI shows titleRes
    val SplitRight = AppCommand("workspace.splitRight", "Split right (a new column)", CommandGroup.Workspace)  // i18n-ok: English title, search matches it; UI shows titleRes
    val SplitDown = AppCommand("workspace.splitDown", "Split down (a new row)", CommandGroup.Workspace)  // i18n-ok: English title, search matches it; UI shows titleRes
    val CloseTab = AppCommand("tab.close", "Close tab", CommandGroup.Workspace)  // i18n-ok: English title, search matches it; UI shows titleRes
    /** Chrome's tab keys (#122): the next or previous tab of the pane last worked in, and its tab 1–8 or last (9). */
    /** The last closed Browser tab back, as Chrome's ⌘⇧T (#122). Off Apple its key stays New terminal in a terminal. */
    val ReopenClosedTab = AppCommand("tab.reopenClosed", "Reopen closed tab", CommandGroup.Workspace)  // i18n-ok: English title, search matches it; UI shows titleRes
    val TabNext = AppCommand("tab.next", "Next tab", CommandGroup.Workspace)  // i18n-ok: English title, search matches it; UI shows titleRes
    val TabPrevious = AppCommand("tab.previous", "Previous tab", CommandGroup.Workspace)  // i18n-ok: English title, search matches it; UI shows titleRes
    private val tabNumbers = (1..9).map { n ->
        AppCommand("tab.goTo$n", if (n == 9) "Go to the last tab" else "Go to tab $n", CommandGroup.Workspace)  // i18n-ok: English title, search matches it; UI shows localizedTitle
    }

    /** Tab [n] (1–8) of the pane, or its last tab for 9. */
    fun tabNumber(n: Int): AppCommand = tabNumbers[n - 1]
    val BrowserFocusAddress = AppCommand("browser.focusAddress", "Focus the address bar", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    val BrowserReload = AppCommand("browser.reload", "Reload the page", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    val BrowserBack = AppCommand("browser.back", "Back", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    val BrowserForward = AppCommand("browser.forward", "Forward", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    val BrowserZoomIn = AppCommand("browser.zoomIn", "Zoom in", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    val BrowserZoomOut = AppCommand("browser.zoomOut", "Zoom out", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    val BrowserZoomReset = AppCommand("browser.zoomReset", "Actual size", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    val BrowserOpenExternally = AppCommand("browser.openExternally", "Open in your browser", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    /** ⌘T in a Browser tab: a new Browser tab beside it, as in Chrome (#122, Q1). New terminal everywhere else. */
    val BrowserNewTab = AppCommand("browser.newTab", "New Browser tab", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    /** A picture of the page into the chat's composer, unsent (#122): only where a chat can take it. */
    val BrowserScreenshot = AppCommand("browser.screenshot", "Send a screenshot to the chat", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    /** The page at a phone's or tablet's size (#122 phase 2), DevTools' device toolbar. */
    val BrowserDeviceMode = AppCommand("browser.deviceMode", "Device mode", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    /** Pick an element on the page and comment on it for an agent (#122 phase 3), DevTools' inspect-element chord. */
    val BrowserPick = AppCommand("browser.pick", "Comment on the page", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    val BrowserInspect = AppCommand("browser.inspect", "Inspect the page", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes
    /** Stops a page while it loads; on a loaded page it declines, so Esc stays the page's. */
    val BrowserStop = AppCommand("browser.stop", "Stop loading", CommandGroup.Browser)  // i18n-ok: English title, search matches it; UI shows titleRes

    val all: List<AppCommand> = listOf(
        NewSession, MoveToNewWindow, NewTerminal, ToggleSidebar, GoToFile, Find, FindNext, FindPrevious,
        OpenSettings, ShowShortcuts, PasteImage, SplitRight, SplitDown, CloseTab, ReopenClosedTab, TabNext, TabPrevious,
        BrowserFocusAddress, BrowserReload, BrowserBack, BrowserForward, BrowserZoomIn, BrowserZoomOut, BrowserZoomReset, BrowserOpenExternally,
        BrowserInspect, BrowserStop, BrowserNewTab, BrowserScreenshot, BrowserDeviceMode, BrowserPick,
    ) + tabNumbers
}

/** [group]'s heading in the app's language; [CommandGroup.title] stays the English one. */
val CommandGroup.titleRes: StringKey
    get() = when (this) {
        CommandGroup.General -> Str.keys_group_general
        CommandGroup.Sessions -> Str.keys_group_sessions
        CommandGroup.Workspace -> Str.keys_group_workspace
        CommandGroup.Chat -> Str.keys_group_chat
        CommandGroup.Files -> Str.keys_group_files
        CommandGroup.Browser -> Str.keys_group_browser
    }

@Composable
fun localizedTitle(group: CommandGroup): String = localized(group.titleRes)

/**
 * [cmd]'s title in the app's language, for menus and the shortcuts list. Its [AppCommand.title] stays English: Settings
 * search matches it. Null for the numbered tab commands, which take their number ([localizedTitle] words them).
 */
fun commandTitleRes(cmd: AppCommand): StringKey? = when (cmd.id) {
    AppCommands.NewSession.id -> Str.keys_cmd_new_session
    AppCommands.MoveToNewWindow.id -> Str.keys_cmd_move_to_new_window
    AppCommands.NewTerminal.id -> Str.keys_cmd_new_terminal
    AppCommands.ToggleSidebar.id -> Str.keys_cmd_toggle_sidebar
    AppCommands.GoToFile.id -> Str.keys_cmd_go_to_file
    AppCommands.Find.id -> Str.keys_cmd_find
    AppCommands.FindNext.id -> Str.keys_cmd_find_next
    AppCommands.FindPrevious.id -> Str.keys_cmd_find_previous
    AppCommands.OpenSettings.id -> Str.keys_cmd_open_settings
    AppCommands.ShowShortcuts.id -> Str.keys_cmd_show_shortcuts
    AppCommands.PasteImage.id -> Str.keys_cmd_paste_image
    AppCommands.SplitRight.id -> Str.keys_cmd_split_right
    AppCommands.SplitDown.id -> Str.keys_cmd_split_down
    AppCommands.CloseTab.id -> Str.keys_cmd_close_tab
    AppCommands.ReopenClosedTab.id -> Str.keys_cmd_reopen_closed_tab
    AppCommands.TabNext.id -> Str.keys_cmd_tab_next
    AppCommands.TabPrevious.id -> Str.keys_cmd_tab_previous
    AppCommands.BrowserFocusAddress.id -> Str.keys_cmd_browser_focus_address
    AppCommands.BrowserReload.id -> Str.keys_cmd_browser_reload
    AppCommands.BrowserBack.id -> Str.keys_cmd_browser_back
    AppCommands.BrowserForward.id -> Str.keys_cmd_browser_forward
    AppCommands.BrowserZoomIn.id -> Str.keys_cmd_browser_zoom_in
    AppCommands.BrowserZoomOut.id -> Str.keys_cmd_browser_zoom_out
    AppCommands.BrowserZoomReset.id -> Str.keys_cmd_browser_zoom_reset
    AppCommands.BrowserOpenExternally.id -> Str.keys_cmd_browser_open_externally
    AppCommands.BrowserNewTab.id -> Str.keys_cmd_browser_new_tab
    AppCommands.BrowserScreenshot.id -> Str.keys_cmd_browser_screenshot
    AppCommands.BrowserDeviceMode.id -> Str.keys_cmd_browser_device_mode
    AppCommands.BrowserPick.id -> Str.keys_cmd_browser_pick
    AppCommands.BrowserInspect.id -> Str.keys_cmd_browser_inspect
    AppCommands.BrowserStop.id -> Str.keys_cmd_browser_stop
    else -> null
}

/** [cmd]'s title in the app's language: what a menu or the shortcuts list shows. */
@Composable
fun localizedTitle(cmd: AppCommand): String {
    commandTitleRes(cmd)?.let { return localized(it) }
    val n = cmd.id.removePrefix("tab.goTo").toIntOrNull()
    return when {
        n == null || !cmd.id.startsWith("tab.goTo") -> cmd.title
        n == 9 -> localized(Str.keys_cmd_tab_last)
        else -> localized(Str.keys_cmd_tab_number, n)
    }
}
