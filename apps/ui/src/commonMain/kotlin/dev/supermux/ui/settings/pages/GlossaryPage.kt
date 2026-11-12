// Voice ▸ Words to spell your way (#97 spec §2.2, §10.1): the machine's dictation glossary as a list — each word with
// Remove, and "Add a word", which opens a field (Enter adds). Every change saves the whole list; a failed save shows
// the list the machine still has. A failed load is never an empty list: adding to it would write one word over the
// real glossary, so the list cannot be changed until it loads.
package dev.supermux.ui.settings.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.retain.rememberRetainedOnPage
import dev.supermux.ui.settings.GLOSSARY_SUB
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.kit.KitGhostButton
import dev.supermux.ui.settings.kit.KitItem
import dev.supermux.ui.settings.kit.KitItemList
import dev.supermux.ui.settings.kit.KitMachineScope
import dev.supermux.ui.settings.kit.KitMark
import dev.supermux.ui.settings.kit.KitNote
import dev.supermux.ui.settings.kit.KitOfflineNote
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitTextAction
import dev.supermux.ui.settings.kit.KitTextField
import dev.supermux.ui.settings.kit.currentMachineOnline
import dev.supermux.ui.settings.kit.kitColors
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.settings_glossary_add
import dev.supermux.ui.resources.settings_glossary_add_word
import dev.supermux.ui.resources.settings_glossary_back
import dev.supermux.ui.resources.settings_glossary_empty
import dev.supermux.ui.resources.settings_glossary_placeholder
import dev.supermux.ui.resources.settings_glossary_remove
import dev.supermux.ui.resources.settings_glossary_save_failed
import dev.supermux.ui.resources.settings_pages_loading
import dev.supermux.ui.resources.settings_pages_try_again
import dev.supermux.ui.resources.settings_voice_glossary_description
import dev.supermux.ui.settings.localizedSettingsTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Load model for the word list — failure is distinct from a legitimate empty list. */
internal sealed class GlossaryLoadState {
    data object Loading : GlossaryLoadState()
    data object Empty : GlossaryLoadState()
    data class Ready(val terms: List<String>) : GlossaryLoadState()
    data object Error : GlossaryLoadState()
}

/** The word list. [onBack] returns to Voice (the hub's `onClose` on a sub-page); wide shows it as "‹ Voice". */
@Composable
fun GlossaryPage(
    actions: VoiceSettingsActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
) {
    KitPage(
        localizedSettingsTitle(SettingsPlace(SettingsSection.Voice, GLOSSARY_SUB)),
        modifier,
        scope = { KitMachineScope() },
        lede = localized(Res.string.settings_voice_glossary_description),
        backLabel = localized(Res.string.settings_glossary_back),
        onBack = onBack,
        tag = "voice_glossary_page",
    ) {
        if (!currentMachineOnline()) KitOfflineNote() else GlossaryList(actions)
    }
}

