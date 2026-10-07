package dev.supermux.workspace

/**
 * The view kinds the "+" offers. Order is the order they appear in the popover.
 *
 * [wire] is the view kind the broker stores, and it is NOT unique: Diff is an `editor` view whose
 * state says `mode = "diff"` (spec §7.2), exactly like Editor is an `editor` in `tree` mode. So the
 * test tag hangs off [tag] rather than the wire, or the two would collide on `tab-add-view-editor`.
 *
 * [placement] is where picking this kind puts the view. Chat, Terminal and Display are a tab in the
 * pane you clicked in; Files and Changes open BESIDE it, because both exist to be looked at next to
 * the thing you are changing — tabbed with it they show nothing you could not already see.
 *
 * [singleton] means one per workspace. A second file tree and a second diff of the same working
 * tree are the same view twice, so picking one that is already open reveals it instead (see
 * [openSingletonView]). Chats and terminals are the opposite — their whole point is having several.
 */
enum class NewViewKind(
    val wire: String,
    val label: String,
    val tag: String = wire,
    val placement: NewViewPlacement = NewViewPlacement.HERE,
    val singleton: Boolean = false,
) {
    CHAT("chat", "Chat"),
    TERMINAL("terminal", "Terminal"),
    EDITOR("editor", "Files", placement = NewViewPlacement.SPLIT_RIGHT, singleton = true),
    DIFF("editor", "Changes", tag = "diff", placement = NewViewPlacement.SPLIT_RIGHT, singleton = true),
    DISPLAY("display", "Display"),
}

/**
 * Where a new view lands relative to the pane its "+" was clicked in.
 *
 * The "+" menu uses each kind's own [NewViewKind.placement] — [HERE] (a tab in this pane) for
 * everything except Files and Changes, which take [SPLIT_RIGHT]. There is still no second menu
 * step: users split anything else by dragging a tab to a pane edge.
 */
enum class NewViewPlacement(val label: String) {
    HERE("In this pane"),
    SPLIT_RIGHT("Split right"),
    SPLIT_DOWN("Split down"),
}
