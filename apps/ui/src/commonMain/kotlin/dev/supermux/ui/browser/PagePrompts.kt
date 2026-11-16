package dev.supermux.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.webview.PermissionKind

/**
 * A site's permission question, as a strip under the tab's bars (#157): who asks, for what, and Block or Allow. It stays
 * until answered; the page waits meanwhile, as it does in a browser. [touch]: a phone's taller strip.
 */
@Composable
internal fun PermissionStrip(prompt: PermissionPrompt, touch: Boolean, modifier: Modifier = Modifier) {
    val c = kitColors()
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = if (touch) 52.dp else 40.dp)
            .background(c.accent.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("browser-permission"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            permissionQuestion(siteName(prompt.origin), prompt.kinds),
            fontSize = if (touch) 14.sp else 13.sp,
            fontWeight = FontWeight.Medium,
            color = c.text,
            modifier = Modifier.weight(1f),
        )
        KitGhostButton(localized(Str.page_permission_block), { prompt.answer(false) }, Modifier.testTag("browser-permission-block"))
        KitGhostButton(localized(Str.page_permission_allow), { prompt.answer(true) }, Modifier.testTag("browser-permission-allow"))
    }
}

/** What a site is called where the user reads it: its host, and its port where it has one. */
internal fun siteName(origin: String): String = origin.substringAfter("://").trimEnd('/') // platform-ok: url

/** "example.com wants to use your camera", in the app's language. */
internal fun permissionQuestion(site: String, kinds: Set<PermissionKind>): String {
    val camera = PermissionKind.Camera in kinds
    val mic = PermissionKind.Microphone in kinds
    val key = when {
        camera && mic -> Str.page_permission_camera_microphone
        camera -> Str.page_permission_camera
        mic -> Str.page_permission_microphone
        PermissionKind.Geolocation in kinds -> Str.page_permission_location
        PermissionKind.Notifications in kinds -> Str.page_permission_notifications
        PermissionKind.ClipboardRead in kinds -> Str.page_permission_clipboard
        else -> Str.page_permission_other
    }
    return localized(key, site)
}

/** A kind's name in the Settings list. */
internal fun permissionKindName(kind: String): String = when (kind) {
    PermissionKind.Camera.name -> localized(Str.site_kind_camera)
    PermissionKind.Microphone.name -> localized(Str.site_kind_microphone)
    PermissionKind.Geolocation.name -> localized(Str.site_kind_location)
    PermissionKind.Notifications.name -> localized(Str.site_kind_notifications)
    PermissionKind.ClipboardRead.name -> localized(Str.site_kind_clipboard)
    else -> kind
}
