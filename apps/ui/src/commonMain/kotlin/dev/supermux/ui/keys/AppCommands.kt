package dev.supermux.ui.keys

/** Where a command sits in the shortcuts list. */
enum class CommandGroup(val title: String) {
    General("General"),
    Sessions("Sessions"),
    Workspace("Workspace"),
    Chat("Chat"),
    Files("Files"),
    Browser("Browser"),
}

/**
 * Something the keyboard can do: a stable [id] that keys and handlers refer to, and a [title] for menus and the
 * shortcuts list. Spec: docs/superpowers/specs/2026-11-07-central-shortcuts-design.md.
 */
data class AppCommand(val id: String, val title: String, val group: CommandGroup)

/** Every app command. A handler is registered with [RegisterCommand] by whatever owns the state it acts on. */
object AppCommands {
    val NewSession = AppCommand("session.new", "New session", CommandGroup.Sessions)
    val MoveToNewWindow = AppCommand("window.moveToNew", "Move to a new window", CommandGroup.Workspace)
    val NewTerminal = AppCommand("workspace.newTerminal", "New terminal tab", CommandGroup.Workspace)
    val ToggleSidebar = AppCommand("sidebar.toggle", "Show or hide the sidebar", CommandGroup.General)
    val GoToFile = AppCommand("files.goTo", "Go to file", CommandGroup.Files)
    /** Find in the focused thing: a chat registers its find bar, and any other view (a browser page) its own. */
    val Find = AppCommand("find", "Find", CommandGroup.General)
    /** The next and previous match of the open find (#122): what owns the find registers them. */
    val FindNext = AppCommand("find.next", "Find next", CommandGroup.General)
    val FindPrevious = AppCommand("find.previous", "Find previous", CommandGroup.General)
    val OpenSettings = AppCommand("settings.open", "Settings", CommandGroup.General)
    val ShowShortcuts = AppCommand("shortcuts.show", "Keyboard shortcuts", CommandGroup.General)
    val PasteImage = AppCommand("composer.pasteImage", "Paste an image into the message", CommandGroup.Chat)
    val SplitRight = AppCommand("workspace.splitRight", "Split right (a new column)", CommandGroup.Workspace)
    val SplitDown = AppCommand("workspace.splitDown", "Split down (a new row)", CommandGroup.Workspace)
    val CloseTab = AppCommand("tab.close", "Close tab", CommandGroup.Workspace)
    /** Chrome's tab keys (#122): the next or previous tab of the pane last worked in, and its tab 1–8 or last (9). */
    /** The last closed Browser tab back, as Chrome's ⌘⇧T (#122). Off Apple its key stays New terminal in a terminal. */
    val ReopenClosedTab = AppCommand("tab.reopenClosed", "Reopen closed tab", CommandGroup.Workspace)
    val TabNext = AppCommand("tab.next", "Next tab", CommandGroup.Workspace)
    val TabPrevious = AppCommand("tab.previous", "Previous tab", CommandGroup.Workspace)
    private val tabNumbers = (1..9).map { n ->
        AppCommand("tab.goTo$n", if (n == 9) "Go to the last tab" else "Go to tab $n", CommandGroup.Workspace)
    }

    /** Tab [n] (1–8) of the pane, or its last tab for 9. */
    fun tabNumber(n: Int): AppCommand = tabNumbers[n - 1]
    val BrowserFocusAddress = AppCommand("browser.focusAddress", "Focus the address bar", CommandGroup.Browser)
    val BrowserReload = AppCommand("browser.reload", "Reload the page", CommandGroup.Browser)
    val BrowserBack = AppCommand("browser.back", "Back", CommandGroup.Browser)
    val BrowserForward = AppCommand("browser.forward", "Forward", CommandGroup.Browser)
    val BrowserZoomIn = AppCommand("browser.zoomIn", "Zoom in", CommandGroup.Browser)
    val BrowserZoomOut = AppCommand("browser.zoomOut", "Zoom out", CommandGroup.Browser)
    val BrowserZoomReset = AppCommand("browser.zoomReset", "Actual size", CommandGroup.Browser)
    val BrowserOpenExternally = AppCommand("browser.openExternally", "Open in your browser", CommandGroup.Browser)
    /** ⌘T in a Browser tab: a new Browser tab beside it, as in Chrome (#122, Q1). New terminal everywhere else. */
    val BrowserNewTab = AppCommand("browser.newTab", "New Browser tab", CommandGroup.Browser)
    /** A picture of the page into the chat's composer, unsent (#122): only where a chat can take it. */
    val BrowserScreenshot = AppCommand("browser.screenshot", "Send a screenshot to the chat", CommandGroup.Browser)
    val BrowserInspect = AppCommand("browser.inspect", "Inspect the page", CommandGroup.Browser)
    /** Stops a page while it loads; on a loaded page it declines, so Esc stays the page's. */
    val BrowserStop = AppCommand("browser.stop", "Stop loading", CommandGroup.Browser)

    val all: List<AppCommand> = listOf(
        NewSession, MoveToNewWindow, NewTerminal, ToggleSidebar, GoToFile, Find, FindNext, FindPrevious,
        OpenSettings, ShowShortcuts, PasteImage, SplitRight, SplitDown, CloseTab, ReopenClosedTab, TabNext, TabPrevious,
        BrowserFocusAddress, BrowserReload, BrowserBack, BrowserForward, BrowserZoomIn, BrowserZoomOut, BrowserZoomReset, BrowserOpenExternally,
        BrowserInspect, BrowserStop, BrowserNewTab, BrowserScreenshot,
    ) + tabNumbers
}
