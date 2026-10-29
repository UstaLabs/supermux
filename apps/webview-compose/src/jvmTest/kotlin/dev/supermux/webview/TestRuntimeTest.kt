package dev.supermux.webview

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlin.test.Test
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class)
class TestRuntimeTest {
    /** Nothing a test's runtime started (pool work, page saves) outlives the test. */
    @Test fun aTestRuntimesScopeEndsWithItsTest() {
        lateinit var scope: CoroutineScope
        runComposeUiTest { scope = testRuntime(FakeWebEngineFactory()).scope }
        assertFalse(scope.isActive)
    }
}
