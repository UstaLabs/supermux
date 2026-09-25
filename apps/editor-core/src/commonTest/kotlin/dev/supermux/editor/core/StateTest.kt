package dev.supermux.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StateTest {
    private val tabSize = Facet.first("tabSize", 4)
    private val words = Facet.list<String>("words")

    /** Counts transactions that changed the document. */
    private val editCount = StateField<Int>("editCount", create = { 0 }, update = { v, tr -> if (tr.docChanged) v + 1 else v })

    @Test fun updateProducesANewStateAndLeavesTheOldOne() {
        val s0 = EditorState.create("hello", extensions = editCount)
        val tr = s0.update(ChangeSpec(5, 5, "!"), userEvent = "input")
        assertEquals("hello", s0.doc.toString())
        assertEquals("hello!", tr.state.doc.toString())
        assertEquals(0, s0.field(editCount))
        assertEquals(1, tr.state.field(editCount))
        assertTrue(tr.isUserEvent("input"))
    }

    @Test fun selectionIsMappedUnlessSetExplicitly() {
        val s0 = EditorState.create("abc", EditorSelection.cursor(3))
        assertEquals(EditorSelection.cursor(4), s0.update(ChangeSpec(0, 0, "x")).state.selection)
        val explicit = s0.update(ChangeSpec(0, 0, "x"), selection = EditorSelection.cursor(1))
        assertEquals(EditorSelection.cursor(1), explicit.state.selection)
        assertTrue(explicit.selectionSet)
    }

    @Test fun userEventMatchesByDottedPrefix() {
        val tr = EditorState.create("a").update(ChangeSpec(1, 1, "b"), userEvent = "input.ime")
        assertTrue(tr.isUserEvent("input"))
        assertTrue(tr.isUserEvent("input.ime"))
        assertFalse(tr.isUserEvent("in"))
        assertFalse(tr.isUserEvent("paste"))
    }

    @Test fun facetsCombineInPrecedenceOrder() {
        val s = EditorState.create(extensions = extensionOf(
            words.of("default-1"),
            Prec.lowest(words.of("lowest")),
            Prec.highest(words.of("highest")),
            words.of("default-2"),
            tabSize.of(2),
            Prec.high(tabSize.of(8)),
        ))
        assertEquals(listOf("highest", "default-1", "default-2", "lowest"), s.facet(words))
        assertEquals(8, s.facet(tabSize))
        assertEquals(4, EditorState.create().facet(tabSize))
    }

    @Test fun aFieldCanProvideAComputedFacet() {
        val shout = StateField<String>(
            "shout", create = { it.doc.toString().uppercase() }, update = { _, tr -> tr.state.doc.toString().uppercase() },
            provide = { f -> words.compute { st -> st.field(f) } },
        )
        val s = EditorState.create("hi", extensions = shout)
        assertEquals(listOf("HI"), s.facet(words))
        assertEquals(listOf("HIX"), s.update(ChangeSpec(2, 2, "x")).state.facet(words))
    }

    @Test fun effectsReachFields() {
        val setFlag = StateEffectType<Boolean>("setFlag")
        val flag = StateField<Boolean>("flag", { false }, { v, tr -> tr.effects.firstNotNullOfOrNull { it.valueIf(setFlag) } ?: v })
        val s0 = EditorState.create(extensions = flag)
        val s1 = s0.update(TransactionSpec(effects = listOf(setFlag.of(true)))).state
        assertTrue(s1.field(flag))
        assertTrue(s1.update(TransactionSpec()).state.field(flag))
    }

    @Test fun compartmentReconfigureSwapsContentAndKeepsOtherFields() {
        val wrap = Compartment("wrap")
        val lineWrap = Facet.first("lineWrap", false)
        val s0 = EditorState.create("a", extensions = extensionOf(editCount, wrap.of(lineWrap.of(false))))
        val s1 = s0.update(ChangeSpec(1, 1, "b")).state
        val tr = s1.update(TransactionSpec(effects = listOf(wrap.reconfigure(lineWrap.of(true)))))
        assertTrue(tr.reconfigured)
        assertTrue(tr.state.facet(lineWrap))
        assertEquals(1, tr.state.field(editCount)) // survived the reconfiguration
        // and the new content persists through later, ordinary transactions
        assertTrue(tr.state.update(ChangeSpec(2, 2, "c")).state.facet(lineWrap))
    }

    @Test fun reconfigureAddsAndDropsFields() {
        val s0 = EditorState.create("a")
        assertNull(s0.fieldOrNull(editCount))
        val s1 = s0.update(TransactionSpec(effects = listOf(StateEffect.reconfigure.of(editCount)))).state
        assertEquals(0, s1.field(editCount))
        val s2 = s1.update(TransactionSpec(effects = listOf(StateEffect.reconfigure.of(extensionOf())))).state
        assertNull(s2.fieldOrNull(editCount))
    }

    @Test fun theSameExtensionIncludedTwiceCountsOnce() {
        val w = words.of("once")
        assertEquals(listOf("once"), EditorState.create(extensions = extensionOf(w, w)).facet(words))
    }

    @Test fun aDuplicateKeepsItsHighestPrecedencePlace() {
        val w = words.of("x")
        assertEquals(listOf("x", "a"), EditorState.create(extensions = extensionOf(words.of("a"), w, Prec.highest(w))).facet(words))
    }

    @Test fun changesAndAChangeSetTogetherAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            TransactionSpec(changes = listOf(ChangeSpec(0)), changeSet = ChangeSet.empty(0))
        }
    }

}
