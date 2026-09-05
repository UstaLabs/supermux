package dev.supermux.ui.platform

import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.ui.editor.engine.EditorEngineFactory
import dev.supermux.ui.editor.engine.UnavailableEditorEngineFactory
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.NoHaptics
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * A recording [Platform] — the pattern every screen test uses to assert a platform call.
 *
 * Lives in its own file (rather than private to one test) because every shared screen that reaches
 * for the platform needs one; `caps` and [qrResult] are constructor knobs so a test can say "this
 * machine has a camera and the user scanned X" in one line.
 */
internal open class FakePlatform(
    override val caps: Caps = NO_CAPS,
    override val haptics: Haptics = NoHaptics,
    /** What [scanQr] hands back; null = the user cancelled (or there is no camera). */
    var qrResult: String? = null,
    /** What [pickFiles] hands back; the default is one small text file. */
    var pickResult: List<PickedFile>? = null,
    /** The editor seam. Defaults to "this machine has no browser", which is what every screen test
     *  that never opens an editor wants; an editor test passes its own recording factory. */
    override val editorEngine: EditorEngineFactory = UnavailableEditorEngineFactory("no engine under test"),
) : Platform {
    val openedUrls = mutableListOf<String>()
    val copied = mutableListOf<String>()
    var pickedKind: PickKind? = null
    var pickedRequester: String? = null
    var scans = 0
    var captures = mutableListOf<String>()
    /** What [captureImage]/[captureVideo] hand back; null = the user backed out. */
    var captureResult: PickedFile? = null

    override fun openUrl(url: String) { openedUrls.add(url) }
    override fun copyToClipboard(text: String) { copied.add(text) }
    override suspend fun pickFiles(kind: PickKind, requester: String): List<PickedFile> {
        pickedKind = kind
        pickedRequester = requester
        return pickResult ?: listOf(PickedFile("a.txt", "text/plain", ByteArrayChunkSource(byteArrayOf(1, 2))))
    }
    override suspend fun scanQr(): String? {
        scans++
        return qrResult
    }

    override suspend fun captureImage(requester: String): PickedFile? {
        captures.add("image:$requester")
        return captureResult
    }

    override suspend fun captureVideo(requester: String): PickedFile? {
        captures.add("video:$requester")
        return captureResult
    }

    /** Push a [PickedFile] here to simulate a pick that outlived its caller (Android recreation). */
    val pendingPicks = MutableSharedFlow<PickedFile>(extraBufferCapacity = 8)

    override fun pendingPicks(requester: String): Flow<PickedFile> = pendingPicks.asSharedFlow()

    override val clipboard: FakeClipboard = FakeClipboard()
    override val files: FakeFiles = FakeFiles()
    override var mic: MicCapture = FakeMic()
    override val tts: FakeTts = FakeTts()
    override val notices: FakeNotices = FakeNotices()
}

/** Recording [ClipboardAccess]: set [images], assert [reads]. */
internal class FakeClipboard(
    var images: List<PickedFile> = emptyList(),
) : ClipboardAccess {
    var reads = 0
    var probes = 0

    /** Runs before [readImages] returns — a gate for "pending chip while the decode is slow". */
    var beforeRead: (() -> Unit)? = null

    override suspend fun readImages(): List<PickedFile> {
        reads++
        beforeRead?.invoke()
        return images
    }

    override fun hasImage(): Boolean {
        probes++
        return images.isNotEmpty()
    }
}

/** Recording [FileAccess]. [saved]/[opened] hold `name|mime|byteCount` so a test asserts one line. */
internal class FakeFiles(
    var saveResult: Boolean = true,
    var openResult: Boolean = true,
) : FileAccess {
    val saved = mutableListOf<String>()
    val opened = mutableListOf<String>()

    /** [SavedFile]s handed back to [openSaved] — proves the chip opens what the user SAVED. */
    val openedSaved = mutableListOf<SavedFile>()

    override suspend fun saveAs(name: String, mime: String, bytes: ByteArray): SavedFile? {
        saved.add("$name|$mime|${bytes.size}")
        return if (saveResult) SavedFile(name, "/fake/$name") else null
    }

    override suspend fun openSaved(saved: SavedFile): Boolean {
        openedSaved.add(saved)
        return openResult
    }

    override suspend fun openExternally(name: String, mime: String, bytes: ByteArray): Boolean {
        opened.add("$name|$mime|${bytes.size}")
        return openResult
    }

    override fun probeMime(name: String): String =
        if (name.endsWith(".png")) "image/png" else "application/octet-stream"

    /** Names handed to [stageTemp], and the URI each got back. */
    val stagedNames = mutableListOf<String>()
    val stagedBytes = mutableListOf<Int>()
    /** Set to make staging fail, as a full disk or a locked cache dir would. */
    var stageFails: Boolean = false

    override suspend fun stageTemp(name: String, bytes: ByteArray): String? {
        stagedNames.add(name)
        stagedBytes.add(bytes.size)
        return if (stageFails) null else "file:///fake/$name"
    }
}

/** Recording [MicCapture]: drive it through [startResult]/[audio] and assert [events]. */
internal class FakeMic(
    override val available: Boolean = true,
    override val liveTranscript: LiveTranscript? = null,
    var startResult: Boolean = true,
    var audio: CapturedAudio? = CapturedAudio(byteArrayOf(1, 2, 3), "d.wav", "audio/wav"),
    var permission: Boolean = true,
) : MicCapture {
    val events = mutableListOf<String>()

    override fun start(): Boolean {
        events.add("start")
        return startResult
    }

    override fun stop(): CapturedAudio? {
        events.add("stop")
        return audio
    }

    override fun cancel() { events.add("cancel") }

    override suspend fun requestPermission(): Boolean {
        events.add("permission")
        return permission
    }
}

/** Recording [LiveTranscript], for the on-device-STT branch of the dictation UI. */
internal class FakeLiveTranscript(private var text: String = "hello") : LiveTranscript {
    private val _partial = MutableStateFlow("")
    override val partial: StateFlow<String> = _partial
    val events = mutableListOf<String>()

    /** Push a partial as the recognizer would. */
    fun emit(value: String) { _partial.value = value }

    override fun start(glossary: List<String>): Boolean {
        events.add("start:${glossary.joinToString(",")}")
        return true
    }

    override fun stop(): String {
        events.add("stop")
        return text
    }

    override fun cancel() { events.add("cancel") }
}

/** Recording [TtsEngine]: [spoken] is what was read aloud, [stops] how often it was silenced. */
internal class FakeTts : TtsEngine {
    val spoken = mutableListOf<String>()
    val chunks = mutableListOf<Int>()
    var stops = 0
    var shutdowns = 0

    override suspend fun speak(text: String) { spoken.add(text) }
    override suspend fun playAudioChunk(bytes: ByteArray) { chunks.add(bytes.size) }
    override fun stop() { stops++ }
    override fun shutdown() { shutdowns++ }
}

/** Recording [NoticeChannel]: assert [shown] for the "that didn't work" line. */
internal class FakeNotices : NoticeChannel {
    val shown = mutableListOf<String>()
    override fun show(text: String) { shown.add(text) }
}

/** A machine that can do nothing — the default, so a test opts INTO each capability it exercises. */
internal val NO_CAPS = Caps(
    push = false,
    camera = false,
    tray = false,
    externalDisplay = false,
    hardwareVideoDecode = false,
    localBroker = false,
    multiWindow = false,
    fileSystem = false,
    clipboardImages = false,
    saveAs = false,
)
