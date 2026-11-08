package dev.supermux.webview

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.supermux.webview.engine.EngineEvent
import kotlin.test.Test
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class WebViewStateLayoutTest {
    /** Compose throws "Can't represent … in Constraints" for a side over 262,142 px: 100,000 dp did at density 2.625. */
    @Test fun theLargestSizeAPageMayReportCanBeLaidOutAtDensity8() = runComposeUiTest {
        val state = WebViewState(WebContent.Html("<p/>", WebOrigin("demo")))
        state.onEngineEvent(EngineEvent.ContentSize(1e9f, 1e9f))
        val size = assertNotNull(state.contentSize)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(8f)) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // Full width and the page's height, as a WrapContentHeight view sizes itself.
                    Box(Modifier.fillMaxWidth().height(size.height))
                    // The page's width, for a caller that sizes something to it.
                    Box(Modifier.horizontalScroll(rememberScrollState())) { Box(Modifier.width(size.width).height(1.dp)) }
                }
            }
        }
        waitForIdle()
    }
}
