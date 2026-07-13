package dev.supermux.desktop.host

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Windows "Host from this PC — preview" card (Plan 3 Task 6 / spec §6). Windows desktop stays
 * client-only for now; instead of hosting natively it shows an ENABLED preview card that explains
 * what's coming, records a preview sign-up, and links the documented advanced WSL-host path.
 * macOS/Linux hide it entirely (they host natively) — see [shouldShowWindowsPreview].
 */

const val WINDOWS_PREVIEW_TITLE = "Host from this PC"
const val WINDOWS_PREVIEW_SUBTITLE = "Native hosting is coming next. Join the preview."

/** The documented advanced path: run the broker inside WSL, which pairs as its own Ubuntu host. */
const val WSL_HOST_DOCS_URL = "https://supermux.dev/docs/desktop/windows-wsl-host"

/** Pure OS gate: the preview card shows ONLY on Windows; native-host platforms hide it. */
fun shouldShowWindowsPreview(osName: String = System.getProperty("os.name") ?: ""): Boolean =
    osName.lowercase().contains("win")

/**
 * Record a preview sign-up locally (no network) — appends a timestamped line to a log the caller can
 * later batch-report. Best-effort; never throws. The card calls this via its `onJoinPreview` default.
 */
fun recordWindowsPreviewSignup(logFile: Path, nowMs: Long = System.currentTimeMillis()): Boolean =
    runCatching {
        Files.createDirectories(logFile.parent)
        Files.writeString(
            logFile,
            "windows-host-preview signup at $nowMs\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND,
        )
        true
    }.getOrDefault(false)

/**
 * The card. [onJoinPreview] records the sign-up (returns true on success → the card shows a thank-you);
 * [onOpenWslGuide] opens the advanced WSL-host docs. Stateless-ish (only the local expand/thanks UI
 * state) so it renders deterministically in the Compose test.
 */
@Composable
fun WindowsHostPreviewCard(
    onJoinPreview: () -> Boolean,
    onOpenWslGuide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var signedUp by remember { mutableStateOf(false) }

    Column(
        modifier
            .testTag("windows_host_preview_card")
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outline.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Computer, contentDescription = null, tint = cs.primary, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(WINDOWS_PREVIEW_TITLE, color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(WINDOWS_PREVIEW_SUBTITLE, color = cs.onSurfaceVariant, fontSize = 11.sp)
            }
        }

        if (signedUp) {
            Text(
                "Thanks — you're on the preview list. We'll email you when native Windows hosting lands.",
                color = cs.primary,
                fontSize = 11.sp,
                modifier = Modifier.testTag("windows_host_preview_thanks"),
            )
        } else {
            Button(
                onClick = { signedUp = onJoinPreview() },
                modifier = Modifier.testTag("windows_host_preview_join").pointerHoverIcon(PointerIcon.Hand),
            ) { Text("Join the preview") }
        }

        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.testTag("windows_host_preview_explain_toggle").pointerHoverIcon(PointerIcon.Hand),
        ) { Text(if (expanded) "Hide details" else "How Windows hosting will work") }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("windows_host_preview_explainer")) {
                Text(
                    "supermux hosts by running a small broker plus tmux next to your agents. macOS and Linux " +
                        "can run these natively today; the native Windows host is in active development.",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                Text(
                    "Advanced: you can host right now from Windows Subsystem for Linux (WSL). Install supermux " +
                        "inside your WSL Ubuntu and it joins your fleet as \"This PC — Ubuntu (WSL)\", a separate host.",
                    color = cs.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                TextButton(
                    onClick = onOpenWslGuide,
                    modifier = Modifier.testTag("windows_host_preview_wsl").pointerHoverIcon(PointerIcon.Hand),
                ) { Text("Open the WSL host guide") }
            }
        }
    }
}
