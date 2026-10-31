package dev.supermux.net

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Live dictation over one WebSocket (`/transcribe/stream`), the client half.
 *
 * The app streams the raw audio as it records; the broker hears the pauses (Silero VAD),
 * transcribes each sentence as the speaker finishes it, and OWNS the dictated text — after every
 * change it sends the complete text so far, which replaces everything the dictation has written.
 * The broker half is `src/core/transcription/stream/dictation-stream.ts`.
 *
 * Wire (2026-10-31):
 *  - app → broker: BINARY frames are raw 16 kHz mono s16le PCM, any chunk size; TEXT frames are
 *    `{"type":"edit","text":…}` (the user changed the dictated text by hand — their version wins),
 *    `{"type":"stop"}` and `{"type":"cancel"}`.
 *  - broker → app: `{"type":"text","text":…}`, `{"type":"final","text":…,"pendingEdit"?:true}`
 *    (Stop's answer; with `pendingEdit` a late `text` may follow), `{"type":"done"}`, and
 *    `{"type":"error","message":…,"fatal":…}`.
 *
 * Why it exists: the app used to judge pauses by loudness and re-upload the whole recording at
 * each one. In ~1 recording in 6 it heard no pause at all in the first 6 s, and 785 MB went up
 * for 133 MB of audio. Streaming sends each sample once and lets one speech model decide, the same
 * way for every app.
 */
sealed interface DictationStreamEvent {
    /** The complete dictated text so far. [seq] = the broker's number for it, when it sends one. */
    data class Text(val text: String, val seq: Long? = null) : DictationStreamEvent

    /** Stop's answer. [pendingEdit] = a spoken-edit check is still out; a [Text] may follow. */
    data class Final(val text: String, val pendingEdit: Boolean = false) : DictationStreamEvent

    /** Nothing more will come. */
    data object Done : DictationStreamEvent

    /** [fatal] = the stream cannot go on; the app falls back to uploading its own recording. */
    data class Error(val message: String, val fatal: Boolean) : DictationStreamEvent
}

/** One open dictation stream. ONE receiver; sends may come from one coroutine at a time. */
interface DictationStreamSocket {
    /** Raw 16 kHz mono s16le audio, in recording order. */
    suspend fun sendAudio(pcm: ByteArray)

    /** The user's own version of the dictated text. [basedOnSeq] = the last broker text they were
     *  shown, so a broker that numbers its texts knows which audio that version covers. */
    suspend fun sendEdit(text: String, basedOnSeq: Long? = null)

    suspend fun sendStop()

    suspend fun sendCancel()

    /** The next event, or null once the socket closed. Frames this client does not know are skipped. */
    suspend fun receive(): DictationStreamEvent?

    /** Give up on the socket from anywhere, without suspending; [receive] then returns null. */
    fun close()
}

/** `ws(s)://host/transcribe/stream?dictationId=…[&session=…]` for a broker [baseUrl] (http(s) or ws(s)). */
fun dictationStreamUrl(baseUrl: String, dictationId: String, sessionId: String?): String {
    val url = StringBuilder("${wsBaseUrl(baseUrl).trimEnd('/')}/transcribe/stream?dictationId=${urlParam(dictationId)}")
    if (!sessionId.isNullOrBlank()) url.append("&session=").append(urlParam(sessionId))
    return url.toString()
}

private val streamJson = Json { ignoreUnknownKeys = true }

/** One broker → app text frame, or null when it is not one this client knows. */
fun decodeDictationStreamEvent(frame: String): DictationStreamEvent? {
    val o = runCatching { streamJson.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return null
    fun str(key: String): String? = (o[key] as? JsonPrimitive)?.contentOrNull
    fun bool(key: String): Boolean = (o[key] as? JsonPrimitive)?.booleanOrNull ?: false
    return when (str("type")) {
        "text" -> DictationStreamEvent.Text(str("text") ?: return null, (o["seq"] as? JsonPrimitive)?.longOrNull)
        "final" -> DictationStreamEvent.Final(str("text") ?: "", bool("pendingEdit"))
        "done" -> DictationStreamEvent.Done
        "error" -> DictationStreamEvent.Error(str("message") ?: "dictation stream error", bool("fatal"))
        else -> null
    }
}

internal fun encodeDictationEdit(text: String, basedOnSeq: Long?): String =
    buildJsonObject {
        put("type", "edit")
        put("text", text)
        if (basedOnSeq != null) put("seq", basedOnSeq)
    }.toString()

internal fun encodeDictationControl(type: String): String = JsonObject(mapOf("type" to JsonPrimitive(type))).toString()

/** Opens dictation streams to one broker over the same socket plumbing as the terminal. */
class DictationStreamClient(
    private val baseUrl: String,
    private val token: String,
    http: HttpClient,
    private val transport: TerminalTransport = KtorTerminalTransport(http),
) {
    /**
     * Connect, run [session] with the open stream, and close it when [session] returns or throws.
     * Throws when the connection cannot be made or breaks — the caller's cue to fall back.
     */
    suspend fun open(dictationId: String, sessionId: String?, session: suspend (DictationStreamSocket) -> Unit) {
        transport.open(dictationStreamUrl(baseUrl, dictationId, sessionId), token) { raw ->
            session(WireDictationSocket(raw))
        }
    }
}

/** [DictationStreamSocket] over a [TerminalSocket] (binary + text frames — the same transport). */
internal class WireDictationSocket(private val raw: TerminalSocket) : DictationStreamSocket {
    override suspend fun sendAudio(pcm: ByteArray) {
        if (pcm.isNotEmpty()) raw.sendBinary(pcm)
    }

    override suspend fun sendEdit(text: String, basedOnSeq: Long?) = raw.sendText(encodeDictationEdit(text, basedOnSeq))

    override suspend fun sendStop() = raw.sendText(encodeDictationControl("stop"))

    override suspend fun sendCancel() = raw.sendText(encodeDictationControl("cancel"))

    override suspend fun receive(): DictationStreamEvent? {
        while (true) {
            when (val frame = raw.receive() ?: return null) {
                is TerminalWireFrame.Text -> decodeDictationStreamEvent(frame.text)?.let { return it }
                is TerminalWireFrame.Binary -> continue
            }
        }
    }

    override fun close() = raw.close()
}
