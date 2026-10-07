package com.swithun.cmpmermaid.core.flowchart.upstream.elk

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ElkJsRuntimeTest {
    @Test
    fun rejectsElkUntilTheAlgorithmHasAPureKotlinTranslation() {
        val result = ElkJsRuntime.layout("""{"id":"root"}""")
        val error = assertIs<GMResult.Err<MermaidError.UnsupportedFeature>>(result).error

        assertEquals("ELK layout", error.feature)
    }
}
