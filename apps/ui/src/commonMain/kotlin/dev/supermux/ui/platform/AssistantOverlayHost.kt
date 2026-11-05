package dev.supermux.ui.platform

import androidx.compose.runtime.Composable

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

    /** The note under the screen switch: how its glasses go on here, and what the system asks. */
    val screenNote: String
        get() = "Double-tap it to put its glasses on: while it wears them, your screen goes with hold-to-talk. " +
            "Android asks each time, and they come off when you lock the phone or after 5 quiet minutes."

    /** More of the overlay's settings under its switch, where the platform has some (the desktop's hold-to-talk key). */
    val moreSettings: (@Composable () -> Unit)?
        get() = null
}
