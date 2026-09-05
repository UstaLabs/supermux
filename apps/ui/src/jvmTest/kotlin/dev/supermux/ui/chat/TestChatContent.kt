package dev.supermux.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.FakePlatform
import dev.supermux.ui.platform.LocalPlatform

/**
 * `setContent` with a [FakePlatform] on `LocalPlatform`.
 *
 * The timeline reads the platform for read-aloud, the attachment chip's save/open and the video
 * staging, and `LocalPlatform` is deliberately static-with-no-default — an unprovided read is a
 * wiring bug in an entry point, so it throws rather than silently no-op'ing. Every chat UI test
 * therefore has to provide one, exactly as the app themes do in production.
 *
 * Defaults are the DESKTOP shape (a pointer, an Expanded window) so the suites ported from
 * `:desktop` assert what they always asserted; a touch/compact test passes its own.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.setPlatformContent(
    platform: FakePlatform = FakePlatform(),
    pointer: Boolean = true,
    widthClass: WindowWidthClass = WindowWidthClass.Expanded,
    /** Drives the composer's Enter policy: Pointer == "there is a real keyboard", so Enter sends. */
    inputMode: InputMode = InputMode.Pointer,
    content: @Composable () -> Unit,
) = setContent {
    CompositionLocalProvider(
        LocalPlatform provides platform,
        LocalPointerAvailable provides pointer,
        LocalWindowWidthClass provides widthClass,
        LocalInputMode provides inputMode,
    ) {
        content()
    }
}

/** 2×2 RGB PNG (73 bytes) — enough for Skiko/Coil to produce a real image. */
internal val TINY_PNG_BYTES: ByteArray = hexBytes(
    "89504e470d0a1a0a0000000d4948445200000002000000020802000000fdd49a73" +
        "0000001049444154789c63f8cfc000440c100a001fee03fd8b5f14d40000000049454e44ae426082",
)

internal fun hexBytes(s: String): ByteArray {
    val clean = s.replace(" ", "")
    return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/**
 * Decode PNG bytes to a Compose bitmap for the `loadImage` TEST seam. Skiko here is fine: this is
 * a JVM test source set, not `:ui` commonMain (production decodes through Coil).
 */
internal fun testDecodePng(bytes: ByteArray): ImageBitmap? =
    runCatching {
        val data = org.jetbrains.skia.Data.makeFromBytes(bytes)
        val codec = org.jetbrains.skia.Codec.makeFromData(data)
        val raster = codec.readPixels()
        if (raster.width <= 0 || raster.height <= 0 || raster.isNull) return@runCatching null
        raster.setImmutable()
        codec.close()
        data.close()
        raster.asComposeImageBitmap()
    }.getOrNull()

/** A [ChunkReader] over a JVM stream, so the capped reader can be exercised without the network. */
internal fun streamReader(stream: java.io.InputStream): ChunkReader =
    ChunkReader { buf -> stream.read(buf) }
