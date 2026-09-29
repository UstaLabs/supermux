package dev.supermux.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** A plugin's panel from its own state (CM6's `showPanel.from(field, v => v.panel)`): null is none. */
class PanelsFacetTest {
    private val open = StateEffectType<Boolean>("open")
    private val field = StateField("panel", { false }, { v, tr -> tr.effects.firstNotNullOfOrNull { it.valueIf(open) } ?: v })
    private val ext = extensionOf(field, panelsFacet.compute(FacetDep.field(field)) { st -> if (st.field(field)) Panel("search", top = true) else null }, panelsFacet.of(Panel("status", top = false)))

    @Test fun aComputedPanelComesAndGoesWithTheField() {
        val st = EditorState.create("x", extensions = ext)
        assertEquals(listOf(Panel("status", top = false)), st.facet(panelsFacet))
        val shown = st.update(TransactionSpec(effects = listOf(open.of(true)))).state
        assertEquals(listOf(Panel("search", top = true), Panel("status", top = false)), shown.facet(panelsFacet))
        val hidden = shown.update(TransactionSpec(effects = listOf(open.of(false)))).state
        assertEquals(listOf(Panel("status", top = false)), hidden.facet(panelsFacet))
        // Unchanged panels keep their instance: the surface does not recompose for a keystroke.
        assertSame(shown.facet(panelsFacet), shown.update(TransactionSpec(changes = listOf(ChangeSpec(0, 0, "a")))).state.facet(panelsFacet))
    }
}
