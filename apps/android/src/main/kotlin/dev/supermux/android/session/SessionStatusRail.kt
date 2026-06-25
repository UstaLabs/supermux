package dev.supermux.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.supermux.android.R
import dev.supermux.proto.GitLiteStatusDto
import dev.supermux.proto.SessionDoneState
import dev.supermux.proto.sessionDoneState

private val DoneGreen = Color(0xFF16A34A)
private val NotDoneAmber = Color(0xFFF59E0B)

/**
 * Leading session status: a colored rail + check/branch icon. Renders an empty
 * fixed-width spacer (for alignment) when there's no status (non-worktree session).
 */
@Composable
fun SessionStatusRail(git: GitLiteStatusDto?, unread: Boolean, modifier: Modifier = Modifier) {
    val state = sessionDoneState(git)
    if (state == null) {
        // Keep avatar alignment consistent; still show the unread cue as a thin bar.
        Box(
            modifier
                .width(4.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (unread) Color(0xFF14B8A6).copy(alpha = 0.7f) else Color.Transparent),
        )
        return
    }
    val color = if (state == SessionDoneState.DONE) DoneGreen else NotDoneAmber
    val icon = if (state == SessionDoneState.DONE) R.drawable.ic_check else R.drawable.ic_git_branch
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(4.dp)
                .height(20.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Spacer(Modifier.width(4.dp))
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
    }
}
