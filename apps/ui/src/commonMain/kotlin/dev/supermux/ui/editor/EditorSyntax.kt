package dev.supermux.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.editor.syntax.LanguageRegistry
import dev.supermux.editor.syntax.SyntaxBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The native editor's syntax backend for this app, loaded once, on first use: the native binding
 * everywhere but the browser, where the wasm module is fetched only when an editor first opens a
 * file (`Platform.editorSyntax`, M5). [load] answering null (or throwing) means "no syntax here": a
 * Linux or CI build without the Mac-built library, a web build that staged the placeholder module.
 * Editors then show plain text; nothing crashes.
 */
class EditorSyntax(private val load: suspend () -> SyntaxBackend?) {
    val registry: LanguageRegistry get() = LanguageRegistry.default

    /** The loaded backend, once [backend] has run (snapshot state: a status line can show it). */
    var loaded: SyntaxBackend? by mutableStateOf(null)
        private set

    /** Why there is no backend, once a load failed (null while none was tried, or it worked). */
    var failure: String? by mutableStateOf(null)
        private set

    private val mutex = Mutex()
    private var done = false

    /** The backend (loading it the first time), or null where there is none. */
    suspend fun backend(): SyntaxBackend? {
        if (done) return loaded
        return mutex.withLock {
            if (!done) {
                try {
                    loaded = load()
                    if (loaded == null) failure = "no syntax library on this platform"
                } catch (e: CancellationException) {
                    throw e // the next caller tries again
                } catch (e: Throwable) {
                    // UnsatisfiedLinkError, a refused wasm module: plain text from now on, said once.
                    failure = e.message ?: e.toString()
                    println("[editor] syntax highlighting is off: $failure")
                }
                done = true
            }
            loaded
        }
    }

    /** The language of [path] (null: plain text: no grammar for it, see LanguageRegistry.forFile). */
    fun languageFor(path: String): String? = registry.forFile(path)

    companion object {
        /** No syntax at all (tests, a host that opts out). */
        val None: EditorSyntax = EditorSyntax { null }
    }
}

/** This platform's backend: the native binding (JVM, Android, iOS) or the wasm module (browser). */
internal expect suspend fun loadPlatformSyntaxBackend(): SyntaxBackend?

/** The app's one syntax backend by default ([dev.supermux.ui.platform.Platform.editorSyntax]). */
val DefaultEditorSyntax: EditorSyntax = EditorSyntax { loadPlatformSyntaxBackend() }
