// The JSON an Agent's avatar is rendered from — so anyone can design their own character.
//
// Everything is optional: an empty `{}` is a valid avatar, and a missing spec falls back to one
// derived from the Agent's name. Sizes are fractions of the body radius; durations are ms.
package dev.supermux.ui.session

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AgentAvatarSpec(
    val version: Int = 1,
    val body: Body = Body(),
    val eyes: Eyes = Eyes(),
    val mouth: Mouth = Mouth(),
    /** Null (or `"cheeks": null`) draws none. */
    val cheeks: Cheeks? = Cheeks(),
    val accessory: Accessory? = null,
    val states: States = States(),
) {
    @Serializable
    data class Body(
        /** blob | circle | squircle | ghost */
        val shape: String = "blob",
        /** Top → bottom gradient; one colour is a flat fill. */
        val colors: List<String> = listOf("#9B87FF", "#5B3FE0"),
        val wobble: Float = 0.025f,
        val lobes: Int = 3,
        val gloss: Boolean = true,
        val outline: String? = null,
    )

    @Serializable
    data class Eyes(
        /** pill | dot | happy | visor | sparkle */
        val style: String = "pill",
        val color: String = "#1B1530",
        val spacing: Float = 0.36f,
        val size: Float = 1f,
        val blink: Boolean = true,
    )

    @Serializable
    data class Mouth(
        /** smile | cat | line | none */
        val style: String = "smile",
        val color: String? = null,
    )

    @Serializable
    data class Cheeks(val color: String = "#FF6B8A")

    @Serializable
    data class Accessory(
        /** antenna | ears | leaf | halo | horns */
        val type: String,
        val color: String = "#FFFFFF",
    )

    @Serializable
    data class States(
        val idle: State = State(speed = 3600, breathe = 0.035f),
        val working: State = State(speed = 1400, wobble = 0.075f, eyes = "glance", mouth = "o"),
        val waiting: State = State(motion = "hop", eyes = "up", mouth = "grin"),
        val asleep: State = State(eyes = "closed", mouth = "none", badge = "z", desaturate = true),
    )

    /** Overrides for one state; null fields keep the base look. */
    @Serializable
    data class State(
        /** One loop of the breathing / wobble clock. */
        val speed: Int? = null,
        val breathe: Float? = null,
        val wobble: Float? = null,
        /** hop | shake | spin | none */
        val motion: String? = null,
        /** glance | up | closed | (any eyes.style) */
        val eyes: String? = null,
        /** o | grin | none | (any mouth.style) */
        val mouth: String? = null,
        /** A small mark beside the head: z | ! | ? | … */
        val badge: String? = null,
        val desaturate: Boolean = false,
    )
}

private val SpecJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** Parse a user's avatar JSON; null when it isn't valid. */
fun parseAgentAvatar(json: String): AgentAvatarSpec? =
    runCatching { SpecJson.decodeFromString(AgentAvatarSpec.serializer(), json) }.getOrNull()

internal fun parseHexColor(hex: String?, fallback: Color = Color.Gray): Color {
    val h = hex?.trim()?.removePrefix("#") ?: return fallback
    val v = h.toLongOrNull(16) ?: return fallback
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        // CSS order #RRGGBBAA (what the avatar JSON and the web preview use).
        8 -> Color(((v and 0xFF) shl 24) or (v ushr 8))
        else -> fallback
    }
}

private val DefaultPalettes = listOf(
    "#9B87FF" to "#5B3FE0", "#5AC8FA" to "#0A6CFF", "#6EE7C8" to "#13A38A", "#FFC56B" to "#FF7A1A",
    "#FF9AA8" to "#E5486A", "#D69CFF" to "#9B3FE0", "#A8E06B" to "#3FA34D", "#FFE07A" to "#E0A800",
)

/** The look an Agent gets when it has no avatar JSON: stable per name. */
fun defaultAgentAvatar(name: String): AgentAvatarSpec {
    val h = name.lowercase().hashCode() and 0x7fffffff
    val (a, b) = DefaultPalettes[h % DefaultPalettes.size]
    return AgentAvatarSpec(body = AgentAvatarSpec.Body(colors = listOf(a, b), lobes = 3 + (h / 7) % 3))
}

/** Example avatars, as a user would write them. */
val AgentAvatarExamples: List<Pair<String, String>> = listOf(
    "Dewey" to """
        {
          "body": { "shape": "blob", "colors": ["#7FD8FF", "#1E7BFF"], "wobble": 0.04, "lobes": 5 },
          "eyes": { "style": "pill" },
          "mouth": { "style": "smile" },
          "accessory": { "type": "leaf", "color": "#5BD67A" }
        }
    """,
    "Ember" to """
        {
          "body": { "shape": "ghost", "colors": ["#FFD36B", "#FF5A1F"], "wobble": 0.05 },
          "eyes": { "style": "happy" },
          "mouth": { "style": "cat" },
          "cheeks": null,
          "states": { "working": { "speed": 900, "wobble": 0.1, "motion": "shake", "eyes": "glance", "mouth": "o" } }
        }
    """,
    "Bolt" to """
        {
          "body": { "shape": "squircle", "colors": ["#3A3F4B", "#1C1F26"], "gloss": false, "outline": "#6EE7C8" },
          "eyes": { "style": "visor", "color": "#6EE7C8" },
          "mouth": { "style": "line", "color": "#6EE7C8" },
          "cheeks": null,
          "accessory": { "type": "antenna", "color": "#6EE7C8" },
          "states": { "waiting": { "motion": "hop", "badge": "!" } }
        }
    """,
    "Mochi" to """
        {
          "body": { "shape": "circle", "colors": ["#FFFFFF", "#F2D7E3"] },
          "eyes": { "style": "dot", "spacing": 0.42 },
          "mouth": { "style": "cat" },
          "accessory": { "type": "ears", "color": "#F2B8CF" }
        }
    """,
    "Nova" to """
        {
          "body": { "shape": "blob", "colors": ["#D69CFF", "#5B3FE0"], "lobes": 4 },
          "eyes": { "style": "sparkle", "color": "#FFFFFF" },
          "mouth": { "style": "smile", "color": "#FFFFFF" },
          "cheeks": null,
          "accessory": { "type": "halo", "color": "#FFE07A" },
          "states": { "working": { "motion": "spin", "eyes": "glance", "mouth": "o" } }
        }
    """,
)
