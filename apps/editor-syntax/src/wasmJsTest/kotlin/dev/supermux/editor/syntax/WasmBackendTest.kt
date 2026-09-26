package dev.supermux.editor.syntax

import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web backend's own part: loading the module, fetching tables. Everything else is the shared suite. */
class WasmBackendTest {
    private val served = "/base/kotlin/editor-syntax/tables/" // karma.config.d/syntax-wasm.js

    @Test fun theRuntimeWasLoadedBeforeTheTests() {
        assertNull(setupFailure, "syntax-test-setup.mjs")
        assertEquals(SyntaxLanguages.ABI_VERSION, Ses.abiVersion())
    }

    @Test fun loadIsIdempotentAndAnotherModuleUrlIsRefused() = runSuspendTest {
        val a = WasmBackend.load(tablesUrl = served)
        val b = WasmBackend.load(tablesUrl = served)
        assertEquals(a.languages, b.languages)
        val e = assertFailsWith<SyntaxException> { WasmBackend.load("/elsewhere/supermux-syntax.wasm") }
        assertTrue("already loaded" in e.message!!, e.message)
    }

    /** Every code-only grammar's .sesz is fetched over HTTP, is the staged blob, and its grammar parses. */
    @Test fun everyGrammarLoadsOnTheWeb() = runSuspendTest {
        val backend = WasmBackend.load(tablesUrl = served)
        val names = SyntaxLanguages.names()
        var fetched = 0
        for (n in names) {
            val path = NativeBackend.tablesPath(n)
            val staged = SyntaxResources.read(path)
            if (staged != null) {
                val probe = "probe/$n.sesz"
                assertNull(loaderFetchResource(probe, loaderTablesUrl(n)).await(), n)
                assertContentEquals(staged, SyntaxResources.read(probe), "$n: the fetched blob is the staged one")
                loaderDropResource(probe)
                fetched++
                // a grammar not loaded yet must come from the fetch itself, not the prefetched copy
                if (!SyntaxLanguages.hasTables(n)) loaderDropResource(path)
            }
            backend.ensureLanguage(n)
            assertTrue(backend.isReady(n), n)
            backend.newParser(n).use { p -> p.parse(RopeText(Rope.of("x = 1\n")), null).close() }
        }
        assertEquals(29, fetched, "code-only grammars")
        println("WEB grammars: ${names.size} parse, $fetched tables blobs fetched from $served")
    }

    @Test fun aMissingTablesUrlIsNamed() = runSuspendTest {
        val err = loaderFetchResource("probe/none.sesz", "/base/kotlin/editor-syntax/tables/no-such-grammar.sesz").await()
        assertTrue(err != null && "404" in err.toString(), "$err")
        val e = assertFailsWith<SyntaxException> { WasmBackend.load(tablesUrl = served).ensureLanguage("cobol") }
        assertEquals(SyntaxStatus.UNKNOWN_LANGUAGE, e.status)
    }
}
