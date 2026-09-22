package dev.supermux.terminal.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.terminal.TerminalEffect
import dev.supermux.terminal.TerminalEngine
import dev.supermux.terminal.TerminalKey
import dev.supermux.terminal.TerminalLimits
import dev.supermux.terminal.TerminalMouse
import dev.supermux.terminal.TerminalSession
import dev.supermux.terminal.TerminalSize
import dev.supermux.terminal.createTerminalEngine
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The byte recorder: what the program on the other end of the pty would actually read.
 *
 * `TerminalEffect.Input` is the engine's encoded user input — the ONLY thing a key press, a mouse
 * event or a paste ever produces — so a test that asserts on these bytes is asserting on the wire,
 * not on the calls this package happens to make. The engine behind it is the REAL pinned Ghostty
 * (`createTerminalEngine`), so "zero bytes" means the program really heard nothing and
 * `ESC[<65;3;2M` really is what SGR mouse reporting puts on the wire.
 *
 * Effects are delivered on the session's owner coroutine; the list is synchronized for the test
 * thread that reads it.
 */
internal class ByteRecorder {
    private val chunks: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())

    fun record(effect: TerminalEffect) {
        if (effect is TerminalEffect.Input) chunks += effect.bytes
    }

    fun bytes(): ByteArray = synchronized(chunks) {
        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var at = 0
        for (chunk in chunks) {
            chunk.copyInto(out, at)
            at += chunk.size
        }
        out
    }

    /** The recorded bytes as text, with ESC shown as `<ESC>` so a failure message is readable. */
    fun text(): String = bytes().decodeToString().replace("\u001b", "<ESC>")

    fun clear() {
        chunks.clear()
    }
}

/** Counts the engine calls the surface makes, in front of a real engine. */
internal class CountingEngine(private val delegate: TerminalEngine) : TerminalEngine by delegate {
    val keyCalls = AtomicInteger()
    val mouseCalls = AtomicInteger()
    val pasteCalls = AtomicInteger()

    // Copy-on-write: the owner coroutine appends while the test thread iterates.

    /** Every key the surface handed the engine, in order: physical code, text, modifiers, action. */
    val keys: MutableList<TerminalKey> = CopyOnWriteArrayList()

    /** Every mouse event the surface handed the engine, in order. */
    val mice: MutableList<TerminalMouse> = CopyOnWriteArrayList()

    override fun key(key: TerminalKey) {
        keyCalls.incrementAndGet()
        keys += key
        delegate.key(key)
    }

    override fun mouse(mouse: TerminalMouse) {
        mouseCalls.incrementAndGet()
        mice += mouse
        delegate.mouse(mouse)
    }

    override fun paste(text: String, allowUnsafe: Boolean): Boolean {
        pasteCalls.incrementAndGet()
        return delegate.paste(text, allowUnsafe)
    }
}

/**
 * A clipboard that records instead of touching the host's.
 *
 * A test that asserted on the real system clipboard would fight every other process on the machine
 * — and on CI there may not be one at all.
 */
internal class RecordingClipboard(@Volatile var content: String? = null) : TerminalClipboard {
    val writes: MutableList<String> = CopyOnWriteArrayList()
    val reads = AtomicInteger()

    override suspend fun read(): String? {
        reads.incrementAndGet()
        return content
    }

    override suspend fun write(text: String) {
        writes += text
        content = text
    }
}

/** One live [Terminal] on one real session, plus everything a test needs to poke at it. */
internal class InputFixture(
    val session: TerminalSession,
    val engine: CountingEngine,
    val recorder: ByteRecorder,
    val accessories: TerminalAccessoryState,
    val scroll: ScrollController,
    val links: MutableList<String>,
    val clipboard: RecordingClipboard,
    val clipboardRequests: MutableList<TerminalEffect.ClipboardRequest>,
    val relay: TerminalEffectRelay,
) {
    /** The selection the ENGINE holds, which is the only one that is ever drawn. */
    fun selection(): dev.supermux.terminal.TerminalSelection? = session.viewports.value.selection

    /** The selected text, straight from the engine (wraps and graphemes included). */
    fun selectedText(): String = runBlocking { session.selectedText() }

    val columns: Int get() = session.viewports.value.size.columns
    val rows: Int get() = session.viewports.value.size.rows
    private val cellWidth: Float get() = session.viewports.value.size.cellWidthPx.toFloat()
    private val cellHeight: Float get() = session.viewports.value.size.cellHeightPx.toFloat()

    /** The centre of viewport cell ([column], [row]) in the surface's own pixels. */
    fun centreOf(column: Int, row: Int): Offset =
        Offset(column * cellWidth + cellWidth / 2f, row * cellHeight + cellHeight / 2f)

    /** Program output, through the real session path. */
    fun feed(text: String) {
        runBlocking { session.receive(text.encodeToByteArray()) }
    }

    fun recorded(): String = recorder.text()

    fun assertSilence(what: String) {
        assertEquals("", recorded(), "$what reached the program")
    }
}

