package dev.supermux.terminal

// Desktop JVM actual: the JNI engine (libsupermux_terminal_jni extracted from this jar's resources
// by JvmNativeLibrary). Deliberately no Kotlin fallback emulator: a missing, corrupt or
// ABI-mismatched library is a typed TerminalEngineUnavailableException.
actual fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine {
    JvmNativeLibrary.ensureLoaded()
    return NativeTerminalEngine.open(size, limits)
}
