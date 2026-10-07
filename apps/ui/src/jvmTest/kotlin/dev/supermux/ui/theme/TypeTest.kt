package dev.supermux.ui.theme

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fonts now ship as Compose Multiplatform resources from `:ui` (Res.font.*), so both apps read the
 * same TTFs. The families resolve inside composition (the resource `Font(...)` builder is
 * @Composable), which is why these are UI tests rather than plain assertions.
 */
@OptIn(ExperimentalTestApi::class)
class TypeTest {
    @Test
    fun monoFamilyRendersText() = runComposeUiTest {
        setContent { Text("Aa", fontFamily = GeistMonoFontFamily) }
        onNodeWithText("Aa").assertIsDisplayed()
    }

    @Test
    fun sansFamilyRendersText() = runComposeUiTest {
        setContent { Text("Bb", fontFamily = GeistFontFamily) }
        onNodeWithText("Bb").assertIsDisplayed()
    }

    /** Desktop's compact scale is the shared default; the touch scale keeps Android's sizes. */
    @Test
    fun typographyKeepsBothScales() = runComposeUiTest {
        setContent {
            assertEquals(14f, supermuxTypography().bodyLarge.fontSize.value)
            assertEquals(16f, supermuxTouchTypography().bodyLarge.fontSize.value)
            assertEquals(18f, supermuxTypography().headlineSmall.fontSize.value)
            assertEquals(22f, supermuxTouchTypography().headlineSmall.fontSize.value)
            Text("typography")
        }
        onNodeWithText("typography").assertIsDisplayed()
    }
}
