package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Agents wearing the overlay's glasses (#62), each rigged avatar plain and in glasses, and a name-derived
 * one, at the overlay's size and enlarged. For eyeballing; a no-op unless SHOTS_DIR is set. RIGS_FILE may
 * name a JSON array of `{"name": …, "rig": {…}}` to draw real avatars:
 *   SHOTS_DIR=/tmp/shots RIGS_FILE=rigs.json ./gradlew :ui:jvmTest --tests '*AvatarGlassesShots*'
 */
@OptIn(ExperimentalTestApi::class)
class AvatarGlassesShots {
    private val sample = """{"format":"supermux-avatar","version":2,"size":100,"parts":[{"id":"head","shape":"group","children":[
        {"id":"face","shape":"circle","cx":50,"cy":52,"r":34,"fill":"#FFD8B8"},
        {"id":"eyes","shape":"group","behaviors":[{"type":"blink"}],"children":[
          {"id":"eyeL","shape":"ellipse","cx":38,"cy":48,"rx":4.5,"ry":6,"fill":"#222222"},
          {"id":"eyeR","shape":"ellipse","cx":62,"cy":48,"rx":4.5,"ry":6,"fill":"#222222"}]}]}],"states":{"idle":{"tracks":[]}}}"""

    @Test fun shots() {
        val dir = System.getenv("SHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        val named = System.getenv("RIGS_FILE")?.let { f ->
            Json.parseToJsonElement(File(f).readText()).jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                parseAvatarRig(o["rig"].toString())?.let { o["name"]!!.jsonPrimitive.content to it }
            }
        } ?: listOf("Sample" to parseAvatarRig(sample)!!)
        for (theme in listOf(AppearanceMode.LIGHT, AppearanceMode.DARK)) runDesktopComposeUiTest(width = 760, height = 1150) {
            mainClock.autoAdvance = false
            setContent {
                SupermuxTheme(appearance = theme) {
                    val cs = MaterialTheme.colorScheme
                    // One row per Agent: plain and in glasses, at the overlay's size and enlarged.
                    Column(Modifier.background(cs.surfaceContainerLowest).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val rows = listOf("Dockie" to null) + named.map { (name, rig) -> name to rig }
                        for ((name, rig) in rows) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.width(110.dp))
                            for (head in listOf(56, 112)) for (worn in listOf(false, true)) Box(
                                Modifier.size(head.dp).clip(CircleShape).background(cs.surfaceContainerHigh.copy(alpha = 0.78f)),
                                contentAlignment = Alignment.Center,
                            ) {
                                val inner = (head * 0.82f).toInt()
                                if (rig != null) {
                                    AvatarRigView(if (worn) rig.wearingGlasses() ?: rig else rig, state = "idle", size = inner, fixedTime = 1_000L)
                                } else {
                                    AgentAvatar(name, online = true, working = false, size = inner, timeMs = 1_000L, glasses = worn)
                                }
                            }
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(500)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "glasses-${theme.name.lowercase()}.png"))
        }
    }
}
