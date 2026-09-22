package dev.supermux.terminal

import android.os.Build
import dev.supermux.terminal.TerminalEngineUnavailableException.Reason

// Android actual: the JNI engine (libsupermux_terminal_jni.so from the AAR's jniLibs, arm64-v8a
// and x86_64). Deliberately no Kotlin fallback emulator.
actual fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine {
    AndroidNativeLibrary.ensureLoaded()
    return NativeTerminalEngine.open(size, limits)
}

internal object AndroidNativeLibrary {
    const val LIBRARY = "supermux_terminal_jni"
    val PACKAGED_ABIS = listOf("arm64-v8a", "x86_64")

    private val failure: TerminalEngineUnavailableException? by lazy { load() }

    fun ensureLoaded() {
        failure?.let { throw TerminalEngineUnavailableException(it.message ?: "", it, it.reason) }
    }

    private fun load(): TerminalEngineUnavailableException? {
        val abis = Build.SUPPORTED_ABIS.orEmpty().toList()
        if (abis.none { it in PACKAGED_ABIS }) {
            return TerminalEngineUnavailableException(
                "no supermux terminal engine for ABIs $abis (packaged: $PACKAGED_ABIS)",
                reason = Reason.UNSUPPORTED_PLATFORM,
            )
        }
        try {
            System.loadLibrary(LIBRARY)
        } catch (e: UnsatisfiedLinkError) {
            val missing = e.message.orEmpty().let { "couldn't find" in it || "not found" in it }
            return TerminalEngineUnavailableException(
                "cannot load lib$LIBRARY.so: ${e.message}", e,
                if (missing) Reason.MISSING_BINARY else Reason.INITIALIZATION_FAILED,
            )
        }
        return try {
            NativeTerminal.verifyAbi(NativeStatus.ABI_VERSION, "lib$LIBRARY.so")
            null
        } catch (e: TerminalEngineUnavailableException) {
            e
        }
    }
}
