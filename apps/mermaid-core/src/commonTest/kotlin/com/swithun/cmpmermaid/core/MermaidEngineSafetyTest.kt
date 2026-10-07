package com.swithun.cmpmermaid.core

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MermaidEngineSafetyTest {
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { TextMetrics(width = 8f, height = 18f) },
    )

    @Test
    fun convertsUnexpectedPluginExceptionToStructuredFailure() {
        val engine = MermaidEngine(
            plugins = listOf(
                plugin { _, _ ->
                    throw IndexOutOfBoundsException("index 4 is outside size 2")
                },
            ),
        )

        val result = engine.render("testDiagram\n  A", context)

        val error = assertIs<MermaidError.Unexpected>(
            assertIs<GMResult.Err<MermaidError>>(result).error,
        )
        assertEquals(MermaidRenderErrorType.UNEXPECTED_EXCEPTION, error.renderErrorType)
        assertEquals("IndexOutOfBoundsException", error.exceptionType)
        assertTrue(error.message.contains("index 4 is outside size 2"))
    }

    @Test
    fun preservesExpectedContentFailure() {
        val expected = MermaidError.Parse(
            line = 2,
            column = 3,
            message = "Unexpected token",
        )
        val engine = MermaidEngine(
            plugins = listOf(plugin { _, _ -> GMResult.Err(expected) }),
        )

        val error = assertIs<GMResult.Err<MermaidError>>(
            engine.render("testDiagram\n  [", context),
        ).error

        assertEquals(expected, error)
        assertEquals(MermaidRenderErrorType.CONTENT_ERROR, error.renderErrorType)
    }

    @Test
    fun classifiesTextMetricProviderExceptionAsUnexpected() {
        val failingContext = context.copy(
            textMetrics = TextMetricProvider {
                throw IndexOutOfBoundsException("font table index")
            },
        )

        val error = assertIs<MermaidError.Unexpected>(
            assertIs<GMResult.Err<MermaidError>>(
                MermaidEngine().render("flowchart LR\n  A --> B", failingContext),
            ).error,
        )

        assertEquals(MermaidRenderErrorType.UNEXPECTED_EXCEPTION, error.renderErrorType)
        assertTrue(error.message.contains("Text measurement failed for 'A'"))
        assertTrue(error.message.contains("font table index"))
    }

    @Test
    fun doesNotConvertCoroutineCancellationToRenderFailure() {
        val engine = MermaidEngine(
            plugins = listOf(
                plugin { _, _ ->
                    throw CancellationException("render cancelled")
                },
            ),
        )

        assertFailsWith<CancellationException> {
            engine.render("testDiagram\n  A", context)
        }
    }

    @Test
    fun doesNotConvertFatalErrorToRenderFailure() {
        val engine = MermaidEngine(
            plugins = listOf(
                plugin { _, _ ->
                    throw AssertionError("fatal renderer failure")
                },
            ),
        )

        assertFailsWith<AssertionError> {
            engine.render("testDiagram\n  A", context)
        }
    }

    private fun plugin(
        implementation: (
            source: String,
            context: MermaidRenderContext,
        ) -> GMResult<MermaidScene, MermaidError>,
    ): MermaidDiagramPlugin = object : MermaidDiagramPlugin {
        override val id: String = "test"
        override val headers: Set<String> = setOf("testdiagram")

        override fun compile(
            source: String,
            context: MermaidRenderContext,
        ): GMResult<MermaidScene, MermaidError> = implementation(source, context)
    }
}
