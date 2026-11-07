package dev.supermux.webview.engine.windows

import kotlin.test.Test
import kotlin.test.assertEquals

class MouseInputTest {
    private fun of(kind: MouseInput.Kind, button: Int = 1, clicks: Int = 1, dx: Float = 0f, dy: Float = 0f, primary: Boolean = false, shift: Boolean = false) =
        MouseInput.of(kind, button, clicks, 12.6f, 30.2f, dx, dy, primary, secondary = false, middle = false, shift = shift, ctrl = false)

    @Test fun pressesAndReleasesNameTheirButtonAndAClickCountOfTwoIsADoubleClick() {
        assertEquals(MouseInput.Send(WinGlue.LEFT_DOWN, WinGlue.MK_LBUTTON, 0, 13, 30), of(MouseInput.Kind.Press, primary = true))
        assertEquals(WinGlue.LEFT_DOUBLE, of(MouseInput.Kind.Press, clicks = 2, primary = true).kind)
        assertEquals(WinGlue.RIGHT_DOWN, of(MouseInput.Kind.Press, button = 3).kind)
        assertEquals(WinGlue.MIDDLE_UP, of(MouseInput.Kind.Release, button = 2).kind)
    }

    @Test fun theWheelIsInWheelDeltasAndShiftedOrSidewaysIsHorizontal() {
        // AWT: positive rotation is toward the user; WM_MOUSEWHEEL: positive is away from the user (the test's mapping).
        assertEquals(MouseInput.Send(WinGlue.WHEEL, 0, -120, 13, 30), of(MouseInput.Kind.Scroll, dy = 1f))
        assertEquals(MouseInput.Send(WinGlue.HORIZONTAL_WHEEL, 0, 60, 13, 30), of(MouseInput.Kind.Scroll, dx = 0.5f))
    }

    @Test fun leavingIsALeaveAndModifiersAreKeys() {
        assertEquals(WinGlue.MOUSE_LEAVE, of(MouseInput.Kind.Exit).kind)
        assertEquals(WinGlue.MK_SHIFT, of(MouseInput.Kind.Move, shift = true).keys)
    }
}
