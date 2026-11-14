package dev.supermux.web

import kotlin.test.Test
import kotlin.test.assertEquals

// #142: the editor's JetBrains Mono is fetched from the terminal's copy, so the browser downloads it once.
class SharedResourcePathTest {
    private val editor = "composeResources/dev.supermux.editor.compose.resources/font"
    private val terminal = "composeResources/dev.supermux.terminal.compose.resources/font"

    @Test fun editorMonoFacesResolveToTheTerminalCopy() {
        for (face in listOf("jetbrains_mono_regular.ttf", "jetbrains_mono_bold.ttf", "jetbrains_mono_italic.ttf")) {
            assertEquals("$terminal/$face", sharedResourcePath("$editor/$face"))
        }
    }

    @Test fun theTerminalCopyAndEverythingElseAreUntouched() {
        assertEquals("$terminal/jetbrains_mono_regular.ttf", sharedResourcePath("$terminal/jetbrains_mono_regular.ttf"))
        assertEquals("composeResources/dev.supermux.ui.resources/font/geist_regular.ttf", sharedResourcePath("composeResources/dev.supermux.ui.resources/font/geist_regular.ttf"))
        assertEquals("$editor/some_other_face.ttf", sharedResourcePath("$editor/some_other_face.ttf"))
    }
}
