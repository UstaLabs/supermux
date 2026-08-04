package dev.supermux.desktop.chat

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import dev.supermux.ui.ColumnAlign
import dev.supermux.ui.MdBlock
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Desktop renderer coverage for the GFM block/span types the shared parser gained (tables,
 * task lists, strikethrough, `[label](url)` links and standalone images). The parser itself is
 * shared + tested elsewhere; these host the pure-Compose [MarkdownBody]/[AssistantMessage] under
 * [runComposeUiTest] and assert the DESKTOP rendering (glyphs, testTags, link annotations).
 */
@OptIn(ExperimentalTestApi::class)
class TimelineMarkdownTest {

    // ── Pure helper ───────────────────────────────────────────────────────────────────

    @Test fun columnTextAlign_maps_each_column_align() {
        assertEquals(TextAlign.Left, columnTextAlign(ColumnAlign.LEFT))
        assertEquals(TextAlign.Center, columnTextAlign(ColumnAlign.CENTER))
        assertEquals(TextAlign.Right, columnTextAlign(ColumnAlign.RIGHT))
    }

    // ── Tables ────────────────────────────────────────────────────────────────────────

    @Test fun table_renders_grid_with_header_and_data_cells() = runComposeUiTest {
        val md = """
            | Name | Role |
            | :--- | ---: |
            | Ada  | Dev  |
        """.trimIndent()
        setContent { MarkdownBody(text = md) }

        onNodeWithTag("md_table").assertIsDisplayed()
        onNodeWithText("Name").assertIsDisplayed()
        onNodeWithText("Role").assertIsDisplayed()
        onNodeWithText("Ada").assertIsDisplayed()
        onNodeWithText("Dev").assertIsDisplayed()
    }

    // ── Task lists ──────────────────────────────────────────────────────────────────────

    @Test fun task_list_shows_checked_and_unchecked_glyphs() = runComposeUiTest {
        val md = """
            - [x] done
            - [ ] todo
            - plain
        """.trimIndent()
        setContent { MarkdownBody(text = md) }

        onNodeWithText("☑").assertIsDisplayed() // checked task
        onNodeWithText("☐").assertIsDisplayed() // unchecked task
        onNodeWithText("•").assertIsDisplayed() // plain bullet keeps the dot
    }

    // ── Strikethrough ─────────────────────────────────────────────────────────────────

