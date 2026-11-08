// The machine page of Step 1 (#97 spec §5): the active machine's own screens, one row each, until Step 2 replaces
// them with the real machine page. Each row opens today's screen as a sub-page.
package dev.supermux.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.ui.settings.MachineSub
import dev.supermux.ui.settings.kit.KitGlyphs
import dev.supermux.ui.settings.kit.KitItem
import dev.supermux.ui.settings.kit.KitItemList
import dev.supermux.ui.settings.kit.KitMark
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.currentMachineName
import dev.supermux.ui.settings.kit.kitAnchor

@Composable
fun InterimMachinePage(
    onOpen: (sub: String) -> Unit,
    showComputer: Boolean,
    modifier: Modifier = Modifier,
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
) {
    KitPage(currentMachineName(), modifier, anchor = anchor, onAnchorShown = onAnchorShown, tag = "machine_page") {
        KitItemList {
            KitItem(
                "Status & updates", Modifier.kitAnchor(MachineSub.STATUS).testTag("machine_row_status"),
                subtitle = "Version, updates and restart", mark = KitMark.Glyph(KitGlyphs.Gauge),
                onClick = { onOpen(MachineSub.STATUS) },
            )
            if (showComputer) {
                KitItem(
                    "This computer", Modifier.kitAnchor(MachineSub.COMPUTER).testTag("machine_row_computer"),
                    subtitle = "Host here, keep running, stay awake, reach it from anywhere",
                    mark = KitMark.Glyph(KitGlyphs.Laptop), onClick = { onOpen(MachineSub.COMPUTER) },
                )
            }
            KitItem(
                "Devices", Modifier.kitAnchor(MachineSub.DEVICES).testTag("machine_row_devices"),
                subtitle = "Phones and computers you paired", mark = KitMark.Glyph(KitGlyphs.Devices),
                onClick = { onOpen(MachineSub.DEVICES) },
            )
            KitItem(
                "Shared links", Modifier.kitAnchor(MachineSub.LINKS).testTag("machine_row_links"),
                subtitle = "Ports shared from chats", mark = KitMark.Glyph(KitGlyphs.Link),
                onClick = { onOpen(MachineSub.LINKS) },
            )
            KitItem(
                "Worktrees", Modifier.kitAnchor(MachineSub.WORKTREES).testTag("machine_row_worktrees"),
                subtitle = "Disk used by chats, clean up", mark = KitMark.Glyph(KitGlyphs.Tree),
                onClick = { onOpen(MachineSub.WORKTREES) },
            )
        }
    }
}
