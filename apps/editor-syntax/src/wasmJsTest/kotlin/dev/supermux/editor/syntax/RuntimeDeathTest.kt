package dev.supermux.editor.syntax

import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.TransactionSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A trap (tree-sitter's allocator abort()s on out of memory; wasi-libc traps) kills the runtime:
 * every later call fails at once with RUNTIME_DEAD, the worker turns syntax off, closing frees nothing.
 */
class RuntimeDeathTest {
    @Test fun aTrapTurnsSyntaxOffAndLaterCallsFailCleanly() = runSuspendTest {
        withFreshRuntime { rt ->
            val backend = NativeBackend()
            backend.ensureLanguageNow("kotlin")
            val host = Host(HighlightSamples.KOTLIN, "kotlin", backend)
            try {
                host.viewport(0 until HighlightSamples.KOTLIN.length)
                host.settle()
                assertFalse(host.spans.isEmpty, "highlighted before the trap")
                runCatching { rt.debugTrap() }
                assertTrue(rt.dead() != null, "the trap marked the runtime dead")
                host.dispatch(TransactionSpec(listOf(ChangeSpec(0, 0, "// typed\n"))))
                host.settle()
                assertTrue(Syntax.isOff(host.state), "syntax is off")
                assertTrue(host.spans.isEmpty)
                assertEquals(SyntaxStatus.RUNTIME_DEAD, (host.worker.lastError as SyntaxException).status)
                val e = assertFailsWith<SyntaxException> { Ses.parserNew() }
                assertEquals(SyntaxStatus.RUNTIME_DEAD, e.status)
            } finally {
                host.worker.close()
                host.worker.join() // frees into a dead runtime: nothing, and no throw
                host.close()
            }
        }
        // the shared runtime is untouched
        testBackend().newParser("kotlin").use { p -> p.parse(RopeText(dev.supermux.editor.core.Rope.of("val a = 1\n")), null).close() }
    }
}
