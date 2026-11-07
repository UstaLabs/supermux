package dev.supermux.chat

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The web app can only hand a file to the browser as a blob URL, and a blob URL has the app's own origin: a page or a
 * script opened from one would run as the app. So a type the browser would run downloads, as opaque bytes, whether it
 * was asked to open or to save.
 */
class WebHandoffTest {
    private val download = WebHandoff(mime = "application/octet-stream", download = true)

    @Test fun an_active_type_opens_as_a_download_of_opaque_bytes() {
        for (mime in listOf(
            "text/html", "application/xhtml+xml", "image/svg+xml", "text/xml", "application/xml", "application/rss+xml",
            "text/javascript", "application/javascript", "application/ecmascript",
        )) {
            assertEquals(download, webHandoff(mime, open = true), mime)
        }
    }

    @Test fun an_active_type_is_recognised_whatever_its_case_or_parameters() {
        assertEquals(download, webHandoff("Text/HTML; charset=utf-8", open = true))
        assertEquals(download, webHandoff(" IMAGE/SVG+XML ", open = true))
    }

    @Test fun an_active_type_is_saved_as_opaque_bytes_too() {
        assertEquals(download, webHandoff("text/html", open = false))
    }

    @Test fun a_type_the_browser_would_have_to_guess_downloads() {
        assertEquals(download, webHandoff("", open = true))
    }

    @Test fun pictures_pdfs_clips_and_text_still_open_in_a_tab_with_their_own_type() {
        for (mime in listOf("image/png", "application/pdf", "video/mp4", "text/plain", "application/octet-stream")) {
            assertEquals(WebHandoff(mime, download = false), webHandoff(mime, open = true), mime)
        }
    }

    @Test fun a_save_downloads_with_the_file_s_own_type() {
        assertEquals(WebHandoff("image/png", download = true), webHandoff("image/png", open = false))
    }
}
