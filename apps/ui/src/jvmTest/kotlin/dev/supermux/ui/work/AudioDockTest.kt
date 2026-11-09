package dev.supermux.ui.work

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.chat.ChatJump
import dev.supermux.ui.chat.ChatSearchHost
import dev.supermux.ui.chat.LocalChatSearch
import dev.supermux.ui.chat.setPlatformContent
import dev.supermux.ui.chat.testHostStore
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.session.SessionListFooter
import dev.supermux.ui.shell.ShellUiState
import dev.supermux.ui.shell.SupermuxApp
import dev.supermux.ui.shell.testFleet
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.work.AudioItem
import dev.supermux.work.AudioOutput
import dev.supermux.work.AudioOutputs
import dev.supermux.work.AudioPlayer
import dev.supermux.work.DownloadOrigin
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The app's audio player on screen (#118, design A): while something plays, the sidebar's bottom bar — the same bar on
 * a phone's Home and on a desktop — grows a now-playing part above its host row (❚❚/▶, the title, its chat, the speed,
 * ✕, a scrubber you drag); the collapsed sidebar's rail shows the player as a ring. Nothing while idle.
 */
@OptIn(ExperimentalTestApi::class)
class AudioDockTest {
    /** What the player told its outputs, and their line back. */
    internal class FakeOutputs : AudioOutputs {
        val made = mutableListOf<FakeOutput>()

        override fun create(events: AudioOutput.Events): AudioOutput = FakeOutput(events).also { made += it }
    }

    internal class FakeOutput(val events: AudioOutput.Events) : AudioOutput {
        val calls = mutableListOf<String>()

        override fun open(uri: String) { calls += "open $uri" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(ms: Long) { calls += "seek $ms" }
        override fun setRate(rate: Float) { calls += "rate $rate" }
        override fun release() { calls += "release" }
    }

    private val note = AudioItem(
        title = "voice-note.m4a",
        source = "/cache/voice-note.m4a",
        origin = DownloadOrigin("s1", "m7"),
        chat = "demo-day-recap",
        key = "att:h/1",
        durationMs = 130_000,
    )

    @Composable
    private fun Footer() = SessionListFooter(appearance = AppearanceMode.LIGHT, onToggleTheme = {}, onUsage = {}, onDevices = {}, onSettings = {})

    private fun ComposeUiTest.footer(player: AudioPlayer, pointer: Boolean = true, search: ChatSearchHost? = null, platform: FakePlatform = FakePlatform()) =
        setPlatformContent(
            platform,
            pointer = pointer,
            widthClass = if (pointer) WindowWidthClass.Expanded else WindowWidthClass.Compact,
            inputMode = if (pointer) InputMode.Pointer else InputMode.Touch,
        ) {
            SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                CompositionLocalProvider(LocalAudioPlayer provides player, LocalChatSearch provides search) { Footer() }
            }
        }

    private fun ComposeUiTest.waitFor(block: () -> Unit) =
        waitUntil(timeoutMillis = 5_000) {
            try {
                block()
                true
            } catch (_: Throwable) {
                false
            }
        }

    /** The docked part stands in the bar's sheet, above its host row. */
    private fun ComposeUiTest.assertDockedAboveTheHostRow() {
        val dock = onNodeWithTag(DOCK).assertIsDisplayed().getUnclippedBoundsInRoot()
        val row = onNodeWithTag("sidebar_footer").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(dock.bottom <= row.top, "the player (${dock.bottom}) is not above the host row (${row.top})")
        assertTrue(dock.left >= row.left - 8.dp() && dock.right <= row.right + 8.dp(), "the player ($dock) is not in the bar ($row)")
    }

    private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

    // ── the docked part ────────────────────────────────────────────────────────────────────────

    @Test fun idleTheBarHasNoPlayer() = runDesktopComposeUiTest {
        val player = AudioPlayer(FakeOutputs())
        footer(player)
        onNodeWithTag("sidebar_footer").assertIsDisplayed()
        onNodeWithTag(DOCK).assertDoesNotExist()
        // It comes with the first item, and goes with ✕.
        player.play(note)
        waitForIdle()
        assertDockedAboveTheHostRow()
    }

