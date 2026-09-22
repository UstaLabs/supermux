package dev.supermux.ui.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure `file://` URI construction for the LSP connect flow — port of Android
 *  `EditorScreen.kt:595-603`'s `joinPath`/`pathToUri`/`dirUri`. */
class EditorPanelLspUriTest {

    @Test fun join_path_puts_exactly_one_slash_between_dir_and_relative_path() {
        assertEquals("/home/user/proj/src/a.ts", joinPath("/home/user/proj/", "src/a.ts"))
        assertEquals("/home/user/proj/src/a.ts", joinPath("/home/user/proj", "src/a.ts"))
        assertEquals("/home/user/proj/src/a.ts", joinPath("/home/user/proj", "/src/a.ts"))
        assertEquals("/home/user/proj/src/a.ts", joinPath("/home/user/proj/", "/src/a.ts"))
    }

    @Test fun path_to_uri_percent_encodes_everything_except_slash() {
        assertEquals("file:///home/user/proj/a.ts", pathToUri("/home/user/proj/a.ts"))
        assertEquals("file:///home/user/my%20project/a.ts", pathToUri("/home/user/my project/a.ts"))
    }

    @Test fun path_to_uri_encodes_special_characters_in_a_segment() {
        val uri = pathToUri("/home/user/weird#name/a b.ts")
        // '#' and the space must be percent-encoded so the string is a legal URI; '/' is preserved.
        assertEquals("file:///home/user/weird%23name/a%20b.ts", uri)
    }

    // CONVERGENCE pinned in cluster C1: Android's old `android.net.Uri.encode(abs, "/")` left the
    // sub-delims `! ~ ' ( )` literal, desktop's `java.net.URLEncoder` percent-encoded them. The one
    // shared encoder follows desktop, so an Android LSP `file://` URI now escapes them too — both
    // forms are legal URIs and cm6's LSP client decodes either, so this is a deliberate one-way
    // change, not a regression.
    @Test fun path_to_uri_percent_encodes_the_sub_delims_android_used_to_leave_literal() {
        assertEquals(
            "file:///w/%21%7E%27%28%29.kt",
            pathToUri("/w/!~'().kt"),
        )
        // …while the characters URLEncoder itself leaves alone stay literal.
        assertEquals("file:///w/a-b_c.d*e.kt", pathToUri("/w/a-b_c.d*e.kt"))
    }

    @Test fun dir_uri_always_ends_with_a_trailing_slash() {
        assertEquals("file:///home/user/proj/", dirUri("/home/user/proj"))
        assertEquals("file:///home/user/proj/", dirUri("/home/user/proj/"))
    }
}
