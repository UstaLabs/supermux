// #123: `:shared` cannot see the catalogs, so what it computes for the screen comes back as data (a chat time,
// how long ago, a tab title) and is worded here, once, in the app's language. Single-caller words sit beside their
// caller instead: Agent name errors (AgentDialogs.kt), the Usage reset date (UsageScreen.kt), git op results
// (SessionHeaderMenus.kt), the working line and its duration (ChatPanel.kt).
package dev.supermux.ui.i18n

import dev.supermux.chat.ChatTime
import dev.supermux.host.HOST_FALLBACK
import dev.supermux.proto.ViewDto
import dev.supermux.util.TimeAgo
import dev.supermux.workspace.ViewTitle
import dev.supermux.workspace.viewTitleOf

/** A chat row's time: `HH:mm` today, "Yesterday HH:mm", else `yyyy-MM-dd HH:mm`. */
fun chatTimeText(t: ChatTime): String = when {
    t.yesterday -> localized(Str.words_time_yesterday, t.clock)
    t.date != null -> "${t.date} ${t.clock}"
    else -> t.clock
}

/** "just now", "5 min ago", "2 h ago", "yesterday", "3 days ago". */
fun timeAgoText(ago: TimeAgo): String = when (ago) {
    TimeAgo.JustNow -> localized(Str.new_tab_closed_just_now)
    is TimeAgo.Minutes -> localizedPlural(Plurals.new_tab_closed_minutes, ago.n, ago.n)
    is TimeAgo.Hours -> localizedPlural(Plurals.new_tab_closed_hours, ago.n, ago.n)
    is TimeAgo.Days -> localizedPlural(Plurals.new_tab_closed_days, ago.n, ago.n)
}

/** A view's tab / accessibility label ([viewTitleOf]): its own name, or the kind's word. */
fun viewTitle(view: ViewDto, sessionName: (String) -> String? = { null }): String =
    when (val t = viewTitleOf(view, sessionName)) {
        is ViewTitle.Name -> t.text
        is ViewTitle.Port -> localized(Str.words_view_port, t.port)
        is ViewTitle.Word -> localized(
            when (t) {
                ViewTitle.Word.NEW_CHAT -> Str.words_view_new_chat
                ViewTitle.Word.CHAT -> Str.shell_kind_chat
                ViewTitle.Word.TERMINAL -> Str.shell_kind_terminal
                ViewTitle.Word.FILE -> Str.notify_file
                ViewTitle.Word.CHANGES -> Str.shell_kind_changes
                ViewTitle.Word.FILES -> Str.shell_kind_files
                ViewTitle.Word.DISPLAY -> Str.shell_kind_display
                ViewTitle.Word.PAGE -> Str.words_view_page
                ViewTitle.Word.NEW_TAB -> Str.shell_new_tab
            },
        )
    }

/** A host's label as shown: the unnamed host's stored fallback ([HOST_FALLBACK]) in the app's language, any real name as is. */
fun String.localizedHost(): String = if (this == HOST_FALLBACK) localized(Str.words_host_unnamed) else this

