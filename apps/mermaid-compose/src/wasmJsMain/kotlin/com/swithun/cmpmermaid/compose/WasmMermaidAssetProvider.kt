@file:OptIn(ExperimentalWasmJsInterop::class)

package com.swithun.cmpmermaid.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneAssetKind
import kotlinx.coroutines.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.ceil
import kotlin.math.min
import org.jetbrains.skia.Data
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthUnit

@Composable
internal actual fun rememberPlatformMermaidAssetProvider(): MermaidAssetProvider? =
    remember { WasmMermaidAssetProvider() }

/**
 * Loads data URI bitmap and SVG assets without granting network access.
 */
class WasmMermaidAssetProvider : MermaidAssetProvider {
    override suspend fun resolve(
        asset: SceneAsset,
    ): GMResult<MermaidResolvedAsset, MermaidAssetError> {
        if (asset.kind == SceneAssetKind.Icon) {
            return GMResult.Err(
                MermaidAssetError.UnsupportedSource(
                    source = asset.source,
                    message = "Icon pack '${asset.source.substringBefore(':')}' is not registered",
                ),
            )
        }
        val loaded = when (val result = decodeDataUri(asset.source.trim())) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        return if (loaded.contentType.equals("image/svg+xml", ignoreCase = true)) {
            decodeSvg(asset, loaded.bytes)
        } else {
            decodeBitmap(asset, loaded.bytes)
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeDataUri(
        source: String,
    ): GMResult<LoadedBytes, MermaidAssetError> {
        if (!source.startsWith("data:", ignoreCase = true)) {
            return GMResult.Err(
                MermaidAssetError.UnsupportedSource(
                    source = source,
                    message = if (
                        source.startsWith("https://", ignoreCase = true) ||
                        source.startsWith("http://", ignoreCase = true)
                    ) {
                        "Network image loading is disabled on Wasm; inject a custom " +
                            "MermaidAssetProvider to enable it"
                    } else {
                        "Unsupported Mermaid asset source: $source"
                    },
                ),
            )
        }
        val comma = source.indexOf(',')
        if (comma <= DATA_PREFIX_LENGTH) {
            return loadFailure(source, "Malformed data URI")
        }
        val metadata = source.substring(DATA_PREFIX_LENGTH, comma)
        val payload = source.substring(comma + 1)
        val bytes = try {
            if (metadata.endsWith(";base64", ignoreCase = true)) {
                if (payload.length > MAX_BASE64_CHARACTERS) {
                    return loadFailure(
                        source,
                        "Asset exceeds the ${MAX_ASSET_BYTES / 1_048_576} MiB limit",
                    )
                }
                Base64.Default.decode(payload)
            } else {
                decodeUriComponent(payload).encodeToByteArray()
            }
        } catch (failure: Throwable) {
            return recoverLoadFailure(source, failure)
        }
        if (bytes.size > MAX_ASSET_BYTES) {
            return loadFailure(source, "Asset exceeds the ${MAX_ASSET_BYTES / 1_048_576} MiB limit")
        }
        return GMResult.Ok(
            LoadedBytes(
                bytes = bytes,
                contentType = metadata.substringBefore(';').takeIf(String::isNotBlank),
            ),
        )
    }

    private fun decodeBitmap(
        asset: SceneAsset,
        bytes: ByteArray,
    ): GMResult<MermaidResolvedAsset, MermaidAssetError> = try {
        val bitmap = bytes.decodeToImageBitmap()
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            loadFailure(asset.source, "Unsupported or invalid image data")
        } else {
            GMResult.Ok(
                MermaidResolvedAsset(
                    image = bitmap,
                    intrinsicWidth = bitmap.width,
                    intrinsicHeight = bitmap.height,
                ),
            )
        }
    } catch (failure: Throwable) {
        recoverLoadFailure(asset.source, failure)
    }

    private fun decodeSvg(
        asset: SceneAsset,
        bytes: ByteArray,
    ): GMResult<MermaidResolvedAsset, MermaidAssetError> {
        val svg = try {
            SVGDOM(Data.makeFromBytes(bytes))
        } catch (failure: Throwable) {
            return recoverLoadFailure(asset.source, failure)
        }
        return try {
            val root = svg.root
                ?: return loadFailure(asset.source, "Unsupported or invalid SVG data")
            val viewBox = root.viewBox
            val intrinsicWidth = absoluteDimension(root.width.value, root.width.unit)
                ?: positiveDimension(viewBox?.width)
                ?: asset.bounds.width
            val intrinsicHeight = absoluteDimension(root.height.value, root.height.unit)
                ?: positiveDimension(viewBox?.height)
                ?: asset.bounds.height
            val rasterWidth = intrinsicWidth * SVG_RASTER_SCALE
            val rasterHeight = intrinsicHeight * SVG_RASTER_SCALE
            val decodeScale = min(
                1f,
                min(
                    MAX_DECODE_DIMENSION / rasterWidth,
                    MAX_DECODE_DIMENSION / rasterHeight,
                ),
            )
            val targetWidth = ceil(rasterWidth * decodeScale).toInt().coerceAtLeast(1)
            val targetHeight = ceil(rasterHeight * decodeScale).toInt().coerceAtLeast(1)
            val surface = Surface.makeRasterN32Premul(targetWidth, targetHeight)
            try {
                svg.setContainerSize(targetWidth.toFloat(), targetHeight.toFloat())
                svg.render(surface.canvas)
                val snapshot = surface.makeImageSnapshot()
                try {
                    GMResult.Ok(
                        MermaidResolvedAsset(
                            image = snapshot.toComposeImageBitmap(),
                            intrinsicWidth = intrinsicWidth.toInt().coerceAtLeast(1),
                            intrinsicHeight = intrinsicHeight.toInt().coerceAtLeast(1),
                        ),
                    )
                } finally {
                    snapshot.close()
                }
            } finally {
                surface.close()
            }
        } catch (failure: Throwable) {
            recoverLoadFailure(asset.source, failure)
        } finally {
            svg.close()
        }
    }

    private fun absoluteDimension(
        value: Float,
        unit: SVGLengthUnit,
    ): Float? = value.takeIf {
        unit != SVGLengthUnit.PERCENTAGE && it.isFinite() && it > 0f
    }

    private fun positiveDimension(value: Float?): Float? =
        value?.takeIf { it.isFinite() && it > 0f }

    // JavaScript interop can surface throwable values outside Kotlin's Exception hierarchy.
    private fun <T> recoverLoadFailure(
        source: String,
        failure: Throwable,
    ): GMResult<T, MermaidAssetError> {
        if (failure is CancellationException || failure is Error) {
            throw failure
        }
        return loadFailure(source, failure.message)
    }

    private fun <T> loadFailure(
        source: String,
        detail: String?,
    ): GMResult<T, MermaidAssetError> = GMResult.Err(
        MermaidAssetError.LoadFailed(
            source = source,
            message = detail?.takeIf(String::isNotBlank)
                ?.let { "Failed to load Mermaid asset '$source': $it" }
                ?: "Failed to load Mermaid asset '$source'",
        ),
    )

    private data class LoadedBytes(
        val bytes: ByteArray,
        val contentType: String?,
    )

    private companion object {
        const val DATA_PREFIX_LENGTH = 5
        const val MAX_ASSET_BYTES = 8 * 1_048_576
        const val MAX_BASE64_CHARACTERS = (MAX_ASSET_BYTES * 4 / 3) + 4
        const val MAX_DECODE_DIMENSION = 4_096f
        const val SVG_RASTER_SCALE = 4f
    }
}

@JsFun("(value) => decodeURIComponent(value)")
private external fun decodeUriComponent(value: String): String
