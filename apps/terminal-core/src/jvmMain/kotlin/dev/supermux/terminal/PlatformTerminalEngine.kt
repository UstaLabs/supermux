package dev.supermux.terminal

// JVM desktop actual. The real engine (JNI (libsupermux_terminal via System.loadLibrary)) lands with the native bindings; until then every call
// fails with the typed exception — the documented red state of EngineContractTest. Deliberately
// no Kotlin fallback emulator.
actual fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine =
    throw TerminalEngineUnavailableException()
