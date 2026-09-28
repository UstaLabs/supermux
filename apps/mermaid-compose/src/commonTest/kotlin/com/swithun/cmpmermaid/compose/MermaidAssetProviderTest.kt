package com.swithun.cmpmermaid.compose

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.SceneAsset
import com.swithun.cmpmermaid.core.SceneAssetKind
import com.swithun.cmpmermaid.core.SceneRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MermaidAssetProviderTest {
    @Test
    fun preservesStructuredProviderFailure() = runTest {
        val states = mutableMapOf<String, MermaidAssetState>()
        val expected = MermaidAssetError.UnsupportedSource(
            source = "https://example.com/image.svg",
            message = "External assets are disabled",
        )

        resolveMermaidAssets(
            assets = listOf(asset()),
            provider = MermaidAssetProvider { GMResult.Err(expected) },
            onResolved = states::set,
        )

        val failure = assertIs<MermaidAssetState.Failed>(states.getValue("asset"))
        assertEquals(expected, failure.error)
    }

    @Test
    fun reportsMissingProviderInsteadOfDroppingAsset() {
        runTest {
            val states = mutableMapOf<String, MermaidAssetState>()

            resolveMermaidAssets(
                assets = listOf(asset()),
                provider = null,
                onResolved = states::set,
            )

            val failure = assertIs<MermaidAssetState.Failed>(states.getValue("asset"))
            assertIs<MermaidAssetError.ProviderRequired>(failure.error)
        }
    }

    @Test
    fun convertsProviderExceptionToLoadFailure() {
        runTest {
            val states = mutableMapOf<String, MermaidAssetState>()

            resolveMermaidAssets(
                assets = listOf(asset()),
                provider = MermaidAssetProvider { throw IllegalStateException("network failed") },
                onResolved = states::set,
            )

            val failure = assertIs<MermaidAssetState.Failed>(states.getValue("asset"))
            assertEquals(
                "network failed",
                assertIs<MermaidAssetError.LoadFailed>(failure.error).message,
            )
        }
    }

    @Test
    fun doesNotConvertCoroutineCancellationToAssetFailure() = runTest {
        assertFailsWith<CancellationException> {
            resolveMermaidAssets(
                assets = listOf(asset()),
                provider = MermaidAssetProvider {
                    throw CancellationException("asset load cancelled")
                },
                onResolved = { _, _ -> },
            )
        }
    }

    @Test
    fun doesNotConvertFatalErrorToAssetFailure() = runTest {
        assertFailsWith<AssertionError> {
            resolveMermaidAssets(
                assets = listOf(asset()),
                provider = MermaidAssetProvider {
                    throw AssertionError("fatal asset failure")
                },
                onResolved = { _, _ -> },
            )
        }
    }

    private fun asset() = SceneAsset(
        id = "asset",
        source = "https://example.com/image.svg",
        bounds = SceneRect(0f, 0f, 48f, 48f),
        kind = SceneAssetKind.Image,
    )
}
