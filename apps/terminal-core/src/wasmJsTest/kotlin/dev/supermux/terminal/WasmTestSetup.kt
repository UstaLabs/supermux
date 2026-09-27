@file:JsModule("./terminal-test-setup.mjs")

package dev.supermux.terminal

/** null when the test runtime loaded (terminal-test-setup.mjs), else "<reason>: <message>". */
internal external val setupFailure: String?
