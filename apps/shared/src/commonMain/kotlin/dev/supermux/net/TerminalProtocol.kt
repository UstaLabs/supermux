package dev.supermux.net

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The /ws/term wire, revision 2 — the client half.
 *
 * The broker half is `src/channels/web/terminal-protocol.ts`, and the fixtures
 * in `TerminalProtocolTest.kt` are the same strings `tests/terminal-protocol.test.ts`
 * uses (that test reads this module's test file and fails when they drift).
 *
 * WHAT REVISION 1 COULD NOT SAY. Output was binary, control was a handful of
 * ad-hoc JSON objects, and the client recognised them by substring — so a
 * failure whose MESSAGE happened to contain the word "exit" closed the tab,
 * and a `reset` invented by the channel at socket-open carried no epoch, so a
 * mid-stream re-sync was indistinguishable from a first attach.
 *
 * Revision 2 is one ORDERED stream:
 *
 *     Ready → Reset → ReplayStart → Output* → ReplayEnd → Output*
 *
 * and then at most one of [TerminalEvent.Exit] (the program ended) or
 * [TerminalEvent.Failure] (we lost it — possibly recoverable). [TerminalEvent.Owner]
 * may arrive at any point after Ready. Binary frames ARE the [TerminalEvent.Output]
 * events: WebSocket ordering is what places them between control frames, which
 * is also why the transport must hand every frame to ONE decoder in arrival
 * order and never race a control frame past the bytes it belongs behind.
 */
const val TERMINAL_PROTOCOL_VERSION = 2

/** The ordered client event stream. The transport owns the codec; these are the
 * public types everything above the socket reacts to. */
sealed interface TerminalEvent {
    /**
     * Always first on a connection. [epoch] opens the CONNECTION's epoch so
     * that control frames arriving before the backend's first [Reset] still
     * carry one; each [Reset] then opens a new render epoch. Ownership is
     * never assumed — [replyOwner] is false until an [Owner] event says
     * otherwise.
     */
    data class Ready(
        val version: Int,
        val epoch: String,
        val replyOwner: Boolean,
        val ownerGeneration: Long,
    ) : TerminalEvent

    /** Everything drawn so far is void; a new epoch begins. */
    data class Reset(val epoch: String) : TerminalEvent
    data class ReplayStart(val epoch: String) : TerminalEvent

    /** Raw pty bytes. Equality is by CONTENT: a ByteArray data class would
     * otherwise compare by identity and quietly make every test pass. */
    data class Output(val bytes: ByteArray) : TerminalEvent {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Output && bytes.contentEquals(other.bytes))
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "Output(${bytes.size} bytes)"
    }

    /** The replay boundary closed: bytes after this are LIVE. */
    data class ReplayEnd(val epoch: String) : TerminalEvent

    /** Whether this viewer currently owns the size — and therefore whether its
     * emulator's query answers are the ones the pty will accept. */
    data class Owner(val epoch: String, val enabled: Boolean, val ownerGeneration: Long) : TerminalEvent

    /**
     * The TARGET PROCESS ended — the one event that closes a tab.
     *
     * The backend can report more than a number and this carries all of it:
     * [known] false means the program ended but no status was reaped (the pty
     * closing and the child becoming reapable race), and [code] and [signal]
     * are then both null — reporting 0 would claim a clean exit nobody
     * observed. A signalled exit carries [signal] with a null [code], because
     * 0 is a real exit code and "killed by SIGKILL" is not it.
     */
    data class Exit(val code: Int?, val signal: Int?, val known: Boolean) : TerminalEvent

    /**
     * We lost the target, or never reached it. NOT an exit: when [recoverable]
     * the client reconnects, otherwise it stops and shows why.
     */
    data class Failure(val code: String, val recoverable: Boolean, val message: String) : TerminalEvent
}

/** What one wire frame meant. */
sealed interface TerminalDecode {
    /** A well-formed event, in order. */
    data class Deliver(val event: TerminalEvent) : TerminalDecode

