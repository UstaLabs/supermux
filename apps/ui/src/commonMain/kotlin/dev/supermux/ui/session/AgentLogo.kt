// Per-agent brand marks, shared by both apps.
//
// Artwork came from [lobe-icons](https://github.com/lobehub/lobe-icons) (claude-color, openai,
// cursor, grok, opencode). It ships as Compose Multiplatform drawable resources — Android vector
// XML, which Compose renders on every target — so the marks stay sharp at any size/density.
//
// The monochrome brands (codex/cursor/grok/opencode) had separate `-dark` SVGs on desktop; here
// one path is tinted from the surface luminance instead, which keeps a single asset per brand and
// tracks a theme flip without a second file. Claude keeps its brand orange in both themes.
//
// Presentation branches on [LocalInputMode] (the spec's Touch/Pointer rule):
//   - Touch  = Android's look — the mark sits inset on a cream tile with a hairline outline, which
//              is what keeps a black mark like Cursor's legible on a phone in both themes.
//   - Pointer = desktop's look — the bare mark, tinted for the surface, no tile.
package dev.supermux.ui.session

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
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

/**
 * The cream plate Android draws every mark on. A brand colour is not a theme colour: the marks are
 * fixed-palette artwork, so on touch they get their own light ground instead of the surface.
 */
internal val AgentTilePlate = Color(0xFFF7F4EE)

/** Test tag on the Touch-only tile, so a test can pin which branch rendered. */
const val AGENT_LOGO_TILE_TAG = "agent_logo_tile"

/** Android's tile corner: proportional, but never rounder than the 12dp the 40dp avatar used. */
internal fun agentTileShape(size: Dp) = RoundedCornerShape((size * 0.28f).coerceAtMost(12.dp))

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
    val touch = LocalInputMode.current == InputMode.Touch
    val res = agentDrawable(agent)

    if (res != null) {
        if (touch) {
            val shape = agentTileShape(size)
            Box(modifier.size(size), contentAlignment = Alignment.Center) {
                // Its own node, so the caller's `modifier` (which usually carries the avatar's
                // test tag) and the tile's tag do not fight over one semantics config.
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(shape)
                        .background(AgentTilePlate)
                        .border(1.dp, cs.outline.copy(alpha = 0.7f), shape)
                        .testTag(AGENT_LOGO_TILE_TAG),
                )
                // Untinted: the cream plate is already a light ground, so the artwork's own
                // dark fill is the legible one.
                Image(
                    painter = painterResource(res),
                    contentDescription = agent,
                    modifier = Modifier.size(size * 0.62f),
                    contentScale = ContentScale.Fit,
                )
            }
        } else {
            val darkSurface = cs.surface.luminance() < 0.5f
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
        }
    } else if (touch) {
        Box(
            modifier
                .size(size)
                .clip(agentTileShape(size))
                .background(cs.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                (fallbackLetter ?: "?").take(1).uppercase(),
                color = cs.onPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.5f).sp,
            )
        }
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
