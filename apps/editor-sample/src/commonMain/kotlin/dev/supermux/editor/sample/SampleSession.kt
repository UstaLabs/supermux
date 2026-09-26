package dev.supermux.editor.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.sample.resources.Res
import dev.supermux.editor.syntax.LanguageRegistry
import dev.supermux.editor.syntax.QueryKind
import dev.supermux.editor.syntax.Syntax
import dev.supermux.editor.syntax.SyntaxBackend
import dev.supermux.editor.syntax.SyntaxWorker
import kotlinx.coroutines.CoroutineScope

/** The files the sample opens. */
enum class SampleFile(val label: String, val language: String?) {
    KOTLIN("HostStore.kt (2k lines)", "kotlin"),
    KOTLIN_10K("10k lines of Kotlin", "kotlin"),
    MARKDOWN("the editor spec (Markdown)", "markdown"),
    TEN_MB("10 MB of Kotlin", "kotlin"),
}

object SampleFiles {
    /** A real Kotlin file of this repository (apps/shared/.../HostStore.kt, 2,231 lines). */
    suspend fun kotlin(): String = Res.readBytes("files/sample_kotlin.txt").decodeToString().replace("\r\n", "\n")

    /** The native editor's own design spec. */
    suspend fun markdown(): String = Res.readBytes("files/sample_markdown.txt").decodeToString().replace("\r\n", "\n")

    /**
     * [kotlin] grown to at least 10,000 lines, still ONE valid Kotlin file: its package and imports
     * once, then its declarations repeated whole (duplicate names parse fine). Pasting whole files
     * would put a `package` in the middle and cut the last copy mid-class; the error nodes that makes
     * slow tree-sitter's incremental reparse (measured on the Mac JVM: 23 ms per keystroke instead of 16).
     */
    fun tenK(kotlin: String): String = repeatBody(kotlin) { lines, _ -> lines >= 10_000 }

    /** [kotlin] grown the same way to at least 10,000,000 UTF-8 bytes. */
    fun tenMb(kotlin: String): String = repeatBody(kotlin) { _, chars -> chars >= 10_000_000 }

    private fun repeatBody(kotlin: String, enough: (lines: Int, chars: Int) -> Boolean): String {
        val lines = kotlin.trimEnd('\n').split('\n')
        val bodyStart = lines.indexOfLast { it.startsWith("import ") || it.startsWith("package ") } + 1
        val header = lines.subList(0, bodyStart).joinToString("\n")
        val body = lines.subList(bodyStart, lines.size).joinToString("\n")
        val bodyLines = lines.size - bodyStart
        return buildString {
            append(header)
            var n = bodyStart
            while (!enough(n, length)) { append('\n'); append(body); n += bodyLines }
            append('\n')
        }
    }

    suspend fun load(file: SampleFile, kotlin: String, markdown: String): String = when (file) {
        SampleFile.KOTLIN -> kotlin
        SampleFile.KOTLIN_10K -> tenK(kotlin)
        SampleFile.MARKDOWN -> markdown
        SampleFile.TEN_MB -> tenMb(kotlin)
    }
}

/**
 * One open document: an [EditorView] with the syntax extension, and the [SyntaxWorker] that colours
 * it. The worker hears about every transaction (a view listener) and the surface's viewport; its
 * results come back through [hop], which must run them on the UI thread in order.
 */
class SampleSession(
    text: String,
    val language: String?,
    backend: SyntaxBackend,
    registry: LanguageRegistry,
    scope: CoroutineScope,
    hop: (() -> Unit) -> Unit,
) : AutoCloseable {
    val view = EditorView(EditorState.create(text, extensions = Syntax.extension(language)))
    val worker = SyntaxWorker(backend, registry, scope, dispatch = { spec -> hop { view.dispatch(spec) } })
    private val removeListener = view.addListener { worker.onState(it.state) }

    init {
        worker.onState(view.state)
    }

    /** The surface's viewport, for the worker: dispatched as `Syntax.setViewport` when it changed. */
    fun onViewport(range: IntRange) {
        if (Syntax.snapshot(view.state)?.viewport == range) return
        view.dispatch(TransactionSpec(effects = listOf(Syntax.setViewport.of(range))))
    }

    override fun close() {
        removeListener()
        worker.close()
    }
}

/**
 * Compile [language]'s queries (and those of the languages its documents always inject) before
 * its first document is parsed. On the web a query compile is one uninterruptible call (26-116 ms
 * cold); doing each in its own task, ahead of the first paint, keeps it off the first parse.
 */
suspend fun precompileSyntax(
    backend: SyntaxBackend,
    registry: LanguageRegistry,
    language: String,
    onCompile: (String) -> Unit = {},
    yieldBetween: suspend () -> Unit,
) {
    val languages = listOf(language) + INJECTED[language].orEmpty()
    for (l in languages) {
        yieldBetween()
        backend.ensureLanguage(l)
        for (k in QueryKind.entries) {
            val source = registry.query(l, k) ?: continue
            yieldBetween()
            onCompile("$l/${k.file}")
            backend.sharedQuery(l, source)
        }
    }
}

private val INJECTED = mapOf("markdown" to listOf("markdown_inline"))

/** The editor for [session], reporting its viewport to the worker and its paints to [stats]. */
@Composable
fun SampleEditorPane(session: SampleSession, theme: EditorTheme, lineWrap: Boolean, stats: FrameStats? = null, modifier: Modifier = Modifier.fillMaxSize()) {
    Editor(
        view = session.view,
        modifier = modifier,
        theme = theme,
        lineWrap = lineWrap,
        onViewport = session::onViewport,
        onPaint = stats?.let { s -> { s.drawEnd() } },
    )
}