    /**
     * Refused, connection survives: a malformed or unknown control frame, a
     * stale epoch, or output before [TerminalEvent.Ready]. Dropping is the
     * point — none of these can be acted on, and a stray bad frame must not
     * end a live shell.
     */
    data class Ignore(val reason: String) : TerminalDecode

    /** The peer is not speaking a protocol we can render. The connection ends
     * and is NOT retried; [code] is the failure code surfaced to the UI. */
    data class Fatal(val code: String, val message: String) : TerminalDecode
}

// ---- wire frames ----------------------------------------------------------

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
internal sealed interface TerminalServerFrame {
    @Serializable @SerialName("ready")
    data class Ready(
        val version: Int,
        val epoch: String,
        val replyOwner: Boolean = false,
        val ownerGeneration: Long = 0,
    ) : TerminalServerFrame

    @Serializable @SerialName("reset")
    data class Reset(val epoch: String) : TerminalServerFrame

    @Serializable @SerialName("replay-start")
    data class ReplayStart(val epoch: String) : TerminalServerFrame

    @Serializable @SerialName("replay-end")
    data class ReplayEnd(val epoch: String) : TerminalServerFrame

    @Serializable @SerialName("owner")
    data class Owner(val epoch: String, val enabled: Boolean, val ownerGeneration: Long) : TerminalServerFrame

    @Serializable @SerialName("exit")
    data class Exit(val known: Boolean, val code: Int? = null, val signal: Int? = null) : TerminalServerFrame

    @Serializable @SerialName("failure")
    data class Failure(val code: String, val recoverable: Boolean, val message: String = "") : TerminalServerFrame
}

/** The text frames a viewer sends. Binary frames are user input. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface TerminalCommand {
    @Serializable @SerialName("resize")
    data class Resize(val cols: Int, val rows: Int) : TerminalCommand

    /** A blur carries no geometry worth believing, so cols/rows default to 0
     * ("keep the size you have") and are then left off the wire entirely. */
    @Serializable @SerialName("focus")
    data class Focus(val focused: Boolean, val cols: Int = 0, val rows: Int = 0) : TerminalCommand

    /**
     * A terminal REPLY this viewer's emulator produced (DA/DSR/kitty answers),
     * base64 in [data]. Stamped with the epoch and owner generation it was
     * produced under: an answer to a screen that no longer exists, or under an
     * ownership since lost, is not an answer — it is keystrokes — and the
     * broker drops it on exactly these two fields.
     */
    @Serializable @SerialName("reply")
    data class Reply(val epoch: String, val ownerGeneration: Long, val data: String) : TerminalCommand

    @Serializable @SerialName("close")
    object Close : TerminalCommand
}

private val terminalJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "type"
}

fun encodeTerminalCommand(command: TerminalCommand): String =
    terminalJson.encodeToString(TerminalCommand.serializer(), command)

@OptIn(ExperimentalEncodingApi::class)
fun encodeReplyPayload(bytes: ByteArray): String = Base64.encode(bytes)

/**
 * Turns wire frames into the ordered event stream, and is the only thing that
 * knows the ordering RULES. It is deliberately not a parser with a switch next
 * to it: the epoch, the replay boundary and the owner generation are exactly
 * what decides whether the next frame means anything, so they live here rather
 * than being re-derived by every caller.
 *
 * Not thread-safe by design — one connection, one decoder, one receive loop.
 */
class TerminalEventDecoder {
    var version: Int = 0
        private set
    var epoch: String? = null
        private set
    var ready: Boolean = false
        private set
    /** Between ReplayStart and ReplayEnd: the screen is HISTORY being redrawn. */
    var replaying: Boolean = false
        private set
    /** True once a replay boundary has closed on the current epoch. */
    var replayClosed: Boolean = false
        private set
    var replyOwner: Boolean = false
        private set
    var ownerGeneration: Long = 0
        private set

