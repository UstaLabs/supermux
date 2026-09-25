package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopLoadTest {
    @Test fun loadsTheLibraryFromJarResources() {
        assertTrue(System.getProperty("editor.syntax.lib") == null, "run through jvmResourceLoadTest")
        assertTrue("json" in SyntaxLanguages.names())
    }
}
