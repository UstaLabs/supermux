package dev.supermux.webview.engine.web

/** An engine that may show a page of another site: what [UrlPageSlots] needs of it. */
internal interface UrlSlotUser {
    /** Whether its view is on screen, as `setVisible` last said. */
    val visible: Boolean

    /**
     * Whether a view's surface is composed with it, even off screen. A page without one is parked for a later view of it
     * (a list item scrolled away), or not shown yet.
     */
    val hasSurface: Boolean

    /** It got the slot it waited for: its page may load now. */
    fun slotGranted()

    /** Its slot goes to a page on screen: its own page must go, since it is off screen. */
    fun yieldSlot()
}

/**
 * The web's budget for pages of another site ([WebEngineDecisions.MAX_LIVE_URL]), whatever the runtime's budget. Each
 * runs in a renderer process of its own, unlike srcdoc demos, so it is counted apart. Main thread only.
 * - An engine that starts such a page asks for a slot ([take]). When none is free, a holder whose view is off screen
 *   gives its slot to a visible engine (one parked for a later view before one whose view is only hidden): its page
 *   goes, freeing the process, its view is covered again (`PaintLost`), and the page loads afresh once its view shows
 *   again and it gets a slot back. A view on screen never loses its slot, so it never goes blank for another's sake.
 * - With no such holder, the page waits behind the runtime's cover, and gets the next slot that frees: engines on
 *   screen first, in the order they asked.
 * - A slot frees when its engine loads other content, resets, crashes or is released ([give]), or gives it to a view on
 *   screen when its own goes off ([visibilityChanged]).
 */
internal object UrlPageSlots {
    private val holders = ArrayList<UrlSlotUser>()
    private val waiting = ArrayList<UrlSlotUser>()

    /** Engines showing a page of another site now. For tests. */
    val held: Int get() = holders.size

    /**
     * A slot for [user], at once if one is free, or if [user] is on screen and a holder off screen gives its own: one
     * parked for a later view of it first, then a hidden one, whose view may come back any moment. Never a holder on
     * screen, and never for a page off screen (a spare, a view not shown yet). Else it waits, keeping its place in line
     * when it asks again, and false.
     */
    fun take(user: UrlSlotUser): Boolean {
        if (user in holders) return true
        if (holders.size < WebEngineDecisions.MAX_LIVE_URL) {
            waiting.remove(user)
            holders += user
            return true
        }
        val offScreen = if (user.visible) holders.firstOrNull { !it.visible && !it.hasSurface } ?: holders.firstOrNull { !it.visible } else null
        if (offScreen != null) {
            waiting.remove(user)
            holders -= offScreen
            holders += user
            offScreen.yieldSlot()
            return true
        }
        if (user !in waiting) waiting += user
        return false
    }

    /** [user] shows no page of another site any more, nor waits for one. Its slot goes to the next in line. */
    fun give(user: UrlSlotUser) {
        waiting.remove(user)
        if (!holders.remove(user)) return
        val next = waiting.firstOrNull { it.visible } ?: waiting.firstOrNull() ?: return
        waiting -= next
        holders += next
        next.slotGranted()
    }

    /** [user]'s view went on or off screen. Off screen, it gives its slot to a page on screen that waits for one. */
    fun visibilityChanged(user: UrlSlotUser) {
        if (user.visible || user !in holders) return
        val next = waiting.firstOrNull { it.visible } ?: return
        waiting -= next
        holders -= user
        holders += next
        user.yieldSlot()
        next.slotGranted()
    }
}
