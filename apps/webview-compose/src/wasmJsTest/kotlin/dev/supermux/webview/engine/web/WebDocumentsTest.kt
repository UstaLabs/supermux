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
