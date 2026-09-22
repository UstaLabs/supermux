package dev.supermux.terminal.consumer

import dev.supermux.terminal.TerminalRuntime
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.Promise
import kotlin.test.Test

/** Run a suspend test body; kotlin-test on wasmJs awaits a returned Promise. */
private fun runAsync(block: suspend () -> Unit): Promise<JsAny?> = Promise { resolve, reject ->
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { result ->
            result.fold({ resolve(null) }, { reject(it.toJsReference()) })
        },
    )
}

/**
 * The browser consumer check: a third-party Kotlin/Wasm build with ONE dependency loads the engine
 * with `TerminalRuntime.initialize()` and runs the same semantic fixture.
 *
 * It deliberately passes NO url: the package default is `new URL("./supermux-terminal.wasm",
 * import.meta.url)` inside `terminal-loader.mjs`, and both files are resources of the published
 * `terminal-core-wasm-js-*.klib`. So a green run also proves the consumer's own bundler emitted the
 * packaged wasm module as an asset — the path a host app takes. If a consumer toolchain does not
 * copy klib resources, this is where that shows up (README: "wasm").
 */
class PackagedWasmEngineTest {
    @Test
    fun packagedWasmModuleRunsTheFixture() = runAsync {
        TerminalRuntime.initialize()
        println("consumer-smoke(wasm): " + ConsumerFixture.run())
    }
}
