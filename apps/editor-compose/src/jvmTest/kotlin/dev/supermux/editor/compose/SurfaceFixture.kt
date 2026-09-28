package dev.supermux.editor.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.supermux.editor.core.EditorState
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertNotNull

internal const val EDITOR_TAG = "editor"

/** The soft keyboard, counted instead of raised (the desktop has none). */
internal class RecordingKeyboard : SoftwareKeyboardController {
    val shows = AtomicInteger()
    val hides = AtomicInteger()
    override fun show() { shows.incrementAndGet() }
    override fun hide() { hides.incrementAndGet() }
}

/** One live [Editor] in the desktop harness, plus what a test needs to poke at it. */
internal class SurfaceFixture(val view: EditorView, val keyboard: RecordingKeyboard, val clipboard: FakeClipboard) {
    val controller: EditorController get() = assertNotNull(view.surface as? EditorController, "the surface is not composed")
    val geometry: Geometry get() = controller.geometry
    var theme: EditorTheme? = null
}

@OptIn(ExperimentalTestApi::class)
internal fun editorTest(
    state: EditorState,
    widthDp: Int = 400,
    heightDp: Int = 300,
    lineWrap: Boolean = false,
    readOnly: Boolean = false,
    theme: ((EditorTheme) -> EditorTheme)? = null,
    onViewport: (IntRange) -> Unit = {},
    onPaint: (() -> Unit)? = null,
    platformMenu: Boolean = false,
    onFontSize: (Float) -> Unit = {},
    exposeField: Boolean = true,
    inputOnAnyFocus: Boolean = true,
    fieldPointerSpy: (() -> Unit)? = null,
    child: (@androidx.compose.runtime.Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null,
    toolbar: androidx.compose.ui.platform.TextToolbar? = null,
    widgets: WidgetRegistry? = null,
    showLineNumbers: Boolean = true,
    lightTheme: EditorTheme? = null,
    darkTheme: EditorTheme? = null,
    body: ComposeUiTest.(SurfaceFixture) -> Unit,
) = runComposeUiTest {
    // Measuring or scrolling inside a draw pass fails every surface test (M3c: measure before draw).
    DrawGuard.strict = true
    val view = EditorView(state)
    val keyboard = RecordingKeyboard()
    val clipboard = FakeClipboard()
    val fixture = SurfaceFixture(view, keyboard, clipboard)
    var wrap by mutableStateOf(lineWrap)
    setContent {
        CompositionLocalProvider(
            LocalSoftwareKeyboardController provides keyboard,
            // A blinking cursor would make every pixel test a coin toss.
            LocalEditorCursorBlink provides false,
            LocalEditorPlatformMenu provides platformMenu,
            LocalEditorExposeField provides exposeField,
            LocalEditorInputOnAnyFocus provides inputOnAnyFocus,
            LocalEditorFieldPointerSpy provides fieldPointerSpy,
            LocalEditorTestChild provides child,
            androidx.compose.ui.platform.LocalTextToolbar provides (toolbar ?: androidx.compose.ui.platform.LocalTextToolbar.current),
        ) {
            val base = EditorTheme.default()
            val t = theme?.invoke(base) ?: base
            fixture.theme = t
            Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                Editor(
                    view = view,
                    modifier = Modifier.fillMaxSize().testTag(EDITOR_TAG),
                    theme = t,
                    lineWrap = wrap,
                    readOnly = readOnly,
                    onViewport = onViewport,
                    onPaint = onPaint,
                    clipboard = clipboard,
                    onFontSize = onFontSize,
                    widgets = widgets ?: androidx.compose.runtime.remember { WidgetRegistry() },
                    showLineNumbers = showLineNumbers,
                    lightTheme = lightTheme,
                    darkTheme = darkTheme,
                )
            }
        }
    }
    waitForIdle()
    try { body(fixture) } finally { DrawGuard.strict = false }
}

/** The hidden input field (the surface's own text node has SetText too, and a content description). */
internal fun hasEditorField(): androidx.compose.ui.test.SemanticsMatcher =
    androidx.compose.ui.test.hasSetTextAction() and !androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)
