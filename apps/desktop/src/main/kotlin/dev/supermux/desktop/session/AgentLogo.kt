// Per-agent brand marks for desktop.
//
// Artwork under `resources/agents/*.svg` was downloaded from
// [lobe-icons](https://github.com/lobehub/lobe-icons) (claude-color, openai, cursor, grok,
// opencode). Display uses Compose Desktop's official SVG path:
//   painterResource("…svg") → SVGPainter → Skia SVGDOM
// which scales with density (no fixed-resolution PNG raster).
// See: JetBrains compose tutorial "Image and in-app icons" / loadSvgPainter.
package dev.supermux.desktop.session

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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Classpath SVG for [agent]. Monochrome brands have a `-dark` variant for dark surfaces;
 * Claude stays brand-orange in both themes.
 */
private fun agentSvgPath(agent: String?, darkSurface: Boolean): String? {
    val key = agent?.lowercase() ?: return null
    return when (key) {
        "claude" -> "agents/claude.svg"
        "codex" -> if (darkSurface) "agents/codex-dark.svg" else "agents/codex.svg"
        "cursor" -> if (darkSurface) "agents/cursor-dark.svg" else "agents/cursor.svg"
        "grok" -> if (darkSurface) "agents/grok-dark.svg" else "agents/grok.svg"
        "opencode" -> if (darkSurface) "agents/opencode-dark.svg" else "agents/opencode.svg"
        else -> null
    }
}

/** Whether [agent] has a bundled brand mark. */
fun hasAgentLogo(agent: String?): Boolean = agentSvgPath(agent, darkSurface = false) != null

/**
 * Per-agent brand logo — **no tile/box**. Vector SVG via [painterResource] so marks stay sharp
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
    val path = agentSvgPath(agent, darkSurface)

    if (path != null) {
        // Desktop painterResource("…svg") → SVGPainter (Skia SVGDOM). Scales with LocalDensity.
        Image(
            painter = painterResource(path),
            contentDescription = agent,
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
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
