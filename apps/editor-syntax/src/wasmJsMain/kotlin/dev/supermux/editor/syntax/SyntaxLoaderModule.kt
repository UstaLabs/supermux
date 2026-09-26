@file:JsModule("./syntax-loader.mjs")

package dev.supermux.editor.syntax

import kotlin.js.Promise

// Named exports of syntax-loader.mjs, a resource of this artifact next to supermux-syntax.wasm.
// The relative specifier is resolved by the host app's bundler against the compiled Kotlin/Wasm
// module, where the resources are staged (native/README.md, "Web").

/** Load the process-wide runtime once; rejects with a SyntaxLoadError ({ reason, message }). */
@JsName("initialize")
internal external fun loaderInitialize(url: String?, tablesUrl: String?): Promise<SyntaxRuntime>

@JsName("currentRuntime")
internal external fun loaderCurrentRuntime(): SyntaxRuntime?

/** The host callbacks every module instance's env.ses_host_read / env.ses_host_match call. */
@JsName("setHost")
internal external fun loaderSetHost(read: (Int, Int) -> String?, match: (Int, Int, String) -> Int)

/** Where the code-only grammar [lang]'s tables are fetched from. */
@JsName("tablesUrl")
internal external fun loaderTablesUrl(lang: String): String

@JsName("dropResource")
internal external fun loaderDropResource(path: String)

@JsName("hasResource")
internal external fun loaderHasResource(path: String): Boolean

/** Resource [path]'s bytes as a latin1 string (one char per byte), or null. */
@JsName("resourceLatin1")
internal external fun loaderResourceLatin1(path: String): String?

/** ses_language_provide_tables from resource [path], without copying it through Kotlin. */
@JsName("provideTablesFromResource")
internal external fun loaderProvideTablesFromResource(name: String, path: String): Int

/** Fetch [url] into resource [path]; resolves to null, or an error message. */
@JsName("fetchResource")
internal external fun loaderFetchResource(path: String, url: String): Promise<JsString?>

/** Resolves in a new task (MessageChannel): input, rendering and timers already queued run first. */
@JsName("nextTask")
internal external fun loaderNextTask(): Promise<JsAny?>

/** A NEW runtime (its own instance and memory), independent of [loaderInitialize]. */
@JsName("loadRuntime")
internal external fun loaderLoadRuntime(url: String?): Promise<SyntaxRuntime>

/** TEST-ONLY (the browser tests' withFreshRuntime): make [rt] the process-wide runtime; returns the previous one. */
@JsName("useRuntimeForTests")
internal external fun loaderUseRuntimeForTests(rt: SyntaxRuntime?): SyntaxRuntime?
