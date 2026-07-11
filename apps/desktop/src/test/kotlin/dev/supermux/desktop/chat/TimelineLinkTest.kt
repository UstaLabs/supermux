package dev.supermux.desktop.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Obligation 4: bare http(s) URLs in assistant prose render as clickable links. We can't observe a
 * click opening a browser under Xvfb, so this asserts the load-bearing fact directly — the rendered
 * [androidx.compose.ui.text.AnnotatedString] carries a `LinkAnnotation` over the URL span (which is
 * exactly what makes it clickable + underlined).
 */
@OptIn(ExperimentalTestApi::class)
class TimelineLinkTest {

    @Test fun assistantMessage_bareUrl_getsLinkAnnotation() = runComposeUiTest {
        val body = "see https://example.com for details"
        setContent { AssistantMessage(text = body) }

        val node = onNodeWithText(body, substring = true).fetchSemanticsNode()
        val annotated = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
        assertTrue(annotated != null, "expected rendered text semantics")
        val links = annotated.getLinkAnnotations(0, annotated.length)
        assertTrue(links.isNotEmpty(), "expected at least one LinkAnnotation over the URL span")
    }
}
