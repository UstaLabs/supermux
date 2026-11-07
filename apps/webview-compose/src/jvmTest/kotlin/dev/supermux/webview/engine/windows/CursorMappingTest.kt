package dev.supermux.webview.engine.windows

import java.awt.Cursor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CursorMappingTest {
    @Test fun systemCursorsAwtHasMapToItsPredefinedTypes() {
        assertEquals(Cursor.DEFAULT_CURSOR, CursorMapping.awtType(CursorMapping.IDC_ARROW))
        assertEquals(Cursor.TEXT_CURSOR, CursorMapping.awtType(CursorMapping.IDC_IBEAM))
        assertEquals(Cursor.WAIT_CURSOR, CursorMapping.awtType(CursorMapping.IDC_WAIT))
        assertEquals(Cursor.CROSSHAIR_CURSOR, CursorMapping.awtType(CursorMapping.IDC_CROSS))
        assertEquals(Cursor.HAND_CURSOR, CursorMapping.awtType(CursorMapping.IDC_HAND))
        assertEquals(Cursor.MOVE_CURSOR, CursorMapping.awtType(CursorMapping.IDC_SIZEALL))
        assertEquals(Cursor.E_RESIZE_CURSOR, CursorMapping.awtType(CursorMapping.IDC_SIZEWE))
        assertEquals(Cursor.N_RESIZE_CURSOR, CursorMapping.awtType(CursorMapping.IDC_SIZENS))
        assertEquals(Cursor.NW_RESIZE_CURSOR, CursorMapping.awtType(CursorMapping.IDC_SIZENWSE))
        assertEquals(Cursor.NE_RESIZE_CURSOR, CursorMapping.awtType(CursorMapping.IDC_SIZENESW))
    }

    @Test fun systemCursorsAwtLacksHaveNoType() {
        assertNull(CursorMapping.awtType(CursorMapping.IDC_APPSTARTING))
        assertNull(CursorMapping.awtType(CursorMapping.IDC_NO))
        assertNull(CursorMapping.awtType(CursorMapping.IDC_HELP))
        assertNull(CursorMapping.awtType(12345))
    }
}
