// #117 W1: "Add widget" on an iPhone. iOS has no way for an app to place a widget, so the menu shows how instead: a
// preview of the "Applets" widget with his own pinned applets, and the three steps on the Home screen.
package dev.supermux.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.pinned
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppletWidgetHowTo(actions: AppletsActions, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = cs.surfaceContainerLow,
        contentColor = cs.onSurface,
        modifier = Modifier.testTag("applet_widget_howto"),
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(localized(Str.applets_widget_ios_title), fontSize = 19.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            // The widget as it will look: his first four pinned applets.
            Column(
                Modifier.widthIn(max = 280.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainerHighest).padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.GridView, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(localized(Str.sessions_applets_title), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    actions.applets.pinned().take(4).forEach { a ->
                        Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AppletIcon(a, 46.dp, actions.loadIcon)
                            Spacer(Modifier.height(4.dp))
                            Text(a.title.ifBlank { a.name }, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Str.applets_widget_ios_step1, Str.applets_widget_ios_step2, Str.applets_widget_ios_step3).forEachIndexed { i, step ->
                    Row {
                        Box(Modifier.width(22.dp)) { Text("${i + 1}.", fontSize = 15.sp, color = cs.onSurfaceVariant) }
                        Text(localized(step), fontSize = 15.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(localized(Str.applets_widget_ios_note), fontSize = 13.sp, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("applet_widget_howto_done")) {
                Text(localized(Str.applets_cover_done), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
