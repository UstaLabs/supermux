package dev.supermux.android.push

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure routing logic for an inbound FCM `data.d` payload — no Android / Firebase deps,
 * so it is fully unit-testable on a plain JVM.
 *
 * The relay puts the payload in the data-only message field `d`:
 *   - BOOTSTRAP: `d` is the *plaintext* JSON `{"kind":"bootstrap","routingToken":"..."}`
 *     (relay/core.ts `register()` → `{ ciphertext: JSON.stringify({kind:"bootstrap", routingToken}) }`).
 *     The app responds by registering the device with the broker (pubkey + routingToken).
 *   - SEALED: `d` is a 4-part sealed blob (see [dev.supermux.push.openSealedPush]) that
 *     decrypts to the broker's PushPayload JSON `{session, sessionId?, text?, kind?, ts}`.
 *     The app decrypts it and posts a notification.
 *
 * [classify] does the branch WITHOUT decrypting (a bootstrap is plaintext JSON; anything
 * else is treated as a sealed blob to be handed to [dev.supermux.push.openSealedPush]).
 */
object PushRouter {
    private val json = Json { ignoreUnknownKeys = true }

    sealed interface Routed {
        /** A bootstrap message carrying the [routingToken] to register with the broker. */
        data class Bootstrap(val routingToken: String) : Routed
        /** A sealed blob to decrypt with the device private key. [blob] is the raw `d`. */
        data class Sealed(val blob: String) : Routed
    }

    /**
     * Branch [d] into [Routed.Bootstrap] vs [Routed.Sealed] without decrypting.
     *
     * A bootstrap is recognized only when [d] parses as a JSON object with
     * `"kind":"bootstrap"` and a non-blank `routingToken`. Everything else (including a
     * sealed blob, which is dot-joined base64url and never valid JSON) is [Routed.Sealed].
     */
    fun classify(d: String): Routed {
        parseBootstrap(d)?.let { return Routed.Bootstrap(it) }
        return Routed.Sealed(d)
    }

    /** Returns the routingToken if [d] is a well-formed bootstrap message, else null. */
    fun parseBootstrap(d: String): String? {
        val obj = parseObjectOrNull(d) ?: return null
        val kind = (obj["kind"] as? JsonPrimitive)?.contentOrNull
        if (kind != "bootstrap") return null
        val token = (obj["routingToken"] as? JsonPrimitive)?.contentOrNull
        return token?.takeIf { it.isNotBlank() }
    }

    /** The decrypted notification payload (broker `PushPayload`, minus fields we don't render). */
    data class Notification(
        val session: String,
        val sessionId: String?,
        val text: String,
    )

    /**
     * Parse the decrypted plaintext of a sealed message into a [Notification].
     *
     * Mirrors the broker `PushPayload` (`src/core/push/sender.ts`): `session` is the
     * human title, `sessionId` (optional) is the deep-link target, `text` is the body
     * (absent for media-only notifications → rendered from `kind`).
     */
    fun parseNotification(plaintext: String): Notification? {
        val obj = parseObjectOrNull(plaintext) ?: return null
        val session = obj["session"]?.jsonPrimitive?.contentOrNull ?: return null
        val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull
        val text = obj["text"]?.jsonPrimitive?.contentOrNull
            ?: mediaLabel(obj["kind"]?.jsonPrimitive?.contentOrNull)
        return Notification(session = session, sessionId = sessionId, text = text)
    }

    private fun mediaLabel(kind: String?): String = when (kind) {
        "photo" -> "Sent a photo"
        "voice" -> "Sent a voice message"
        "audio" -> "Sent audio"
        "video_note" -> "Sent a video note"
        "document" -> "Sent a document"
        else -> "New message"
    }

    private fun parseObjectOrNull(s: String): JsonObject? {
        val t = s.trim()
        if (!t.startsWith("{")) return null
        return try {
            json.parseToJsonElement(t) as? JsonObject
        } catch (_: SerializationException) {
            null
        }
    }
}
