package dev.supermux.workspace

/**
 * The view kinds a New tab offers (#91). Order is the order of its rows, and of their keys 1–5.
 *
 * [wire] is the view kind the broker stores, and it is NOT unique: Diff is an `editor` view whose
 * state says `mode = "diff"` (spec §7.2), exactly like Editor is an `editor` in `tree` mode. So the
 * test tag hangs off [tag] rather than the wire, or the two would collide on `new-tab-choice-editor`.
 *
 * Every kind opens in the New tab it was picked in — Files and Changes too (Ahmet, 2026-11-07); they
 * used to open beside it as a split.
 *
 * [singleton] means one per workspace. A second file tree and a second diff of the same working
 * tree are the same view twice, so picking one that is already open reveals it instead (see
 * [openSingletonView]). Chats and terminals are the opposite — their whole point is having several.
 */
enum class NewViewKind(
    val wire: String,
    val label: String,
    val tag: String = wire,
    val singleton: Boolean = false,
) {
    CHAT("chat", "Chat"),
    TERMINAL("terminal", "Terminal"),
    EDITOR("editor", "Files", singleton = true),
    DIFF("editor", "Changes", tag = "diff", singleton = true),
    DISPLAY("display", "Display"),
}
