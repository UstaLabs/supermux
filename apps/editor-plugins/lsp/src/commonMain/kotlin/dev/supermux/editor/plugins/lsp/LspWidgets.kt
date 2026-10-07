package dev.supermux.editor.plugins.lsp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.selectAll
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.editor.compose.Hover
import dev.supermux.editor.compose.WidgetRegistry
import dev.supermux.editor.compose.WidgetScope
import dev.supermux.editor.compose.codeTextInput
import dev.supermux.editor.compose.isTouchFirstPlatform

/** Test tags of the LSP tooltips and panels. */
object LspTags {
    const val HOVER = "lsp-hover"
    const val HOVER_CLOSE = "lsp-hover-close"
    const val SIGNATURE = "lsp-signature"
    const val REFERENCES = "lsp-references"
    const val RENAME_FIELD = "lsp-rename-field"
    const val RENAME_NOTE = "lsp-rename-note"
    fun reference(i: Int) = "lsp-reference-$i"
}

/** Register the hover and signature tooltips and the references and rename panels for [client]'s documents. */
fun LspClient.registerWidgets(registry: WidgetRegistry) {
    val client = this
    registry.register(LspPlugin.HOVER_TOOLTIP) { k -> HoverContent(this, client, k.id) }
    registry.register(LspPlugin.SIGNATURE_TOOLTIP) { SignatureContent(this) }
    registry.register("panel:" + LspPlugin.REFERENCES_PANEL) { ReferencesPanel(this, client) }
    registry.register("panel:" + LspPlugin.RENAME_PANEL) { RenamePanel(this, client) }
}

private fun Modifier.press(label: String, onPress: () -> Unit): Modifier =
    pointerInput(label) { detectTapGestures { onPress() } }.semantics { role = Role.Button; contentDescription = label; onClick { onPress(); true } }

private val shape = RoundedCornerShape(6.dp)

@Composable
private fun Box(scope: WidgetScope, tag: String, content: @Composable () -> Unit) {
    val theme = scope.theme
    androidx.compose.foundation.layout.Box(
        Modifier.widthIn(max = 520.dp).heightIn(max = 260.dp).shadow(6.dp, shape).clip(shape).background(theme.background)
            .border(1.dp, theme.gutterForeground.copy(alpha = 0.35f), shape).padding(horizontal = 10.dp, vertical = 6.dp).testTag(tag),
    ) { content() }
}

@Composable
private fun HoverContent(scope: WidgetScope, client: LspClient, id: String) {
    val text = client.hoverTexts[id] ?: return
    val theme = scope.theme
    val style = TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = (theme.fontSizeSp - 0.5f).sp)
    Box(scope, LspTags.HOVER) {
        Row(verticalAlignment = Alignment.Top) {
            BasicText(
                text,
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
                style = style,
            )
            // A close affordance: touch has no pointer that leaves (a tap elsewhere closes it too).
            androidx.compose.foundation.layout.Box(
                Modifier.size(if (isTouchFirstPlatform) 40.dp else 20.dp).press("Close") { Hover.closeHover.run(scope.editor) }.testTag(LspTags.HOVER_CLOSE),
                contentAlignment = Alignment.Center,
            ) { BasicText("×", style = style.copy(color = theme.gutterForeground)) }
        }
    }
}

