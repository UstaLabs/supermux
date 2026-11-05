package dev.supermux.ui.platform

/**
 * The assistant overlay's system side (#62): on Android, "Display over other apps" and the mic it
 * needs for hold-to-talk. Null where the platform has no overlay; the Appearance switch is gated by
 * [Caps.assistantOverlay].
 */
interface AssistantOverlayHost {
    /** True when the system lets supermux draw over other apps. */
    fun canDrawOverOtherApps(): Boolean

    /** Opens the system page where the user allows it. */
    fun openPermissionPage()

    /** Asks for the microphone (hold to talk), in context. True when it is granted. */
    suspend fun requestMic(): Boolean
}
