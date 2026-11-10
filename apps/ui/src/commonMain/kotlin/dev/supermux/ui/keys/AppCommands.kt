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
    val OpenSettings = AppCommand("settings.open", "Settings", CommandGroup.General)
    val ShowShortcuts = AppCommand("shortcuts.show", "Keyboard shortcuts", CommandGroup.General)
    val PasteImage = AppCommand("composer.pasteImage", "Paste an image into the message", CommandGroup.Chat)
    val SplitRight = AppCommand("workspace.splitRight", "Split right (a new column)", CommandGroup.Workspace)
    val SplitDown = AppCommand("workspace.splitDown", "Split down (a new row)", CommandGroup.Workspace)
    val CloseTab = AppCommand("tab.close", "Close tab", CommandGroup.Workspace)
    val BrowserFocusAddress = AppCommand("browser.focusAddress", "Focus the address bar", CommandGroup.Browser)
    val BrowserReload = AppCommand("browser.reload", "Reload the page", CommandGroup.Browser)
    val BrowserBack = AppCommand("browser.back", "Back", CommandGroup.Browser)
    val BrowserForward = AppCommand("browser.forward", "Forward", CommandGroup.Browser)
    val BrowserZoomIn = AppCommand("browser.zoomIn", "Zoom in", CommandGroup.Browser)
    val BrowserZoomOut = AppCommand("browser.zoomOut", "Zoom out", CommandGroup.Browser)
    val BrowserZoomReset = AppCommand("browser.zoomReset", "Actual size", CommandGroup.Browser)
    val BrowserOpenExternally = AppCommand("browser.openExternally", "Open in your browser", CommandGroup.Browser)

    val all: List<AppCommand> = listOf(
        NewSession, MoveToNewWindow, NewTerminal, ToggleSidebar, GoToFile, Find,
        OpenSettings, ShowShortcuts, PasteImage, SplitRight, SplitDown, CloseTab,
        BrowserFocusAddress, BrowserReload, BrowserBack, BrowserForward, BrowserZoomIn, BrowserZoomOut, BrowserZoomReset, BrowserOpenExternally,
    )
}
