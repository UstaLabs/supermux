package dev.supermux.desktop.editor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-function tests for the M4g-1 markdown-preview toggle (plan Task 1/3):
 * [isMarkdownPath] (verbatim port of Android EditorScreen.kt:582-583) and the
 * showPreviewToggle/showPreview derivation ([EditorPanel.kt]:~194-196), tested here rather than
 * through a hosted EditorPanel because the derivation is a pure function of
 * (activeTab?.path, editor.previewMode) that doesn't need Compose to exercise.
 */
class EditorPanelMarkdownTest {

    // ── isMarkdownPath ──────────────────────────────────────────────────────

    @Test fun markdown_path_dot_md_is_markdown() {
        assertTrue(isMarkdownPath("a.md"))
    }

    @Test fun markdown_path_uppercase_extension_is_markdown() {
        assertTrue(isMarkdownPath("README.MD"))
    }

    @Test fun markdown_path_dot_markdown_is_markdown() {
        assertTrue(isMarkdownPath("x.markdown"))
    }

    @Test fun markdown_path_deep_mixed_case_markdown_extension_is_markdown() {
        assertTrue(isMarkdownPath("/deep/path/notes.Markdown"))
    }

    @Test fun markdown_path_dot_txt_is_not_markdown() {
        assertFalse(isMarkdownPath("a.txt"))
    }

    @Test fun markdown_path_dot_mdx_is_not_markdown() {
        assertFalse(isMarkdownPath("a.mdx"))
    }

    @Test fun markdown_path_empty_string_is_not_markdown() {
        assertFalse(isMarkdownPath(""))
    }

    @Test fun markdown_path_no_dot_is_not_markdown() {
        assertFalse(isMarkdownPath("mdfile"))
    }

    @Test fun markdown_path_dot_md_dot_bak_is_not_markdown() {
        assertFalse(isMarkdownPath("a.md.bak"))
    }

    // ── showPreviewToggle / showPreview derivation ─────────────────────────

    @Test fun preview_toggle_is_shown_when_the_active_tab_is_markdown() {
        assertTrue(editorPreviewGate(activePath = "notes.md", previewMode = false).showPreviewToggle)
    }

    @Test fun preview_toggle_is_hidden_when_the_active_tab_is_not_markdown() {
        assertFalse(editorPreviewGate(activePath = "notes.txt", previewMode = false).showPreviewToggle)
    }

    @Test fun preview_toggle_is_hidden_when_there_is_no_active_tab() {
        assertFalse(editorPreviewGate(activePath = null, previewMode = false).showPreviewToggle)
    }

    @Test fun preview_is_shown_only_when_both_markdown_and_preview_mode_are_on() {
        val gate = editorPreviewGate(activePath = "notes.md", previewMode = true)
        assertTrue(gate.showPreview)
    }

    @Test fun preview_is_hidden_when_preview_mode_is_off_even_on_markdown() {
        assertFalse(editorPreviewGate(activePath = "notes.md", previewMode = false).showPreview)
    }

    @Test fun preview_is_hidden_on_a_non_markdown_tab_even_when_preview_mode_is_on() {
        assertFalse(editorPreviewGate(activePath = "notes.txt", previewMode = true).showPreview)
    }
}
