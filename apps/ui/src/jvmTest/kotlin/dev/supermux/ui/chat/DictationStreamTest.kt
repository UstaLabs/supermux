package dev.supermux.ui.chat

import dev.supermux.net.DictationStreamEvent
import dev.supermux.net.DictationStreamSocket
import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A mic that hands out raw PCM the test "speaks" into it; [stop] returns everything as one clip. */
private class PcmMic(private val streams: Boolean = true) : MicCapture {
    private var pcm = ByteArray(0)
    var capturing = false
    var stopped = 0
    val loud = MutableStateFlow(0f)
    fun say(vararg bytes: Int) { pcm += ByteArray(bytes.size) { bytes[it].toByte() } }
    override val level: StateFlow<Float> get() = loud
    override fun start(): Boolean { capturing = true; return true }
    override fun stop(): CapturedAudio? {
        capturing = false
        stopped++
        return if (pcm.isEmpty()) null else CapturedAudio(pcm.copyOf(), "rec.wav", "audio/wav")
    }
    override fun cancel() { capturing = false }
    override fun pcmSince(byteOffset: Long): ByteArray? {
        if (!streams || !capturing) return null
        return if (byteOffset >= pcm.size) ByteArray(0) else pcm.copyOfRange(byteOffset.toInt(), pcm.size)
    }
    /** The per-pause path's snapshot (used only when the recording does not stream). */
    override suspend fun snapshot(): CapturedAudio? = CapturedAudio(byteArrayOf(1), "live.wav", "audio/wav")
    override suspend fun requestPermission(): Boolean = true
    override val available: Boolean get() = true
    override val liveTranscript: LiveTranscript? = null
}

/** The broker end of a stream, driven by the test. */
private class FakeSocket : DictationStreamSocket {
    val audio = mutableListOf<Byte>()
    val edits = mutableListOf<Pair<String, Long?>>()
    var stops = 0
    var closed = false
    private val events = Channel<DictationStreamEvent>(Channel.UNLIMITED)
    fun push(ev: DictationStreamEvent) { events.trySend(ev) }
    fun hangUp() { events.close() }
    override suspend fun sendAudio(pcm: ByteArray) { audio += pcm.toList() }
    override suspend fun sendEdit(text: String, basedOnSeq: Long?) { edits += text to basedOnSeq }
    override suspend fun sendStop() { stops++ }
    override suspend fun sendCancel() = Unit
    override suspend fun receive(): DictationStreamEvent? = events.receiveCatching().getOrNull()
    override fun close() { closed = true; events.close() }
}

