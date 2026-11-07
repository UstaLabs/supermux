package dev.supermux.webview.wintest

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

    @Test fun cssCursorsExpectTheMatchingSystemCursor() {
        assertEquals(CursorMapping.Expected("IDC_HAND"), CursorMapping.expectedFor("pointer"))
        assertEquals(CursorMapping.Expected("IDC_IBEAM"), CursorMapping.expectedFor("text"))
        assertEquals(CursorMapping.Expected("IDC_SIZEWE"), CursorMapping.expectedFor("ew-resize"))
    }

    @Test fun cursorsWithoutAnAwtTypeExpectACopyOfTheSystemImage() {
        assertEquals(CursorMapping.Expected("custom", CursorMapping.IDC_APPSTARTING), CursorMapping.expectedFor("progress"))
        assertEquals(CursorMapping.Expected("custom", CursorMapping.IDC_NO), CursorMapping.expectedFor("not-allowed"))
    }

    @Test fun aPageImageCursorExpectsACustomCursor() {
        assertEquals(CursorMapping.Expected("custom"), CursorMapping.expectedFor("custom"))
    }
}
