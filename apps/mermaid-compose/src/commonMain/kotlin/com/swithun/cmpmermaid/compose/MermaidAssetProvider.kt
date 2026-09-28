package com.swithun.cmpmermaid.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.SceneAsset
import kotlinx.coroutines.CancellationException
import com.swithun.cmpmermaid.core.SceneAssetKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface MermaidAssetError {
    val message: String

    data class ProviderRequired(
        val source: String,
        override val message: String = "No asset provider can load '$source' on this platform",
    ) : MermaidAssetError

    data class UnsupportedSource(
        val source: String,
        override val message: String = "Unsupported Mermaid asset source: $source",
    ) : MermaidAssetError

    data class LoadFailed(
        val source: String,
        override val message: String,
    ) : MermaidAssetError
}

data class MermaidResolvedAsset(
    val image: ImageBitmap,
    val intrinsicWidth: Int = image.width,
    val intrinsicHeight: Int = image.height,
)

/**
 * Retained for source and binary compatibility. Built-in network loading is no
 * longer available; inject a host-owned [MermaidAssetProvider] for external assets.
 */
@Deprecated(
    message = "Built-in network loading was removed; inject a host MermaidAssetProvider",
)
enum class MermaidNetworkAccess {
    Disabled,
    HttpAndHttps,
}

fun interface MermaidAssetProvider {
    suspend fun resolve(
        asset: SceneAsset,
    ): GMResult<MermaidResolvedAsset, MermaidAssetError>
}

@Composable
internal expect fun rememberPlatformMermaidAssetProvider(): MermaidAssetProvider?

internal sealed interface MermaidAssetState {
    object Loading : MermaidAssetState

    data class Resolved(
        val asset: MermaidResolvedAsset,
    ) : MermaidAssetState

    data class Failed(
        val error: MermaidAssetError,
    ) : MermaidAssetState
}

internal suspend fun resolveMermaidAssets(
    assets: List<SceneAsset>,
    provider: MermaidAssetProvider?,
    onResolved: (String, MermaidAssetState) -> Unit,
) {
    assets.forEach { asset ->
        val result = if (provider == null) {
            GMResult.Err(MermaidAssetError.ProviderRequired(asset.source))
        } else {
            try {
                provider.resolve(asset)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                GMResult.Err(
                    MermaidAssetError.LoadFailed(
                        source = asset.source,
                        message = failure.message?.takeIf(String::isNotBlank)
                            ?: "Failed to load Mermaid asset '${asset.source}'",
                    ),
                )
            }
        }
        onResolved(
            asset.id,
            when (result) {
                is GMResult.Ok -> MermaidAssetState.Resolved(result.value)
                is GMResult.Err -> MermaidAssetState.Failed(result.error)
            },
        )
    }
}

internal fun MermaidAssetProvider.cached(): MermaidAssetProvider =
    CachingMermaidAssetProvider(this)

private class CachingMermaidAssetProvider(
    private val delegate: MermaidAssetProvider,
) : MermaidAssetProvider {
    private val mutex = Mutex()
    private val cache = mutableMapOf<AssetCacheKey, GMResult<MermaidResolvedAsset, MermaidAssetError>>()

    override suspend fun resolve(
        asset: SceneAsset,
    ): GMResult<MermaidResolvedAsset, MermaidAssetError> = mutex.withLock {
        val key = AssetCacheKey(asset.kind, asset.source)
        cache[key] ?: delegate.resolve(asset).also { result ->
            cache[key] = result
        }
    }

    private data class AssetCacheKey(
        val kind: SceneAssetKind,
        val source: String,
    )
}
