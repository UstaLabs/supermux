package dev.supermux.terminal

// browser actual. The real engine (the ghostty-vt wasm module + st_* exports) lands with the native bindings; until then every call
// fails with the typed exception — the documented red state of EngineContractTest. Deliberately
// no Kotlin fallback emulator.
actual fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine =
    throw TerminalEngineUnavailableException()
