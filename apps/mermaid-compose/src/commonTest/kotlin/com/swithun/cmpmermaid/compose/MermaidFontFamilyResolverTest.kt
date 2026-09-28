package com.swithun.cmpmermaid.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidFontFamilyResolverTest {
    @Test
    fun parsesMermaidCssFontStackInFallbackOrder() {
        assertEquals(
            listOf("recursive variable", "arial", "sans-serif"),
            parseCssFontFamilies("\"Recursive Variable\", arial, sans-serif"),
        )
    }

    @Test
    fun preservesCommaInsideQuotedFamilyName() {
        assertEquals(
            listOf("custom, family", "monospace"),
            parseCssFontFamilies("\"Custom, Family\", monospace"),
        )
    }

    @Test
    fun groupsCjkCharactersAndPunctuationIntoFontRanges() {
        assertEquals(
            listOf(1..2, 5..5),
            "A中文 B。".cjkFontRanges(),
        )
    }

    @Test
    fun preservesUtf16RangeForSupplementaryCjkCharacters() {
        assertEquals(
            listOf(1..2),
            "A\uD840\uDC00B".cjkFontRanges(),
        )
    }

    @Test
    fun groupsSupportedDingbatsIntoBundledSymbolFontRanges() {
        assertEquals(
            listOf(1..2),
            "A✓✗→B".bundledSymbolFontRanges(),
        )
    }

    @Test
    fun usesBundledSymbolFontForHeavyBlackHeart() {
        assertEquals(
            listOf(1..1),
            "A❤B".bundledSymbolFontRanges(),
        )
    }
}
