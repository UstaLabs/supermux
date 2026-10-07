package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.SceneTextBaselineShift
import com.swithun.cmpmermaid.core.SceneTextSpan
import com.swithun.cmpmermaid.core.SceneTextWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MermaidTextPortTest {
    @Test
    fun translatesMarkedStrongAndEmphasisTokens() {
        val result = MermaidTextPort.render(
            source = "This **is** _Markdown_",
            labelType = FlowLabelType.Markdown,
            config = MermaidRenderOptions(htmlLabels = false),
        )

        val text = assertIs<GMResult.Ok<MermaidRenderedText>>(result).value
        assertEquals("This is Markdown", text.text)
        assertEquals(
            listOf(
                SceneTextSpan(5, 7, weight = SceneTextWeight.Bold),
                SceneTextSpan(8, 16, italic = true),
            ),
            text.spans,
        )
    }

    @Test
    fun translatesMermaidLineBreakAndEntityBoundaries() {
        val result = MermaidTextPort.render(
            source = "Line one<br/>Line two &amp; &#35; &#x2764;",
            labelType = FlowLabelType.Text,
            config = MermaidRenderOptions(),
        )

        val text = assertIs<GMResult.Ok<MermaidRenderedText>>(result).value
        assertEquals("Line one\nLine two & # ❤", text.text)
    }

    @Test
    fun preservesUnsupportedMarkedTokenRawTextForHtmlLabels() {
        val result = MermaidTextPort.render(
            source = "[label](https://example.com)",
            labelType = FlowLabelType.Markdown,
            config = MermaidRenderOptions(),
        )

        val text = assertIs<GMResult.Ok<MermaidRenderedText>>(result).value
        assertEquals("[label](https://example.com)", text.text)
    }

    @Test
    fun omitsUnsupportedMarkedTokenInsideSvgLabelParagraph() {
        val result = MermaidTextPort.render(
            source = "before [label](https://example.com) after",
            labelType = FlowLabelType.Markdown,
            config = MermaidRenderOptions(htmlLabels = false),
        )

        val text = assertIs<GMResult.Ok<MermaidRenderedText>>(result).value
        assertEquals("before after", text.text)
    }

    @Test
    fun doesNotEmphasizeUnderscoresInsideWords() {
        val result = MermaidTextPort.render(
            source = "a_b_c foo_bar",
            labelType = FlowLabelType.Markdown,
            config = MermaidRenderOptions(),
        )

        val text = assertIs<GMResult.Ok<MermaidRenderedText>>(result).value
        assertEquals("a_b_c foo_bar", text.text)
        assertEquals(emptyList(), text.spans)
    }

    @Test
    fun projectsSupportedHtmlFormattingIntoSceneTextSpans() {
        val result = MermaidTextPort.render(
            source = "<u>under</u> <s>strike</s> " +
                "<span style='color:#ff0000;text-decoration:underline'>red</span> " +
                "H<sub>2</sub>O x<sup>2</sup>",
            labelType = FlowLabelType.Text,
            config = MermaidRenderOptions(),
        )

        val text = assertIs<GMResult.Ok<MermaidRenderedText>>(result).value
        assertEquals("under strike red H2O x2", text.text)
        assertEquals(
            listOf(
                SceneTextSpan(0, 5, underline = true),
                SceneTextSpan(6, 12, lineThrough = true),
                SceneTextSpan(
                    13,
                    16,
                    underline = true,
                    color = SceneColor(0xFFFF0000),
                ),
                SceneTextSpan(
                    18,
                    19,
                    baselineShift = SceneTextBaselineShift.Subscript,
                    fontSizeScale = 5f / 6f,
                ),
                SceneTextSpan(
                    22,
                    23,
                    baselineShift = SceneTextBaselineShift.Superscript,
                    fontSizeScale = 5f / 6f,
                ),
            ),
            text.spans,
        )
    }

    @Test
    fun removesForbiddenHtmlContentLikeDompurify() {
        val result = MermaidTextPort.render(
            source = "safe<script>alert(1)</script><style>body{display:none}</style>after",
            labelType = FlowLabelType.Text,
            config = MermaidRenderOptions(),
        )

        val text = assertIs<GMResult.Ok<MermaidRenderedText>>(result).value
        assertEquals("safeafter", text.text)
    }

    @Test
    fun decodesBrowserHtmlEntitiesAndPreservesUnknownNames() {
        val result = MermaidTextPort.render(
            source = "&copy; &euro; &Alpha; &NotEqualTilde; &bogus;",
            labelType = FlowLabelType.Text,
            config = MermaidRenderOptions(),
        )

        val text = assertIs<GMResult.Ok<MermaidRenderedText>>(result).value
        assertEquals("© € Α ≂̸ &bogus;", text.text)
    }

    @Test
    fun decodesMermaidDecimalEntityPlaceholder() {
        val result = MermaidTextPort.render(
            source = "heart: ﬂ°°9829¶ß",
            labelType = FlowLabelType.Text,
            config = MermaidRenderOptions(),
        )

        val text = assertIs<GMResult.Ok<MermaidRenderedText>>(result).value
        assertEquals("heart: ♥", text.text)
    }

    @Test
    fun keepsHtmlTagsVisibleWhenHtmlLabelsAreDisabled() {
        val result = MermaidTextPort.render(
            source = "<u>under</u>",
            labelType = FlowLabelType.Text,
            config = MermaidRenderOptions(htmlLabels = false),
        )

        val text = assertIs<GMResult.Ok<MermaidRenderedText>>(result).value
        assertEquals("<u> under </u>", text.text)
    }

    @Test
    fun rejectsHtmlFeaturesWithoutNativeInteractionOrAssetSemantics() {
        val linkResult = MermaidTextPort.render(
            source = "<a href='https://example.com'>link</a>",
            labelType = FlowLabelType.Text,
            config = MermaidRenderOptions(),
        )
        val imageResult = MermaidTextPort.render(
            source = "<img src='image.png'>",
            labelType = FlowLabelType.Text,
            config = MermaidRenderOptions(),
        )

        assertIs<MermaidError.UnsupportedFeature>(
            assertIs<GMResult.Err<MermaidError>>(linkResult).error,
        )
        assertIs<MermaidError.UnsupportedFeature>(
            assertIs<GMResult.Err<MermaidError>>(imageResult).error,
        )
    }

    @Test
    fun rejectsFontAwesomeReplacementOnlyForHtmlLabels() {
        val htmlResult = MermaidTextPort.render(
            source = "fa:fa-twitter for peace",
            labelType = FlowLabelType.Text,
            config = MermaidRenderOptions(),
        )
        val svgResult = MermaidTextPort.render(
            source = "fa:fa-twitter for peace",
            labelType = FlowLabelType.Text,
            config = MermaidRenderOptions(htmlLabels = false),
        )

        val error = assertIs<MermaidError.UnsupportedFeature>(
            assertIs<GMResult.Err<MermaidError>>(htmlResult).error,
        )
        assertEquals("FontAwesome label icon", error.feature)
        assertEquals(
            "fa:fa-twitter for peace",
            assertIs<GMResult.Ok<MermaidRenderedText>>(svgResult).value.text,
        )
    }
}
