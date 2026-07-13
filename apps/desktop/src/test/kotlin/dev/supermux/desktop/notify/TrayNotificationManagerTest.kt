package dev.supermux.desktop.notify

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * M5-3 Task 2: [TrayNotificationManager]. [TrayState]'s `notificationChannel` is a RENDEZVOUS
 * channel (capacity 0, confirmed via javap against the real ui-desktop-1.11.1.jar) and
 * `sendNotification` calls non-suspending `trySend` — a `notify()` with NO active collector is
 * silently dropped (exactly how the real `Tray` composable's always-collecting internal loop
 * behaves). This test therefore starts the collector FIRST and pumps it to the
 * suspended-on-receive point with `runCurrent()` BEFORE calling `notify()` — reversing that order
 * would deadlock (or silently lose the event and hang on `.first()`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrayNotificationManagerTest {
    @Test fun notify_posts_an_info_notification_onto_the_tray_states_flow() = runTest {
        val trayState = TrayState()
        val manager = TrayNotificationManager(trayState)
        var received: Notification? = null
        val job = launch { received = trayState.notificationFlow.first() }
        runCurrent() // let the collector suspend on the rendezvous receive first

        manager.notify("s1", "my-session", "hello from the agent")
        job.join()

        assertEquals("my-session", received?.title)
        assertEquals("hello from the agent", received?.message)
        assertEquals(Notification.Type.Info, received?.type)
    }
}
