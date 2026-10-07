package dev.supermux.ui.session

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode

/**
 * Session avatar: the real agent brand mark ([AgentLogo]) when the agent is recognised, otherwise
 * the session name's initials on a primary tile.
 *
 * NOT used in the session list rows — matching both apps, where list rows deliberately stay lean
 * (the small `SessionStatusRail` IS the row's leading visual).
 *
 * When [sessionId], [sharedScope] and [animScope] are ALL non-null the avatar joins the
 * list→chat shared-element transition (Android's navigation animation); with any of them null it
 * renders as a plain avatar, which is what desktop does.
 *
 * The initials fallback follows the same Touch/Pointer rule as [AgentLogo]: touch keeps Android's
 * fixed 12dp-ish corner and fixed 13sp initials (the avatar is a fixed 40dp there, and the phone
 * type scale is not the desktop one), pointer keeps desktop's size-proportional metrics.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SessionAvatar(
    name: String,
    agent: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    sessionId: String? = null,
    sharedScope: SharedTransitionScope? = null,
    animScope: AnimatedVisibilityScope? = null,
) {
    val cs = MaterialTheme.colorScheme
    val shared = if (sessionId != null && sharedScope != null && animScope != null) {
        with(sharedScope) {
            modifier.sharedElement(
                rememberSharedContentState(key = "avatar-$sessionId"),
                animatedVisibilityScope = animScope,
            )
        }
    } else {
        modifier
    }

    if (hasAgentLogo(agent)) {
        AgentLogo(agent = agent, size = size, modifier = shared)
    } else if (LocalInputMode.current == InputMode.Touch) {
        Box(
            shared
                .size(size)
                .clip(agentTileShape(size))
                .background(cs.primary)
                .border(1.dp, cs.outline.copy(alpha = 0.7f), agentTileShape(size)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(2).uppercase(),
                color = cs.onPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }
    } else {
        Box(
            shared
                .size(size)
                .clip(RoundedCornerShape(size * 0.3f))
                .background(cs.primary)
                .border(1.dp, cs.outline.copy(alpha = 0.7f), RoundedCornerShape(size * 0.3f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(2).uppercase(),
                color = cs.onPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value * 0.32f).sp,
            )
        }
    }
}
