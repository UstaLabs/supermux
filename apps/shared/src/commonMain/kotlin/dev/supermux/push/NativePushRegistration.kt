package dev.supermux.push

/** How one phone's native-push registration with a broker ended (card #86). */
sealed interface NativePushRegistration {
    /** One line for the platform's own log. Never carries a token. */
    val summary: String

    /** The broker holds this device's routing token, so it can push here. Not a data class: its
     *  toString would print the routing token. */
    class Registered(val routingToken: String, val relayUrl: String) : NativePushRegistration {
        override val summary: String get() = "registered at $relayUrl"
    }

    /** The broker names no push relay. */
    data object NoPushRelay : NativePushRegistration {
        override val summary: String get() = "the broker names no push relay"
    }

    /** An older relay answers without the routing token and sends it by push (the bootstrap). */
    data class AwaitingBootstrap(val relayUrl: String) : NativePushRegistration {
        override val summary: String get() = "$relayUrl sent no routing token; it comes by push"
    }

    /** [stage] is "me" (asking the broker for its push relay), "relay" or "broker" (storing the token). */
    data class Failed(val stage: String, val error: String, val relayUrl: String? = null) : NativePushRegistration {
        override val summary: String get() = "failed at $stage${relayUrl?.let { " ($it)" }.orEmpty()}: $error"
    }
}
