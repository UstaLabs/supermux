package dev.supermux.ui.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.proto.SlashCommand
import dev.supermux.ui.files.FileBadge
import dev.supermux.ui.files.FileBadgeBox
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.mux_logo
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.session.hasAgentLogo
import dev.supermux.ui.theme.MonoFontFamily
import org.jetbrains.compose.resources.painterResource

// The `/` menu (card #56, option B): the list on the left and, where there is room, the highlighted
// command in full on the right — its argument syntax, aliases, where it comes from and its whole
// description, which is a paragraph for most skills. Narrower than [SlashMenuDetailMinWidth] it is the
// list alone, each row with its description's first clause. The `@` menu draws the same rows
// ([ComposerMenuRow]) in the same card.

/** One row of either composer menu. Fixed, so the card's height is known before it is drawn. */
internal val MenuRowHeight = 32.dp
internal val MenuHeaderHeight = 26.dp
internal val MenuFooterHeight = 28.dp
private val MenuListPadding = 4.dp

/** The card shows the detail side from this width up. */
val SlashMenuDetailMinWidth = 560.dp

/** The tallest the card gets; the shortest when it has a detail side to fill. */
val SlashMenuMaxHeight = 348.dp
private val SlashMenuMinDetailHeight = 196.dp

/** The neutral badge tone the file badges use, for sources that have no brand mark. */
private val NeutralBadge = Color(0xFF8B8B85)

/** The card's height for [model]: its rows, headers and footer, clamped to [SlashMenuMaxHeight]. */
fun slashMenuHeight(model: SlashMenuModel, detail: Boolean): Dp {
    val headers = model.sections.count { it.title != null && it.rows.isNotEmpty() }
    val list = MenuListPadding * 2 + MenuHeaderHeight * headers + MenuRowHeight * model.rows.size + MenuFooterHeight
    val floor = if (detail) SlashMenuMinDetailHeight else 0.dp
    return list.coerceIn(floor, SlashMenuMaxHeight)
}

/**
 * The `/` menu's content, stateless: the composer owns the model, the highlight and the keys.
 * [onHighlight] moves the highlight (the pointer resting on a row, so the detail follows it);
 * [onPick] applies a row. [insertOnly] is the launcher, whose commands only ever insert text.
 */
