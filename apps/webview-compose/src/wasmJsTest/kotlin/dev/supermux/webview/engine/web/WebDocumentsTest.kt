package dev.supermux.webview.engine.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebDocumentsTest {
    @Test fun theScriptGoesRightAfterADoctypeSoThePageStaysInStandardsMode() {
        assertEquals("<!DOCTYPE html><script>s()</script><p>x", WebDocuments.withScript("<!DOCTYPE html><p>x", "s()"))
        assertEquals("\n  <!doctype HTML ><script>s()</script>x", WebDocuments.withScript("\n  <!doctype HTML >x", "s()"))
        assertEquals("﻿<!-- a --><!doctype html><script>s()</script>x", WebDocuments.withScript("﻿<!-- a --><!doctype html>x", "s()"))
    }

    @Test fun withoutADoctypeTheScriptComesFirst() {
        assertEquals("<script>s()</script><p>x</p>", WebDocuments.withScript("<p>x</p>", "s()"))
    }

    /**
     * A demo is any agent's text, and the scan runs on the main thread of every client that shows it. A backtracking
     * pattern took 1.4 s over 20 leading comments with no doctype after them, and four times that for each 2 more: 20
     * comments come first, so the old scan fails here instead of hanging the run.
     */
    @Test fun aPageThatStartsWithManyCommentsIsScannedInLinearTime() {
        for (count in listOf(20, 10_000)) {
            val comments = "<!---->".repeat(count)
            val start = nowMillis()
            val without = WebDocuments.withScript("$comments<p>x", "s()")
            val with = WebDocuments.withScript("$comments\n<!doctype html><p>x", "s()")
            val took = nowMillis() - start
            assertEquals("<script>s()</script>$comments<p>x", without)
            assertEquals("$comments\n<!doctype html><script>s()</script><p>x", with)
            assertTrue(took < 250.0, "$count leading comments took $took ms")
        }
    }

    /** Comments end where the browser's tokenizer ends them, so a doctype after one is found where the browser finds it. */
    @Test fun commentsEndWhereTheBrowserEndsThem() {
        assertEquals("<!--><!doctype html><script>s()</script>x", WebDocuments.withScript("<!--><!doctype html>x", "s()"))
        assertEquals("<!---><!doctype html><script>s()</script>x", WebDocuments.withScript("<!---><!doctype html>x", "s()"))
        assertEquals("<!-- a --!> <!doctype html><script>s()</script>x", WebDocuments.withScript("<!-- a --!> <!doctype html>x", "s()"))
        assertEquals("<!-- a ---><!doctype html><script>s()</script>x", WebDocuments.withScript("<!-- a ---><!doctype html>x", "s()"))
        // A comment that never ends swallows the rest, doctype and all.
        assertEquals("<script>s()</script><!-- <!doctype html>x", WebDocuments.withScript("<!-- <!doctype html>x", "s()"))
        // A doctype that never ends isn't one the script can follow.
        assertEquals("<script>s()</script><!doctype html", WebDocuments.withScript("<!doctype html", "s()"))
    }

    @Test fun aClosingScriptTagInTheScriptCannotEndItEarly() {
        val doc = WebDocuments.withScript("x", "var a = '</script><b>';")
        assertEquals("<script>var a = '<\\/script><b>';</script>x", doc)
    }

    @Test fun theErrorPageEscapesItsMessageAndFollowsTheTheme() {
        val page = WebDocuments.errorPage("<b>no</b> & \"quotes\"", dark = true)
        assertTrue("&lt;b&gt;no&lt;/b&gt; &amp; &quot;quotes&quot;" in page, page)
        assertTrue("content=\"dark\"" in page, page)
        assertFalse("<script" in page, page)
        assertTrue("content=\"light\"" in WebDocuments.errorPage("x", dark = false))
    }
}
