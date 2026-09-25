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

    @Test fun rootAndCompartmentReconfigureInOneTransactionBothApply() {
        val wrap = Compartment("wrap")
        val lineWrap = Facet.first("lineWrap", false)
        val s0 = EditorState.create("a", extensions = wrap.of(lineWrap.of(false)))
        val newRoot = extensionOf(editCount, wrap.of(lineWrap.of(false)))
        for (effects in listOf(
            listOf(StateEffect.reconfigure.of(newRoot), wrap.reconfigure(lineWrap.of(true))),
            listOf(wrap.reconfigure(lineWrap.of(true)), StateEffect.reconfigure.of(newRoot)),
        )) {
            val s1 = s0.update(TransactionSpec(effects = effects)).state
            assertTrue(s1.facet(lineWrap), "compartment content")
            assertEquals(0, s1.field(editCount), "new root")
        }
    }

    @Test fun staticFacetValuesAreSharedAcrossStates() {
        val s0 = EditorState.create("a", extensions = extensionOf(words.of("w"), editCount))
        val s1 = s0.update(ChangeSpec(1, 1, "b")).state
        assertSame(s0.facet(words), s1.facet(words))
    }

    @Test fun unchangedDynamicFacetValuesAreReused() {
        val s0 = EditorState.create("a", extensions = words.compute { st -> if (st.doc.length > 3) "long" else "short" })
        val s1 = s0.update(ChangeSpec(1, 1, "b")).state
        assertEquals(listOf("short"), s1.facet(words))
        assertSame(s0.facet(words), s1.facet(words))
        val s2 = s1.update(ChangeSpec(2, 2, "cde")).state
        assertEquals(listOf("long"), s2.facet(words))
    }

    @Test fun aCustomCompareDecidesWhenAFacetValueIsReused() {
        // Compares only the length, so a same-length edit keeps the old value.
        val docText = Facet.define<String, String>("docText", compare = { a, b -> a.length == b.length }) { it.joinToString("") }
        val s0 = EditorState.create("ab", extensions = docText.compute { it.doc.toString() })
        val s1 = s0.update(ChangeSpec(0, 1, "x")).state
        assertSame(s0.facet(docText), s1.facet(docText))
        assertEquals("ab", s1.facet(docText))
        assertEquals("xbc", s1.update(ChangeSpec(2, 2, "c")).state.facet(docText))
    }

    @Test fun nestedComputedFacetsRunOncePerState() {
        var cRuns = 0
        val a = Facet.first("a", 0); val b = Facet.first("b", 0); val c = Facet.first("c", 0)
        val s0 = EditorState.create("x", extensions = extensionOf(
            a.compute { it.facet(b) + it.facet(c) },
            b.compute { it.facet(c) + 1 },
            c.compute { cRuns++; it.doc.length },
        ))
        assertEquals(1, cRuns)
        assertEquals(3, s0.facet(a))
        s0.update(ChangeSpec(1, 1, "y"))
        assertEquals(2, cRuns)
    }

    @Test fun aFacetCycleFailsWithAClearError() {
        val p = Facet.first("p", 0); val q = Facet.first("q", 0)
        val e = assertFailsWith<IllegalStateException> {
            EditorState.create(extensions = extensionOf(p.compute { it.facet(q) }, q.compute { it.facet(p) }))
        }
        val message = e.message.orEmpty()
        assertTrue("Facet(p)" in message && "Facet(q)" in message, message)
    }

    @Test fun fieldsThatProvideThemselvesOrEachOtherResolve() {
        lateinit var self: StateField<Int>
        self = StateField("self", { 0 }, { v, _ -> v }, provide = { f -> extensionOf(f, words.of("self")) })
        val s = EditorState.create(extensions = self)
        assertEquals(0, s.field(self))
        assertEquals(listOf("self"), s.facet(words))

        lateinit var one: StateField<Int>
        lateinit var two: StateField<Int>
        one = StateField("one", { 1 }, { v, _ -> v }, provide = { two })
        two = StateField("two", { 2 }, { v, _ -> v }, provide = { one })
        val t = EditorState.create(extensions = one)
        assertEquals(1, t.field(one))
        assertEquals(2, t.field(two))
    }

    @Test fun anEqualStaticFacetKeepsItsInstanceThroughAReconfigure() {
        var compares = 0
        val counted = Facet.define<String, List<String>>("counted", compare = { x, y -> compares++; x == y }) { it }
        val slot = Compartment("slot")
        // Records what a field sees while the state is still being built.
        val seen = StateField<List<String>>("seen", { it.facet(counted) }, { _, tr -> tr.state.facet(counted) })
        val s0 = EditorState.create(extensions = extensionOf(slot.of(counted.of("a")), seen))
        val s1 = s0.update(TransactionSpec(effects = listOf(slot.reconfigure(counted.of("a"))))).state
        assertSame(s0.facet(counted), s1.facet(counted))
        assertSame(s1.facet(counted), s1.field(seen))
        val before = compares
        val s2 = s1.update(ChangeSpec(0, 0, "x")).state
        assertSame(s1.facet(counted), s2.facet(counted))
        assertEquals(before, compares, "an unchanged configuration never deep-compares static values")
    }

    @Test fun aFacetWithoutProvidersReturnsOneInstance() {
        val empty = Facet.list<String>("empty")
        val s = EditorState.create()
        assertSame(s.facet(empty), s.facet(empty))
        assertSame(s.facet(empty), s.update(ChangeSpec(0, 0, "x")).state.facet(empty))
    }

    @Test fun aDocDependentProviderSkipsSelectionOnlyTransactions() {
        var runs = 0
        var doubleRuns = 0
        val len = Facet.first("len", 0)
        val double = Facet.first("double", 0)
        val s0 = EditorState.create("abc", extensions = extensionOf(
            len.compute(FacetDep.Doc) { runs++; it.doc.length },
            double.compute(FacetDep.facet(len)) { doubleRuns++; it.facet(len) * 2 },
        ))
        assertEquals(1, runs); assertEquals(1, doubleRuns)
        val s1 = s0.update(TransactionSpec(selection = EditorSelection.cursor(2))).state
        assertEquals(1, runs, "doc unchanged"); assertEquals(1, doubleRuns, "len unchanged")
        assertEquals(3, s1.facet(len)); assertEquals(6, s1.facet(double))
        val s2 = s1.update(ChangeSpec(0, 0, "z")).state
        assertEquals(2, runs); assertEquals(2, doubleRuns)
        assertEquals(8, s2.facet(double))
    }

    @Test fun aFieldDependentProviderRunsOnlyWhenTheFieldChanges() {
        var runs = 0
        val count = Facet.first("count", -1)
        val s0 = EditorState.create("a", extensions = extensionOf(editCount, count.compute(FacetDep.field(editCount)) { runs++; it.field(editCount) }))
        assertEquals(1, runs)
        val s1 = s0.update(TransactionSpec(selection = EditorSelection.cursor(1))).state
        assertEquals(1, runs)
        val s2 = s1.update(ChangeSpec(1, 1, "b")).state
        assertEquals(2, runs)
        assertEquals(1, s2.facet(count))
    }

    @Test fun aProviderWithoutDepsRunsEveryState() {
        var runs = 0
        val s0 = EditorState.create("a", extensions = words.compute { runs++; "w" })
        s0.update(TransactionSpec(selection = EditorSelection.cursor(1)))
        assertEquals(2, runs)
    }

    @Test fun aFieldDepComparesBoxedValuesByEquality() {
        var runs = 0
        // 1000 is outside the boxing cache, so an unchanged value comes back as a new box.
        val big = StateField<Int>("big", { 1000 }, { v, tr -> if (tr.docChanged) v + 1 else v })
        val out = Facet.first("out", 0)
        val s0 = EditorState.create("a", extensions = extensionOf(big, out.compute(FacetDep.field(big)) { runs++; it.field(big) }))
        val s1 = s0.update(TransactionSpec(selection = EditorSelection.cursor(1))).state
        assertEquals(1, runs, "unchanged value")
        assertEquals(1001, s1.update(ChangeSpec(1, 1, "b")).state.facet(out))
        assertEquals(2, runs)
    }

    @Test fun aCycleInDeclaredFacetDepsFailsInCreate() {
        val p = Facet.first("p", 0); val q = Facet.first("q", 0)
        val e = assertFailsWith<IllegalArgumentException> {
            EditorState.create(extensions = extensionOf(p.compute(FacetDep.facet(q)) { 0 }, q.compute(FacetDep.facet(p)) { 0 }))
        }
        val message = e.message.orEmpty()
        assertTrue("Facet(p)" in message && "Facet(q)" in message, message)
    }

    @Test fun aFacetThatLosesItsProvidersKeepsAnEqualInstance() {
        val flat = Facet.define<List<String>, List<String>>("flat") { it.flatten() }
        val slot = Compartment("slot")
        val s0 = EditorState.create(extensions = slot.of(flat.of(emptyList())))
        val s1 = s0.update(TransactionSpec(effects = listOf(slot.reconfigure(extensionOf())))).state
        assertEquals(emptyList(), s1.facet(flat))
        assertSame(s0.facet(flat), s1.facet(flat))
    }
}
