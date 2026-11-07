package dev.supermux.ui.keys

/** Where a command sits in the shortcuts list. */
enum class CommandGroup(val title: String) {
    General("General"),
    Sessions("Sessions"),
    Workspace("Workspace"),
    Chat("Chat"),
    Files("Files"),
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
    val FindInChat = AppCommand("chat.find", "Find in chat", CommandGroup.Chat)
    val OpenSettings = AppCommand("settings.open", "Settings", CommandGroup.General)
    val ShowShortcuts = AppCommand("shortcuts.show", "Keyboard shortcuts", CommandGroup.General)
    val PasteImage = AppCommand("composer.pasteImage", "Paste an image into the message", CommandGroup.Chat)
    val SplitRight = AppCommand("workspace.splitRight", "Split right (a new column)", CommandGroup.Workspace)
    val SplitDown = AppCommand("workspace.splitDown", "Split down (a new row)", CommandGroup.Workspace)

    val all: List<AppCommand> = listOf(
        NewSession, MoveToNewWindow, NewTerminal, ToggleSidebar, GoToFile, FindInChat,
        OpenSettings, ShowShortcuts, PasteImage, SplitRight, SplitDown,
    )
}
