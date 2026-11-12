// #123: a sentence with a name in it, for the callbacks that show it (a notice after a click, a LaunchedEffect's toast).
package dev.supermux.ui.shell

import androidx.compose.runtime.Composable
import dev.supermux.ui.i18n.localized
import org.jetbrains.compose.resources.StringResource

private const val SLOT = "\u0000"

/**
 * [resource] (one `%1$s`) in the app's language, looked up here in composable code and filled in later, where only
 * plain code runs: `val saved = localizedTemplate(Res.string.x)` … `onClick = { notices.show(saved(name)) }`.
 */
@Composable
internal fun localizedTemplate(resource: StringResource): (String) -> String {
    val text = localized(resource, SLOT)
    return { arg -> text.replace(SLOT, arg) }
}
