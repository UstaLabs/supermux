package dev.supermux.ui.shell

import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.workspace.LayoutNode
import dev.supermux.workspace.NewViewKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewTabsTest {
    private val real = LayoutNode.Group("g", listOf("a", "b"), "b")
    private val p = LayoutNode.Group("p", listOf("x"), "x")
    private val files = ViewDto("files", "w", "editor", state = buildJsonObject { put("mode", JsonPrimitive("tree")) })
    private val ws = WorkspaceDto(id = "w", name = "Supermux PM", workdir = "/home/u/projects/supermux", repoRoot = "/home/u/projects/supermux", branch = "main")

    @Test fun splitRightOnOnePaneMakesTwoColumns() {
        assertEquals(LayoutNode.Split("row", listOf(0.5, 0.5), listOf(real, p)), withPaneAtEdge(real, "row", p))
    }

    @Test fun splitRightOnColumnsAddsTheRightmostAndKeepsTheirProportions() {
        val cols = LayoutNode.Split("row", listOf(0.6, 0.4), listOf(LayoutNode.Group("a", listOf("1")), LayoutNode.Group("b", listOf("2"))))
        val out = withPaneAtEdge(cols, "row", p) as LayoutNode.Split
        assertEquals(listOf("a", "b", "p"), out.children.map { (it as LayoutNode.Group).id })
        assertEquals(listOf(0.4, 0.8 / 3, 1.0 / 3).map { Math.round(it * 1e6) }, out.sizes.map { Math.round(it * 1e6) })
    }

    @Test fun splitDownUnderRowsAddsOneMoreFullWidthRow() {
        val rows = LayoutNode.Split("column", listOf(0.6, 0.4), listOf(LayoutNode.Group("t", listOf("1")), LayoutNode.Group("b", listOf("2"))))
        val out = withPaneAtEdge(rows, "column", p) as LayoutNode.Split
        assertEquals(listOf("t", "b", "p"), out.children.map { (it as LayoutNode.Group).id })
    }

    @Test fun theButtonsSitOnTheTopRightPane() {
        val two = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(LayoutNode.Group("g1", listOf("a")), LayoutNode.Group("g2", listOf("b"))))
        assertEquals("g2", topRightGroupId(LayoutNode.Split("column", listOf(0.6, 0.4), listOf(two, LayoutNode.Group("g3", listOf("c"))))))
    }

    @Test fun filesAlreadyOpenIsASwitchToIt() {
        val tree = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(real, LayoutNode.Group("g2", listOf("files"), "files")))
        assertEquals(NewTabPick.SwitchTo("files", "g2"), newTabPick(NewViewKind.EDITOR, tree, mapOf("files" to files)))
    }

    @Test fun aTerminalIsAlwaysANewView() {
        val tree = LayoutNode.Group("g", listOf("files"), "files")
        assertEquals(NewTabPick.Add, newTabPick(NewViewKind.TERMINAL, tree, mapOf("files" to files)))
    }

    @Test fun everyKindGetsARowSayingWhatItIsFor() {
        val rows = newTabChoices(ws, real, emptyMap())
        assertEquals(listOf("Chat", "Terminal", "Files", "Changes", "Display", "Browser"), rows.map { it.label })
        assertEquals("A shell in supermux", rows[1].detail)
        assertEquals("A web page, or an app running on a port", rows[5].detail)
        assertEquals(NewTabGlyphs.Browser, rows[5].icon)
    }

    @Test fun anOpenFilesRowSaysSo() {
        val tree = LayoutNode.Group("g", listOf("files"), "files")
        assertTrue(newTabChoices(ws, tree, mapOf("files" to files)).single { it.kind == NewViewKind.EDITOR }.alreadyOpen)
    }

    @Test fun thePhoneGetsItsOwnKinds() {
        assertEquals(phoneAddKinds(), newTabChoices(ws, real, emptyMap(), phoneAddKinds()).map { it.kind })
    }
}
