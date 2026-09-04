// Per-agent brand marks, shared by both apps.
//
// Artwork came from [lobe-icons](https://github.com/lobehub/lobe-icons) (claude-color, openai,
// cursor, grok, opencode). It ships as Compose Multiplatform drawable resources — Android vector
// XML, which Compose renders on every target — so the marks stay sharp at any size/density.
//
// The monochrome brands (codex/cursor/grok/opencode) had separate `-dark` SVGs on desktop; here
// one path is tinted from the surface luminance instead, which keeps a single asset per brand and
// tracks a theme flip without a second file. Claude keeps its brand orange in both themes.
package dev.supermux.ui.session

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.agent_claude
import dev.supermux.ui.resources.agent_codex
import dev.supermux.ui.resources.agent_cursor
import dev.supermux.ui.resources.agent_grok
import dev.supermux.ui.resources.agent_opencode
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/** The bundled mark for [agent], or null when the agent has no artwork. */
private fun agentDrawable(agent: String?): DrawableResource? = when (agent?.lowercase()) {
    "claude" -> Res.drawable.agent_claude
    "codex" -> Res.drawable.agent_codex
    "cursor" -> Res.drawable.agent_cursor
    "grok" -> Res.drawable.agent_grok
    "opencode" -> Res.drawable.agent_opencode
    else -> null
}

/** Brands whose artwork is a single silhouette, so it must be tinted for the current surface. */
private fun isMonochrome(agent: String?): Boolean = agent?.lowercase() != "claude"

/** Whether [agent] has a bundled brand mark. */
fun hasAgentLogo(agent: String?): Boolean = agentDrawable(agent) != null

/**
 * Per-agent brand logo — **no tile/box**. Vector artwork via [painterResource] so marks stay sharp
 * at any size/density. Unknown agents fall back to a plain initial.
 */
@Composable
fun AgentLogo(
    agent: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    fallbackLetter: String? = agent?.take(1)?.uppercase(),
) {
    val cs = MaterialTheme.colorScheme
    val darkSurface = cs.surface.luminance() < 0.5f
    val res = agentDrawable(agent)

    if (res != null) {
        Image(
            painter = painterResource(res),
            contentDescription = agent,
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
            colorFilter = if (isMonochrome(agent)) {
                ColorFilter.tint(if (darkSurface) Color(0xFFEDEDED) else Color(0xFF1A1A1A))
            } else {
                null
            },
        )
    } else {
        Box(
            modifier
                .size(size)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (fallbackLetter ?: "?").take(1).uppercase(),
                color = cs.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.72f).sp,
            )
        }
    }
}
