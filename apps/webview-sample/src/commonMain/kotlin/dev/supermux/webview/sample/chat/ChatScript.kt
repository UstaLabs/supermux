package dev.supermux.webview.sample.chat

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.webview.sample.chat.PreviewBridge.Code
import dev.supermux.webview.sample.chat.PreviewBridge.Colour
import dev.supermux.webview.sample.chat.PreviewBridge.Counter
import dev.supermux.webview.sample.chat.PreviewBridge.Form
import dev.supermux.webview.sample.chat.PreviewBridge.Game
import dev.supermux.webview.sample.chat.PreviewKind.Canvas
import dev.supermux.webview.sample.chat.PreviewKind.Css
import dev.supermux.webview.sample.chat.PreviewKind.Interactive
import dev.supermux.webview.sample.chat.PreviewKind.Svg
import dev.supermux.webview.sample.chat.PreviewKind.Three

/** A row of the chat. [id] is its key in the list, for as long as the app runs. */
@Immutable
sealed interface ChatItem {
    val id: String
}

/** "Yesterday", "Today". */
class DayMarker(override val id: String, val label: String) : ChatItem

/** What the user wrote, at [time]. */
class UserMessage(override val id: String, val text: String, val time: String) : ChatItem

/** The agent's answer: a line or two, and the previews it made. */
class AgentMessage(override val id: String, val text: String, val time: String, val previews: List<PreviewSpec>) : ChatItem

/** The agent writing: shown while it makes an answer to what the user just sent. */
class TypingIndicator(override val id: String) : ChatItem

/**
 * Every preview of the scripted chat, made once. Fixed-height previews say their height; wrap-content ones follow their
 * page. The colours are each page's own: its accent, and for pages with a stage of their own (3D, canvas art, the
 * heroes) the stage's top and bottom, which their placeholder shows before the first snapshot exists.
 */
object Previews {
    // three.js
    val galaxyClassic = spec("galaxy-classic", "galaxy", "classic", "Particle galaxy", Three, 280.dp, 0xFFFFB35C, 0xFF111A3D, 0xFF02030A)
    val galaxyRose = spec("galaxy-rose", "galaxy", "rose", "Rose galaxy", Three, 260.dp, 0xFFFF86CF, 0xFF2A0F33, 0xFF06020B)
    val galaxyEmerald = spec("galaxy-emerald", "galaxy", "emerald", "Emerald galaxy", Three, 240.dp, 0xFFA8FFD8, 0xFF06262B, 0xFF010608)
    val knotGold = spec("knot-gold", "torus-knot", "gold", "Torus knot, moving lights", Three, 260.dp, 0xFFE8B04A, 0xFF2A1A10, 0xFF0A0706)
    val knotNeon = spec("knot-neon", "torus-knot", "neon", "Neon torus knot", Three, 240.dp, 0xFF9FE8FF, 0xFF1A1440, 0xFF07061A)
    val terrainMeadow = spec("terrain-meadow", "terrain", "meadow", "Low-poly fly-over", Three, 240.dp, 0xFF7CC66B, 0xFF9FD3FF, 0xFFDDEFFF)
    val terrainDusk = spec("terrain-dusk", "terrain", "dusk", "Fly-over at dusk", Three, 220.dp, 0xFFFF9A62, 0xFF3B1D4A, 0xFF120A1F)
    val orbitPlanet = spec(
        "orbit-planet", "orbit", "planet", "A planet to spin", Three, 300.dp, 0xFF5EC8FF, 0xFF0B1230, 0xFF020410,
        pane = true, hint = "drag to rotate",
    )
    val orbitCrystal = spec(
        "orbit-crystal", "orbit", "crystal", "Crystal cluster", Three, 280.dp, 0xFFC9A7FF, 0xFF1B1030, 0xFF07040E,
        pane = true, hint = "drag to rotate",
    )
    val waveNeon = spec("wave-neon", "instanced-wave", "neon", "676 instanced cubes", Three, 240.dp, 0xFF3DF5FF, 0xFF0C0F24, 0xFF04050C)
    val waveSunset = spec("wave-sunset", "instanced-wave", "sunset", "Sunset wave", Three, 260.dp, 0xFFFF8A5B, 0xFF2B1022, 0xFF0D0509)

