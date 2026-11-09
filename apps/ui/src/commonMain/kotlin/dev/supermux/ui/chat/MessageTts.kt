package dev.supermux.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.chat.plainTextForSpeech
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.platform.TtsEngine
import dev.supermux.work.AudioItem
import dev.supermux.work.AudioPlayer
import dev.supermux.work.DownloadOrigin
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Process-wide read-aloud STATE: which message is speaking, and whether to use the platform voice
 * or ChatGPT (codex) through the broker's `/speak` stream. [resolveEngine] / [speakRemoteStream]
 * are wired from each host's connection layer once a host is connected.
 *
 * The noise itself is [TtsEngine] (`Platform.tts`) — D1 moved `TextToSpeech`/`MediaPlayer` and
 * desktop's `ProcessBuilder` player behind that seam; this is pure state + sequencing, and D3
 * merged the two apps' byte-identical copies into this one.
 *
 * Deliberately an `object`: read-aloud must survive an Android activity recreation (rotate while a
 * message is being read and the SAME state has to be able to stop it), so this is process-scoped,
 * not remembered per composition. It is only ever touched from a Compose frame or from a coroutine
 * on [scope], so `speakingKey` needs no extra thread marshalling.
 *
 * In a fleet the reading is the app's [AudioPlayer]'s item (#118 car 2): "Read aloud · <its first words>" with its chat,
 * one thing playing at a time. The OS voice and the chunk stream make their own sound, so the player can only stop
 * them ([AudioPlayer.playOwn]); the codex voice's progressive URL plays in the player's own output, with ❚❚, seek and
 * speed. Either way the player's ✕ or its next item ends the reading, and [speakingKey] follows.
 */
object MessageTts {
    private val gen = atomic(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Returns "platform" | "codex" (default platform). */
    var resolveEngine: (suspend () -> String)? = null

    /**
     * POST /speak NDJSON stream. Invokes `onChunk` for each audio piece as it arrives.
     * Required for the codex engine.
     */
    var speakRemoteStream: (suspend (text: String, onChunk: (ByteArray) -> Unit) -> Unit)? = null

    /**
     * POST /speak/start → URL of one progressive MP3, or null on a broker without the route. Used
     * instead of [speakRemoteStream] wherever the engine [TtsEngine.canPlayUrl]: the media player
     * starts on the first bytes rather than waiting for each piece to be fully synthesized.
     */
    var speakRemoteUrl: (suspend (text: String) -> String?)? = null

    var speakingKey by mutableStateOf<String?>(null)
        private set

    /** The player the last reading went to, so [stop] (the app going to the background) stops it there too. */
    private var player: AudioPlayer? = null

    fun isSpeaking(textKey: String): Boolean = speakingKey == textKey

    fun toggle(
        tts: TtsEngine,
        rawText: String,
        player: dev.supermux.work.AudioPlayer? = null,
        origin: dev.supermux.work.DownloadOrigin? = null,
        chat: String? = null,
    ) {
        val plain = plainTextForSpeech(rawText)
        if (plain.isBlank()) return
        if (speakingKey == plain) {
            stop(tts)
            return
        }
        tts.prepareForTap()
        if (player != null) this.player = player
        val item = player?.let { AudioItem(readAloudTitle(plain), source = "", origin = origin, chat = chat, key = READ_ALOUD_KEY + plain) }
        val at = player?.let { p -> item?.let { Reading(p, it) } }
        scope.launch {
            val eng = runCatching { resolveEngine?.invoke() }.getOrNull()?.ifBlank { null } ?: "platform"
            if (eng == "codex" && speakRemoteStream != null) speakCodex(tts, plain, rawText, at)
            else speakPlatform(tts, plain, at)
        }
    }

    fun stop(tts: TtsEngine) {
        gen.incrementAndGet()
        tts.stop()
        speakingKey = null
        // The player's reading goes with it; an attachment playing there is not ours to stop.
        player?.let { p -> if (p.state.value?.item?.key?.startsWith(READ_ALOUD_KEY) == true) p.stop() }
    }

    /** Release the underlying engine — the app is going away, not just this message. */
    fun shutdown(tts: TtsEngine) {
        stop(tts)
        tts.shutdown()
    }

    /** Stream the broker's audio chunks into [tts] in arrival order; a newer [gen] abandons them. */
    private suspend fun speakCodex(tts: TtsEngine, plain: String, rawText: String, at: Reading?) {
        val remote = speakRemoteStream ?: return speakPlatform(tts, plain, at)
        // Silence whatever is speaking BEFORE starting the next one. Android's TextToSpeech
        // QUEUE_FLUSH makes this look redundant; desktop's engine is a child `say`/`ffplay`
        // process that keeps running until it is killed, so without this two messages overlap.
        stop(tts)
        val g = gen.incrementAndGet()
        speakingKey = plain
        val urlFor = speakRemoteUrl
        val inPlayer = at != null && at.player.hasOutputs
        if ((tts.canPlayUrl || inPlayer) && urlFor != null) {
            // A failed start (older broker: null; anything else: thrown) falls through to the
            // chunk stream below rather than leaving the message silent.
            val url = runCatching { urlFor(rawText) }.getOrNull()
            if (url != null) {
                if (inPlayer) {
                    // The player's own output: ❚❚, seek and speed as an attachment. It says when it is gone.
                    if (gen.value == g) at!!.player.play(at.item.copy(source = url)) { if (gen.value == g) speakingKey = null }
                    return
                }
                val ended = at?.let { ownSound(tts, it, g) }
                try {
                    if (gen.value == g) tts.playUrl(url)
                } finally {
                    ended?.invoke()
                    if (gen.value == g) speakingKey = null
                }
                return
            }
        }
        val ended = at?.let { ownSound(tts, it, g) }
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        val producer = scope.launch(Dispatchers.Default) {
            try {
                // trySend is non-suspending; the channel is unlimited so it cannot fail.
                remote(rawText) { bytes -> if (gen.value == g) queue.trySend(bytes) }
            } catch (_: Exception) {
                // The consumer sees the close and clears the speaking marker.
            } finally {
                queue.close()
            }
        }
        try {
            for (bytes in queue) {
                if (gen.value != g) break
                tts.playAudioChunk(bytes)
            }
        } finally {
            producer.cancel()
            queue.close()
            ended?.invoke()
            if (gen.value == g) speakingKey = null
        }
    }

    private suspend fun speakPlatform(tts: TtsEngine, plain: String, at: Reading?) {
        // See speakCodex: stop the previous utterance first, or desktop overlaps two processes.
        stop(tts)
        val g = gen.incrementAndGet()
        speakingKey = plain
        val ended = at?.let { ownSound(tts, it, g) }
        try {
            tts.speak(plain)
        } finally {
            ended?.invoke()
            if (gen.value == g) speakingKey = null
        }
    }

    /** The engine's own sound as the player's item, ✕ only: the player's ✕ or next item silences reading [g]. */
    private fun ownSound(tts: TtsEngine, at: Reading, g: Int): () -> Unit =
        at.player.playOwn(at.item) { if (gen.value == g) stop(tts) }

    /** Where a reading shows: the fleet's player, as [item]. */
    private class Reading(val player: AudioPlayer, val item: AudioItem)

    /** The player item's key for a reading: this prefix and the text read. */
    internal const val READ_ALOUD_KEY = "read-aloud:"

    /** "Read aloud · " and the message's first words, short enough for the dock's one line. */
    internal fun readAloudTitle(plain: String): String {
        val words = plain.trim().split(Regex("\\s+"))
        val shown = StringBuilder()
        var all = true
        for ((i, w) in words.withIndex()) {
            if (i >= 6 || shown.length + w.length + 1 > 32) {
                all = false
                break
            }
            if (shown.isNotEmpty()) shown.append(' ')
            shown.append(w)
        }
        if (shown.isEmpty()) {
            shown.append(words.first().take(30))
            all = words.size == 1 && words.first().length <= 30
        }
        return "Read aloud · " + shown + if (all) "" else "…"
    }
}

/**
 * The timeline's read-aloud control, over the process-wide [MessageTts] and `Platform.tts`.
 *
 * D2's per-app read-aloud adapters are gone with `MessageTts`'s move: the meta row builds
 * this straight from `LocalPlatform`. The "Nothing to read" notice (Android's toast, desktop's
 * snackbar) is what both hosts already did for a message with no speakable text.
 */
class PlatformReadAloud(
    private val platform: Platform,
    /** The fleet's player (#118 car 2): the reading is its item, from [origin] in [chat]. Null: the engine alone. */
    private val player: AudioPlayer? = null,
    private val origin: DownloadOrigin? = null,
    private val chat: String? = null,
) : ReadAloud {
    override fun isSpeaking(text: String): Boolean = MessageTts.isSpeaking(plainTextForSpeech(text))

    override fun toggle(text: String) {
        if (plainTextForSpeech(text).isBlank()) {
            platform.notices.show("Nothing to read")
            return
        }
        MessageTts.toggle(platform.tts, text, player, origin, chat)
    }

    override val available: Boolean get() = true
}