@Composable
fun SlashMenu(
    model: SlashMenuModel,
    selectedIndex: Int,
    onHighlight: (Int) -> Unit,
    onPick: (SlashCommand) -> Unit,
    agent: String?,
    detail: Boolean,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "slash_item_",
    insertOnly: Boolean = false,
    agentCommandCount: Int = 0,
    controlCount: Int = 0,
) {
    val agentLabel = agentLabelOf(agent)
    val selected = model.rows.getOrNull(selectedIndex)
    val runs = !insertOnly && selected?.command?.action != null
    val count = if (model.query.isEmpty()) {
        listOfNotNull(
            "$agentCommandCount $agentLabel".takeIf { agentCommandCount > 0 },
            "$controlCount supermux".takeIf { controlCount > 0 },
        ).joinToString(" · ")
    } else {
        "${model.rows.size} of ${agentCommandCount + controlCount}"
    }
    Row(modifier) {
        Column(Modifier.then(if (detail) Modifier.fillMaxWidth(0.55f) else Modifier.fillMaxWidth()).fillMaxHeight()) {
            SlashList(
                model = model,
                selectedIndex = selectedIndex,
                onHighlight = onHighlight.takeIf { detail },
                onPick = onPick,
                agent = agent,
                withDescriptions = !detail,
                testTagPrefix = testTagPrefix,
                insertOnly = insertOnly,
                modifier = Modifier.weight(1f),
            )
            MenuFooter(
                left = count,
                keys = if (detail) listOf("↑↓" to "") else listOf("↑↓" to "move", "↵" to if (runs) "run" else "insert", "esc" to "close"),
            )
        }
        if (detail) {
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            SlashDetail(
                row = selected,
                agent = agent,
                agentLabel = agentLabel,
                runs = runs,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
    }
}

/** The list: section headers and rows, scrolled so the highlight is always in view. */
@Composable
private fun SlashList(
    model: SlashMenuModel,
    selectedIndex: Int,
    onHighlight: ((Int) -> Unit)?,
    onPick: (SlashCommand) -> Unit,
    agent: String?,
    withDescriptions: Boolean,
    testTagPrefix: String,
    insertOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    // Headers and rows as one list; a command can sit in Recent and in its own section, so keys
    // carry the section.
    val items = remember(model) {
        buildList {
            var row = 0
            for (s in model.sections) {
                if (s.rows.isEmpty()) continue
                if (s.title != null) add(MenuItem.Header("h:${s.key}", s.title, s.rows.size.takeIf { s.showCount }))
                for (r in s.rows) add(MenuItem.Command("r:${s.key}:${r.command.id}", row++, r))
            }
        }
    }
    val list = rememberLazyListState()
    val headerPx = with(LocalDensity.current) { MenuHeaderHeight.roundToPx() }
    LaunchedEffect(selectedIndex, items) {
        val target = items.indexOfFirst { it is MenuItem.Command && it.index == selectedIndex }
        if (target < 0) return@LaunchedEffect
        val headerAbove = items.getOrNull(target - 1) is MenuItem.Header
        val info = list.layoutInfo
        val seen = info.visibleItemsInfo.firstOrNull { it.index == target }
        if (seen == null) {
            list.scrollToItem(if (headerAbove) target - 1 else target)
            return@LaunchedEffect
        }
        val top = seen.offset - if (headerAbove) headerPx else 0
        val bottom = seen.offset + seen.size
        when {
            top < info.viewportStartOffset -> list.scrollBy((top - info.viewportStartOffset).toFloat())
            bottom > info.viewportEndOffset -> list.scrollBy((bottom - info.viewportEndOffset).toFloat())
        }
    }
    LazyColumn(modifier.fillMaxWidth(), state = list, contentPadding = PaddingValues(vertical = MenuListPadding)) {
        items(items, key = { it.key }) { item ->
            when (item) {
                is MenuItem.Header -> MenuHeader(item.title, item.count)
                is MenuItem.Command -> SlashRowView(
                    row = item.row,
                    selected = item.index == selectedIndex,
                    onHover = onHighlight?.let { h -> { h(item.index) } },
                    onClick = { onPick(item.row.command) },
                    agent = agent,
                    withDescription = withDescriptions,
                    tag = "$testTagPrefix${item.row.command.name}",
                    insertOnly = insertOnly,
                )
            }
        }
    }
}

private sealed interface MenuItem {
    val key: String

    data class Header(override val key: String, val title: String, val count: Int?) : MenuItem
    data class Command(override val key: String, val index: Int, val row: SlashRow) : MenuItem
}

/**
 * One command: its source mark, the name (a plugin's prefix dimmed, the matched letters bold), the
 * agent's argument hint, the alias that matched and, without a detail side, the description.
 */
@Composable
private fun SlashRowView(
    row: SlashRow,
    selected: Boolean,
    onHover: (() -> Unit)?,
    onClick: () -> Unit,
    agent: String?,
    withDescription: Boolean,
    tag: String,
    insertOnly: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val cmd = row.command
    ComposerMenuRow(selected = selected, tag = tag, onClick = onClick, onHover = onHover) {
        SlashSourceMark(cmd, agent)
        Text(
            text = slashName(cmd, row.nameHits),
            fontFamily = MonoFontFamily,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        cmd.argumentHint?.takeIf { it.isNotBlank() }?.let { hint ->
            Text(
                text = hint,
                color = cs.onSurfaceVariant.copy(alpha = 0.75f),
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (withDescription) Modifier.widthIn(max = 180.dp) else Modifier.weight(1f, fill = false),
            )
        }
        row.alias?.let { alias ->
            Text(
                text = buildAnnotatedString {
                    append("· ${cmd.sigil}")
                    append(bolded(alias, row.aliasHits, cs.onSurface))
                },
                color = cs.onSurfaceVariant,
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
                maxLines = 1,
            )
        }
        if (withDescription) {
            Text(
                text = rowDescription(cmd, row.descriptionHits, cs.onSurface),
                color = cs.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Supermux's own commands run on pick; the source of the highlighted one is named.
            val tail = when {
                cmd.action != null && !insertOnly -> "runs"
                selected -> sourceLabel(cmd)
                else -> null
            }
            tail?.let {
                Text(it, color = cs.onSurfaceVariant.copy(alpha = 0.8f), fontSize = 11.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** The highlighted command in full. */
@Composable
private fun SlashDetail(
    row: SlashRow?,
    agent: String?,
    agentLabel: String,
    runs: Boolean,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp),
        ) {
            if (row != null) {
                val cmd = row.command
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SlashSourceMark(cmd, agent)
                    Text(slashName(cmd, emptyList()), fontFamily = MonoFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
                cmd.argumentHint?.takeIf { it.isNotBlank() }?.let { hint ->
                    Text(
                        "${cmd.sigil}${cmd.name} $hint",
                        color = cs.onSurfaceVariant,
                        fontFamily = MonoFontFamily,
                        fontSize = 12.5.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                sourceLong(cmd, agentLabel)?.let {
                    Text(it, color = cs.onSurfaceVariant, fontSize = 11.5.sp, modifier = Modifier.padding(top = 10.dp))
                }
                cmd.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        color = cs.onSurface.copy(alpha = 0.86f),
                        fontSize = 12.5.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                val also = cmd.aliases.filter { it != cmd.name }
                if (also.isNotEmpty()) {
                    Text(
                        buildAnnotatedString {
                            append("Also ")
                            also.forEachIndexed { i, a ->
                                if (i > 0) append("  ")
                                withStyle(SpanStyle(color = cs.onSurface, fontFamily = MonoFontFamily)) { append(cmd.sigil + a) }
                            }
                        },
                        color = cs.onSurfaceVariant,
                        fontSize = 11.5.sp,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
        MenuFooter(left = "", keys = listOf("↵" to if (runs) "run now" else "insert", "esc" to "close"))
    }
}

/**
 * A row of either composer menu: the highlight, the shared paddings, and a click that leaves focus
 * (and with it the caret) in the field. [onHover] moves the highlight to it when the pointer rests on it.
 */
@Composable
internal fun ComposerMenuRow(
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    onHover: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    if (onHover != null) LaunchedEffect(hovered) { if (hovered) onHover() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MenuRowHeight)
            .testTag(tag)
            .background(if (selected) cs.surfaceContainerHighest else Color.Transparent)
            .hoverable(hover)
            .focusProperties { canFocus = false }
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun MenuHeader(title: String, count: Int?) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(MenuHeaderHeight).padding(start = 14.dp, end = 10.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title.uppercase(), color = cs.onSurfaceVariant, fontSize = 10.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp, maxLines = 1)
        count?.let { Text("$it", color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 10.5.sp, maxLines = 1) }
    }
}

/** The card's bottom line: a count on the left, the keys that matter on the right. */
@Composable
internal fun MenuFooter(left: String, keys: List<Pair<String, String>>) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().height(MenuFooterHeight)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(cs.outlineVariant))
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(left, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            for ((key, what) in keys) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        key,
                        color = cs.onSurfaceVariant,
                        fontFamily = MonoFontFamily,
                        fontSize = 10.5.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .background(cs.onSurface.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp),
                    )
                    if (what.isNotEmpty()) Text(what, color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Where [cmd] comes from, as a 16dp mark: supermux's own logo, the agent's for its built-ins, a
 * lettered badge for a plugin, `./` for the project's, `~` for the user's. Nothing when nothing says.
 */
@Composable
internal fun SlashSourceMark(cmd: SlashCommand, agent: String?) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when {
            cmd.action != null -> Image(
                painter = painterResource(Res.drawable.mux_logo),
                contentDescription = null,
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                modifier = Modifier.size(14.dp),
            )
            cmd.source == SlashCommand.SOURCE_BUILTIN && hasAgentLogo(agent) -> AgentLogo(agent, 13.dp)
            cmd.source == SlashCommand.SOURCE_PLUGIN && !cmd.plugin.isNullOrBlank() ->
                FileBadgeBox(FileBadge(cmd.plugin!!.first().uppercase(), NeutralBadge), alpha = 1f)
            cmd.source == SlashCommand.SOURCE_PROJECT -> FileBadgeBox(FileBadge("./", NeutralBadge), alpha = 1f)
            cmd.source == SlashCommand.SOURCE_USER -> FileBadgeBox(FileBadge("~", NeutralBadge), alpha = 1f)
        }
    }
}

/** The name as typed: sigil, a plugin prefix dimmed, the matched letters bold. */
@Composable
private fun slashName(cmd: SlashCommand, hits: List<Int>): AnnotatedString {
    val cs = MaterialTheme.colorScheme
    val dim = cs.onSurfaceVariant.copy(alpha = 0.8f)
    val prefix = namePrefix(cmd.name).length
    val hit = hits.toSet()
    return buildAnnotatedString {
        withStyle(SpanStyle(color = if (prefix > 0) dim else cs.onSurface)) { append(cmd.sigil) }
        var i = 0
        while (i < cmd.name.length) {
            val inPrefix = i < prefix
            val isHit = i in hit
            var j = i
            while (j < cmd.name.length && (j < prefix) == inPrefix && (j in hit) == isHit) j++
            val style = SpanStyle(
                color = if (inPrefix && !isHit) dim else cs.onSurface,
                fontWeight = if (isHit) FontWeight.Bold else null,
            )
            withStyle(style) { append(cmd.name.substring(i, j)) }
            i = j
        }
    }
}

/** [text] with the characters at [hits] bold in [color]. */
private fun bolded(text: String, hits: List<Int>, color: Color): AnnotatedString = buildAnnotatedString {
    val hit = hits.toSet()
    text.forEachIndexed { i, c ->
        if (i in hit) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(c) } else append(c)
    }
}

/**
 * A row's one line of description: up to the end of its first clause, or — when the description is
 * what matched — the words around the match, so the bold letters are in view.
 */
private fun rowDescription(cmd: SlashCommand, hits: List<Int>?, color: Color): AnnotatedString {
    val desc = cmd.description.orEmpty().replace(Regex("\\s+"), " ").trim()
    if (hits.isNullOrEmpty()) return AnnotatedString(firstClause(desc))
    val at = hits.first()
    if (at < 40) return bolded(desc, hits, color)
    val from = desc.lastIndexOf(' ', at - 20).let { if (it < 0) at - 20 else it + 1 }.coerceAtLeast(0)
    return buildAnnotatedString {
        append("…")
        append(bolded(desc.substring(from), hits.map { it - from }, color))
    }
}

/** Up to the first sentence or dash break past the opening words; the rest is the detail's job. */
internal fun firstClause(desc: String): String {
    val cut = Regex("""(\. |\s[—–]\s|\s-\s)""").find(desc, startIndex = minOf(16, desc.length))
    return if (cut != null) desc.substring(0, cut.range.first) else desc.removeSuffix(".")
}

/** A short source label for the highlighted row of the narrow list. */
private fun sourceLabel(cmd: SlashCommand): String? = when (cmd.source) {
    SlashCommand.SOURCE_BUILTIN -> "built-in"
    SlashCommand.SOURCE_PLUGIN -> cmd.plugin
    SlashCommand.SOURCE_PROJECT -> "this project"
    SlashCommand.SOURCE_USER -> "your skill"
    else -> null
}

/** The detail's source line. */
private fun sourceLong(cmd: SlashCommand, agentLabel: String): String? = when {
    cmd.action != null -> "supermux · runs now"
    cmd.source == SlashCommand.SOURCE_BUILTIN -> "$agentLabel built-in"
    cmd.source == SlashCommand.SOURCE_PLUGIN && !cmd.plugin.isNullOrBlank() -> "${cmd.plugin} plugin"
    cmd.source == SlashCommand.SOURCE_PROJECT -> "This project"
    cmd.source == SlashCommand.SOURCE_USER -> "Your skill"
    else -> null
}

/** "Claude", "Codex", … for the session's agent; "Agent" when there is none. */
fun agentLabelOf(agent: String?): String =
    agent?.lowercase()?.let { dev.supermux.ui.accounts.agentDisplayName(it) } ?: "Agent"
