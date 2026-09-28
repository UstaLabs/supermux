package dev.supermux.editor.plugins.highlight

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.supermux.editor.compose.Editor
import dev.supermux.editor.compose.EditorTheme
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.panelsFacet
import dev.supermux.editor.plugins.basics.basics
import dev.supermux.editor.syntax.NativeBackend
import dev.supermux.editor.syntax.Syntax
import dev.supermux.editor.syntax.SyntaxDebug
import dev.supermux.editor.syntax.SyntaxLimits
import dev.supermux.editor.syntax.TokenClasses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** The syntax host over the REAL worker and native binding: a serial dispatcher stands in for the UI thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyntaxHostTest {
    private val backend = NativeBackend()
    private val ui = Dispatchers.Default.limitedParallelism(1)

    private val kotlinText = "package a\n\nfun main() {\n    val s = \"(\"\n    println(s)\n}\n"

    private fun classes(state: EditorState): Set<String> =
        state.facet(decorationsFacet).flatMap { set -> set.mapNotNull { (it.value as? Decoration.Mark)?.classes } }.flatten().toSet()

    /** Poll on the UI dispatcher until [cond] holds (10 s). */
    private suspend fun until(what: String, cond: () -> Boolean) {
        repeat(1000) {
            if (withContext(ui) { cond() }) return
            delay(10)
        }
        fail("timed out waiting for $what")
    }

    private fun run(block: suspend CoroutineScope.(CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + ui)
        try { block(scope) } finally { scope.cancel() }
    }

    @Test fun openTypeAndSpansArrive() = run { scope ->
        val view = withContext(ui) { EditorView(EditorState.create(kotlinText, extensions = highlight("kotlin"))) }
        val host = withContext(ui) { SyntaxHost(view, backend, scope = scope).also { it.start() } }
        until("the first colours") { TokenClasses.KEYWORD in classes(view.state) }
        // Type a keyword at the start: the spans map at once, then the worker colours it.
        withContext(ui) { view.dispatch(dev.supermux.editor.core.TransactionSpec(selection = EditorSelection.cursor(0))); view.typeText("import b.c\n") }
        until("the typed line's colours") {
            view.state.facet(decorationsFacet).any { set -> set.any { it.from == 0 && (it.value as? Decoration.Mark)?.classes?.contains(TokenClasses.KEYWORD) == true } }
        }
        withContext(ui) { host.close() }
        host.join()
    }

    @Test fun closingTheHostFreesEveryNativeTree() = run { scope ->
        val before = SyntaxDebug.liveTrees()
        val view = withContext(ui) { EditorView(EditorState.create(kotlinText, extensions = highlight("kotlin"))) }
        val host = withContext(ui) { SyntaxHost(view, backend, scope = scope).also { it.start() } }
        until("the first colours") { TokenClasses.KEYWORD in classes(view.state) }
        assertTrue(SyntaxDebug.liveTrees() > before, "the worker holds no tree")
        withContext(ui) { host.close() }
        host.join()
        assertEquals(before, SyntaxDebug.liveTrees(), "native trees leaked after close")
        // Nothing more reaches the view after close.
        withContext(ui) { view.typeText("x") }
        delay(200)
    }

    @Test fun aDocumentSwitchResetsTheWorker() = run { scope ->
        val view = withContext(ui) { EditorView(EditorState.create(kotlinText, extensions = highlight("kotlin"))) }
        val host = withContext(ui) { SyntaxHost(view, backend, scope = scope).also { it.start() } }
        until("kotlin colours") { TokenClasses.KEYWORD in classes(view.state) }
        // The host shows another file in the same view: Markdown, a new state (version 0 again).
        val md = "# Title\n\nSome *text* here.\n"
        withContext(ui) { view.setState(EditorState.create(md, extensions = highlight("markdown"))) }
        until("markdown colours") { TokenClasses.MARKUP_HEADING in classes(view.state) }
        assertTrue(TokenClasses.KEYWORD !in classes(view.state), "the kotlin spans leaked into the markdown document")
        withContext(ui) { host.close() }
        host.join()
    }

    @Test fun aDocumentTooBigForSyntaxShowsTheSyntaxOffPanel() = run { scope ->
        val view = withContext(ui) { EditorView(EditorState.create(kotlinText.repeat(50), extensions = highlight("kotlin"))) }
        val host = withContext(ui) { SyntaxHost(view, backend, scope = scope, limits = SyntaxLimits(maxDocumentLength = 100)).also { it.start() } }
        until("syntax off") { Syntax.isOff(view.state) }
        until("the panel") { view.state.facet(panelsFacet).any { it.id == SyntaxHost.OFF_PANEL } }
        assertTrue(host.isOff)
        withContext(ui) { host.close() }
        host.join()
    }

    @Test fun bracketMatchingIgnoresBracketsInStringsWithRealSyntax() = run { scope ->
        val text = "fun f() = g(\"(\", x)\n"
        val end = text.indexOf(")\n") + 1
        val view = withContext(ui) { EditorView(EditorState.create(text, EditorSelection.cursor(end), extensionOf(highlight("kotlin"), basics()))) }
        val host = withContext(ui) { SyntaxHost(view, backend, scope = scope).also { it.start() } }
        until("colours") { TokenClasses.STRING in classes(view.state) }
        val open = text.indexOf("g(") + 1
        until("the code parenthesis matched") {
            view.state.facet(decorationsFacet).any { set -> set.any { it.from == open && (it.value as? Decoration.Mark)?.classes?.contains("matching-bracket") == true } }
        }
        withContext(ui) { host.close() }
        host.join()
    }

    @Test fun foldsFromFoldsScmAreLineBasedAndKeepTheClosingBraceVisible() = run { scope ->
        val view = withContext(ui) { EditorView(EditorState.create(kotlinText, extensions = highlight("kotlin"))) }
        val host = withContext(ui) { SyntaxHost(view, backend, scope = scope).also { it.start() } }
        until("folds") { Syntax.folds(view.state).isNotEmpty() }
        val st = withContext(ui) { view.state }
        val lineFrom = kotlinText.indexOf("fun main")
        val lineTo = kotlinText.indexOf('\n', lineFrom)
        val service = st.facet(dev.supermux.editor.core.foldServiceFacet).single()
        // fun main() {⋯}: from the line's end to the "}" (the function_body node).
        assertEquals(dev.supermux.editor.core.FoldRange(lineTo, kotlinText.lastIndexOf('}')), service.foldable(st, lineFrom, lineTo))
        // A line inside with no block of its own has none.
        val inner = kotlinText.indexOf("println")
        assertEquals(null, service.foldable(st, inner, kotlinText.indexOf('\n', inner)))
        withContext(ui) { host.close() }
        host.join()
    }

    @Test fun everyTokenClassTheSyntaxLayerEmitsIsColouredInBothThemes() = run { scope ->
        val samples = mapOf(
            "kotlin" to kotlinText + "class A<T>(val x: Int = 0x1F) { /* c */ @Deprecated(\"d\") fun f() = listOf(1.5, true, null, \"\\n\") }\n",
            "markdown" to "# H\n\n*em* **strong** [link](http://x) `code`\n\n```kotlin\nval x = 1\n```\n",
            "json" to "{\"a\": [1, 2.5, true, null, \"s\"]}\n",
            "python" to "import os\n@dec\ndef f(a, b=1):\n    \"\"\"doc\"\"\"\n    return a + b  # c\n",
            "html" to "<!DOCTYPE html><html lang=\"en\"><body class=\"x\"><script>let a = 1</script><style>p { color: red }</style></body></html>\n",
            "rust" to "use std::io;\n#[derive(Debug)]\nstruct S<'a> { x: &'a str }\nfn main() { let v: Vec<i32> = vec![1]; println!(\"{}\", v[0]); }\n",
            "go" to "package main\nimport \"fmt\"\nfunc main() { var x int = 1; fmt.Println(x) } // c\n",
            "typescript" to "interface I { a: number }\nconst f = (x: I): string => `\${x.a}` as string;\nexport default /re+/g;\n",
        )
        val emitted = HashSet<String>()
        for ((lang, text) in samples) {
            val view = withContext(ui) { EditorView(EditorState.create(text, extensions = highlight(lang))) }
            val host = withContext(ui) { SyntaxHost(view, backend, scope = scope).also { it.start() } }
            until("$lang colours") { classes(view.state).isNotEmpty() }
            emitted += classes(view.state)
            withContext(ui) { host.close() }
            host.join()
        }
        assertTrue(emitted.size > 10, "too few token classes emitted: $emitted")
        for (theme in listOf(EditorTheme.light(FontFamily.Monospace), EditorTheme.dark(FontFamily.Monospace))) {
            for (cls in emitted) assertTrue(theme.styleOf(cls) != null, "a syntax class without a colour: $cls")
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun rememberSyntaxHostFeedsTheViewportAndDisposesWithTheEditor() = runComposeUiTest {
        val text = (0 until 3000).joinToString("\n") { "val x$it = $it" }
        val view = EditorView(EditorState.create(text, extensions = highlight("kotlin")))
        var shown by androidx.compose.runtime.mutableStateOf(true)
        setContent {
            if (shown) Box(Modifier.size(400.dp, 300.dp)) {
                rememberSyntaxHost(view, backend)
                Editor(view, Modifier.fillMaxSize())
            }
        }
        waitUntil(timeoutMillis = 10_000) { TokenClasses.NUMBER in classes(view.state) }
        val vp = Syntax.snapshot(view.state)!!.viewport
        assertTrue(!vp.isEmpty() && vp.last < text.length / 2, "the worker did not get the surface's viewport: $vp")
        val before = SyntaxDebug.liveTrees()
        assertTrue(before > 0)
        shown = false
        waitForIdle()
        waitUntil(timeoutMillis = 10_000) { SyntaxDebug.liveTrees() < before }
    }
}