    /** A binary frame: pty bytes. */
    fun onBinary(bytes: ByteArray): TerminalDecode {
        // The contract forbids output before `ready`, and bytes we cannot
        // place in an epoch are bytes we cannot un-draw when that epoch is
        // replaced.
        if (!ready) return TerminalDecode.Ignore("output before ready")
        return TerminalDecode.Deliver(TerminalEvent.Output(bytes))
    }

    /** A text frame: one control object. */
    fun onText(text: String): TerminalDecode {
        val frame = try {
            terminalJson.decodeFromString(TerminalServerFrame.serializer(), text)
        } catch (_: Throwable) {
            // Malformed JSON, an unknown discriminator, a field of the wrong
            // type: nothing actionable, and never a reason to end a shell.
            return TerminalDecode.Ignore("undecodable control frame")
        }
        return when (frame) {
            is TerminalServerFrame.Ready -> {
                if (frame.version != TERMINAL_PROTOCOL_VERSION) {
                    TerminalDecode.Fatal(
                        "protocol-unsupported",
                        "broker speaks terminal protocol ${frame.version}, this client speaks $TERMINAL_PROTOCOL_VERSION",
                    )
                } else if (ready) {
                    TerminalDecode.Ignore("duplicate ready")
                } else {
                    ready = true
                    version = frame.version
                    epoch = frame.epoch
                    replyOwner = frame.replyOwner
                    ownerGeneration = frame.ownerGeneration
                    TerminalDecode.Deliver(
                        TerminalEvent.Ready(frame.version, frame.epoch, frame.replyOwner, frame.ownerGeneration),
                    )
                }
            }
            is TerminalServerFrame.Reset -> {
                if (!ready) return TerminalDecode.Ignore("reset before ready")
                // A reset ALWAYS wins: it is how the backend says the screen a
                // client holds is void, including one from an epoch it has
                // never heard of.
                epoch = frame.epoch
                replaying = false
                replayClosed = false
                TerminalDecode.Deliver(TerminalEvent.Reset(frame.epoch))
            }
            is TerminalServerFrame.ReplayStart -> when {
                !ready -> TerminalDecode.Ignore("replay-start before ready")
                frame.epoch != epoch -> TerminalDecode.Ignore("replay-start for a stale epoch")
                replaying -> TerminalDecode.Ignore("nested replay")
                else -> {
                    replaying = true
                    TerminalDecode.Deliver(TerminalEvent.ReplayStart(frame.epoch))
                }
            }
            is TerminalServerFrame.ReplayEnd -> when {
                !ready -> TerminalDecode.Ignore("replay-end before ready")
                frame.epoch != epoch -> TerminalDecode.Ignore("replay-end for a stale epoch")
                !replaying -> TerminalDecode.Ignore("replay-end outside a replay")
                else -> {
                    replaying = false
                    replayClosed = true
                    TerminalDecode.Deliver(TerminalEvent.ReplayEnd(frame.epoch))
                }
            }
            is TerminalServerFrame.Owner -> when {
                !ready -> TerminalDecode.Ignore("owner before ready")
                frame.epoch != epoch -> TerminalDecode.Ignore("owner for a stale epoch")
                // The broker only ever increments. An older generation is a
                // reordered or replayed frame, and acting on it would make a
                // viewer believe it owns a lease that moved on.
                frame.ownerGeneration <= ownerGeneration -> TerminalDecode.Ignore("stale owner generation")
                else -> {
                    replyOwner = frame.enabled
                    ownerGeneration = frame.ownerGeneration
                    TerminalDecode.Deliver(TerminalEvent.Owner(frame.epoch, frame.enabled, frame.ownerGeneration))
                }
            }
            // Exit and Failure are accepted even before `ready`: refusing the
            // connection IS what a broker does before it can say ready.
            is TerminalServerFrame.Exit ->
                TerminalDecode.Deliver(TerminalEvent.Exit(frame.code, frame.signal, frame.known))
            is TerminalServerFrame.Failure ->
                TerminalDecode.Deliver(TerminalEvent.Failure(frame.code, frame.recoverable, frame.message))
        }
    }
}