@Composable
private fun GlossaryList(actions: VoiceSettingsActions) {
    val c = kitColors()
    val scope = rememberCoroutineScope()
    val couldntReach = pagesCouldntReach()
    val saveFailed = localized(Res.string.settings_glossary_save_failed)
    val terms = remember { mutableStateListOf<String>() }
    // "Add a word" open, with the word being typed in it, kept for the page on display (#89).
    val form = rememberRetainedOnPage("voice:glossary") { GlossaryForm() }
    var loadState by remember { mutableStateOf<GlossaryLoadState>(GlossaryLoadState.Loading) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }

    fun show(list: List<String>) {
        terms.clear()
        terms.addAll(list)
        loadState = if (list.isEmpty()) GlossaryLoadState.Empty else GlossaryLoadState.Ready(list)
    }

    LaunchedEffect(reloadKey) {
        if (loadState !is GlossaryLoadState.Ready && loadState !is GlossaryLoadState.Empty) loadState = GlossaryLoadState.Loading
        val result = actions.glossaryLoad()
        if (result == null) {
            terms.clear()
            loadState = GlossaryLoadState.Error
        } else {
            show(result)
            error = null
        }
    }

    LaunchedEffect(loadState, reloadKey) {
        if (loadState !is GlossaryLoadState.Error) return@LaunchedEffect
        while (isActive) {
            delay(ERROR_AUTO_RETRY_MS)
            val result = actions.glossaryLoad() ?: continue
            show(result)
            break
        }
    }

    val loaded = loadState is GlossaryLoadState.Ready || loadState is GlossaryLoadState.Empty

    fun persist() {
        val snapshot = terms.toList()
        scope.launch {
            val saved = actions.glossarySave(snapshot)
            if (saved == null) {
                // Show what the machine still has — never an empty list on a failed reload.
                actions.glossaryLoad()?.let(::show)
                error = saveFailed
            } else {
                error = null
                show(saved)
            }
        }
    }

    fun add() {
        val t = form.newTerm.trim()
        if (!loaded) return
        if (t.isEmpty() || terms.any { it.equals(t, ignoreCase = true) }) {
            form.newTerm = ""
            return
        }
        terms.add(t)
        form.newTerm = ""
        loadState = GlossaryLoadState.Ready(terms.toList())
        persist()
    }

    fun remove(term: String) {
        terms.remove(term)
        loadState = if (terms.isEmpty()) GlossaryLoadState.Empty else GlossaryLoadState.Ready(terms.toList())
        persist()
    }

    error?.let {
        Text(it, fontSize = 12.sp, color = c.danger, modifier = Modifier.padding(bottom = 6.dp).testTag("voice_glossary_error"))
    }
    when (loadState) {
        GlossaryLoadState.Loading -> KitNote(localized(Res.string.settings_pages_loading), Modifier.testTag("voice_glossary_loading"))
        GlossaryLoadState.Error -> {
            KitNote(couldntReach, Modifier.testTag("voice_glossary_load_error"))
            KitTextAction(
                localized(Res.string.settings_pages_try_again),
                onClick = { reloadKey++ },
                modifier = Modifier.padding(top = 4.dp).testTag("voice_glossary_retry"),
            )
        }
        GlossaryLoadState.Empty -> KitNote(
            localized(Res.string.settings_glossary_empty),
            Modifier.padding(bottom = 10.dp).testTag("voice_glossary_empty"),
        )
        is GlossaryLoadState.Ready -> Unit
    }
    if (!loaded) return

    KitItemList(Modifier.testTag("voice_glossary_list")) {
        for (term in terms) {
            val tagSafe = term.replace(Regex("[^A-Za-z0-9._-]"), "_")
            KitItem(term, Modifier.testTag("voice_glossary_term_$tagSafe")) {
                KitGhostButton(localized(Res.string.settings_glossary_remove), onClick = { remove(term) }, modifier = Modifier.testTag("voice_glossary_remove_$tagSafe"))
            }
        }
        if (!form.adding) {
            KitItem(
                localized(Res.string.settings_glossary_add_word),
                Modifier.testTag("voice_glossary_add_item"),
                mark = KitMark.Add,
                onClick = { form.adding = true },
            )
        } else {
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KitTextField(
                    value = form.newTerm,
                    onValueChange = { form.newTerm = it },
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    placeholder = localized(Res.string.settings_glossary_placeholder),
                    // No autocorrect on names and technical terms; Enter (or the keyboard's Done) adds.
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    onSubmit = { add() },
                    fieldTag = "voice_glossary_input",
                )
                KitGhostButton(
                    localized(Res.string.settings_glossary_add),
                    onClick = { add() },
                    modifier = Modifier.testTag("voice_glossary_add"),
                    enabled = form.newTerm.isNotBlank(),
                )
            }
        }
    }
}

/**
 * The word list's "Add a word", kept for the page on display (`rememberRetainedOnPage`, #89): open or not, and the
 * word being typed. Voice clears it when it shows again, so a word half typed belongs to one visit of the list.
 */
internal class GlossaryForm {
    var adding by mutableStateOf(false)
    var newTerm by mutableStateOf("")

    fun clear() {
        adding = false
        newTerm = ""
    }
}