/** The signature above the call: the active parameter bold and underlined, `1/3` when several, its docs. */
@Composable
private fun SignatureContent(scope: WidgetScope) {
    val editor = scope.editor
    val theme = scope.theme
    val data by remember(editor) { derivedStateOf { LspPlugin.state(editor.state).signature } }
    val d = data ?: return
    val sig = d.signatures.getOrNull(d.active) ?: return
    val param = sig.parameters.getOrNull(sig.activeParameter ?: d.activeParameter)
    val style = TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = (theme.fontSizeSp - 0.5f).sp)
    Box(scope, LspTags.SIGNATURE) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (d.signatures.size > 1) {
                    BasicText("${d.active + 1}/${d.signatures.size}", style = style.copy(color = theme.gutterForeground))
                    Spacer(Modifier.width(6.dp))
                }
                BasicText(buildAnnotatedString {
                    append(sig.label)
                    if (param != null && !param.isEmpty() && param.last < sig.label.length) {
                        addStyle(SpanStyle(fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline, color = theme.cursor), param.first, param.last + 1)
                    }
                }, style = style)
            }
            sig.documentation?.takeIf { it.isNotBlank() }?.let {
                BasicText(it, style = style.copy(color = theme.gutterForeground, fontSize = (theme.fontSizeSp - 1f).sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Find references' results: `line: text`, a tap or Enter goes there (another document: `onNavigate`). */
@Composable
private fun ReferencesPanel(scope: WidgetScope, client: LspClient) {
    val editor = scope.editor
    val theme = scope.theme
    val s by remember(editor) { derivedStateOf { LspPlugin.state(editor.state) } }
    val refs = s.references ?: return
    val style = TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = (theme.fontSizeSp - 0.5f).sp)
    val dim = style.copy(color = theme.gutterForeground)
    val row = if (isTouchFirstPlatform) 44.dp else 24.dp
    Column(Modifier.fillMaxWidth().background(theme.gutterBackground).testTag(LspTags.REFERENCES)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("References (${refs.size})", style = dim, modifier = Modifier.weight(1f))
            androidx.compose.foundation.layout.Box(Modifier.size(row).press("Close references") { LspPlugin.closeReferencePanel.run(editor); scope.focusEditor() }, contentAlignment = Alignment.Center) { BasicText("×", style = style) }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = row * 7)) {
            itemsIndexed(refs) { i, r ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = row)
                        .background(if (i == s.selectedReference) theme.selection else Color.Transparent)
                        .press("Reference ${i + 1}") { LspPlugin.goToReference(client, editor, i); scope.focusEditor() }
                        .testTag(LspTags.reference(i)).padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val where = if (r.from == null) r.location.uri.substringAfterLast('/') + ":" else ""
                    BasicText("$where${r.line + 1}", style = dim)
                    Spacer(Modifier.width(8.dp))
                    BasicText(r.text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** CM6's rename dialog: "New name", the word selected; Enter renames, Escape closes. */
@Composable
private fun RenamePanel(scope: WidgetScope, client: LspClient) {
    val editor = scope.editor
    val theme = scope.theme
    val prompt by remember(editor) { derivedStateOf { LspPlugin.state(editor.state).rename } }
    val p = prompt ?: return
    val field = rememberTextFieldState(p.word)
    val focus = remember { FocusRequester() }
    LaunchedEffect(p.focus) {
        field.edit { replace(0, length, p.word); selectAll() }
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
    fun submit() {
        LspPlugin.submitRename(client, editor, field.text.toString())
    }
    // The prompt goes (renamed, cancelled): the focus goes back to the editor.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { scope.focusEditor() } }
    fun cancel() { LspPlugin.closeRename.run(editor); scope.focusEditor() }
    val style = TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = theme.fontSizeSp.sp)
    val target = if (isTouchFirstPlatform) 44.dp else 28.dp
    Column(Modifier.fillMaxWidth().background(theme.gutterBackground)) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText("New name", style = style.copy(color = theme.gutterForeground))
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            field,
            Modifier.weight(1f).heightIn(min = target).clip(RoundedCornerShape(6.dp)).background(theme.foreground.copy(alpha = 0.07f))
                .padding(horizontal = 6.dp, vertical = 4.dp).focusRequester(focus).codeTextInput().testTag(LspTags.RENAME_FIELD)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) { Key.Enter, Key.NumPadEnter -> { submit(); true }; Key.Escape -> { cancel(); true }; else -> false }
                },
            textStyle = style,
            cursorBrush = SolidColor(theme.cursor),
            lineLimits = TextFieldLineLimits.SingleLine,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Done),
            onKeyboardAction = { submit() },
        )
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.layout.Box(Modifier.heightIn(min = target).clip(RoundedCornerShape(6.dp)).background(theme.selection).press("Rename") { submit() }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { BasicText("Rename", style = style) }
        androidx.compose.foundation.layout.Box(Modifier.size(target).press("Cancel rename") { cancel() }, contentAlignment = Alignment.Center) { BasicText("×", style = style) }
    }
    val note = p.error ?: if (p.pending) "Renaming…" else null
    if (note != null) BasicText(
        note,
        Modifier.padding(start = 8.dp, bottom = 4.dp).testTag(LspTags.RENAME_NOTE).semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
        style = style.copy(color = if (p.error != null) Color(0xFFE06C75) else theme.gutterForeground, fontSize = (theme.fontSizeSp - 1).sp),
    )
    }
}
