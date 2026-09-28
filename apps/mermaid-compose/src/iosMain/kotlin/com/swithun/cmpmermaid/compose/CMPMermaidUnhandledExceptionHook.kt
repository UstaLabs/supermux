package com.swithun.cmpmermaid.compose

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.native.terminateWithUnhandledException

/**
 * Platform-neutral details captured from an unhandled Kotlin/Native exception.
 *
 * The host decides whether and where to report this information. CMPMermaid does not depend on a
 * crash-reporting SDK and does not perform network or persistence work.
 */
class CMPMermaidUnhandledExceptionInfo internal constructor(
    val version: Int,
    val stackTraceAddresses: List<Long>,
    val message: String?,
    val simpleClassName: String?,
    val qualifiedName: String?,
)

/**
 * Installs a process-wide Kotlin/Native unhandled-exception hook for the host application.
 *
 * Only the application that owns the process should install this hook. Installing it replaces any
 * previously installed Kotlin/Native hook. After [handler] returns, the original exception still
 * terminates the process with the standard Kotlin/Native behavior.
 */
class CMPMermaidUnhandledExceptionHook {
    @OptIn(ExperimentalNativeApi::class)
    fun install(handler: (CMPMermaidUnhandledExceptionInfo) -> Unit) {
        setUnhandledExceptionHook { throwable ->
            val info = throwable.toCMPMermaidUnhandledExceptionInfo()
            try {
                handler(info)
            } finally {
                terminateWithUnhandledException(throwable)
            }
        }
    }
}

@OptIn(ExperimentalNativeApi::class)
internal fun Throwable.toCMPMermaidUnhandledExceptionInfo(): CMPMermaidUnhandledExceptionInfo =
    CMPMermaidUnhandledExceptionInfo(
        version = 1,
        stackTraceAddresses = getStackTraceAddresses(),
        message = message,
        simpleClassName = this::class.simpleName,
        qualifiedName = this::class.qualifiedName,
    )
