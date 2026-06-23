package dev.supermux.android.push

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * POST_NOTIFICATIONS runtime permission (API 33+).
 *
 * Call [request] once early in an Activity's `onCreate` (before `setContent`). On API < 33
 * the permission is install-time and this is a no-op. This is the minimal flow the app has
 * today; a richer pre-permission rationale UI can be layered on later.
 */
object PushPermission {
    /** Register + fire a one-shot POST_NOTIFICATIONS request if needed. No-op below API 33. */
    fun request(activity: ComponentActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (SupermuxMessagingService.hasPostPermission(activity)) return
        val launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* granted-or-not: the notification path checks again at post time */ }
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
