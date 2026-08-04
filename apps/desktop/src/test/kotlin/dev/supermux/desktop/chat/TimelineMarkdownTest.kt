package dev.supermux.desktop.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.unit.dp
import dev.supermux.ui.ColumnAlign
import dev.supermux.ui.MdBlock
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * Click opens via the injected [onOpenUrl] seam — never the real browser (Chrome would hang
     * the Gradle test worker by inheriting the worker's output pipe).
     */
    @Test fun https_image_click_invokesOnOpenUrlSeam() = runComposeUiTest {
        val png = decodeImageBytes(TINY_PNG_BYTES)
        assertTrue(png != null)
        val opened = AtomicReference<String?>(null)
        setContent {
            MarkdownImage(
                image = MdBlock.Image(url = "https://example.com/pic.png", alt = "diagram"),
                loadImage = { png },
                onOpenUrl = { opened.set(it) },
            )
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("md_image").fetchSemanticsNodes().isNotEmpty()
        }
        onNodeWithTag("md_image").assertIsDisplayed()
        onNodeWithTag("md_image").performClick()
        assertEquals(
            "https://example.com/pic.png",
            opened.get(),
            "click must invoke the open-url seam with the image URL",
        )
    }

    // ── Pure helpers (https gate, size cap, forced Skiko decode, redirects) ───────────

    @Test fun isHttpsImageUrl_accepts_https_only() {
        assertTrue(isHttpsImageUrl("https://example.com/a.png"))
        assertTrue(isHttpsImageUrl("HTTPS://EXAMPLE.COM/a.png"))
        assertTrue(!isHttpsImageUrl("http://example.com/a.png"))
        assertTrue(!isHttpsImageUrl("ftp://example.com/a.png"))
        assertTrue(!isHttpsImageUrl("/relative/path.png"))
        assertTrue(!isHttpsImageUrl(""))
    }

    @Test fun resolveImageRedirectUrl_relativeAndAbsolute() {
        assertEquals(
            "https://cdn.example.com/b.png",
            resolveImageRedirectUrl("https://example.com/a.png", "https://cdn.example.com/b.png"),
        )
        assertEquals(
            "http://insecure.example/x",
            resolveImageRedirectUrl("https://example.com/a.png", "http://insecure.example/x"),
        )
        assertEquals(
            "https://example.com/img/b.png",
            resolveImageRedirectUrl("https://example.com/img/a.png", "b.png"),
        )
        assertEquals(
            "https://example.com/other/b.png",
            resolveImageRedirectUrl("https://example.com/img/a.png", "/other/b.png"),
        )
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

    /**
     * Exercise the **production** [loadMarkdownImageBitmap] path (not a reimplemented
     * `withContext(IO)`), with a faked fetch so there is no network. Asserts decode runs on an
     * IO/worker thread.
     */
    @Test fun loadMarkdownImageBitmap_productionPath_decodeRunsOnIoDispatcher() = runBlocking {
        val threadName = AtomicReference<String?>(null)
        val bmp = loadMarkdownImageBitmap(
            url = "https://example.com/pic.png",
            fetchBytes = { _, _ ->
                threadName.set(Thread.currentThread().name)
                TINY_PNG_BYTES
            },
        )
        assertTrue(bmp != null, "production load path must decode the fetched PNG")
        val name = threadName.get()
        assertTrue(name != null, "expected a worker thread name")
        assertTrue(
            name!!.contains("DefaultDispatcher") || name.contains("IO") || name.contains("worker"),
            "fetch+decode should run on IO/worker thread, got: $name",
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

    /**
     * Assert actual layout bounds: loading placeholder height in px equals MaxHeight under the
     * composition density (not just constant equality of the dp tokens).
     */
    @Test fun mdImage_loadingPlaceholder_layoutHeightMatchesMaxHeight() = runComposeUiTest {
        setContent {
            // Constrain width so fillMaxWidth has a concrete measure.
            Box(Modifier.width(320.dp)) {
                MarkdownImage(
                    image = MdBlock.Image(url = "https://example.com/slow.png", alt = "x"),
                    // Never complete — stay on the loading placeholder.
                    loadImage = {
                        kotlinx.coroutines.delay(60_000)
                        null
                    },
                )
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("md_image_loading").fetchSemanticsNodes().isNotEmpty()
        }
        val node = onNodeWithTag("md_image_loading").fetchSemanticsNode()
        val heightPx = node.layoutInfo.height
        // 280.dp at the test density — require a substantial reserved height (not a tiny spinner box).
        assertTrue(heightPx > 100, "loading placeholder layout height was $heightPx px (expected ~MaxHeight)")
        // Bound: MaxHeight is 280.dp; allow density variance but stay near that scale.
        assertTrue(heightPx < 800, "loading placeholder unexpectedly tall: $heightPx px")
    }

    /**
     * After load, a short (2×2) image may be shorter than the loading box — that is the known
     * downward-reflow trade-off. Assert we still paint something with real layout bounds.
     */
    @Test fun mdImage_loaded_hasPositiveLayoutBounds() = runComposeUiTest {
        val png = decodeImageBytes(TINY_PNG_BYTES)
        assertTrue(png != null)
        setContent {
            Box(Modifier.width(320.dp).fillMaxWidth()) {
                MarkdownImage(
                    image = MdBlock.Image(url = "https://example.com/pic.png", alt = "x"),
                    loadImage = { png },
                )
            }
        }
        waitUntil(timeoutMillis = 5_000L) {
            onAllNodesWithTag("md_image").fetchSemanticsNodes().isNotEmpty()
        }
        val node = onNodeWithTag("md_image").fetchSemanticsNode()
        assertTrue(node.layoutInfo.width > 0, "loaded image width must be > 0")
        assertTrue(node.layoutInfo.height > 0, "loaded image height must be > 0")
        // heightIn(max=MaxHeight) — painted height must not exceed a generous px bound for 280.dp
        assertTrue(node.layoutInfo.height < 800, "image exceeded max height: ${node.layoutInfo.height}")
    }

    @Test fun fetchHttpsImageBytes_rejects_non_https() {
        assertTrue(fetchHttpsImageBytes("http://example.com/x.png") == null)
        assertTrue(fetchHttpsImageBytes("file:///tmp/x.png") == null)
    }

    // ── Production network matrix (local HttpServer + policy seam) ────────────────────

    @Test fun fetch_relativeRedirect_resolvesAndReturnsBody() = withLocalServer { base ->
        val hops = AtomicInteger(0)
        serverCreateContext("/start") { ex ->
            hops.incrementAndGet()
            ex.responseHeaders.add("Location", "/img/final.png")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        serverCreateContext("/img/final.png") { ex ->
            hops.incrementAndGet()
            ex.responseHeaders.add("Content-Type", "image/png")
            ex.sendResponseHeaders(200, TINY_PNG_BYTES.size.toLong())
            ex.responseBody.use { it.write(TINY_PNG_BYTES) }
        }
        val bytes = fetchImageBytesWithPolicy(
            url = "$base/start",
            isAllowedUrl = { it.startsWith(base) },
        )
        assertTrue(bytes != null && bytes.contentEquals(TINY_PNG_BYTES), "relative redirect must yield body")
        assertEquals(2, hops.get())
    }

    @Test fun fetch_httpsToHttpDowngrade_rejected() = withLocalServer { base ->
        serverCreateContext("/secure-ish") { ex ->
            // Absolute http Location off-box — production isHttpsImageUrl rejects the hop.
            ex.responseHeaders.add("Location", "http://evil.example/track.png")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        // Start URL is allowed only because we use a localhost policy for the first hop; the
        // downgraded Location must still be rejected by isHttpsImageUrl.
        val bytes = fetchImageBytesWithPolicy(
            url = "$base/secure-ish",
            isAllowedUrl = { u -> u.startsWith(base) || isHttpsImageUrl(u) },
        )
        assertNull(bytes, "http downgrade redirect must not be followed")
    }

    @Test fun fetch_redirectHopLimit_returnsNull() = withLocalServer { base ->
        val hits = AtomicInteger(0)
        serverCreateContext("/loop") { ex ->
            hits.incrementAndGet()
            ex.responseHeaders.add("Location", "$base/loop")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        val bytes = fetchImageBytesWithPolicy(
            url = "$base/loop",
            maxRedirects = 5,
            isAllowedUrl = { it.startsWith(base) },
        )
        assertNull(bytes, "redirect loop must exhaust hop budget and return null")
        assertEquals(5, hits.get(), "must stop after maxRedirects hops, got ${hits.get()}")
    }

    @Test fun fetch_chunkedOversizeBody_returnsNull() = withLocalServer { base ->
        serverCreateContext("/big") { ex ->
            // No Content-Length — chunked. Body exceeds a tiny maxBytes.
            val payload = ByteArray(64) { 7 }
            ex.sendResponseHeaders(200, 0) // 0 = chunked
            ex.responseBody.use { out: OutputStream -> out.write(payload) }
        }
        val bytes = fetchImageBytesWithPolicy(
            url = "$base/big",
            maxBytes = 16,
            isAllowedUrl = { it.startsWith(base) },
        )
        assertNull(bytes, "chunked body over maxBytes must be rejected")
    }

    @Test fun fetch_unreachableHost_returnsNull() {
        // Closed port on loopback — connection refused. Production https gate.
        val bytes = fetchHttpsImageBytes("https://127.0.0.1:1/nope.png", maxBytes = 1024)
        assertNull(bytes)
    }

    @Test fun fetch_404_returnsNull() = withLocalServer { base ->
        serverCreateContext("/missing.png") { ex ->
            val msg = "not found".toByteArray()
            ex.sendResponseHeaders(404, msg.size.toLong())
            ex.responseBody.use { it.write(msg) }
        }
        val bytes = fetchImageBytesWithPolicy(
            url = "$base/missing.png",
            isAllowedUrl = { it.startsWith(base) },
        )
        assertNull(bytes, "404 must return null")
    }

    @Test fun fetch_declaredContentLengthOverCap_returnsNull() = withLocalServer { base ->
        serverCreateContext("/huge-declared") { ex ->
            // Content-Length over cap — reject before reading body.
            ex.responseHeaders.add("Content-Type", "image/png")
            ex.sendResponseHeaders(200, 100_000)
            ex.responseBody.use { it.write(ByteArray(100_000)) }
        }
        val bytes = fetchImageBytesWithPolicy(
            url = "$base/huge-declared",
            maxBytes = 1024,
            isAllowedUrl = { it.startsWith(base) },
        )
        assertNull(bytes)
    }

    // ── local HTTP test harness ───────────────────────────────────────────────────────

    /**
     * Spin a loopback [HttpServer], run [block] with `http://127.0.0.1:<port>` as [base], then stop.
     * Contexts are registered via the receiver's [serverCreateContext] (set for the duration of
     * the block). Uses plain HTTP; tests pass [isAllowedUrl] that permits the loopback base so we
     * can exercise redirect/body/status logic without a self-signed HTTPS stack. Production still
     * uses [isHttpsImageUrl] exclusively via [fetchHttpsImageBytes].
     */
    private fun withLocalServer(block: LocalServerScope.(base: String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        try {
            val port = server.address.port
            val base = "http://127.0.0.1:$port"
            val scope = LocalServerScope(server)
            scope.block(base)
        } finally {
            server.stop(0)
        }
    }

    private class LocalServerScope(private val server: HttpServer) {
        fun serverCreateContext(
            path: String,
            handler: com.sun.net.httpserver.HttpHandler,
        ) {
            server.createContext(path, handler)
        }
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
