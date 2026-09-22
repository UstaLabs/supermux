package dev.supermux.terminal

// iOS actual. The real engine (cinterop against the static libghostty-vt + st_* wrapper) lands with the native bindings; until then every call
// fails with the typed exception — the documented red state of EngineContractTest. Deliberately
// no Kotlin fallback emulator.
actual fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine =
    throw TerminalEngineUnavailableException()
