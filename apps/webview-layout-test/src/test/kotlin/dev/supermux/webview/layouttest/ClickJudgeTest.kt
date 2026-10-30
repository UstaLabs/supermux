package dev.supermux.webview.layouttest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClickJudgeTest {
    private val probe = ClickProbe("menu-item-1", 620f, 400f, Side.Compose)

    @Test fun onlyTheExpectedSidePasses() {
        val verdict = ClickJudge.judge(probe, listOf(Received(Side.Compose, "menu-item-1", 1_100)), from = 1_000)
        assertTrue(verdict.pass)
        assertEquals("expected Compose, got Compose (Compose:menu-item-1)", verdict.detail())
    }

    @Test fun bothSidesFail() {
        val verdict = ClickJudge.judge(
            probe,
            listOf(Received(Side.Page, "down@BODY", 1_050), Received(Side.Compose, "menu-item-1", 1_060)),
            from = 1_000,
        )
        assertFalse(verdict.pass)
        assertEquals("expected Compose, got Compose+Page (Page:down@BODY, Compose:menu-item-1)", verdict.detail())
    }

    @Test fun nothingFails() {
        val verdict = ClickJudge.judge(probe, emptyList(), from = 1_000)
        assertFalse(verdict.pass)
        assertEquals("expected Compose, got nothing", verdict.detail())
    }

    @Test fun onlyTheClicksWindowCounts() {
        val received = listOf(Received(Side.Page, "early", 999), Received(Side.Compose, "late", 1_701), Received(Side.Compose, "ok", 1_700))
        assertEquals(listOf("Compose:ok"), ClickJudge.judge(probe, received, from = 1_000).what)
    }
}
