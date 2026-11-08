package dev.supermux.net

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which links the app hands to a browser or the OS. Messages carry links an agent wrote, so anything that could run
 * code or reach a local file is refused, however its scheme is spelled.
 */
class OpenableLinkTest {
    private fun show(url: String) = url.map { if (it < ' ') "\\u" + it.code.toString(16).padStart(4, '0') else "$it" }.joinToString("")

    @Test fun web_mail_phone_and_text_links_open() {
        for (url in listOf(
            "https://example.com/a?b=c", "http://localhost:3000/", "HTTPS://EXAMPLE.COM", "mailto:a@b.co",
            "tel:+905551234567", "sms:+905551234567",
        )) {
            assertTrue(isOpenableLink(url), url)
        }
    }

    @Test fun script_data_and_local_file_links_never_open() {
        for (url in listOf(
            "javascript:alert(1)", "vbscript:msgbox(1)", "data:text/html,<script>alert(1)</script>",
            "file:///etc/passwd", "file://host/share/x.exe", "blob:https://example.com/0f1e", "about:blank",
            "intent://scan#Intent;scheme=zxing;end", "supermux://pair?t=abc",
        )) {
            assertFalse(isOpenableLink(url), url)
        }
    }

    @Test fun case_and_whitespace_tricks_never_open() {
        for (url in listOf(
            "JaVaScRiPt:alert(1)", " javascript:alert(1)", "java\tscript:alert(1)", "java\nscript:alert(1)",
            "javascript\t:alert(1)", "\u0000javascript:alert(1)", "\u000b javascript:alert(1)",
        )) {
            assertFalse(isOpenableLink(url), show(url))
        }
    }

    @Test fun a_link_with_no_scheme_never_opens() {
        for (url in listOf("#top", "/s/abc", "//evil.example/x", "\\\\host\\share\\x.exe", "", "   ")) {
            assertFalse(isOpenableLink(url), url)
        }
    }

    @Test fun surrounding_whitespace_does_not_hide_a_web_link() {
        assertTrue(isOpenableLink(" https://example.com/ \n"))
    }
}