    // CSS
    val heroAurora = spec("hero-aurora", "gradient-hero", "aurora", "Hero · Aurora", Css, 220.dp, 0xFF6EE7D8, 0xFF1E3A8A, 0xFF5B21B6)
    val heroSunrise = spec("hero-sunrise", "gradient-hero", "sunrise", "Hero · Sunrise", Css, 200.dp, 0xFFFFB347, 0xFFFF6B6B, 0xFFFFA94D)
    val heroOcean = spec("hero-ocean", "gradient-hero", "ocean", "Hero · Ocean", Css, 240.dp, 0xFF38BDF8, 0xFF1E40AF, 0xFF0E7490)
    val loadersViolet = wrap("loaders-violet", "loaders", "violet", "Eight loaders", Css, 0xFF7C5CFF, initial = 320.dp)
    val loadersCoral = wrap("loaders-coral", "loaders", "coral", "Loaders · coral", Css, 0xFFFF6F61, initial = 320.dp)
    val flipCard = wrap("flip-card", "card-flip", "card", "3D card flip", Css, 0xFF6366F1, initial = 270.dp, hint = "tap to flip")
    val flipPass = wrap("flip-pass", "card-flip", "pass", "Boarding pass", Css, 0xFF14B8A6, initial = 270.dp, hint = "tap to flip")
    val typeBold = spec("type-bold", "kinetic-type", "bold", "Kinetic type", Css, 200.dp, 0xFFFFD43B, 0xFF0A0A0A, 0xFF000000)
    val typePastel = spec("type-pastel", "kinetic-type", "pastel", "Kinetic type · pastel", Css, 180.dp, 0xFFF472B6, 0xFFFFF7ED, 0xFFFDF2E9)

    // Canvas 2D
    val flowOcean = spec("flow-ocean", "flow-field", "ocean", "Flow field", Canvas, 240.dp, 0xFF4FC3F7, 0xFF041526, 0xFF020A14)
    val flowEmber = spec("flow-ember", "flow-field", "ember", "Ember flow", Canvas, 220.dp, 0xFFFF7A3D, 0xFF1A0804, 0xFF0A0302)
    val flowNeon = spec("flow-neon", "flow-field", "neon", "Neon flow", Canvas, 260.dp, 0xFFE040FB, 0xFF0B0418, 0xFF05020C)
    val lifeMint = spec("life-mint", "life", "mint", "Game of Life", Canvas, 220.dp, 0xFF5CF2B0, 0xFF06201C, 0xFF03110F, hint = "tap to seed")
    val lifeAmber = spec("life-amber", "life", "amber", "Life · amber", Canvas, 200.dp, 0xFFFFB000, 0xFF1A1206, 0xFF0D0903, hint = "tap to seed")
    val fireworksNight = spec(
        "fireworks-night", "fireworks", "night", "Fireworks", Canvas, 260.dp, 0xFFFFD166, 0xFF05081C, 0xFF2A1445, hint = "tap to launch",
    )
    val fireworksCity = spec(
        "fireworks-city", "fireworks", "city", "Fireworks over the city", Canvas, 240.dp, 0xFFFF6FB5, 0xFF141436, 0xFF05050F,
        hint = "tap to launch",
    )
    val voiceNote = wrap("voice-note", "waveform", "note", "Voice note", Canvas, 0xFF22C55E, initial = 92.dp, hint = "tap to play")
    val voiceLive = wrap("voice-live", "waveform", "live", "Recording", Canvas, 0xFFEF4444, initial = 112.dp)
    val confetti = wrap("confetti", "confetti", "default", "Confetti", Canvas, 0xFFFF5D8F, height = 200.dp, hint = "tap the button")