internal const val INPUT_TAG = "terminal-input"
internal const val INPUT_TIMEOUT = 10_000L

/**
 * A real [Terminal], on a real session, on the real engine, with every byte of user input recorded.
 *
 * The surface is driven through Compose's own test harness — the same composable, the same modifier
 * chain, the same pointer and key dispatch a device would use — because the routing this suite is
 * about (who consumes a gesture, in which pass, and what the engine then encodes) only exists once
 * all of those are in the picture.
 */
@OptIn(ExperimentalTestApi::class)
internal fun terminalInputTest(
    focused: Boolean = true,
    limits: TerminalLimits = TerminalLimits(),
    body: ComposeUiTest.(InputFixture) -> Unit,
) = runComposeUiTest {
    val recorder = ByteRecorder()
    val relay = TerminalEffectRelay()
    var engine: CountingEngine? = null
    val session = runBlocking {
        TerminalSession.open(
            size = TerminalSize(40, 10, 8, 16),
            limits = limits,
            effects = relay.wrap(recorder::record),
            engineFactory = { size, engineLimits ->
                CountingEngine(createTerminalEngine(size, engineLimits)).also { engine = it }
            },
        )
    }
    try {
        var scroll: ScrollController? = null
        val accessories = TerminalAccessoryState()
        val links = mutableListOf<String>()
        val clipboard = RecordingClipboard()
        val requests = CopyOnWriteArrayList<TerminalEffect.ClipboardRequest>()
        setContent {
            CompositionLocalProvider(LocalTerminalEffects provides relay) {
                Box(Modifier.size(400.dp, 300.dp)) {
                    Terminal(
                        session = session,
                        modifier = Modifier.fillMaxSize().testTag(INPUT_TAG),
                        accessories = accessories,
                        clipboard = clipboard,
                        onLink = { links += it },
                        onClipboard = { requests += it },
                    ) {
                        scroll = LocalTerminalScroll.current
                    }
                }
            }
        }
        waitForIdle()
        if (focused) {
            onNodeWithTag(INPUT_TAG).requestFocus()
            waitForIdle()
        }
        // Focus reporting (mode 1004 is off) and the first frame are not input; start from silence.
        recorder.clear()
        body(
            InputFixture(
                session = session,
                engine = assertNotNull(engine, "the fixture engine was never built"),
                recorder = recorder,
                accessories = accessories,
                scroll = assertNotNull(scroll, "Terminal did not publish its scroll controller"),
                links = links,
                clipboard = clipboard,
                clipboardRequests = requests,
                relay = relay,
            ),
        )
    } finally {
        runBlocking { session.close() }
    }
}

/**
 * Turn mouse reporting on the way a program does: SGR encoding (1006) over button-event tracking
 * (1002, the mode an editor or a pager sets when it wants drags as well as clicks).
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.enableSgrMouse(fixture: InputFixture, alternateScreen: Boolean = false) {
    if (alternateScreen) fixture.feed("\u001b[?1049h")
    fixture.feed("\u001b[?1002h\u001b[?1006h")
    waitUntil(timeoutMillis = INPUT_TIMEOUT) {
        val modes = fixture.session.viewports.value.modes
        modes.mouseTracking && modes.alternateScreen == alternateScreen
    }
    waitForIdle()
    fixture.recorder.clear()
}

/**
 * Put the terminal on the ALTERNATE screen with mouse tracking off, the way a pager does, and say
 * whether alternate scroll (1007) should be on. 1007 is on by default, so "off" takes a sequence.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.enterAlternateScreen(fixture: InputFixture, alternateScroll: Boolean = true) {
    fixture.feed(if (alternateScroll) "\u001b[?1049h" else "\u001b[?1049h\u001b[?1007l")
    waitUntil(timeoutMillis = INPUT_TIMEOUT) {
        val modes = fixture.session.viewports.value.modes
        modes.alternateScreen && modes.alternateScroll == alternateScroll && !modes.mouseTracking
    }
    waitForIdle()
    fixture.recorder.clear()
}