/**
 * The streamed dictation: audio goes to the broker as it is recorded, the broker's text fills the
 * field, Stop takes its `final`, and anything wrong with the stream falls back to uploading the
 * recording. Virtual time — the 100 ms pump runs on the test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DictationStreamTest {

    /** The composer's field, as the controller sees it: [lag] = writes reach it only on [recompose]. */
    private class Field(val lag: Boolean = false) {
        var text = ""
        private var pending: String? = null
        fun write(t: String) { if (lag) pending = t else text = t }
        fun recompose() { pending?.let { text = it }; pending = null }
    }

    private class Rig(
        val ctrl: DictationController,
        val mic: PcmMic,
        val socket: FakeSocket,
        val field: Field,
        val opened: MutableList<String>,
        val uploads: MutableList<ByteArray>,
        val appended: MutableList<String>,
    )

    private fun TestScope.rig(
        streams: Boolean = true,
        caps: Boolean? = true,
        field: Field = Field(),
        openFails: Boolean = false,
        upload: String? = "uploaded text",
    ): Rig {
        val mic = PcmMic(streams)
        val socket = FakeSocket()
        val opened = mutableListOf<String>()
        val uploads = mutableListOf<ByteArray>()
        val appended = mutableListOf<String>()
        val ctrl = DictationController(mic, TestScope(StandardTestDispatcher(testScheduler)))
        ctrl.streamingAvailable = caps
        ctrl.openDictationStream = { id, session ->
            opened += id
            if (openFails) error("connection refused")
            session(socket)
        }
        ctrl.transcribeAudio = { bytes, _, _ -> uploads += bytes; upload }
        ctrl.onLive = { field.write(it) }
        ctrl.currentText = { field.text }
        ctrl.onAppend = { appended += it; field.write(it) }
        return Rig(ctrl, mic, socket, field, opened, uploads, appended)
    }

    @Test fun audio_goes_out_once_in_order_and_the_brokers_text_fills_the_field() = runTest {
        val r = rig()
        var partials = 0
        var warms = 0
        r.ctrl.transcribePartial = { _ -> partials++; LivePass("x") }
        r.ctrl.transcribeWarm = { warms++ }
        r.ctrl.startMic()
        assertTrue(r.ctrl.recording)
        r.mic.say(1, 2, 3)
        testScheduler.advanceTimeBy(150)
        r.mic.say(4, 5)
        testScheduler.advanceTimeBy(100)
        testScheduler.runCurrent()
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), r.socket.audio)

        r.socket.push(DictationStreamEvent.Text("hello there", seq = 1))
        testScheduler.runCurrent()
        assertEquals("hello there", r.field.text)
        assertEquals("hello there", r.ctrl.liveTranscript)

        // Loud or not, nothing is uploaded per pause and nothing is warmed: the broker does it all.
        r.mic.loud.value = 0.5f
        testScheduler.advanceTimeBy(1_000)
        r.mic.loud.value = 0f
        testScheduler.advanceTimeBy(2_000)
        assertEquals(0, partials)
        assertEquals(0, warms)
        assertEquals(1, r.opened.size)
        r.ctrl.cancelMic()
    }

    @Test fun stop_sends_the_tail_then_stop_and_writes_the_final_text() = runTest {
        val r = rig()
        r.ctrl.startMic()
        r.mic.say(1, 2)
        testScheduler.advanceTimeBy(150)
        r.mic.say(3) // the tail: not pumped yet when Stop comes
        r.ctrl.stopMic()
        assertTrue(r.ctrl.transcribing, "the strip is up on the frame Stop was tapped")
        testScheduler.runCurrent()
        assertEquals(listOf<Byte>(1, 2, 3), r.socket.audio)
        assertEquals(1, r.socket.stops)

        r.socket.push(DictationStreamEvent.Final("hello world"))
        testScheduler.runCurrent()
        assertEquals("hello world", r.field.text)
        assertFalse(r.ctrl.transcribing)
        assertNull(r.ctrl.liveTranscript)
        assertTrue(r.uploads.isEmpty(), "a streamed recording is never uploaded again")
        assertTrue(r.socket.closed)
    }

    @Test fun a_late_spoken_edit_lands_only_over_the_untouched_final_text() = runTest {
        val r = rig()
        r.ctrl.startMic()
        r.ctrl.stopMic()
        testScheduler.runCurrent()
        r.socket.push(DictationStreamEvent.Final("one. two.", pendingEdit = true))
        testScheduler.runCurrent()
        assertEquals("one. two.", r.field.text)
        assertFalse(r.ctrl.transcribing, "Stop does not wait for the edit check")

        r.socket.push(DictationStreamEvent.Text("one."))
        testScheduler.runCurrent()
        assertEquals("one.", r.field.text)

        // The user changes the text; a second late edit must not overwrite their change.
        r.field.text = "one. mine"
        r.socket.push(DictationStreamEvent.Text("nothing"))
        testScheduler.runCurrent()
        assertEquals("one. mine", r.field.text)

        r.socket.push(DictationStreamEvent.Done)
        testScheduler.runCurrent()
        assertTrue(r.socket.closed)
    }

    @Test fun the_late_edit_window_closes_on_its_own() = runTest {
        val r = rig()
        r.ctrl.startMic()
        r.ctrl.stopMic()
        testScheduler.runCurrent()
        r.socket.push(DictationStreamEvent.Final("text", pendingEdit = true))
        testScheduler.runCurrent()
        assertFalse(r.socket.closed)
        testScheduler.advanceTimeBy(8_100)
        testScheduler.runCurrent()
        assertTrue(r.socket.closed)
    }

    @Test fun a_fatal_error_falls_back_to_uploading_the_recording() = runTest {
        val r = rig()
        r.ctrl.startMic()
        r.mic.say(7, 8)
        testScheduler.advanceTimeBy(150)
        r.socket.push(DictationStreamEvent.Text("partial words"))
        r.socket.push(DictationStreamEvent.Error("speech detection failed", fatal = true))
        testScheduler.runCurrent()
        r.ctrl.stopMic()
        testScheduler.runCurrent()
        assertEquals(1, r.uploads.size)
        assertContentEquals(byteArrayOf(7, 8), r.uploads[0])
        assertEquals(listOf("uploaded text"), r.appended)
        assertFalse(r.ctrl.transcribing)
    }

    @Test fun a_stream_that_never_answers_falls_back_after_the_timeout() = runTest {
        val r = rig()
        r.ctrl.startMic()
        r.mic.say(1)
        r.ctrl.stopMic()
        testScheduler.advanceTimeBy(14_000)
        testScheduler.runCurrent()
        assertTrue(r.uploads.isEmpty())
        assertTrue(r.ctrl.transcribing)
        testScheduler.advanceTimeBy(1_100)
        testScheduler.runCurrent()
        assertEquals(1, r.uploads.size)
        assertFalse(r.ctrl.transcribing)
    }

    @Test fun a_socket_that_cannot_open_falls_back_at_once() = runTest {
        val r = rig(openFails = true)
        r.ctrl.startMic()
        r.mic.say(1, 2)
        testScheduler.advanceTimeBy(300)
        r.ctrl.stopMic()
        testScheduler.runCurrent()
        assertEquals(1, r.uploads.size, "no 15 s wait when the stream is already known dead")
        assertEquals(listOf("uploaded text"), r.appended)
    }

    @Test fun a_failed_upload_keeps_what_the_stream_already_showed() = runTest {
        val r = rig(upload = null)
        r.ctrl.startMic()
        r.mic.say(1)
        testScheduler.advanceTimeBy(150)
        r.socket.push(DictationStreamEvent.Text("kept words"))
        testScheduler.runCurrent()
        r.socket.hangUp()
        testScheduler.runCurrent()
        r.ctrl.stopMic()
        testScheduler.runCurrent()
        assertEquals(listOf("kept words"), r.appended)
    }

    @Test fun a_hand_edit_is_sent_once_it_settles() = runTest {
        val r = rig()
        r.ctrl.startMic()
        r.socket.push(DictationStreamEvent.Text("hello world", seq = 4))
        testScheduler.runCurrent()
        assertEquals("hello world", r.field.text)

        r.field.text = "hello there"
        testScheduler.advanceTimeBy(150)
        assertTrue(r.socket.edits.isEmpty(), "not before it settles")
        testScheduler.advanceTimeBy(400)
        testScheduler.runCurrent()
        assertEquals(listOf<Pair<String, Long?>>("hello there" to 4L), r.socket.edits)

        // The broker carries on from the user's version.
        r.socket.push(DictationStreamEvent.Text("hello there and more", seq = 5))
        testScheduler.runCurrent()
        assertEquals("hello there and more", r.field.text)
        testScheduler.advanceTimeBy(1_000)
        assertEquals(1, r.socket.edits.size)
        r.ctrl.cancelMic()
    }

    @Test fun the_users_typing_wins_over_a_broker_text_that_arrives_meanwhile() = runTest {
        val r = rig()
        r.ctrl.startMic()
        r.socket.push(DictationStreamEvent.Text("one", seq = 1))
        testScheduler.runCurrent()
        r.field.text = "one typed"
        r.socket.push(DictationStreamEvent.Text("one two", seq = 2))
        testScheduler.runCurrent()
        assertEquals("one typed", r.field.text)
        assertEquals(listOf<Pair<String, Long?>>("one typed" to 1L), r.socket.edits)
        r.ctrl.cancelMic()
    }

    @Test fun our_own_write_on_its_way_to_the_field_is_not_a_hand_edit() = runTest {
        val field = Field(lag = true)
        val r = rig(field = field)
        r.ctrl.startMic()
        r.socket.push(DictationStreamEvent.Text("first", seq = 1))
        testScheduler.runCurrent()
        assertEquals("", field.text, "not recomposed yet")
        // The field lags for a long while (an app in the background stops recomposing).
        testScheduler.advanceTimeBy(5_000)
        r.socket.push(DictationStreamEvent.Text("first second", seq = 2))
        testScheduler.runCurrent()
        assertTrue(r.socket.edits.isEmpty())
        field.recompose()
        testScheduler.advanceTimeBy(1_000)
        assertEquals("first second", field.text)
        assertTrue(r.socket.edits.isEmpty())
        r.ctrl.cancelMic()
    }

    @Test fun nothing_said_shows_didnt_catch_that() = runTest {
        val r = rig()
        r.ctrl.startMic()
        r.ctrl.stopMic()
        testScheduler.runCurrent()
        r.socket.push(DictationStreamEvent.Final(""))
        testScheduler.runCurrent()
        assertEquals("Didn't catch that", r.ctrl.banner)
        assertTrue(r.uploads.isEmpty())
    }

    @Test fun cancel_closes_the_stream_and_writes_nothing_more() = runTest {
        val r = rig()
        r.ctrl.startMic()
        r.socket.push(DictationStreamEvent.Text("words"))
        testScheduler.runCurrent()
        r.ctrl.cancelMic()
        testScheduler.runCurrent()
        assertTrue(r.socket.closed)
        assertEquals(0, r.socket.stops)
        assertNull(r.ctrl.liveTranscript)
        assertTrue(r.uploads.isEmpty())
    }

    @Test fun without_the_brokers_say_so_the_per_pause_uploads_run() = runTest {
        for (caps in listOf<Boolean?>(false, null)) {
            val r = rig(caps = caps)
            var partials = 0
            r.ctrl.transcribePartial = { _ -> partials++; LivePass("legacy") }
            r.ctrl.startMic()
            r.mic.loud.value = 0.3f
            testScheduler.advanceTimeBy(1_000)
            r.mic.loud.value = 0f
            testScheduler.advanceTimeBy(800)
            testScheduler.runCurrent()
            assertTrue(r.opened.isEmpty())
            assertEquals(1, partials, "caps=$caps")
            r.ctrl.cancelMic()
        }
    }

    @Test fun a_mic_without_raw_pcm_keeps_the_per_pause_uploads() = runTest {
        val r = rig(streams = false)
        r.ctrl.startMic()
        testScheduler.advanceTimeBy(500)
        assertTrue(r.opened.isEmpty())
        r.ctrl.cancelMic()
    }

    @Test fun a_new_recording_closes_the_previous_streams_late_edit_window() = runTest {
        val r = rig()
        r.ctrl.startMic()
        r.ctrl.stopMic()
        testScheduler.runCurrent()
        r.socket.push(DictationStreamEvent.Final("first", pendingEdit = true))
        testScheduler.runCurrent()
        assertFalse(r.socket.closed)
        r.ctrl.startMic()
        testScheduler.runCurrent()
        assertTrue(r.socket.closed)
        assertEquals(2, r.opened.size)
        assertTrue(r.opened[0] != r.opened[1], "each recording has its own dictation id")
        r.ctrl.cancelMic()
    }
}
