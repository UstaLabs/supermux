package dev.supermux.ui.editor

import dev.supermux.editor.syntax.NativeBackend
import dev.supermux.editor.syntax.SyntaxBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The native ses_* binding. Touching `languages` loads the library (JNI / the linked static
// archive) off the UI thread; a missing or refused library throws, and EditorSyntax turns that into
// plain-text editors.
internal actual suspend fun loadPlatformSyntaxBackend(): SyntaxBackend? =
    withContext(Dispatchers.Default) { NativeBackend().also { it.languages } }
