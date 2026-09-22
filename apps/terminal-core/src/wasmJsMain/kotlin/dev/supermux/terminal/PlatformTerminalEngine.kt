package dev.supermux.terminal

// browser actual. The real engine (the ghostty-vt wasm module + st_* exports) lands with the wasm
// loader; until then every call fails with the typed exception (reason NOT_LINKED) — the
// documented red state of EngineContractTest on wasm. Deliberately no Kotlin fallback emulator.
actual fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine =
    throw TerminalEngineUnavailableException()
