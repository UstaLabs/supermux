// #167: the sheet a share from another app opens — what's shared, which agent, a message, Send. Hosted by Android's
// ShareActivity (iOS has its own SwiftUI sheet in the share extension, drawn after this one).
package dev.supermux.ui.share

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ShortText
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.share.ShareItem
import dev.supermux.share.ShareTarget
import dev.supermux.share.ShareTargets
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.i18n.uppercaseInAppLanguage
import dev.supermux.ui.session.AgentAvatar
import dev.supermux.ui.session.AvatarRig
import dev.supermux.ui.session.AvatarRigView

/** What the sheet shows about one shared thing. */
data class SharePreview(val item: ShareItem, val thumb: ImageBitmap? = null)

/**
 * The share sheet's content: [items] shared, the [targets] to pick from ([preselected] when the share came from a
 * Direct Share shortcut), and [onSend] with the chosen target and the message. [sending] shows the send in progress.
 */
@Composable
fun ShareSheet(
    items: List<SharePreview>,
    targets: List<ShareTarget>,
    preselected: ShareTarget?,
    avatars: Map<String, AvatarRig>,
    sending: Boolean,
    onClose: () -> Unit,
    onSend: (ShareTarget, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    var picked by remember { mutableStateOf(preselected) }
    var agentsTab by remember { mutableStateOf(preselected?.isAgent ?: true) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    val hosts = remember(targets) { targets.map { it.hostId to it.hostName }.distinct() }
    var hostFilter by remember { mutableStateOf<String?>(null) }
    val shown = ShareTargets.filter(targets, agentsTab, hostFilter, query)
    val multiHost = hosts.size > 1

    Surface(
        modifier.fillMaxSize().testTag("share_sheet"),
        color = cs.background,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.testTag("share_close")) { Icon(Icons.Default.Close, localized(Str.share_close)) }
                Text(localized(Str.share_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = 4.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SharedPreviewCard(items) }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(localized(Str.share_to).uppercaseInAppLanguage(), color = cs.onSurfaceVariant, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
                        if (multiHost) {
                            TextButton(onClick = {
                                val order = listOf<String?>(null) + hosts.map { it.first }
                                hostFilter = order[(order.indexOf(hostFilter) + 1) % order.size]
                            }, modifier = Modifier.testTag("share_host")) {
                                Text(localized(Str.share_host, hosts.firstOrNull { it.first == hostFilter }?.second ?: localized(Str.share_host_all)), fontSize = 12.sp)
                            }
                        }
                    }
                }
                item {
                    TextField(
                        value = query, onValueChange = { query = it }, singleLine = true,
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text(localized(Str.share_search)) },
                        shape = RoundedCornerShape(12.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = cs.surfaceVariant, unfocusedContainerColor = cs.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("share_search"),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = agentsTab, onClick = { agentsTab = true }, label = { Text(localized(Str.share_tab_agents)) }, modifier = Modifier.testTag("share_tab_agents"))
                        FilterChip(selected = !agentsTab, onClick = { agentsTab = false }, label = { Text(localized(Str.share_tab_sessions)) }, modifier = Modifier.testTag("share_tab_sessions"))
                    }
                }
                if (shown.isEmpty()) item {
                    Text(
                        localized(if (targets.isEmpty()) Str.share_none else Str.share_no_match),
                        color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
                items(shown, key = { "${it.hostId}/${it.sessionId}" }) { t ->
                    TargetRow(t, avatars[t.sessionId], selected = picked?.hostId == t.hostId && picked?.sessionId == t.sessionId, showHost = multiHost) { picked = t }
                }
            }
            Column(Modifier.fillMaxWidth().background(cs.background).padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp)) {
                Text(
                    picked?.let { "${localized(Str.share_to)} ${it.name}" } ?: localized(Str.share_pick),
                    color = cs.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    OutlinedTextField(
                        value = message, onValueChange = { message = it },
                        placeholder = { Text(localized(Str.share_message)) },
                        shape = RoundedCornerShape(20.dp), maxLines = 5,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("share_message"),
                    )
                    Spacer(Modifier.width(8.dp))
                    val target = picked
                    FilledIconButton(
                        onClick = { if (target != null && !sending) onSend(target, message) },
                        enabled = target != null && !sending,
                        modifier = Modifier.size(48.dp).testTag("share_send"),
                    ) {
                        if (sending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                        else Icon(Icons.AutoMirrored.Filled.Send, localized(Str.share_send))
                    }
                }
            }
        }
    }
}

@Composable
private fun SharedPreviewCard(items: List<SharePreview>) {
    val cs = MaterialTheme.colorScheme
    val files = items.filter { it.item.path != null }
    val first = items.firstOrNull()?.item
    val firstText = first?.text
    val (title, sub) = when {
        items.size > 1 -> localizedPlural(Plurals.share_items, items.size, items.size) to (files.mapNotNull { it.item.name }.joinToString(", ").ifEmpty { first?.text.orEmpty() })
        first?.path != null -> (first.name ?: "") to listOfNotNull(first.mime, dev.supermux.ui.worktree.formatBytes(first.size).takeIf { first.size > 0 }).joinToString(" · ")
        firstText != null && isLink(firstText) -> firstText to localized(Str.share_link)
        else -> (first?.text.orEmpty()) to localized(Str.share_text)
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface).border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.take(3).forEach { Thumb(it, if (items.size > 1) 46 else 56) }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Thumb(p: SharePreview, size: Int) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val thumb = p.thumb
    val text = p.item.text
    if (thumb != null) {
        Image(thumb, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size.dp).clip(shape))
    } else {
        val icon = when {
            p.item.path != null -> Icons.Default.Description
            text != null && isLink(text) -> Icons.Default.Link
            else -> Icons.AutoMirrored.Filled.ShortText
        }
        Box(Modifier.size(size.dp).clip(shape).background(cs.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = cs.onSurfaceVariant)
        }
    }
}

@Composable
private fun TargetRow(t: ShareTarget, rig: AvatarRig?, selected: Boolean, showHost: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) cs.primary.copy(alpha = 0.10f) else cs.surface)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("share_target_${t.sessionId}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            rig?.let { AvatarRigView(it, state = "idle", seed = t.sessionId, size = 36) } ?: AgentAvatar(t.name, online = true, working = false, size = 36)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(t.preview, t.hostName.takeIf { showHost }).joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

/** The result bar after Send, at the bottom of the app the user shared from. */
@Composable
fun ShareResultBar(text: String, action: String?, queued: Boolean, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF2A2C29)).padding(horizontal = 14.dp, vertical = 12.dp).testTag("share_result"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (queued) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color(0xFFF2F2F2), fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction, modifier = Modifier.testTag("share_result_action")) { Text(action, fontWeight = FontWeight.SemiBold) }
    }
}

internal fun isLink(s: String): Boolean = s.trim().let { (it.startsWith("http://") || it.startsWith("https://")) && ' ' !in it && '\n' !in it }