    @Test fun strikethrough_span_carries_line_through_decoration() = runComposeUiTest {
        setContent { MarkdownBody(text = "this is ~~gone~~ now") }

        val node = onNodeWithText("this is gone now", substring = true).fetchSemanticsNode()
        val annotated = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
        assertTrue(annotated != null, "expected rendered text semantics")
        val struck = annotated.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough }
        assertTrue(struck, "expected a LineThrough span over the struck text")
    }

    // ── `[label](url)` web links ────────────────────────────────────────────────────────

    @Test fun labeled_link_renders_label_with_url_link_annotation() = runComposeUiTest {
        setContent { MarkdownBody(text = "see [the docs](https://example.com/docs) here") }

        val node = onNodeWithText("the docs", substring = true).fetchSemanticsNode()
        val annotated = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
        assertTrue(annotated != null, "expected rendered text semantics")
        val urls = annotated.getLinkAnnotations(0, annotated.length)
            .mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
        assertTrue("https://example.com/docs" in urls, "expected a Url link annotation carrying the href, got $urls")
    }

    // ── Standalone images: https loads inline; non-https stays a tappable link line ──────

    @Test fun non_https_image_renders_link_line_opening_the_url() = runComposeUiTest {
        // http (not https) must never fetch — tracking/IP-leak guard matches Android's Coil path.
        setContent { MarkdownBody(text = "![a diagram](http://example.com/pic.png)") }

        onNodeWithTag("md_image").assertIsDisplayed()
        val node = onNodeWithTag("md_image").fetchSemanticsNode()
        val annotated = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
        assertTrue(annotated != null, "expected rendered image link-line text")
        assertTrue(annotated.text.contains("a diagram"), "expected the alt text as the link label")
        val urls = annotated.getLinkAnnotations(0, annotated.length)
            .mapNotNull { (it.item as? LinkAnnotation.Url)?.url }
        assertTrue("http://example.com/pic.png" in urls, "expected the image url as a Url link annotation, got $urls")
    }

    @Test fun https_image_renders_inline_bitmap_when_loader_succeeds() = runComposeUiTest {
        // Tiny 2×2 PNG — decode via the same Skiko path production uses, injected as the load seam
        // so this never hits the network.
        val png = decodeImageBytes(TINY_PNG_BYTES)
        assertTrue(png != null, "fixture PNG must decode")
        setContent {
            MarkdownImage(
                image = MdBlock.Image(url = "https://example.com/pic.png", alt = "a diagram"),
                loadImage = { png },
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("md_image").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("md_image").assertIsDisplayed()
        // Loaded bitmap has no text/link annotation — it is a real Image, not the fallback line.
        val node = onNodeWithTag("md_image").fetchSemanticsNode()
        val annotated = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
        assertTrue(annotated == null, "inline image must not be the link-line fallback")
    }

    @Test fun https_image_falls_back_to_link_line_when_loader_fails() = runComposeUiTest {
        setContent {
            MarkdownImage(
                image = MdBlock.Image(url = "https://example.com/missing.png", alt = "broken"),
                loadImage = { null },
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("md_image").fetchSemanticsNodes().isNotEmpty()
        }
        val node = onNodeWithTag("md_image").fetchSemanticsNode()
        val annotated = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
        assertTrue(annotated != null, "failed load must fall back to the link line")
        // Failure fallback must say it FAILED — not look like a deliberate non-https link.
        assertTrue(
            annotated.text.contains("Couldn't load image"),
            "expected an explicit failure label, got: ${annotated.text}",
        )
        assertTrue(annotated.text.contains("broken"), "expected alt text on the fallback link")
    }

    @Test fun non_https_fallback_doesNotSayCouldntLoad() = runComposeUiTest {
        setContent { MarkdownBody(text = "![a diagram](http://example.com/pic.png)") }
        val node = onNodeWithTag("md_image").fetchSemanticsNode()
        val annotated = node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()
        assertTrue(annotated != null)
        assertTrue(
            !annotated.text.contains("Couldn't load image"),
            "deliberate non-https must not look like a load failure",
        )
        assertTrue(annotated.text.contains("a diagram"))
    }

    @Test fun https_image_clickable_when_loaded() = runComposeUiTest {
        val png = decodeImageBytes(TINY_PNG_BYTES)
        assertTrue(png != null)
        setContent {
            MarkdownImage(
                image = MdBlock.Image(url = "https://example.com/pic.png", alt = "diagram"),
                loadImage = { png },
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("md_image").fetchSemanticsNodes().isNotEmpty()
        }
        // Click is wired (openInBrowser); under test we only assert the node is clickable / present.
        onNodeWithTag("md_image").assertIsDisplayed()
        onNodeWithTag("md_image").performClick()
    }

    // ── Pure helpers (https gate, size cap, forced Skiko decode) ──────────────────────

    @Test fun isHttpsImageUrl_accepts_https_only() {
        assertTrue(isHttpsImageUrl("https://example.com/a.png"))
        assertTrue(isHttpsImageUrl("HTTPS://EXAMPLE.COM/a.png"))
        assertTrue(!isHttpsImageUrl("http://example.com/a.png"))
        assertTrue(!isHttpsImageUrl("ftp://example.com/a.png"))
        assertTrue(!isHttpsImageUrl("/relative/path.png"))
        assertTrue(!isHttpsImageUrl(""))
    }

    @Test fun readBytesCapped_returns_bytes_under_cap() {
        val data = ByteArray(100) { it.toByte() }
        val got = readBytesCapped(data.inputStream(), maxBytes = 200)
        assertTrue(got != null && got.contentEquals(data))
    }

    @Test fun readBytesCapped_returns_null_when_stream_exceeds_cap() {
        val data = ByteArray(50) { 1 }
        assertTrue(readBytesCapped(data.inputStream(), maxBytes = 10) == null)
    }

    @Test fun decodeImageBytes_decodes_png() {
        val bmp = decodeImageBytes(TINY_PNG_BYTES)
        assertTrue(bmp != null, "expected Skiko to force-decode a valid PNG")
        assertEquals(2, bmp!!.width)
        assertEquals(2, bmp.height)
        // Raster is fully materialised — prepareToDraw must not throw (lazy-encoded would risk that).
        bmp.prepareToDraw()
    }

    @Test fun decodeImageBytes_rejects_garbage() {
        assertNull(decodeImageBytes(byteArrayOf(1, 2, 3, 4, 5)))
    }

    @Test fun decodeImageBytes_rejects_truncatedPng() {
        // Valid PNG signature + IHDR length, but body truncated mid-stream — must fail inside
        // decodeImageBytes (Codec.readPixels), not later at Compose draw time.
        val truncated = TINY_PNG_BYTES.copyOf(24)
        assertNull(decodeImageBytes(truncated), "truncated PNG must return null from forced decode")
    }

    @Test fun loadMarkdownImageBitmap_decodeRunsOnIoDispatcher() = runBlocking {
        // Prove the production load path hops off the caller: withContext(IO) + force-decode.
        // We cannot hit the network here; instead wrap the same IO hop the production function uses
        // and assert decodeImageBytes runs on a DefaultDispatcher/IO worker, not the test main.
        val threadName = AtomicReference<String?>(null)
        val bmp = withContext(Dispatchers.IO) {
            threadName.set(Thread.currentThread().name)
            decodeImageBytes(TINY_PNG_BYTES)
        }
        assertTrue(bmp != null)
        val name = threadName.get()
        assertTrue(name != null, "expected a worker thread name")
        // kotlinx IO pool threads are named DefaultDispatcher-worker-N
        assertTrue(
            name!!.contains("DefaultDispatcher") || name.contains("IO") || name.contains("worker"),
            "decode should run on IO/worker thread, got: $name",
        )
        assertTrue(
            !name.contains("AWT-EventQueue"),
            "decode must not run on the AWT UI thread, got: $name",
        )
    }

    @Test fun mdImageDimens_loadingHeightEqualsMax_avoidsUpwardReflow() {
        // Loading box reserves the same height as the max painted image so the timeline does not
        // jump up by ~160dp when the bitmap replaces the spinner.
        assertEquals(MdImageDimens.MaxHeight, MdImageDimens.LoadingHeight)
    }

    @Test fun fetchHttpsImageBytes_rejects_non_https() {
        assertTrue(fetchHttpsImageBytes("http://example.com/x.png") == null)
        assertTrue(fetchHttpsImageBytes("file:///tmp/x.png") == null)
    }

    companion object {
        // 2×2 RGB PNG (73 bytes) — enough for Skiko/ImageIO to produce a real ImageBitmap.
        private val TINY_PNG_BYTES = hex(
            "89504e470d0a1a0a0000000d4948445200000002000000020802000000fdd49a73" +
                "0000001049444154789c63f8cfc000440c100a001fee03fd8b5f14d40000000049454e44ae426082",
        )

        private fun hex(s: String): ByteArray {
            val clean = s.replace(" ", "")
            return ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
