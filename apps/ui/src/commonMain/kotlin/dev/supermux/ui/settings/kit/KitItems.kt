// The Settings kit's item lists (#97): providers, logins, accounts, language servers, devices, links — a mark,
// a title and a subtitle, and a quiet status or one ghost button at the end. The prototype's `.items` / `.it`.
package dev.supermux.ui.settings.kit

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What leads an item: two letters, a glyph, or the "+" of an add row. */
sealed interface KitMark {
    data class Letters(val text: String) : KitMark
    data class Glyph(val vector: ImageVector) : KitMark
    data object Add : KitMark
}

/** A list of [KitItem]s: a hairline above the first. */
@Composable
fun KitItemList(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth()) {
        KitHairline()
        content()
    }
}

/**
 * One item. [onClick] makes the whole item open something (a chevron shows unless it is an add row); the
 * [trailing] slot holds a [KitItemStatus] or a [KitGhostButton].
 */
@Composable
fun KitItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    mark: KitMark? = null,
    onClick: (() -> Unit)? = null,
    indent: Boolean = false,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = kitColors()
    val m = kitMetrics()
    val add = mark == KitMark.Add
    val click = if (onClick != null) {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Column(modifier.fillMaxWidth().then(click)) {
        Row(
            Modifier.fillMaxWidth().padding(start = if (indent) 38.dp else 0.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (mark != null) KitMarkBox(mark)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = m.label, color = if (add) c.accent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        fontSize = m.desc,
                        color = c.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), content = trailing)
            if (onClick != null && !add) Icon(KitGlyphs.Chevron, contentDescription = null, tint = c.faint, modifier = Modifier.size(14.dp))
        }
        KitHairline()
    }
}

@Composable
private fun KitMarkBox(mark: KitMark) {
    val c = kitColors()
    Box(
        Modifier.size(26.dp).border(1.dp, c.line, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (mark) {
            is KitMark.Letters -> Text(mark.text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = c.dim)
            is KitMark.Glyph -> Icon(mark.vector, contentDescription = null, tint = c.dim, modifier = Modifier.size(15.dp))
            KitMark.Add -> Icon(KitGlyphs.Plus, contentDescription = null, tint = c.accent, modifier = Modifier.size(15.dp))
        }
    }
}

/** "● Ready", "Sign in again" (warning tone, no dot), "You". */
@Composable
fun KitItemStatus(text: String, dot: KitDot? = null, warn: Boolean = false, modifier: Modifier = Modifier) {
    val c = kitColors()
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (dot != null && !warn) KitDotMark(dot)
        Text(text, fontSize = kitMetrics().desc, color = if (warn) c.warn else c.dim, maxLines = 1)
    }
}
