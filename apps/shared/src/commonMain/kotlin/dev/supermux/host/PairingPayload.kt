package dev.supermux.host

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The QR / deep-link pairing payload (spec §3.4). Parsing validates version,
 *  action, required fields, and that any relay origin is a supermux relay. */
@Serializable
data class PairingPayload(
    val v: Int,
    val action: String,
    val hostId: String,
    val name: String,
    val relayUrl: String? = null,
    val directUrl: String? = null,
    val claimSecret: String,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(raw: String): PairingPayload? = try {
            val p = json.decodeFromString(serializer(), raw)
            when {
                p.v != 1 || p.action != "pair" -> null
                p.hostId.isBlank() || p.claimSecret.isBlank() -> null
                p.relayUrl != null && !p.relayUrl.contains(".relay.supermux.dev") -> null
                else -> p
            }
        } catch (_: Exception) { null }
    }
}