    // SVG
    val barsUsers = wrap("bars-users", "bar-chart", "users", "Weekly active users", Svg, 0xFF6366F1, initial = 286.dp)
    val barsRevenue = wrap("bars-revenue", "bar-chart", "revenue", "Revenue by region", Svg, 0xFF14B8A6, initial = 286.dp)
    val barsBuilds = wrap("bars-builds", "bar-chart", "builds", "CI build minutes", Svg, 0xFFF59E0B, initial = 286.dp)
    val lineLatency = wrap("line-latency", "line-chart", "latency", "p95 latency", Svg, 0xFF0EA5E9, initial = 266.dp)
    val lineStars = wrap("line-stars", "line-chart", "stars", "GitHub stars", Svg, 0xFFF5B000, initial = 266.dp)
    val ringsActivity = wrap("rings-activity", "progress-rings", "activity", "Activity rings", Svg, 0xFFFF2D55, initial = 230.dp, hint = "tap to replay")
    val ringsGoals = wrap("rings-goals", "progress-rings", "goals", "Release goals", Svg, 0xFF8B5CF6, initial = 230.dp, hint = "tap to replay")
    val blobCandy = spec("blob-candy", "blob", "candy", "Morphing blob", Svg, 220.dp, 0xFFFF6FB5, 0xFFFFE3F1, 0xFFFFD6C2)
    val blobLagoon = spec("blob-lagoon", "blob", "lagoon", "Lagoon blob", Svg, 200.dp, 0xFF2DD4BF, 0xFFDDF7F4, 0xFFD3E8FF)
    val clockClassic = wrap("clock-classic", "clock", "classic", "Analog clock", Svg, 0xFFEF4444, initial = 182.dp)
    val clockMinimal = wrap("clock-minimal", "clock", "minimal", "Minimal clock", Svg, 0xFF6366F1, initial = 190.dp)
    val weatherSunny = wrap("weather-sunny", "weather", "sunny", "Weather · Istanbul", Svg, 0xFFFFB020, initial = 250.dp)
    val weatherRain = wrap("weather-rain", "weather", "rain", "Weather · London", Svg, 0xFF60A5FA, initial = 250.dp)

    // Interactive, over the bridge
    val counter = wrap("counter", "counter", "default", "A counter the app keeps", Interactive, 0xFF6366F1, initial = 190.dp, bridge = Counter)
    val mixer = wrap("mixer", "color-mixer", "default", "Colour mixer", Interactive, 0xFFFF6A3D, initial = 310.dp, bridge = Colour)
    val breakout = spec(
        "breakout", "breakout", "default", "Breakout", Interactive, 320.dp, 0xFF22D3EE, 0xFF0F172A, 0xFF020617,
        pane = true, hint = "drag the paddle", bridge = Game,
    )
    val form = wrap("form", "form", "default", "Book a demo", Interactive, 0xFF10B981, initial = 520.dp, hint = "type in it", bridge = Form)
    val codeKotlin = spec(
        "code-kotlin", "code-card", "kotlin", "WebViewDemo.kt", Interactive, 260.dp, 0xFF7F52FF, 0xFF1E1F29, 0xFF16171F,
        pane = true, hint = "scrolls inside", bridge = Code,
    )
    val notesMarkdown = wrap(
        "md-notes", "code-card", "markdown", "Release notes", Interactive, 0xFF0EA5E9, height = 280.dp,
        pane = true, hint = "scrolls inside", bridge = Code,
    )

    /** Every preview of the script, in no particular order. */
    val all: List<PreviewSpec> = listOf(
        galaxyClassic, galaxyRose, galaxyEmerald, knotGold, knotNeon, terrainMeadow, terrainDusk, orbitPlanet, orbitCrystal,
        waveNeon, waveSunset, heroAurora, heroSunrise, heroOcean, loadersViolet, loadersCoral, flipCard, flipPass, typeBold,
        typePastel, flowOcean, flowEmber, flowNeon, lifeMint, lifeAmber, fireworksNight, fireworksCity, voiceNote, voiceLive,
        confetti, barsUsers, barsRevenue, barsBuilds, lineLatency, lineStars, ringsActivity, ringsGoals, blobCandy,
        blobLagoon, clockClassic, clockMinimal, weatherSunny, weatherRain, counter, mixer, breakout, form, codeKotlin,
        notesMarkdown,
    )

    /** A preview with a stage of its own, at a fixed [height]. */
    private fun spec(
        slug: String,
        template: String,
        variant: String,
        title: String,
        kind: PreviewKind,
        height: Dp,
        accent: Long,
        top: Long,
        bottom: Long,
        pane: Boolean = false,
        hint: String? = null,
        bridge: PreviewBridge = PreviewBridge.None,
    ) = PreviewSpec(
        slug, template, variant, title, kind, height, Color(accent), listOf(Color(top), Color(bottom)),
        pane = pane, hint = hint, bridge = bridge,
    )

    /** A preview that follows the theme, wrapping its page's height unless it says a [height]. */
    private fun wrap(
        slug: String,
        template: String,
        variant: String,
        title: String,
        kind: PreviewKind,
        accent: Long,
        initial: Dp = 220.dp,
        height: Dp? = null,
        pane: Boolean = false,
        hint: String? = null,
        bridge: PreviewBridge = PreviewBridge.None,
    ) = PreviewSpec(slug, template, variant, title, kind, height, Color(accent), null, initial, pane, hint, bridge)
}

