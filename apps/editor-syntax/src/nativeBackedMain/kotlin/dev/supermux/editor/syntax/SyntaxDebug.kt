package dev.supermux.editor.syntax

/**
 * Debug API for leak checks in hosts' tests (a syntax host that closes its worker must free every
 * tree): the native trees alive right now, process-wide. Not a stable API.
 */
object SyntaxDebug {
    fun liveTrees(): Long = Ses.debugLiveTrees()
}
