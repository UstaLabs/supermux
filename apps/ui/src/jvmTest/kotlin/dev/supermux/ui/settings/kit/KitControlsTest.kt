package dev.supermux.ui.settings.kit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import dev.supermux.ui.chat.setPlatformContent
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KitControlsTest {
    private fun role(r: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, r)

    @Test fun a_standalone_toggle_is_a_switch_that_flips() = runComposeUiTest {
        var on by mutableStateOf(false)
        setPlatformContent { KitToggle(on, onCheckedChange = { on = it }, modifier = Modifier.testTag("t"), label = "Enabled") }
        onNodeWithTag("t").assertIsOff().assert(role(Role.Switch))
        onNodeWithTag("t").performClick()
        onNodeWithTag("t").assertIsOn()
        assertEquals(true, on)
    }

    @Test fun a_disabled_toggle_does_not_flip() = runComposeUiTest {
        var on by mutableStateOf(false)
        setPlatformContent { KitToggle(on, onCheckedChange = { on = it }, modifier = Modifier.testTag("t"), enabled = false) }
        onNodeWithTag("t").assertIsNotEnabled()
        onNodeWithTag("t").performClick()
        assertEquals(false, on)
    }

    @Test fun options_mark_the_chosen_one_and_report_a_new_choice() = runComposeUiTest {
        var picked by mutableStateOf("Dark")
        setPlatformContent {
            KitOptions(listOf("System", "Light", "Dark"), picked, { picked = it }, { it }, tag = { "opt_$it" })
        }
        onNodeWithTag("opt_Dark").assertIsSelected()
        onNodeWithTag("opt_Light").assertIsNotSelected().performClick()
        onNodeWithTag("opt_Light").assertIsSelected()
        assertEquals("Light", picked)
    }

    @Test fun a_ghost_button_is_a_button_and_clicks() = runComposeUiTest {
        var n = 0
        setPlatformContent { KitGhostButton("Run now", { n++ }, Modifier.testTag("b")) }
        onNodeWithTag("b").assert(role(Role.Button)).performClick()
        assertEquals(1, n)
    }

    @Test fun a_ghost_button_answers_enter_from_the_keyboard() = runComposeUiTest {
        var n = 0
        setPlatformContent { KitGhostButton("Run now", { n++ }, Modifier.testTag("b")) }
        onNodeWithTag("b").requestFocus()
        onNodeWithTag("b").performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, n)
    }

    @Test fun a_disabled_ghost_button_does_nothing() = runComposeUiTest {
        var n = 0
        setPlatformContent { KitGhostButton("Clear", { n++ }, Modifier.testTag("b"), enabled = false) }
        onNodeWithTag("b").assertIsNotEnabled().performClick()
        assertEquals(0, n)
    }

    @Test fun a_text_action_clicks() = runComposeUiTest {
        var n = 0
        setPlatformContent { KitTextAction("Try again", { n++ }, Modifier.testTag("a")) }
        onNodeWithTag("a").assert(role(Role.Button)).performClick()
        assertEquals(1, n)
    }

    @Test fun the_stepper_steps_and_stops_at_its_ends() = runComposeUiTest {
        var v by mutableStateOf(31)
        setPlatformContent { KitStepper(v, { v = it }, 8..32, tag = "tf") }
        onNodeWithTag("tf_plus").performClick()
        onNodeWithTag("tf_value").assertTextEquals("32")
        onNodeWithTag("tf_plus").assertIsNotEnabled()
        onNodeWithTag("tf_minus").performClick()
        assertEquals(31, v)
    }

    @Test fun the_slider_takes_a_value_through_semantics_and_snaps_to_its_step() = runComposeUiTest {
        var v by mutableStateOf(1f)
        setPlatformContent { KitSlider(v, { v = it }, 0.9f..1.3f, step = 0.05f, modifier = Modifier.testTag("s")) }
        onNodeWithTag("s").performSemanticsAction(SemanticsActions.SetProgress) { it(1.12f) }
        assertEquals(1.1f, v, 0.001f)
    }

    @Test fun the_slider_steps_with_the_arrow_keys() = runComposeUiTest {
        var v by mutableStateOf(1f)
        setPlatformContent { KitSlider(v, { v = it }, 0.9f..1.3f, step = 0.05f, modifier = Modifier.testTag("s")) }
        onNodeWithTag("s").requestFocus()
        onNodeWithTag("s").performKeyInput { pressKey(Key.DirectionRight) }
        assertEquals(1.05f, v, 0.001f)
    }
}