/**
 * The scripted conversation: a user asking an agent for live previews, yesterday afternoon and this morning, 100
 * messages with [Previews.all] in them. Built once, when the chat's state is made.
 */
fun chatScript(): List<ChatItem> = ScriptBuilder().apply {
    day("Yesterday")
    user("16:02", "Hey! I'm redoing our landing page. Can you mock up a hero section?")
    agent("16:02", "Here's an animated mesh-gradient hero. Only transforms animate, so it stays cheap with a few on screen.", Previews.heroAurora)
    user("16:03", "Pretty. Two more colourways?")
    agent("16:03", "Sunrise and Ocean.", Previews.heroSunrise, Previews.heroOcean)
    user("16:05", "Ocean it is.")
    agent("16:05", "Noted 🌊 Every card in this chat is a real web page, live while it's on screen.")
    user("16:08", "We need loading states too")
    agent("16:08", "Eight loaders in pure CSS. They follow your light or dark theme.", Previews.loadersViolet)
    user("16:09", "In our brand coral?")
    agent("16:09", "Coral it is.", Previews.loadersCoral)
    user("16:12", "Can a card flip in 3D?")
    agent("16:12", "Tap it.", Previews.flipCard)
    user("16:13", "Ha! A boarding pass version?")
    agent("16:13", "IST → SFO. The fine print is on the back.", Previews.flipPass)
    user("16:16", "Something typographic for the launch video")
    agent("16:16", "Kinetic type, letter by letter.", Previews.typeBold)
    user("16:17", "Softer?")
    agent("16:17", "Pastel, with a rotating word.", Previews.typePastel)
    user("16:25", "Switching to data. Weekly active users?")
    agent("16:25", "Bars grow in, then the numbers refresh every few seconds.", Previews.barsUsers)
    user("16:26", "Revenue by region, and our CI minutes")
    agent("16:26", "Both, in one message.", Previews.barsRevenue, Previews.barsBuilds)
    user("16:29", "How did latency look after the deploy?")
    agent("16:29", "p95 dropped by about a third after the deploy marker.", Previews.lineLatency)
    user("16:30", "And GitHub stars 😏")
    agent("16:30", "Up and to the right.", Previews.lineStars)
    user("16:33", "Activity rings, like the watch?")
    agent("16:33", "Move, exercise, stand. Tap to replay.", Previews.ringsActivity)
    user("16:34", "Same idea for our release goals")
    agent("16:34", "Tests, coverage, docs.", Previews.ringsGoals)
    user("16:38", "A clock widget?")
    agent("16:38", "Two takes, both on your local time.", Previews.clockClassic, Previews.clockMinimal)
    user("16:41", "Weather for the dashboard")
    agent("16:41", "Istanbul today.", Previews.weatherSunny)
    user("16:42", "And London, to be fair")
    agent("16:42", "Of course it's raining.", Previews.weatherRain)
    user("16:50", "Can a preview talk to the app?")
    agent(
        "16:50",
        "Over the bridge, yes. The count lives in the app: each tap asks the app to add one, and the page shows its answer.",
        Previews.counter,
    )
    user("16:52", "Something with sliders?")
    agent("16:52", "A colour mixer. Drag the sliders: the app sees the colour live, under the card.", Previews.mixer)
    user("16:55", "Does typing work inside a preview?")
    agent("16:55", "Try this form. It keeps your draft even when its engine moves on to another preview.", Previews.form)
    user("16:58", "Show me the code behind all this")
    agent("16:58", "The gist, in a card that scrolls inside itself.", Previews.codeKotlin)
    user("16:59", "And the release notes?")
    agent("16:59", "Rendered from markdown.", Previews.notesMarkdown)
    user("17:01", "I'm bored. Game?")
    agent("17:01", "Breakout. Drag the paddle; the app hears your score.", Previews.breakout)
    user("17:10", "Ok, calling it a day. Thanks!")
    agent("17:10", "Anytime. Generative art tomorrow?")

    day("Today")
    user("09:02", "Morning! Generative art, as promised?")
    agent("09:02", "A flow field: 1,400 particles riding a noise field that drifts over time.", Previews.flowOcean)
    user("09:03", "In fire colours")
    agent("09:03", "Ember.", Previews.flowEmber)
    user("09:04", "And neon")
    agent("09:04", "Neon flow.", Previews.flowNeon)
    user("09:07", "Game of Life?")
    agent("09:07", "Conway's Life on a wrapping board. Tap to drop gliders.", Previews.lifeMint)
    user("09:08", "Amber, like an old terminal")
    agent("09:08", "Amber.", Previews.lifeAmber)
    user("09:10", "Something organic for the empty state")
    agent("09:10", "A morphing blob: its outline is noise, rebuilt every frame.", Previews.blobCandy)
    user("09:11", "Cooler colours?")
    agent("09:11", "Lagoon.", Previews.blobLagoon)
    user("09:14", "Can you mock a voice message?")
    agent("09:14", "A voice note. Tap the waveform to seek.", Previews.voiceNote)
    user("09:15", "And the recording state")
    agent("09:15", "Recording, with a live waveform.", Previews.voiceLive)
    user("09:20", "We just hit 10k users!! 🎉")
    agent("09:20", "Fireworks, then. Tap to launch your own.", Previews.fireworksNight)
    user("09:21", "Over the city?")
    agent("09:21", "Over the city.", Previews.fireworksCity)
    user("09:22", "And confetti for the team channel")
    agent("09:22", "Tap the button.", Previews.confetti)
    user("09:30", "Now 3D. Something metallic?")
    agent(
        "09:30",
        "A torus knot under three moving lights. The reflections come from a small environment map the page builds itself.",
        Previews.knotGold,
    )
    user("09:31", "Neon version!")
    agent("09:31", "A (3,5) knot under magenta, teal and violet lights.", Previews.knotNeon)
    user("09:34", "A landscape?")
    agent("09:34", "A low-poly fly-over: simplex-noise terrain, rebuilt every frame as the camera glides.", Previews.terrainMeadow)
    user("09:35", "At dusk")
    agent("09:35", "Dusk.", Previews.terrainDusk)
    user("09:38", "How much can it push?")
    agent("09:38", "676 cubes in one instanced mesh, riding an interference wave. One draw call.", Previews.waveNeon)
    user("09:39", "Warmer?")
    agent("09:39", "Sunset.", Previews.waveSunset)
    user("09:42", "I want something I can touch and rotate")
    agent("09:42", "Drag the planet to spin it, pinch to zoom. A drag inside it doesn't scroll the chat.", Previews.orbitPlanet)
    user("09:43", "Another!")
    agent("09:43", "A crystal cluster, same controls.", Previews.orbitCrystal)
    user("09:47", "Grand finale: a galaxy?")
    agent("09:47", "14,000 points on four spiral arms.", Previews.galaxyClassic)
    user("09:48", "Pinker")
    agent("09:48", "Rose: three arms, a tighter spin.", Previews.galaxyRose)
    user("09:48", "And a green one")
    agent("09:48", "Emerald, five arms.", Previews.galaxyEmerald)
    user("09:50", "How many previews are in this chat now?")
    agent("09:50", COUNT_ANSWER)
    user("09:51", "And it all works offline?")
    agent(
        "09:51",
        "Every page, three.js included, is bundled in the app and served from its own origin. Ask me for more below: " +
            "try \"galaxy\" or \"fireworks\".",
    )
}.items.let { items ->
    // The count answer is written once the script is whole.
    val previews = items.sumOf { (it as? AgentMessage)?.previews?.size ?: 0 }
    val messages = items.count { it is UserMessage || it is AgentMessage }
    items.map {
        if (it is AgentMessage && it.text == COUNT_ANSWER) {
            AgentMessage(
                it.id,
                "$previews, across $messages messages. On a phone at most four pages are live at once; the others show " +
                    "their last snapshot, and the ones you scroll past stay parked, alive, until another needs the engine.",
                it.time,
                it.previews,
            )
        } else {
            it
        }
    }
}

private const val COUNT_ANSWER = "<count>"

private class ScriptBuilder {
    val items = ArrayList<ChatItem>()
    private var next = 0

    fun day(label: String) {
        items += DayMarker("day-${next++}", label)
    }

    fun user(time: String, text: String) {
        items += UserMessage("m-${next++}", text, time)
    }

    fun agent(time: String, text: String, vararg previews: PreviewSpec) {
        items += AgentMessage("m-${next++}", text, time, previews.toList())
    }
}
