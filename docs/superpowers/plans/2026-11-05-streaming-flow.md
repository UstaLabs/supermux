# Streaming flow (the ink edge) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The desktop app shows a live reply at a steady pace with a soft ink edge and a gliding scroll (option B3 that Ahmet picked), falling back to steady whole words under reduced motion.

**Architecture:**
- A pure display clock (`RevealClock`, shared) decides, frame by frame, how much of the live text is laid out and how opaque each character is.
- The UI (`LiveReveal.kt`) runs it once per frame only while there is something to move or dry. It draws the edge as a gradient mask over the last text blocks; draw only, the text relayouts a word at a time.
- The live row and the stored row that replaces it share one reveal, keyed by stream.
- ChatPanel's autoscroll eases toward the end while a revealing reply grows. #1's follow rules stay as they are.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform (desktop JVM first), kotlin.test, Compose UI test (`runComposeUiTest`).

**Spec:** `docs/superpowers/specs/2026-11-05-streaming-flow-design.md`

**Box rules for every Gradle run:**
- Run from `apps/`, under the heavy-job lock:
  `flock -w 3600 /tmp/supermux-heavy.lock systemd-run --user --scope -q -p MemoryMax=8G -- ./gradlew -Pkotlin.compiler.execution.strategy=in-process --no-daemon -Dorg.gradle.jvmargs=-Xmx2g --console=plain <tasks>`
- Shorthand used below: `~/streaming-probe/gradle-round.sh <tasks>` is exactly that.

---

## File structure

| File | Change | Responsibility |
|---|---|---|
| `apps/shared/src/commonMain/kotlin/dev/supermux/chat/RevealClock.kt` | create | Pure pacing and opacity state |
| `apps/shared/src/commonTest/kotlin/dev/supermux/chat/RevealClockTest.kt` | create | Its tests |
| `apps/ui/src/commonMain/kotlin/dev/supermux/ui/theme/ReduceMotion.kt` | create | `LocalReduceMotion` |
| `apps/desktop/src/main/kotlin/dev/supermux/desktop/platform/ReduceMotion.kt` | create | Ask the OS (macOS, GNOME) |
| `apps/desktop/src/test/kotlin/dev/supermux/desktop/platform/ReduceMotionTest.kt` | create | Parsing tests |
| `apps/desktop/src/main/kotlin/dev/supermux/desktop/theme/DesktopTheme.kt` | modify | Provide `LocalReduceMotion` |
| `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/LiveReveal.kt` | create | `LiveRevealState`, `LiveReveals`, `RevealDriver`, `InkBlock`, `Modifier.inkEdge`, `blockSourceEnds` |
| `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Markdown.kt` | modify | `MdText` draws the edge; `MarkdownBody(reveal = …)` |
| `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/LiveRows.kt` | modify | `LiveReplyRow` reveals |
| `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Timeline.kt` | modify | `AssistantMessage(reveal = …)`; `TimelineItemRow` continues an unfinished reveal |
| `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatPanel.kt` | modify | Reveal registry and the glide |
| `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/LiveRevealTest.kt` | create | Block mapping, reveal and continuation tests |
| `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/ChatAutoscrollTest.kt` | modify | Two new glide tests; existing tests untouched |

---

### Task 1: RevealClock

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/chat/RevealClock.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/chat/RevealClockTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.supermux.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Card #76 (B3, the ink edge): the live reply's display clock. */
class RevealClockTest {
    /** 200 chars, every word followed by a space. */
    private val words = "word ".repeat(40)

    /** One 16 ms frame at a time from [fromMs] through [toMs], the text unchanged; returns the last frame's time. */
    private fun RevealClock.frames(text: String, done: Boolean, fromMs: Double, toMs: Double, reduced: Boolean = false): Double {
        var t = fromMs
        while (t <= toMs) { advance(t, text, done, reduced); t += 16.0 }
        return t - 16.0
    }

    private fun atWordBoundary(text: String, i: Int) =
        i == 0 || i == text.length || text[i].isWhitespace() || text[i - 1].isWhitespace()

    @Test fun aBurstSpreadsOverAboutTau() {
        val clock = RevealClock()
        clock.advance(0.0, words, done = false)
        // One frame covers 1 - exp(-16/150) of the 200-char backlog: about 20 characters, not all of them.
        assertTrue(clock.head in 15.0..25.0, "head ${clock.head}")
        clock.frames(words, done = false, fromMs = 16.0, toMs = 150.0)
        assertTrue(clock.head in 110.0..150.0, "after about tau, about 63%: ${clock.head}")
        clock.frames(words, done = false, fromMs = 166.0, toMs = 1200.0)
        assertEquals(200, clock.laidOut)
    }

    @Test fun aTrickleFlowsAtTheFloor() {
        val clock = RevealClock()
        clock.advance(0.0, "ab ", done = false)
        // 3 * (1 - exp(-16/150)) = 0.3 is under the floor: 30 chars/s * 16 ms = 0.48.
        assertEquals(0.48, clock.head, 1e-9)
    }

    @Test fun theTrailingHalfWordWaitsWhileTheStreamFlows() {
        val clock = RevealClock()
        var text = "hello wor"
        val t = clock.frames(text, done = false, fromMs = 0.0, toMs = 250.0)
        assertEquals("hello ", text.take(clock.laidOut))
        text = "hello world and"
        clock.frames(text, done = false, fromMs = t + 16, toMs = t + 250)
        assertEquals("hello world ", text.take(clock.laidOut))
    }

    @Test fun aPauseRevealsTheTrailingWord() {
        val clock = RevealClock()
        clock.frames("Live tail.", done = false, fromMs = 0.0, toMs = 1000.0)
        assertEquals(10, clock.laidOut, "after 300 ms with nothing new, the last word shows")
    }

    @Test fun theEndRevealsTheTrailingWord() {
        val clock = RevealClock()
        clock.advance(0.0, "hello wor", done = false)
        clock.frames("hello world", done = true, fromMs = 16.0, toMs = 400.0)
        assertEquals(11, clock.laidOut)
    }

