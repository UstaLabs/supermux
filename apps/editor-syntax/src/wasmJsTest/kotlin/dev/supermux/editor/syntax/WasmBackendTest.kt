package dev.supermux.editor.syntax

import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The web backend's own part: loading the module, fetching tables. Everything else is the shared suite. */
class WasmBackendTest {
    private val served = "/base/kotlin/editor-syntax/tables/" // karma.config.d/syntax-wasm.js; syntax-test-setup.mjs

    @Test fun theRuntimeWasLoadedBeforeTheTests() {
        assertNull(setupFailure, "syntax-test-setup.mjs")
        assertEquals(SyntaxLanguages.ABI_VERSION, Ses.abiVersion())
    }

    @Test fun aRefusedLoadChangesNothing() = runSuspendTest {
        val a = WasmBackend.load(tablesUrl = served)
        val b = WasmBackend.load()
        assertEquals(a.languages, b.languages)
        val before = loaderTablesUrl("ruby")
        val e = assertFailsWith<SyntaxException> { WasmBackend.load("/elsewhere/supermux-syntax.wasm") }
        assertTrue("already loaded" in e.message!!, e.message)
        val t = assertFailsWith<SyntaxException> { WasmBackend.load(tablesUrl = "/elsewhere/tables/") }
        assertTrue("already served" in t.message!!, t.message)
        assertEquals(before, loaderTablesUrl("ruby"))
        assertTrue(before.endsWith("$served" + "ruby.sesz"), before)
    }

    /** Every code-only grammar's .sesz is fetched over HTTP, is the staged blob, and its grammar parses. */
    @Test fun everyGrammarLoadsOnTheWeb() = runSuspendTest {
        val backend = WasmBackend.load(tablesUrl = served)
        val names = SyntaxLanguages.names()
        var fetched = 0
        for (n in names) {
            val staged = SyntaxResources.read(NativeBackend.tablesPath(n))
            if (staged != null) {
                val probe = "probe/$n.sesz"
                assertNull(loaderFetchResource(probe, loaderTablesUrl(n)).await(), n)
                assertContentEquals(staged, SyntaxResources.read(probe), "$n: the fetched blob is the staged one")
                loaderDropResource(probe)
                fetched++
            }
            backend.ensureLanguage(n)
            assertTrue(backend.isReady(n), n)
            backend.newParser(n).use { p -> p.parse(RopeText(Rope.of("x = 1\n")), null).close() }
        }
        assertEquals(29, fetched, "code-only grammars")
        println("WEB grammars: ${names.size} parse, $fetched tables blobs fetched from $served")
    }

    @Test fun aMissingTablesUrlIsNamed() = runSuspendTest {
        val err = loaderFetchResource("probe/none.sesz", "$served" + "no-such-grammar.sesz").await()
        assertTrue(err != null && "404" in err.toString(), "$err")
        val e = assertFailsWith<SyntaxException> { WasmBackend.load(tablesUrl = served).ensureLanguage("cobol") }
        assertEquals(SyntaxStatus.UNKNOWN_LANGUAGE, e.status)
    }

    /** A refused blob is not kept: the next ensureLanguage fetches again (here: the right one). */
    @Test fun badTablesAreFetchedAgain() = runSuspendTest {
        withFreshRuntime {
            var bad = true
            val b = WasmBackend(NativeBackend(tables = { null })) { lang ->
                if (bad) "/base/kotlin/sesz/fsharp-tampered.sesz" else "$served$lang.sesz"
            }
            assertFalse(b.isReady("fsharp"))
            val e = assertFailsWith<SyntaxException> { b.ensureLanguage("fsharp") }
            assertEquals(SyntaxStatus.BAD_TABLES, e.status)
            assertFalse(loaderHasResource("wasm-backend/fsharp.sesz"), "a refused blob is dropped")
            assertFalse(b.isReady("fsharp"))
            bad = false
            b.ensureLanguage("fsharp")
            assertTrue(b.isReady("fsharp"))
            assertFalse(loaderHasResource("wasm-backend/fsharp.sesz"), "a provided blob is not kept twice")
            b.newParser("fsharp").use { p -> p.parse(RopeText(Rope.of("let x = 1\n")), null).use { assertFalse(it.hasError) } }
        }
    }
}

/**
 * Run [block] against a NEW wasm instance (nothing loaded, its own memory) as the process runtime,
 * then put the shared one back. Every handle made inside must be freed inside, a worker's too (close
 * AND join it: it frees on its own coroutine). A pointer of one instance freed into another corrupts
 * that one (the loader then reports it dead): handles do not know their runtime.
 */
internal suspend fun withFreshRuntime(block: suspend (SyntaxRuntime) -> Unit) {
    val fresh = loaderLoadRuntime(null).await()
    val previous = loaderUseRuntimeForTests(fresh)
    try {
        block(fresh)
    } finally {
        loaderUseRuntimeForTests(previous)
    }
}
