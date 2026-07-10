package dev.supermux.android.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Space
import kotlinx.coroutines.delay

/**
 * Routes editing to a pre-warmed [EditorEngine] WebView, falling back to native text
 * only if the renderer never becomes ready.
 */
@Composable
fun WebCodeEditor(
    engine: EditorEngine,
    content: String,
    filename: String,
    fontSize: Int,
    scrollTop: Int = 0,
    revealLine: Pair<Int, Int?>? = null,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onRevealConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(content, filename, scrollTop) {
        engine.setDocument(content, filename, scrollTop)
    }
    // Reveal-line in its OWN effect: consuming it (revealLine→null) must NOT re-key the document
    // push above, or the trailing cmSetScrollTop would race/override cmRevealLine on a cold open.
    LaunchedEffect(revealLine) {
        revealLine?.let {
            engine.revealLine(it.first, it.second)
            onRevealConsumed()   // one-shot so returning to this tab restores scroll instead of re-jumping
        }
    }

    LaunchedEffect(engine) {
        if (engine.ready || engine.failed) return@LaunchedEffect
        delay(8_000)
        if (!engine.ready) engine.failed = true
    }

    if (engine.failed) {
        NativeCodeEditor(content = content, fontSize = fontSize, onChange = onChange, modifier = modifier)
        return
    }

    // Dark backing so the WebView doesn't flash white while it attaches / first-paints when the
    // editor pane opens.
    Box(modifier.background(Color(0xFF282C34))) {
        EditorWebViewHost(engine = engine, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun NativeCodeEditor(
    content: String,
    fontSize: Int,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF282C34)),
    ) {
        BasicTextField(
            value = content,
            onValueChange = onChange,
            textStyle = TextStyle(
                color = Color(0xFFABB2BF),
                fontFamily = MonoFontFamily,
                fontSize = fontSize.sp,
                lineHeight = (fontSize + 6).sp,
            ),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(Space.md),
        )
        Text(
            "Native editor (WebView unavailable)",
            color = cs.onSurfaceVariant,
            fontSize = 10.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(Space.sm),
        )
    }
}