    @Test fun onAPhoneTooItDocksAboveTheHostRow() = runDesktopComposeUiTest(width = 420, height = 860) {
        val player = AudioPlayer(FakeOutputs()).apply { play(note) }
        footer(player, pointer = false)
        assertDockedAboveTheHostRow()
    }

    @Test fun itShowsTheTitleItsChatAndTheSpeed() = runDesktopComposeUiTest {
        val player = AudioPlayer(FakeOutputs()).apply { play(note) }
        footer(player)
        onNodeWithTag("now_playing_title").assertTextEquals("voice-note.m4a")
        onNodeWithTag("now_playing_chat").assertTextEquals("demo-day-recap")
        onNode(hasTestTag("now_playing_speed")).assertIsDisplayed()
        onNodeWithText("1×").assertIsDisplayed()
        onNodeWithTag("now_playing_elapsed").assertTextEquals("0:00")
        onNodeWithTag("now_playing_length").assertTextEquals("2:10")
    }

    @Test fun theSpeedGoesOneOneAndAHalfTwo() = runDesktopComposeUiTest {
        val outputs = FakeOutputs()
        val player = AudioPlayer(outputs).apply { play(note) }
        footer(player)
        onNodeWithTag("now_playing_speed").performClick()
        onNodeWithText("1.5×").assertIsDisplayed()
        assertEquals(1.5f, player.state.value?.speed)
        onNodeWithTag("now_playing_speed").performClick()
        onNodeWithText("2×").assertIsDisplayed()
        onNodeWithTag("now_playing_speed").performClick()
        onNodeWithText("1×").assertIsDisplayed()
        assertEquals(listOf("rate 1.5", "rate 2.0", "rate 1.0"), outputs.made.single().calls.filter { it.startsWith("rate") })
    }

    @Test fun theCrossStopsIt() = runDesktopComposeUiTest {
        val outputs = FakeOutputs()
        val player = AudioPlayer(outputs).apply { play(note) }
        footer(player)
        onNodeWithTag("now_playing_stop").performClick()
        waitForIdle()
        assertNull(player.state.value)
        assertEquals("release", outputs.made.single().calls.last())
        onNodeWithTag(DOCK).assertDoesNotExist()
    }

    @Test fun playPauseToggles() = runDesktopComposeUiTest {
        val outputs = FakeOutputs()
        val player = AudioPlayer(outputs).apply { play(note) }
        footer(player)
        onNode(hasTestTag("now_playing_toggle") and hasContentDescription("Pause")).performClick()
        waitForIdle()
        assertFalse(assertNotNull(player.state.value).playing)
        onNode(hasTestTag("now_playing_toggle") and hasContentDescription("Play")).performClick()
        waitForIdle()
        assertTrue(assertNotNull(player.state.value).playing)
        assertEquals(listOf("pause", "play"), outputs.made.single().calls.drop(2))
    }

    @Test fun theScrubberShowsWhereItIsAndADragSeeks() = runDesktopComposeUiTest {
        val outputs = FakeOutputs()
        val player = AudioPlayer(outputs).apply { play(note) }
        footer(player)
        outputs.made.single().events.onProgress(42_000, 130_000)
        waitForIdle()
        onNodeWithTag("now_playing_elapsed").assertTextEquals("0:42")
        onNode(hasTestTag("now_playing_seek") and hasProgressBarRangeInfo(ProgressBarRangeInfo(42f / 130f, 0f..1f))).assertExists()

        onNodeWithTag("now_playing_seek").performTouchInput {
            down(Offset(width * 0.25f, centerY))
            moveTo(Offset(width * 0.5f, centerY))
            moveTo(Offset(width * 0.75f, centerY))
            up()
        }
        waitForIdle()
        val seeks = outputs.made.single().calls.filter { it.startsWith("seek") }
        assertEquals(1, seeks.size, "one seek, once let go: $seeks")
        val to = seeks.single().removePrefix("seek ").toLong()
        assertTrue(abs(to - 97_500) < 3_000, "dragged to three quarters of 2:10, seeked to $to")
        assertTrue(abs(assertNotNull(player.state.value).positionMs - 97_500) < 3_000)
    }

