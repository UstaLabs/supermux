package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * uiarc's own tool-call demo ("Is anything blocking the 2.8 release?") replayed through our
 * component at its timing, captured frame by frame at 60fps for a side-by-side with the page.
 * A no-op unless SHOTS_DIR is set:
 *   SHOTS_DIR=/tmp/toolcall ./gradlew :ui:jvmTest --tests '*ToolCallShots*'
 *   ffmpeg -framerate 60 -i /tmp/toolcall/light/f%04d.png -pix_fmt yuv420p toolcall.mp4
 */
@OptIn(ExperimentalTestApi::class)
class ToolCallShots {
    private val search = ToolCallData(
        id = "1", tool = ToolMarks.github, title = "Searched issues in arc-ui/web", status = ToolCallStatus.Running,
        titles = mapOf(ToolCallStatus.Running to "Searching issues in arc-ui/web"),
        input = listOf("repo" to "arc-ui/web", "query" to "is:open label:release-blocker milestone:2.8", "sort" to "updated"),
        output = ToolCallOutput.Items(
            listOf(
                ToolCallOutput.Item("Checkout button stays disabled after a coupon is removed", "#1284", "checkout"),
                ToolCallOutput.Item("Webhook retry posts a second charge after a 504", "#1291", "billing"),
                ToolCallOutput.Item("Session is lost after SSO sign in on iPad", "#1302", "auth"),
                ToolCallOutput.Item("Release notes page returns 404 on docs preview", "#1306", "docs"),
            ),
        ),
        durationMs = 1200,
    )
    private val project = ToolCallData(
        id = "2", tool = ToolMarks.linear, title = "Read the Web 2.8 release project", status = ToolCallStatus.Running,
        titles = mapOf(ToolCallStatus.Running to "Reading the Web 2.8 release project"),
        input = listOf("project" to "Web 2.8", "include" to "blockers"),
        output = ToolCallOutput.Table(
            listOf("issue", "state", "owner"),
            listOf(listOf("WEB-412", "blocked", "mira"), listOf("WEB-418", "blocked", "jon"), listOf("WEB-420", "in review", "ana")),
        ),
        summary = "2 blocked",
        durationMs = 760,
    )
    private val migrations = ToolCallData(
        id = "3", tool = ToolMarks.postgres, title = "Queried pending migrations", status = ToolCallStatus.Running,
        titles = mapOf(ToolCallStatus.Running to "Querying pending migrations", ToolCallStatus.Error to "Could not query pending migrations"),
        input = listOf("database" to "app_production", "sql" to "select version, name, applied_at from schema_migrations where applied_at is null order by version"),
        durationMs = 5000,
    )

    @Test fun record() {
        val root = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        for (theme in listOf(AppearanceMode.LIGHT, AppearanceMode.DARK)) runDesktopComposeUiTest(1360, 1100) {
            val dir = File(root, theme.name.lowercase()).apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            var calls by mutableStateOf(listOf(search))
            var active by mutableStateOf(true)
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(LocalPlatform provides FakePlatform(), LocalDensity provides Density(2f)) {
                    SupermuxTheme(appearance = theme) {
                        val bg = if (theme == AppearanceMode.LIGHT) ToolCallColors.Light.surfaceMuted else ToolCallColors.Dark.surfaceMuted
                        Box(Modifier.size(680.dp, 550.dp).background(bg).padding(24.dp)) {
                            Column(Modifier.width(632.dp)) {
                                ToolCallGroup(
                                    calls = calls, active = active,
                                    onRetry = { c -> calls = calls.map { if (it.id == c.id) it.copy(status = ToolCallStatus.Running) else it } },
                                )
                            }
                        }
                    }
                }
            }
            var frame = 0
            fun run(ms: Int) = repeat(ms / 16) {
                mainClock.advanceTimeBy(16)
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "f%04d.png".format(frame++)))
            }
            fun set(id: String, s: ToolCallStatus, message: String? = null) {
                calls = calls.map { if (it.id == id) it.copy(status = s, message = message) else it }
            }
            run(800)
            set("1", ToolCallStatus.Success); calls = calls + project; run(1200)
            set("2", ToolCallStatus.Success); calls = calls + migrations; run(1300)
            set("3", ToolCallStatus.Error, "Connection to app_production timed out after 5s"); active = false; run(1400)
            onNodeWithText("Searched issues in arc-ui/web").performClick(); run(1000)
            onNodeWithText("Searched issues in arc-ui/web").performClick(); run(500)
            onNodeWithText("Could not query pending migrations").performClick(); run(1100)
            onNodeWithText("Retry").performClick(); run(900)
            set("3", ToolCallStatus.Success); run(1600)
        }
    }
}
