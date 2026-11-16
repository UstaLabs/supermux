package dev.supermux.ui.chat

import dev.supermux.proto.CompactAdviceDto
import dev.supermux.proto.ContextUsageDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #144/#146: one prompt to compact, shown as a chip by the ring (pointer) or the composer's first line (touch).
 * The usage-limit advice (#146) wins over the near-autocompact one; "Not now" hides each until it changes.
 */
class NearLimitPromptTest {
    private val near = ContextUsageDto(used = 240_000, size = 300_000)
    private val advice = CompactAdviceDto(usedPct = 88, resetsAt = 1_791_651_000_000)

    private fun prompt(
        context: ContextUsageDto? = near, threshold: Long? = 267_000, compactAdvice: CompactAdviceDto? = null,
        compacting: Boolean = false, canCompact: Boolean = true, contextDismissed: Boolean = false, limitDismissedFor: Long? = null,
    ) = nearLimitPrompt(context, threshold, compactAdvice, compacting, canCompact, contextDismissed, limitDismissedFor)

    @Test fun near_autocompact_prompts_with_its_percentage_and_point() {
        assertEquals(NearLimitPrompt.Context(pct = 80, threshold = 267_000), prompt())
        assertNull(prompt(context = ContextUsageDto(100_000, 300_000)))
        // An unknown window (size 0) never prompts.
        assertNull(prompt(context = ContextUsageDto(240_000, 0)))
    }

    @Test fun the_usage_limit_advice_wins() {
        assertEquals(NearLimitPrompt.Limit(usedPct = 88, resetsAt = 1_791_651_000_000), prompt(compactAdvice = advice))
        assertEquals(NearLimitPrompt.Limit(88, 1_791_651_000_000), prompt(context = null, compactAdvice = advice))
    }

    @Test fun nothing_while_compacting_without_compact_or_after_not_now() {
        assertNull(prompt(compacting = true, compactAdvice = advice))
        assertNull(prompt(canCompact = false, compactAdvice = advice))
        assertNull(prompt(contextDismissed = true))
        // Not now on the limit hides it for that window; the near-autocompact prompt may still show.
        assertEquals(NearLimitPrompt.Context(80, 267_000), prompt(compactAdvice = advice, limitDismissedFor = advice.resetsAt))
        assertEquals(NearLimitPrompt.Limit(88, 2L), prompt(compactAdvice = advice.copy(resetsAt = 2L), limitDismissedFor = advice.resetsAt))
    }
}
