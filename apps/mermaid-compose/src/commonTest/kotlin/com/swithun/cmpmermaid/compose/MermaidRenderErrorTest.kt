package com.swithun.cmpmermaid.compose

import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderErrorInfo
import com.swithun.cmpmermaid.core.MermaidRenderErrorType
import kotlin.test.Test
import kotlin.test.assertEquals

class MermaidRenderErrorTest {
    @Test
    fun reportsContentErrorWithOriginalSource() {
        val source = "flowchart TD\n  A["
        var callback: MermaidRenderErrorInfo? = null

        reportMermaidRenderError(
            error = MermaidError.Parse(2, 4, "Unexpected token"),
            source = source,
            onError = { error -> callback = error },
        )

        assertEquals(
            MermaidRenderErrorInfo(
                type = MermaidRenderErrorType.CONTENT_ERROR,
                message = "Unexpected token",
                source = source,
            ),
            callback,
        )
    }

    @Test
    fun reportsUnexpectedExceptionOnlyOnceForOneScene() {
        val reporter = MermaidRenderExceptionReporter()
        val errors = mutableListOf<MermaidError.Unexpected>()

        reporter.report(IndexOutOfBoundsException("bad index"), errors::add)
        reporter.report(IllegalStateException("second failure"), errors::add)

        val error = errors.single()
        assertEquals(MermaidRenderErrorType.UNEXPECTED_EXCEPTION, error.renderErrorType)
        assertEquals("IndexOutOfBoundsException", error.exceptionType)
        assertEquals(
            "Unexpected Mermaid rendering exception (IndexOutOfBoundsException): bad index",
            error.message,
        )
    }
}