    @Test fun aLongTokenDoesNotWait() {
        val clock = RevealClock()
        val token = "x".repeat(40)
        var t = 0.0
        // One more character every frame: never a pause, yet a run over 32 chars is not a half word to wait for.
        while (t <= 600.0) { clock.advance(t, token.take(minOf(40, 8 + (t / 16).toInt())), done = false); t += 16.0 }
        assertEquals(40, clock.laidOut)
    }

    @Test fun theWordTheHeadIsInIsLaidOutWhole() {
        val clock = RevealClock()
        clock.advance(0.0, "alpha beta gamma ", done = false)
        // head = 17 * (1 - exp(-16/150)) = 1.7: inside "alpha", which is laid out whole.
        assertTrue(clock.head < 5.0, "head ${clock.head}")
        assertEquals(5, clock.laidOut)
    }

    @Test fun anEdgeFadesBehindTheHeadAndNothingShowsPastTheLayout() {
        val clock = RevealClock()
        val now = clock.frames(words, done = false, fromMs = 0.0, toMs = 96.0)
        val head = clock.head.toInt()
        assertEquals(0f, clock.alphaAt(clock.laidOut, now))
        val near = clock.alphaAt(head - 1, now)
        val far = clock.alphaAt(head - 12, now)
        assertTrue(near < 1f && far < 1f, "both inside the 24-char edge: $near, $far")
        assertTrue(far > near, "further behind the head is more opaque: $far > $near")
    }

    @Test fun aPauseNeverLeavesAFadedTail() {
        val clock = RevealClock()
        val now = clock.frames("word ".repeat(10), done = false, fromMs = 0.0, toMs = 1500.0)
        assertFalse(clock.animating)
        for (i in 0 until clock.laidOut) assertEquals(1f, clock.alphaAt(i, now), "char $i")
        assertEquals(clock.laidOut, clock.firstUnsettled())
    }

    @Test fun itIdlesOnceCaughtUpAndDryAndWakesForNewText() {
        val clock = RevealClock()
        val now = clock.frames("word ".repeat(10), done = false, fromMs = 0.0, toMs = 1500.0)
        assertFalse(clock.animating)
        clock.advance(now + 16, "word ".repeat(12), done = false)
        assertTrue(clock.animating)
    }

    @Test fun reducedMotionShowsWholeWordsAtThePaceWithNoEdge() {
        val clock = RevealClock()
        val text = "alpha beta gamma "
        clock.advance(0.0, text, done = false, reduced = true)
        assertEquals(0, clock.laidOut, "the head is inside alpha: nothing yet, never a fragment")
        val now = clock.frames(text, done = false, fromMs = 16.0, toMs = 120.0, reduced = true)
        assertTrue(atWordBoundary(text, clock.laidOut), "a word boundary: ${clock.laidOut}")
        for (i in 0 until clock.laidOut) assertEquals(1f, clock.alphaAt(i, now))
        clock.frames(text, done = false, fromMs = now + 16, toMs = now + 1500, reduced = true)
        assertEquals(17, clock.laidOut)
        assertFalse(clock.animating)
    }

