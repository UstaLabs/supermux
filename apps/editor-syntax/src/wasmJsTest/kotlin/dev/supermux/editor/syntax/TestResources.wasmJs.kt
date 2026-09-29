@file:JsModule("./syntax-test-setup.mjs")

package dev.supermux.editor.syntax

/** null when syntax-test-setup.mjs loaded the runtime and every test resource, else why not. */
internal external val setupFailure: String?