    @Test fun itsChatOpensAtTheMessage() = runDesktopComposeUiTest {
        val opened = mutableListOf<String>()
        val search = ChatSearchHost(searchAll = { emptyList() }, openSession = { opened += it }, hasSession = { it == "s1" })
        val player = AudioPlayer(FakeOutputs()).apply { play(note) }
        footer(player, search = search)
        onNodeWithTag("now_playing_chat").performClick()
        waitForIdle()
        assertEquals(ChatJump("s1", "m7", reveal = true), search.jump.value)
        assertEquals(listOf("s1"), opened)
        assertTrue(player.state.value?.playing == true, "opening its chat does not stop it")
    }

    @Test fun aChatThatIsGoneSaysSo() = runDesktopComposeUiTest {
        val platform = FakePlatform()
        val search = ChatSearchHost(searchAll = { emptyList() }, openSession = {}, hasSession = { false })
        val player = AudioPlayer(FakeOutputs()).apply { play(note) }
        footer(player, search = search, platform = platform)
        onNodeWithTag("now_playing_chat").performClick()
        waitForIdle()
        assertNull(search.jump.value)
        assertEquals(listOf("That chat is no longer available"), platform.notices.shown)
    }

    // ── the shell ──────────────────────────────────────────────────────────────────────────────

    @Test fun theFleetsPlayerDocksOnAPhonesHome() = runDesktopComposeUiTest(width = 420, height = 860) {
        val fleet = testFleet(testHostStore())
        try {
            fleet.audio.useOutputs(FakeOutputs())
            setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
                SupermuxTheme(appearance = AppearanceMode.LIGHT) { SupermuxApp(fleet = fleet, ui = ShellUiState()) }
            }
            waitForIdle()
            onNodeWithTag(DOCK).assertDoesNotExist()
            fleet.audio.play(note)
            waitForIdle()
            assertDockedAboveTheHostRow()
        } finally {
            fleet.close()
        }
    }

    @Test fun theCollapsedSidebarsRailShowsThePlayersRing() = runDesktopComposeUiTest(width = 1200, height = 900) {
        val fleet = testFleet(testHostStore())
        try {
            val outputs = FakeOutputs()
            fleet.audio.useOutputs(outputs)
            setPlatformContent {
                SupermuxTheme(appearance = AppearanceMode.LIGHT) {
                    SupermuxApp(fleet = fleet, ui = ShellUiState().apply { sidebarCollapsed = true })
                }
            }
            waitForIdle()
            onNodeWithTag(RAIL_RING).assertDoesNotExist()
            fleet.audio.play(note)
            outputs.made.single().events.onProgress(65_000, 130_000)
            waitForIdle()
            val ring = onNodeWithTag(RAIL_RING).assertIsDisplayed().getUnclippedBoundsInRoot()
            onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0.5f, 0f..1f)) and hasContentDescription("Now playing: voice-note.m4a")).assertExists()
            val sheet = onNodeWithTag("sidebar_rail_footer").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(ring.bottom <= sheet.top, "the ring ends at ${ring.bottom}, inside the rail's bottom sheet (${sheet.top})")
            // It opens the player beside the rail; ✕ there stops it and the ring goes.
            onNodeWithTag(RAIL_RING).performClick()
            waitFor { onNodeWithTag("now_playing_popover").assertIsDisplayed() }
            onNodeWithTag("now_playing_stop").performClick()
            waitForIdle()
            assertNull(fleet.audio.state.value)
            waitFor { onNodeWithTag(RAIL_RING).assertDoesNotExist() }
        } finally {
            fleet.close()
        }
    }

    private fun ComposeUiTest.onNodeWithText(text: String) = onNode(androidx.compose.ui.test.hasText(text))

    private companion object {
        const val DOCK = "now_playing"
        const val RAIL_RING = "rail_audio"
    }
}