    @Test fun theEndCatchesUpFaster() {
        val open = RevealClock().apply { advance(0.0, words, done = false) }
        val ended = RevealClock().apply { advance(0.0, words, done = true) }
        assertTrue(ended.head > open.head * 1.5, "tau 75 ms once the stream has ended: ${ended.head} vs ${open.head}")
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `~/streaming-probe/gradle-round.sh :shared:jvmTest --tests 'dev.supermux.chat.RevealClockTest'`
Expected: compilation fails, `Unresolved reference: RevealClock`.

- [ ] **Step 3: Implement**

```kotlin
package dev.supermux.chat

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Card #76 (B3, the ink edge): the live reply's display clock. Text arrives in lumps (50 ms batches, empty ticks,
 * bursts); this decides, frame by frame, how much of it is on screen and how opaque each character is.
 *
 * - The head moves toward what may be shown, covering `1 - exp(-dt / tau)` of the backlog each frame ([TAU_MS], and
 *   [DONE_TAU_MS] once the stream has ended), never slower than [FLOOR_CHARS_PER_SECOND].
 * - While the stream is open, the trailing run of letters may be half a word: it waits, unless it is longer than
 *   [LONG_RUN] or nothing new has arrived for [STALL_MS].
 * - The text is laid out to the end of the word the head is in. A character's opacity is the larger of its distance
 *   behind the head over [EDGE_CHARS] and its eased age on screen over [DRY_MS]; past the layout it is zero.
 * - Reduced motion: whole words only, every laid-out character opaque.
 */
class RevealClock {
    /** How far the reveal has got, in characters; continuous. */
    var head = 0.0
        private set

    /** How much of the text to lay out. */
    var laidOut = 0
        private set

    /** Another frame would change what is drawn: still moving, drying, or holding a trailing word for a pause. */
    var animating = false
        private set

    private var reduced = false
    private var lastNow = Double.NaN
    private var arrived = 0
    private var arrivedAt = 0.0

    /** Stretches laid out in the last [DRY_MS], oldest first: what the age term still fades. */
    private val fresh = ArrayDeque<Stretch>()

    private class Stretch(val from: Int, val to: Int, val at: Double)

    fun advance(nowMs: Double, text: String, done: Boolean, reduced: Boolean = false) {
        if (text.length < laidOut) reset()
        this.reduced = reduced
        val dt = if (lastNow.isNaN()) FRAME_MS else (nowMs - lastNow).coerceIn(0.0, MAX_DT_MS)
        lastNow = nowMs
        if (text.length != arrived) { arrived = text.length; arrivedAt = nowMs }
        val target = revealable(text, whole = done || nowMs - arrivedAt >= STALL_MS)
        if (head < target) {
            val gain = (target - head) * (1 - exp(-dt / if (done) DONE_TAU_MS else TAU_MS))
            head = min(target.toDouble(), head + max(gain, FLOOR_CHARS_PER_SECOND * dt / 1000))
        }
        val next = if (reduced) lastWordEnd(text, floor(head).toInt()) else min(wordEndAfter(text, ceil(head).toInt()), max(target, laidOut))
        if (next > laidOut) {
            if (!reduced) fresh.addLast(Stretch(laidOut, next, nowMs))
            laidOut = next
        }
        while (fresh.isNotEmpty() && (reduced || nowMs - fresh.first().at >= DRY_MS)) fresh.removeFirst()
        animating = head < target || fresh.isNotEmpty() || target < text.length
    }

    /** How opaque the character at [index] draws at [nowMs]. */
    fun alphaAt(index: Int, nowMs: Double): Float {
        if (index >= laidOut) return 0f
        if (reduced) return 1f
        val stretch = fresh.lastOrNull { index >= it.from } ?: return 1f
        val behind = ((head - index) / EDGE_CHARS).coerceIn(0.0, 1.0)
        val age = ((nowMs - stretch.at) / DRY_MS).coerceIn(0.0, 1.0)
        return max(behind, easeOut(age)).toFloat()
    }

    /** The first character that is not fully opaque yet ([laidOut] when every one is). */
    fun firstUnsettled(): Int = fresh.firstOrNull()?.from ?: laidOut

    fun reset() {
        head = 0.0
        laidOut = 0
        animating = false
        lastNow = Double.NaN
        arrived = 0
        arrivedAt = 0.0
        fresh.clear()
    }

    companion object {
        const val TAU_MS = 150.0
        const val DONE_TAU_MS = 75.0
        const val FLOOR_CHARS_PER_SECOND = 30.0
        const val EDGE_CHARS = 24.0
        const val DRY_MS = 450.0
        const val STALL_MS = 300.0
        const val LONG_RUN = 32
        private const val FRAME_MS = 16.0
        private const val MAX_DT_MS = 64.0

        /**
         * How much of [text] may be shown: all of it when [whole]; otherwise up to its trailing run of letters, which
         * may be half a word, unless that run is longer than [LONG_RUN] (a long token, or a script without spaces).
         */
        fun revealable(text: String, whole: Boolean): Int {
            if (whole) return text.length
            var i = text.length
            while (i > 0 && !text[i - 1].isWhitespace()) i--
            return if (text.length - i > LONG_RUN) text.length else i
        }

        /** The end of the word [p] is inside; [p] itself at a word boundary. */
        private fun wordEndAfter(text: String, p: Int): Int {
            var i = p.coerceIn(0, text.length)
            if (i == 0 || text[i - 1].isWhitespace()) return i
            while (i < text.length && !text[i].isWhitespace()) i++
            return i
        }

        /** The end of the last whole word at or before [p]: never a fragment. */
        private fun lastWordEnd(text: String, p: Int): Int {
            var i = p.coerceIn(0, text.length)
            while (i > 0 && i < text.length && !text[i - 1].isWhitespace() && !text[i].isWhitespace()) i--
            return i
        }

        private fun easeOut(k: Double) = 1 - (1 - k) * (1 - k) * (1 - k)
    }
}
```

- [ ] **Step 4: Run them to see them pass**

Run: `~/streaming-probe/gradle-round.sh :shared:jvmTest --tests 'dev.supermux.chat.RevealClockTest'`
Expected: BUILD SUCCESSFUL, 12 tests passed.

- [ ] **Step 5: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/chat/RevealClock.kt apps/shared/src/commonTest/kotlin/dev/supermux/chat/RevealClockTest.kt
git commit -m "feat(apps): the live reply's display clock: steady pace, whole words, an ink edge (#76)"
```

---

### Task 2: Reduced motion from the OS (desktop)

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/theme/ReduceMotion.kt`
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/platform/ReduceMotion.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/theme/DesktopTheme.kt`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/platform/ReduceMotionTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package dev.supermux.desktop.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Card #76: the ink edge and the gliding scroll step aside when the OS asks for less motion. */
class ReduceMotionTest {
    @Test fun macOsReadsTheUniversalAccessSetting() {
        assertTrue(reduceMotionFrom("Mac OS X", "1\n"))
        assertFalse(reduceMotionFrom("Mac OS X", "0\n"))
        // The key is absent until the user has touched the setting: `defaults` says so on stderr.
        assertFalse(reduceMotionFrom("Mac OS X", "The domain/default pair of (com.apple.universalaccess, reduceMotion) does not exist"))
    }

    @Test fun gnomeReadsEnableAnimations() {
        assertTrue(reduceMotionFrom("Linux", "false\n"))
        assertFalse(reduceMotionFrom("Linux", "true\n"))
    }

    @Test fun anythingElseOrNoAnswerIsFalse() {
        assertFalse(reduceMotionFrom("Windows 11", "1"))
        assertFalse(reduceMotionFrom("Mac OS X", null))
    }
}
```

- [ ] **Step 2: Run it to see it fail**

Run: `~/streaming-probe/gradle-round.sh :desktop:test --tests 'dev.supermux.desktop.platform.ReduceMotionTest'`
Expected: compilation fails, `Unresolved reference: reduceMotionFrom`.

- [ ] **Step 3: Implement**

`apps/ui/src/commonMain/kotlin/dev/supermux/ui/theme/ReduceMotion.kt`:

```kotlin
package dev.supermux.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The OS asks for less motion (macOS "Reduce motion", GNOME with animations off, Android's animator scale at 0,
 * iOS "Reduce Motion"). False unless a platform root provides it. Card #76: the live reply then shows whole words at
 * the steady pace, with no ink edge, and the transcript jumps instead of gliding.
 */
val LocalReduceMotion = staticCompositionLocalOf { false }
```

`apps/desktop/src/main/kotlin/dev/supermux/desktop/platform/ReduceMotion.kt`:

```kotlin
package dev.supermux.desktop.platform

import java.util.concurrent.TimeUnit

/** What one settings query's [output] says about reduced motion on [osName]; false when it cannot tell. */
internal fun reduceMotionFrom(osName: String, output: String?): Boolean {
    val value = output?.trim() ?: return false
    return when {
        osName.startsWith("Mac", ignoreCase = true) -> value == "1"
        osName.startsWith("Linux", ignoreCase = true) -> value == "false"
        else -> false
    }
}

/** Asks the OS once: macOS `defaults`, GNOME `gsettings`. Windows and anything unknown read as false. Blocking. */
internal fun probeReduceMotion(osName: String = System.getProperty("os.name").orEmpty()): Boolean {
    val command = when {
        osName.startsWith("Mac", ignoreCase = true) -> listOf("defaults", "read", "com.apple.universalaccess", "reduceMotion")
        osName.startsWith("Linux", ignoreCase = true) -> listOf("gsettings", "get", "org.gnome.desktop.interface", "enable-animations")
        else -> return false
    }
    return runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else {
            reduceMotionFrom(osName, process.inputStream.bufferedReader().readText())
        }
    }.getOrDefault(false)
}
```

In `DesktopTheme.kt`, add the imports and provide the local (asked off the UI thread, false until it answers):

```kotlin
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.supermux.desktop.platform.probeReduceMotion
import dev.supermux.ui.theme.LocalReduceMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
```

```kotlin
    val prefs = uiPrefs ?: remember { UiPrefs(InMemorySettingsStore()) }
    // Card #76: the OS's reduced-motion setting, asked once per window off the UI thread (false until it answers).
    val reduceMotion by produceState(false) { value = withContext(Dispatchers.IO) { probeReduceMotion() } }
    CompositionLocalProvider(
        LocalContextMenuRepresentation provides contextMenu,
        LocalPlatform provides platform,
        LocalUiPrefs provides prefs,
        LocalUiPrefsOrNull provides prefs,
        LocalReduceMotion provides reduceMotion,
    ) {
```

- [ ] **Step 4: Run it to see it pass**

Run: `~/streaming-probe/gradle-round.sh :desktop:test --tests 'dev.supermux.desktop.platform.ReduceMotionTest'`
Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/theme/ReduceMotion.kt apps/desktop/src/main/kotlin/dev/supermux/desktop/platform/ReduceMotion.kt apps/desktop/src/test/kotlin/dev/supermux/desktop/platform/ReduceMotionTest.kt apps/desktop/src/main/kotlin/dev/supermux/desktop/theme/DesktopTheme.kt
git commit -m "feat(desktop): read the OS's reduced-motion setting (#76)"
```

---

### Task 3: The reveal in Compose, and the ink edge on Markdown text

**Files:**
- Create: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/LiveReveal.kt`
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Markdown.kt` (`MdText` ~L248, `MarkdownBody` ~L380)
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/LiveRevealTest.kt`

- [ ] **Step 1: Write the failing tests** (block mapping, and the reveal through `MarkdownBody`)

```kotlin
package dev.supermux.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.parseMarkdownBlocks
import dev.supermux.ui.theme.LocalReduceMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Card #76 (B3, the ink edge): the live reply's reveal in Compose. */
@OptIn(ExperimentalTestApi::class)
class LiveRevealTest {
    private val text = (1..6).joinToString(" ") { "Sentence $it of the live reply goes on for a while." } + " The end."

    @Test fun blockEndsAreFoundFromTheEnd() {
        val src = "Intro **bold** text.\n\nSecond para."
        assertEquals(listOf<Int?>(20, 34), blockSourceEnds(src, parseMarkdownBlocks(src)))
    }

    @Test fun headingsAndListItemsEndWhereTheirTextEnds() {
        val src = "# Title\n\n- one\n- two"
        assertEquals(listOf<Int?>(7, 14, 20), blockSourceEnds(src, parseMarkdownBlocks(src)))
    }

    @Test fun codeBlocksGetNoEdge() {
        val src = "Run this:\n\n```sh\nls -l\n```\n\nThen look."
        assertEquals(listOf<Int?>(9, null, src.length), blockSourceEnds(src, parseMarkdownBlocks(src)))
    }

    @Test fun theTextAppearsProgressivelyAndEndsWhole() = runComposeUiTest {
        val reveal = LiveRevealState()
        mainClock.autoAdvance = false
        setPlatformContent {
            RevealDriver(reveal, text, done = false)
            MarkdownBody(text = text.take(reveal.laidOut), reveal = reveal)
        }
        repeat(5) { mainClock.advanceTimeByFrame() }
        assertTrue(reveal.laidOut in 1 until text.length, "a few words in after 80 ms: ${reveal.laidOut}")
        assertTrue(reveal.masking, "the newest words are still inking in")
        assertTrue(onAllNodesWithText("The end.", substring = true).fetchSemanticsNodes().isEmpty())
        mainClock.autoAdvance = true
        waitForIdle()
        assertEquals(text.length, reveal.laidOut)
        assertFalse(reveal.masking, "dry once idle")
        onNodeWithText("The end.", substring = true).assertExists()
    }

    @Test fun reducedMotionShowsWholeWordsWithNoEdge() = runComposeUiTest {
        val reveal = LiveRevealState()
        mainClock.autoAdvance = false
        setPlatformContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                RevealDriver(reveal, text, done = false)
                MarkdownBody(text = text.take(reveal.laidOut), reveal = reveal)
            }
        }
        repeat(6) { mainClock.advanceTimeByFrame() }
        val n = reveal.laidOut
        assertTrue(n == 0 || text[n - 1].isWhitespace() || text[n].isWhitespace(), "a word boundary: '${text.take(n)}'")
        assertFalse(reveal.masking, "no ink edge under reduced motion")
    }
}
```

- [ ] **Step 2: Run them to see them fail**

Run: `~/streaming-probe/gradle-round.sh :ui:jvmTest --tests 'dev.supermux.ui.chat.LiveRevealTest'`
Expected: compilation fails, `Unresolved reference: LiveRevealState` / `blockSourceEnds` / `RevealDriver` / parameter `reveal`.

- [ ] **Step 3: Implement `LiveReveal.kt`**

```kotlin
package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextLayoutResult
import dev.supermux.chat.RevealClock
import dev.supermux.ui.MdBlock
import dev.supermux.ui.theme.LocalReduceMotion
import kotlinx.coroutines.flow.first
import kotlin.math.max

/**
 * Card #76 (B3, the ink edge): a reply's reveal while it is written. The [RevealClock] runs once per frame only while
 * there is something to move or dry. Composition reads [laidOut], which changes a word at a time. The draw pass reads
 * the frame, so the edge redraws each frame without recomposing anything.
 */
@Stable
internal class LiveRevealState {
    internal val clock = RevealClock()

    /** How much of the reply's text to lay out. */
    var laidOut by mutableIntStateOf(0)
        private set

    /** The stream has ended and its reveal is complete: the row may draw statically. */
    var finished by mutableStateOf(false)
        private set

    /** The frame the edge was last drawn for. */
    private var frameMs by mutableLongStateOf(0L)
    private var text by mutableStateOf("")
    private var done by mutableStateOf(false)
    private var reduced by mutableStateOf(false)

    /** Some laid-out text is still inking in (false under reduced motion and once dry). */
    val masking: Boolean get() = frameMs >= 0 && !reduced && clock.firstUnsettled() < clock.laidOut

    internal fun offer(text: String, done: Boolean, reduced: Boolean) {
        this.text = text
        this.done = done
        this.reduced = reduced
    }

    /** Sleeps until there is new text (or the end), then runs the clock a frame at a time until it settles. */
    internal suspend fun run() {
        while (true) {
            snapshotFlow { text.length > clock.laidOut || (done && !finished) }.first { it }
            do {
                val now = withFrameMillis { it }
                clock.advance(now.toDouble(), text, done, reduced)
                laidOut = clock.laidOut
                frameMs = now
            } while (clock.animating)
            if (done) finished = true
        }
    }

    /** Whether a block whose raw text ends at [sourceEnd] has characters still inking in (read in a layer block). */
    internal fun masks(sourceEnd: Int): Boolean = frameMs >= 0 && !reduced && clock.firstUnsettled() < sourceEnd

    /**
     * Fades the newest characters of one text block: per line, a horizontal gradient of each character's opacity,
     * applied with [BlendMode.DstIn] over what the text drew. A character's source index is counted back from
     * [sourceEnd], the end of the block's raw Markdown (markup after it shifts the edge by a character or two).
     */
    internal fun drawEdge(scope: DrawScope, layout: TextLayoutResult, sourceEnd: Int) {
        val now = frameMs.toDouble()
        if (reduced) return
        val n = layout.layoutInput.text.length
        val firstSource = sourceEnd - n
        val from = (clock.firstUnsettled() - firstSource).coerceIn(0, n)
        if (from >= n) return
        var line = layout.getLineForOffset(from)
        while (line < layout.lineCount) {
            val start = max(from, layout.getLineStart(line))
            val end = layout.getLineEnd(line, visibleEnd = true)
            if (start < end) {
                val left = layout.getBoundingBox(start).left
                val right = max(layout.getLineRight(line), left + 1f)
                val stops = (start until end).map { j ->
                    val box = layout.getBoundingBox(j)
                    val x = (((box.left + box.right) / 2 - left) / (right - left)).coerceIn(0f, 1f)
                    x to Color.Black.copy(alpha = clock.alphaAt(firstSource + j, now))
                }.sortedBy { it.first }
                val padded = listOf(0f to stops.first().second) + stops + listOf(1f to stops.last().second)
                val top = layout.getLineTop(line)
                scope.drawRect(
                    brush = Brush.horizontalGradient(*padded.toTypedArray(), startX = left, endX = right),
                    topLeft = Offset(left, top),
                    size = Size(right - left, layout.getLineBottom(line) - top),
                    blendMode = BlendMode.DstIn,
                )
            }
            line++
        }
    }
}

/** Feeds [state] this row's text and runs its clock while the row is composed. */
@Composable
internal fun RevealDriver(state: LiveRevealState, text: String, done: Boolean) {
    val reduced = LocalReduceMotion.current
    SideEffect { state.offer(text, done, reduced) }
    LaunchedEffect(state) { state.run() }
}

/** One chat's reveals by stream: the live row and the stored row that takes its place share one. */
@Stable
internal class LiveReveals {
    private val byStream = HashMap<String, LiveRevealState>()

    fun of(stream: String): LiveRevealState = byStream.getOrPut(stream) { LiveRevealState() }

    /** The reveal a stored reply continues: its stream's, while that has not finished. */
    fun unfinished(stream: String?): LiveRevealState? = stream?.let { byStream[it] }?.takeIf { !it.finished }
}

internal val LocalLiveReveals = staticCompositionLocalOf<LiveReveals?> { null }

/** The reveal a Markdown text block belongs to, and where its raw text ends in the revealed source. */
@Immutable
internal data class InkBlock(val reveal: LiveRevealState, val sourceEnd: Int)

internal val LocalInkBlock = compositionLocalOf<InkBlock?> { null }

/**
 * The ink edge over one text block. The offscreen layer that [BlendMode.DstIn] needs exists only while the block
 * has characters inking in.
 */
internal fun Modifier.inkEdge(block: InkBlock, layout: () -> TextLayoutResult?): Modifier =
    graphicsLayer {
        compositingStrategy = if (block.reveal.masks(block.sourceEnd)) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }.drawWithContent {
        drawContent()
        val l = layout() ?: return@drawWithContent
        block.reveal.drawEdge(this, l, block.sourceEnd)
    }

/**
 * Where each text block's raw Markdown ends in [source], found from the end (where a reveal is). Null for blocks the
 * edge skips (code, tables, images, rules) and for text it cannot find (a quote spread over several `>` lines).
 */
internal fun blockSourceEnds(source: String, blocks: List<MdBlock>): List<Int?> {
    val ends = MutableList<Int?>(blocks.size) { null }
    var limit = source.length
    for (k in blocks.indices.reversed()) {
        val block = blocks[k]
        val raw = when (block) {
            is MdBlock.Prose -> block.text
            is MdBlock.Heading -> block.text
            is MdBlock.Bullet -> block.text
            is MdBlock.Numbered -> block.text
            is MdBlock.Quote -> block.text
            is MdBlock.Code -> block.code
            else -> null
        }?.trimEnd()
        if (raw.isNullOrEmpty()) continue
        val at = source.lastIndexOf(raw, limit - raw.length)
        if (at < 0) continue
        limit = at
        if (block !is MdBlock.Code) ends[k] = at + raw.length
    }
    return ends
}
```

- [ ] **Step 4: Hook `MdText` and `MarkdownBody` (Markdown.kt)**

In `MdText`, read the block and draw the edge inside any padding the caller set (before `linkModifier`):

```kotlin
    val selector = LocalLinkSelector.current
    // Card #76: a text block of a revealing reply draws the ink edge over itself.
    val ink = LocalInkBlock.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
```

```kotlin
    Text(
        text = text,
        // Last in the chain so pointer positions are in the text's own coordinates.
        modifier = modifier.then(if (ink == null) Modifier else Modifier.inkEdge(ink) { layout }).then(linkModifier),
```

In `MarkdownBody`, add the parameter (last) and its KDoc line, then wrap each block so the structure is the same with or without a reveal:

```kotlin
    document: Boolean = false,
    /** Card #76: the reveal this text is being written under (the live reply): its text blocks draw the ink edge. */
    reveal: LiveRevealState? = null,
) {
```

```kotlin
    val blocks = parseMarkdownBlocks(text)
    val inkEnds = reveal?.let { blockSourceEnds(text, blocks) }
```

```kotlin
        var prev: MdBlock? = null
        for ((index, block) in blocks.withIndex()) {
            if (prev != null) Box(Modifier.height(mdBlockGap(prev, block, document)))
            prev = block
            CompositionLocalProvider(LocalInkBlock provides inkEnds?.get(index)?.let { InkBlock(reveal!!, it) }) {
                when (block) {
                    // ... every existing branch, unchanged ...
                }
            }
        }
```

- [ ] **Step 5: Run the tests to see them pass**

Run: `~/streaming-probe/gradle-round.sh :ui:jvmTest --tests 'dev.supermux.ui.chat.LiveRevealTest'`
Expected: BUILD SUCCESSFUL, 5 tests passed. If a block-mapping test fails on the parser's raw text (for example a heading or list item keeping its marker), fix the expected index in the test only after checking `parseMarkdownBlocks(src)` really returns that text.

- [ ] **Step 6: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/LiveReveal.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Markdown.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/LiveRevealTest.kt
git commit -m "feat(apps): reveal Markdown under the ink edge, drawn as a mask (#76)"
```

---

### Task 4: The live row reveals, and the stored row finishes it

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/LiveRows.kt` (`LiveReplyRow`)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Timeline.kt` (`AssistantMessage` ~L217, `TimelineItemRow` AgentReply branch ~L850)
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatPanel.kt` (registry, provider ~L1047, live items ~L1094)
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/LiveRevealTest.kt` (append)

- [ ] **Step 1: Write the failing test** (append to `LiveRevealTest`, with imports `androidx.compose.runtime.getValue`, `mutableStateOf`, `setValue`, `dev.supermux.chat.TimelineItem`, `dev.supermux.proto.LogEntry`, `dev.supermux.state.LiveReply`)

```kotlin
    @Test fun theStoredReplyFinishesAnUnfinishedReveal() = runComposeUiTest {
        val reveals = LiveReveals()
        var live by mutableStateOf(true)
        mainClock.autoAdvance = false
        setPlatformContent {
            CompositionLocalProvider(LocalLiveReveals provides reveals) {
                if (live) LiveReplyRow(LiveReply("k1", "reply", text), reveals.of("k1"), onOpenFile = {})
                else TimelineItemRow(TimelineItem.Msg(LogEntry(id = "m1", ts = "2026-01-01T00:00:00Z", direction = "outbound", text = text, stream = "k1")))
            }
        }
        repeat(5) { mainClock.advanceTimeByFrame() }
        val before = reveals.of("k1").laidOut
        assertTrue(before in 1 until text.length, "mid-reveal: $before")
        live = false
        mainClock.advanceTimeByFrame()
        val after = reveals.of("k1").laidOut
        assertTrue(after in before until text.length, "the stored row picks up where the live row was: $before -> $after")
        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(reveals.of("k1").finished)
        onNodeWithText("The end.", substring = true).assertExists()
    }
```

- [ ] **Step 2: Run it to see it fail**

Run: `~/streaming-probe/gradle-round.sh :ui:jvmTest --tests 'dev.supermux.ui.chat.LiveRevealTest'`
Expected: compilation fails, `LiveReplyRow` takes no `LiveRevealState`.

- [ ] **Step 3: Implement**

`LiveRows.kt`, `LiveReplyRow`:

```kotlin
/**
 * Card #76: the reply while the agent writes it. Same look and padding as the stored reply that takes its place
 * (TimelineItemRow's message row), minus the time / copy row that only a finished message has. It shows at the
 * reveal's steady pace under the ink edge; the stored row finishes a reveal this one leaves unfinished.
 */
@Composable
internal fun LiveReplyRow(reply: LiveReply, reveal: LiveRevealState, onOpenFile: (FilePathRef) -> Unit) {
    RevealDriver(reveal, reply.text, done = false)
    Column(Modifier.fillMaxWidth().padding(vertical = Space.sm).testTag("chat-live-reply")) {
        LinkSelectionContainer {
            MarkdownBody(
                text = liveMarkdown(reply.text.take(reveal.laidOut)),
                modifier = Modifier.fillMaxWidth(),
                onOpenFile = onOpenFile,
                linkify = true,
                reveal = reveal,
            )
        }
    }
}
```

`Timeline.kt`, `AssistantMessage`: add the parameter last, drive the reveal to its end, and lay out only what it has reached:

```kotlin
    media: (@Composable () -> Unit)? = null,
    /** Card #76: the live reply's unfinished reveal this stored reply takes over and runs to the end. */
    reveal: LiveRevealState? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        if (reveal != null) RevealDriver(reveal, text, done = true)
        // LinkSelectionContainer makes the prose selectable/copyable; links inside stay clickable, and a
        // right-click on one selects the whole link.
        LinkSelectionContainer {
            MarkdownBody(
                text = if (reveal == null) text else dev.supermux.chat.liveMarkdown(text.take(reveal.laidOut)),
                modifier = Modifier.fillMaxWidth(),
                onOpenFile = onOpenFile,
                linkify = true,
                onOpenUrl = onOpenUrl,
                loadImage = loadImage,
                reveal = reveal,
            )
        }
```

`Timeline.kt`, `TimelineItemRow`'s AgentReply branch:

```kotlin
                                MessageBody.AgentReply -> AssistantMessage(
                                    text,
                                    onOpenFile = onOpenFile,
                                    ts = item.entry.ts,
                                    media = if (!filesInReply) null else {
                                        { AttachmentList(atts.orEmpty(), alignEnd = false, media = media, entryId = item.entry.id) }
                                    },
                                    reveal = LocalLiveReveals.current?.unfinished(item.entry.stream),
                                )
```

`ChatPanel.kt`: one registry per chat, provided with the transcript's other locals, and the live row takes its stream's reveal:

```kotlin
    // Card #76: the reveals of this chat's live replies, by stream (the stored reply finishes an unfinished one).
    val reveals = remember(session.id) { LiveReveals() }
```

```kotlin
            androidx.compose.runtime.CompositionLocalProvider(
                LocalCouncilActivity provides state.councilActivity,
                LocalChatMediaViewer provides mediaOpener,
                LocalLiveReveals provides reveals,
            ) {
```

```kotlin
                items(liveRows, key = ::liveRowKey) { row ->
                    if (row.thinking) LiveThinkingRow(row, folded = liveRows.any { !it.thinking })
                    else LiveReplyRow(row, reveals.of(row.stream), onOpenFile)
                }
```

- [ ] **Step 4: Run the reveal tests and the whole autoscroll suite**

Run: `~/streaming-probe/gradle-round.sh :ui:jvmTest --tests 'dev.supermux.ui.chat.LiveRevealTest' --tests 'dev.supermux.ui.chat.ChatAutoscrollTest'`
Expected: BUILD SUCCESSFUL; `LiveRevealTest` 6 passed; `ChatAutoscrollTest` unchanged and all passing. Its live tests end their text with a word and wait for idle, so the pause rule shows the last word.

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/LiveRows.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Timeline.kt apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatPanel.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/LiveRevealTest.kt
git commit -m "feat(apps): the live reply reveals at a steady pace; the stored reply finishes it (#76)"
```

---

### Task 5: The gliding scroll

**Files:**
- Modify: `apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatPanel.kt` (autoscroll `run { }` ~L657-744; helpers near `transcriptAtEnd` ~L178)
- Test: `apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/ChatAutoscrollTest.kt` (append two tests)

- [ ] **Step 1: Write the failing tests** (append; imports `androidx.compose.ui.unit.sp`, `kotlin.math.abs`, `kotlin.math.max`)

```kotlin
    @Test fun a_revealing_reply_is_followed_by_a_glide_not_a_jump() = runComposeUiTest {
        panel()
        state = state.copy(live = listOf(live("Live paragraph one.")))
        waitForIdle()
        val line = with(density) { 20.sp.toPx() }
        mainClock.autoAdvance = false
        state = state.copy(live = listOf(live("Live paragraph one.\n\n" + paragraphs("Live", 3) + "\n\nLive tail.")))
        var prev = topOf("Live paragraph one.") ?: fail("the live reply is not laid out")
        var biggest = 0f
        repeat(150) {
            mainClock.advanceTimeByFrame()
            val now = topOf("Live paragraph one.") ?: return@repeat
            biggest = max(biggest, abs(now - prev))
            prev = now
        }
        mainClock.autoAdvance = true
        waitForIdle()
        assertTrue(onScreen("Live tail."), "the growing end is followed")
        assertTrue(biggest < line * 0.6f, "moved $biggest px in one frame (a line is $line): a jump, not a glide")
    }

    @Test fun a_readers_drag_during_a_glide_wins() = runComposeUiTest {
        panel()
        state = state.copy(live = listOf(live("Live paragraph one.")))
        waitForIdle()
        state = state.copy(live = listOf(live("Live paragraph one.\n\n" + paragraphs("Live", 12) + "\n\nLive tail.")))
        mainClock.advanceTimeBy(200)
        drag(+300f)
        val anchor = readingAnchorIn("Live", 12)
        val before = topOf(anchor) ?: fail("no anchor")
        waitForIdle()
        assertEquals(before, topOf(anchor) ?: Float.NaN, 1f, "the glide pulled the reader back after their drag")
        assertFalse(onScreen("Live tail."), "the reader left the end")
    }

    /** The first paragraph of [label] on screen now. */
    private fun ComposeUiTest.readingAnchorIn(label: String, count: Int): String =
        (1..count).map { "$label paragraph $it:" }.firstOrNull { onScreen(it) } ?: "Live paragraph one."
```

- [ ] **Step 2: Run them to see the first fail**

Run: `~/streaming-probe/gradle-round.sh :ui:jvmTest --tests 'dev.supermux.ui.chat.ChatAutoscrollTest'`
Expected: `a_revealing_reply_is_followed_by_a_glide_not_a_jump` FAILS: a frame moves a whole line, since `pinToEnd` jumps. The drag test may already pass; it guards the glide.

- [ ] **Step 3: Implement**

Helpers next to `transcriptAtEnd`:

```kotlin
/** How far the transcript's end is below the viewport's end, in px: 0 at the end, MAX_VALUE when the last row is off screen. */
private fun distanceToEnd(state: LazyListState): Float {
    if (!state.canScrollForward) return 0f
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return Float.MAX_VALUE
    if (last.index != info.totalItemsCount - 1) return Float.MAX_VALUE
    return (last.offset + last.size + info.afterContentPadding - info.viewportEndOffset).toFloat()
}

/** Card #76: how fast the transcript eases toward a revealing reply's end; past [GLIDE_MAX_PX] it jumps as before. */
private const val GLIDE_TAU_MS = 90.0
private const val GLIDE_MAX_PX = 400f

/**
 * Eases [state] to the transcript's end: each frame covers `1 - exp(-dt / GLIDE_TAU_MS)` of what is left, then it
 * settles exactly there. A reader's drag outranks it (their scroll cancels ours).
 */
private suspend fun glideToEnd(state: LazyListState) {
    var last = withFrameMillis { it }
    while (true) {
        val left = distanceToEnd(state)
        if (left <= 0.5f) break
        if (left > GLIDE_MAX_PX) break
        val now = withFrameMillis { it }
        val dt = (now - last).coerceIn(1L, 64L).toDouble()
        last = now
        state.scrollBy(max(1f, left * (1 - exp(-dt / GLIDE_TAU_MS)).toFloat()))
    }
    if (state.canScrollForward) state.scrollBy(100_000f)
}
```

In the autoscroll `run { }`, after `pinToEnd()`:

```kotlin
        // Card #76: a revealing reply (the live row, or the stored row finishing its reveal) grows a word at a time;
        // while following, the transcript eases after it instead of jumping a line at a time. Reduced motion jumps.
        val reduceMotion = dev.supermux.ui.theme.LocalReduceMotion.current
        val revealingTail = liveRows.lastOrNull()?.let { !it.thinking } == true ||
            (liveRows.isEmpty() && (timelineItems.lastOrNull() as? TimelineItem.Msg)?.entry?.stream?.let { reveals.unfinished(it) } != null)
        val glideTail by rememberUpdatedState(!reduceMotion && revealingTail)
        val glides = remember(session.id) { kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED) }
        LaunchedEffect(session.id, listState) {
            for (request in glides) {
                followScroll = true
                try {
                    glideToEnd(listState)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // The reader took the scroll: theirs wins. Only a real cancellation ends this loop.
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                } finally {
                    followScroll = false
                }
            }
        }
```

And in the grow branch of the `TranscriptTail` collector, replace the pin with:

```kotlin
                if (grew && panelActive && autoFollow && find.current < 0 && listState.canScrollForward) {
                    when {
                        glideTail -> glides.trySend(Unit)
                        listState.isScrollInProgress -> pendingPin = true
                        else -> launch { pinToEnd() }
                    }
                }
```

Imports to add in ChatPanel.kt: `androidx.compose.runtime.withFrameMillis`, `kotlin.math.exp`, `kotlin.math.max` (if not already there), `dev.supermux.chat.TimelineItem` (if not already).

- [ ] **Step 4: Run the whole autoscroll suite**

Run: `~/streaming-probe/gradle-round.sh :ui:jvmTest --tests 'dev.supermux.ui.chat.ChatAutoscrollTest' --tests 'dev.supermux.ui.chat.LiveRevealTest'`
Expected: BUILD SUCCESSFUL, every existing test plus the two new ones passing.

- [ ] **Step 5: Commit**

```bash
git add apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/ChatPanel.kt apps/ui/src/jvmTest/kotlin/dev/supermux/ui/chat/ChatAutoscrollTest.kt
git commit -m "feat(apps): the transcript glides after a revealing reply instead of jumping (#76)"
```

---

### Task 6: Whole suites, and Ahmet's look

- [ ] **Step 1: Run every suite CI runs for these modules**

Run: `xvfb-run -a` is CI's; here: `~/streaming-probe/gradle-round.sh --continue :shared:jvmTest :ui:jvmTest :desktop:test`
Expected: BUILD SUCCESSFUL. Compare any failure against plain `dev` (`/home/ahmet/streaming-probe/dev-wt`) before calling it pre-existing.

- [ ] **Step 2: Watch it once on the box**

Run the desktop app under Xvfb against a scratch broker (the #76 `desktop-e2e.sh` recipe), send "write a long poem for me" to a Claude session, and record 20 s. Check that the words ink in steadily, nothing jumps, and the end lands without a pop.

- [ ] **Step 3: Ahmet's Mac**

Ask #62 (45bdb259) for a slot in `~/work/supermux-hotreload` (theirs until 21:00); run the hot reload from this branch only once the `.lease` names this session. Tell Tally what Ahmet should do and see.

- [ ] **Step 4: Note it on #76 and take the demo down**

`issues comment 76 "…"` with the commits and test counts; `systemctl --user stop flow-demo` and unexpose `streaming-flow` once Ahmet has seen the app.

---

## Addendum (2026-11-05 evening): live replies on every surface

Ahmet tried B3 on his Mac ("ok its good, can be merged to dev") and then asked for it everywhere ("i think it can
be switched on everywhere"), each platform checked before the merge.

- **Gate:** `LIVE_REPLY_SURFACES` = desktop, android, ios, web (`HostStoreLogTailTest` pins the set).
- **Reduced motion:** the shared `HostTheme` takes `reduceMotion` and provides `LocalReduceMotion`
  (`HostThemeReduceMotionTest`). Each platform theme passes its OS answer, kept current while it changes:
  - Android `AndroidTheme`: `Settings.Global.ANIMATOR_DURATION_SCALE == 0` ("Remove animations"), watched by a
    `ContentObserver`; `ReduceMotionTest` covers the rule.
  - iOS `IosTheme`: `UIAccessibilityIsReduceMotionEnabled()`, updated on `UIAccessibilityReduceMotionStatusDidChangeNotification`.
  - Web `WebTheme`: `matchMedia("(prefers-reduced-motion: reduce)")` plus its `change` event.
  - Desktop already asks the OS (Task 2).
- **Checks before the merge:** all run against the isolated scratch broker on :9976, from this branch, with its
  own state, using a Codex chat (the default Claude login was rate-limited). Each records a streamed reply, then
  repeats it with reduced motion on, which must show whole words and no edge:
  - web: headless Chrome, `reducedMotion: "reduce"`;
  - Android: the box emulator (pixel_api35) with the debug APK, paired by `supermux://pair`, then animator scale 0;
  - iOS: the Mac simulator in `~/work/supermux-hotreload` under the lease, paired by `SIMCTL_CHILD_SM_PAIR_*`
    over an SSH reverse tunnel, then Reduce Motion on.
- **Suites:** the broker suite (`bun test --isolate`, typecheck, core), `:shared:jvmTest :ui:jvmTest :desktop:test
  :android:testDebugUnitTest`, the web compile, and iOS on the Mac.
- **Trap:** in this worktree `src/channels/web/static` is a symlink to the main checkout's live client, and
  `:web:stageForBroker` deletes its output wholesale. Replace the link with a real folder before staging, and only
  once the live preview broker no longer serves this worktree.
