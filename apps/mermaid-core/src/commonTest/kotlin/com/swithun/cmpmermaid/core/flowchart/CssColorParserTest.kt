package com.swithun.cmpmermaid.core.flowchart

import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.SceneColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CssColorParserTest {
    @Test
    fun parsesHexWithOptionalAlpha() {
        assertEquals(SceneColor(0xFFAABBCC), CssColorParser.parse("#abc"))
        assertEquals(SceneColor(0xDDAABBCC), CssColorParser.parse("#abcd"))
        assertEquals(SceneColor(0x80112233), CssColorParser.parse("#11223380"))
    }

    @Test
    fun parsesRgbAndRgba() {
        assertEquals(SceneColor(0xFFFF8000), CssColorParser.parse("rgb(255, 128, 0)"))
        assertEquals(SceneColor(0x8000FF00), CssColorParser.parse("rgba(0, 255, 0, 0.5)"))
        assertEquals(SceneColor(0xFF0080FF), CssColorParser.parse("rgb(0%, 50%, 100%)"))
    }

    @Test
    fun parsesHslAndNamedColors() {
        assertEquals(SceneColor(0xFF00FF00), CssColorParser.parse("hsl(120, 100%, 50%)"))
        assertEquals(SceneColor(0x800000FF), CssColorParser.parse("hsla(240, 100%, 50%, 50%)"))
        assertEquals(SceneColor(0xFF90EE90), CssColorParser.parse("lightgreen"))
    }

    @Test
    fun rejectsUnknownColor() {
        assertNull(CssColorParser.parse("brand-primary"))
    }
}
