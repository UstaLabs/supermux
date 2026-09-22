@file:JsModule("./terminal-loader.mjs")

package dev.supermux.terminal

import kotlin.js.Promise

// Named exports of wasm/terminal-loader.mjs, shipped as a resource of this artifact next to
// supermux-terminal.wasm (see README "Browser (wasmJs)"). The relative specifier is resolved by the
// host app's bundler against the compiled Kotlin/Wasm module, where the resources are staged.

/** Load the process-wide runtime once; rejects with a TerminalLoadError ({ reason, message }). */
@JsName("initialize")
internal external fun loaderInitialize(url: String?): Promise<LoaderRuntime>

/** A NEW runtime (own instance/memory) for [url], independent of [loaderInitialize]. */
@JsName("loadRuntime")
internal external fun loaderLoadRuntime(url: String?): Promise<LoaderRuntime>

@JsName("currentRuntime")
internal external fun loaderCurrentRuntime(): LoaderRuntime?

@JsName("lastFailure")
internal external fun loaderLastFailure(): JsAny?

@JsName("isLoading")
internal external fun loaderIsLoading(): Boolean
