package dev.supermux.webview.engine.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopAppTest {
    @Test fun anAppHasAFolderNameOfItsOwnWithoutDoingAnything() {
        assertEquals("com.example.chat", DesktopApp.appId("com.example.chat", "/opt/chat/bin/Chat", "x.MainKt", "/a"))
        assertEquals("Chat", DesktopApp.appId(null, "/opt/chat/bin/Chat", "x.MainKt", "/a"), "a packaged app: its launcher")
        assertEquals("Chat", DesktopApp.appId(null, "C:/Program Files/Chat/Chat.exe", "x.MainKt", "/a"), "without .exe")
        val dev = DesktopApp.appId(null, null, "dev.supermux.webview.sample.MainKt --flag", "/home/u/app/build/classes:/lib/x.jar")
        assertTrue(dev.startsWith("dev.supermux.webview.sample.MainKt-"), dev)
        assertNotEquals(dev, DesktopApp.appId(null, null, "dev.supermux.webview.sample.MainKt", "/home/u/other/build/classes"))
        assertEquals("a_b_c", DesktopApp.appId("a/b c", null, null, null), "a folder's name, never a path")
        assertEquals("default", DesktopApp.appId(null, null, null, null))
    }

    @Test fun windowsReservedNamesAndTrailingDotsNeverNameAFolder() {
        // A Windows folder can't be called CON or end with a dot or a space: such an id gets a prefix.
        assertEquals("app-CON", DesktopApp.appId("CON", null, null, null))
        assertEquals("app-nul", DesktopApp.appId("nul", null, null, null))
        assertEquals("chat", DesktopApp.appId("chat.", null, null, null))
    }
}
