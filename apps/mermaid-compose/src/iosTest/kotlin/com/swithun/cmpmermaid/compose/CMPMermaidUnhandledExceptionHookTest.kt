package com.swithun.cmpmermaid.compose

import kotlin.test.Test
import kotlin.test.assertEquals

class CMPMermaidUnhandledExceptionHookTest {
    @Test
    fun convertsThrowableToPublicExceptionInfo() {
        val failure = IllegalStateException("render failed")

        val info = failure.toCMPMermaidUnhandledExceptionInfo()

        assertEquals(1, info.version)
        assertEquals("render failed", info.message)
        assertEquals("IllegalStateException", info.simpleClassName)
        assertEquals("kotlin.IllegalStateException", info.qualifiedName)
    }
}
