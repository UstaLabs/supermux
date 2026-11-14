package dev.supermux.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #148 (Ahmet's screenshot): a fenced code block indented under a list item vanished; the item and the next paragraph
 * rendered. A list item can hold any blocks (CommonMark list-item continuation), and every one of them is drawn, in
 * order, after the item's own line.
 */
class ListItemBlocksTest {
    private val ps = "Set-ExecutionPolicy -Scope CurrentUser RemoteSigned"

    @Test fun aFenceUnderANumberedItemStays() {
        val b = parseMarkdownBlocks("1. **Open PowerShell.**\n2. **Paste this and press Enter:**\n   ```powershell\n   $ps\n   ```\n\nThen restart.")
        assertEquals(
            listOf(
                MdBlock.Numbered(1, "**Open PowerShell.**"),
                MdBlock.Numbered(2, "**Paste this and press Enter:**"),
                MdBlock.Code("powershell", ps),
                MdBlock.Prose("Then restart."),
            ),
            b,
        )
    }

    @Test fun aFenceUnderABulletStays() {
        assertEquals(
            listOf(MdBlock.Bullet("run it:"), MdBlock.Code(null, "make all")),
            parseMarkdownBlocks("- run it:\n\n  ```\n  make all\n  ```"),
        )
    }

    @Test fun anyIndentFromTwoToFourSpacesKeepsTheCode() {
        for (indent in listOf("  ", "   ", "    ")) {
            val b = parseMarkdownBlocks("-  step\n$indent```sh\n${indent}ls -l\n$indent```")
            assertEquals(MdBlock.Code("sh", "ls -l"), b.single { it is MdBlock.Code }, "indent ${indent.length}")
        }
    }

    @Test fun aTabIndentKeepsTheCode() {
        val b = parseMarkdownBlocks("1. step\n\t```\n\techo hi\n\t```")
        assertEquals(MdBlock.Code(null, "echo hi"), b.single { it is MdBlock.Code })
    }

    @Test fun severalBlocksInOneItemKeepTheirOrder() {
        val b = parseMarkdownBlocks("1. First:\n   ```\n   a\n   ```\n\n   then also\n\n   ```\n   b\n   ```\n2. Next")
        assertEquals(
            listOf(
                MdBlock.Numbered(1, "First:"),
                MdBlock.Code(null, "a"),
                MdBlock.Prose("then also"),
                MdBlock.Code(null, "b"),
                MdBlock.Numbered(2, "Next"),
            ),
            b,
        )
    }

    @Test fun aNestedListStillNests() {
        assertEquals(
            listOf(MdBlock.Bullet("a"), MdBlock.Bullet("b", depth = 1), MdBlock.Bullet("c")),
            parseMarkdownBlocks("- a\n  - b\n- c"),
        )
    }
}
