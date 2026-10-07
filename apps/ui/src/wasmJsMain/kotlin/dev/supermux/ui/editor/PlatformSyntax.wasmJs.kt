package dev.supermux.ui.editor

import dev.supermux.editor.syntax.SyntaxBackend
import dev.supermux.editor.syntax.WasmBackend

// The browser's wasm module, at the loader's default URLs. The web app's WebPlatform passes the
// hashed tables directory instead (`Platform.editorSyntax`); this default only serves a host
// that does not.
internal actual suspend fun loadPlatformSyntaxBackend(): SyntaxBackend? = WasmBackend.load()
