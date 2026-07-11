package dev.supermux.desktop.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/** The SMX_KCEF_EXTRA_ARGS whitespace parser (punch-list d). */
class KcefExtraArgsTest {

    @Test
    fun null_and_blank_yield_no_args() {
        assertEquals(emptyList(), parseExtraArgs(null))
        assertEquals(emptyList(), parseExtraArgs(""))
        assertEquals(emptyList(), parseExtraArgs("   "))
        assertEquals(emptyList(), parseExtraArgs("\t\n "))
    }

    @Test
    fun single_switch() {
        assertEquals(listOf("--in-process-gpu"), parseExtraArgs("--in-process-gpu"))
    }

    @Test
    fun multiple_switches_split_on_any_whitespace_run() {
        assertEquals(
            listOf("--in-process-gpu", "--disable-gpu-sandbox"),
            parseExtraArgs("--in-process-gpu   --disable-gpu-sandbox"),
        )
        assertEquals(
            listOf("--a", "--b", "--c"),
            parseExtraArgs("  --a\t--b \n --c  "),
        )
    }

    @Test
    fun no_quoting_a_spaced_value_splits_into_two_tokens() {
        // Documented limitation: no quote handling. `--foo=a b` becomes two tokens.
        assertEquals(listOf("--foo=a", "b"), parseExtraArgs("--foo=a b"))
    }
}
