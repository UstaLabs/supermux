package dev.supermux.android.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.ExperimentalSharedTransitionApi
import dev.supermux.android.session.SessionListScreen
import dev.supermux.android.theme.SupermuxTheme
import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo

/**
 * Debug-only harness to eyeball the grouped session list with deterministic data,
 * without a broker or pairing. Launch with:
 *   adb shell am start -n dev.supermux.android/.debug.GroupPreviewActivity
 * Not present in the release variant (lives in the `debug/` source set).
 */
class GroupPreviewActivity : ComponentActivity() {

    @OptIn(ExperimentalSharedTransitionApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sessions = listOf(
            SessionInfo(id = "s_dockie", name = "dockie", workdir = "/home/ahmet/projects/supermux", agent = "claude", role = "personal_assistant"),
            SessionInfo(id = "s_travel", name = "travel-assistant", workdir = "/home/ahmet/projects/supermux", agent = "claude", role = "personal_assistant"),
            SessionInfo(id = "s_release", name = "android-playstore-release", workdir = "/home/ahmet/projects/supermux", agent = "claude"),
            SessionInfo(id = "s_build", name = "build-supermux", workdir = "/home/ahmet/projects/supermux", agent = "codex"),
            SessionInfo(id = "s_desktop", name = "desktop-linux-windows", workdir = "/home/ahmet/projects/supermux", agent = "cursor"),
            SessionInfo(id = "s_editbug", name = "android-editor-rebuild-bug", workdir = "/home/ahmet/projects/supermux", agent = "claude", status = "suspended"),
            SessionInfo(id = "s_promo", name = "supermux-promo-video", workdir = "/home/ahmet/projects/supermux", agent = "claude"),
            SessionInfo(id = "s_web1", name = "supermux-website-total-redesign", workdir = "/home/ahmet/projects/supermux-website", agent = "claude"),
            SessionInfo(id = "s_web2", name = "share-card-hero-redraw", workdir = "/home/ahmet/projects/supermux-website", agent = "codex"),
        )

        fun log(id: String, text: String, mins: Int, dir: String = "outbound") =
            id to LogEntry(id = "l_$id", ts = tsMinutesAgo(mins), direction = dir, text = text)

        val last = mapOf(
            log("s_dockie", "Box status — slammed but healthy, not dying:…", 3),
            log("s_travel", "Re-anchored the whole plan to your payday — every…", 8),
            log("s_release", "Yeah yeah spin up a demo broker, do the legal one…", 12),
            log("s_build", "TestFlight build 80 (v1.3) is live in Smoke Test. Chec…", 20),
            log("s_desktop", "Both parity gaps are done and now live in the app on…", 35),
            log("s_editbug", "You're right on both counts, and thank you for stayin…", 60, "inbound"),
            log("s_promo", "And here's \"One Night with Supermux\" with the real l…", 90),
            log("s_web1", "Share card updated and live. When someone pastes…", 150),
            log("s_web2", "Draft of the new hero section is ready for your review.", 240, "inbound"),
        )

        val agentState = mapOf(
            "s_release" to AgentStatus(phase = "running", state = "working", working = true),
            "s_build" to AgentStatus(phase = "idle", state = "idle"),
        )

        setContent {
            SupermuxTheme {
                SessionListScreen(
                    sessions = sessions,
                    home = "/home/ahmet",
                    activeId = "s_release",
                    onOpen = {},
                    lastBySession = last,
                    agentState = agentState,
                )
            }
        }
    }

    private fun tsMinutesAgo(mins: Int): String =
        java.time.Instant.now().minusSeconds(mins * 60L).toString()
}
