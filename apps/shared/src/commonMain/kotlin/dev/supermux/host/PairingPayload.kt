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
        private val HOST_ID = Regex("^[a-z2-7]{26}$") // 128-bit base32, per spec §3.1

        fun parse(raw: String): PairingPayload? = try {
            val p = json.decodeFromString(serializer(), raw)
            when {
                p.v != 1 || p.action != "pair" -> null
                !HOST_ID.matches(p.hostId) || p.claimSecret.isBlank() -> null
                p.relayUrl != null && !isSupermuxRelayOrigin(p.relayUrl) -> null
                else -> p
            }
        } catch (_: Exception) { null }

        /** True only when the URL's HOST (not any substring) is a supermux relay.
         *  Rejects e.g. https://evil.example.com/.relay.supermux.dev. */
        internal fun isSupermuxRelayOrigin(url: String): Boolean {
            val afterScheme = url.substringAfter("://", "")
            if (afterScheme.isEmpty()) return false
            val host = afterScheme
                .substringBefore("/").substringBefore("?").substringBefore("#")
                .substringBefore(":").substringAfter("@") // strip port + any userinfo
            return host == "relay.supermux.dev" || host.endsWith(".relay.supermux.dev")
        }
    }
}
