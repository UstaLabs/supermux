package dev.supermux.ui.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Settings owns its look (#97 spec §3, §10.10): no stock Material component in Settings' own files — the kit in
 * `ui/settings/kit` replaces each one. Run from `apps/ui` (Gradle's test working directory).
 */
class SettingsLookGuardTest {
    private val names = listOf(
        "Switch", "SegmentedButton", "SingleChoiceSegmentedButtonRow", "MultiChoiceSegmentedButtonRow",
        "OutlinedButton", "Button", "FilledTonalButton", "ElevatedButton", "TextButton", "IconButton",
        "TopAppBar", "CenterAlignedTopAppBar", "Scaffold", "Card", "ElevatedCard", "OutlinedCard",
        "FilterChip", "AssistChip", "SuggestionChip", "InputChip", "CircularProgressIndicator",
        "LinearProgressIndicator", "FloatingActionButton", "ExtendedFloatingActionButton", "ModalBottomSheet",
        "Checkbox", "RadioButton", "Slider", "OutlinedTextField", "TextField", "ListItem",
    ).joinToString("|")
    private val banned = Regex("""^import androidx\.compose\.material3\.($names)$""")
    private val qualified = Regex("""androidx\.compose\.material3\.($names)\(""")
    private val wildcard = Regex("""^import androidx\.compose\.material3\.\*$""")

    private val roots = listOf(
        "src/commonMain/kotlin/dev/supermux/ui/settings",
        "src/commonMain/kotlin/dev/supermux/ui/accounts",
    )

    /** Deleted in Step 2 (the machine page): untouched until then. Never add to this list. */
    private val step2 = setOf(
        "settings/SystemSettingsScreen.kt",
        "settings/HostingSettingsScreen.kt",
        "settings/DevicesSettingsScreen.kt",
        "settings/ProxiesSettingsScreen.kt",
        "settings/WorktreesSettingsScreen.kt",
    )

    /** Converted by this plan, one page task at a time. Remove a file the moment it is clean. */
    private val pending = setOf(
        "settings/SettingsHub.kt",
        "settings/AppearanceSettingsScreen.kt",
        "settings/StorageSettingsScreen.kt",
        "settings/TelemetrySettingsSection.kt",
        "settings/CuratorSettingsScreen.kt",
        "settings/VoiceSettingsScreen.kt",
        "settings/EditorSettingsScreen.kt",
        "settings/LspSettingsScreen.kt",
        "settings/GitHostingScreen.kt",
        "settings/AgentSettingsScreen.kt",
        "accounts/AccountsSettingsScreen.kt",
        "accounts/AccountsWidgets.kt",
        "accounts/AddAccountSheet.kt",
    )

    private fun hits(): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        for (r in roots) {
            val dir = File(r)
            assertTrue(dir.isDirectory, "run from apps/ui: ${dir.absolutePath} is missing")
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val rel = f.invariantSeparatorsPath.substringAfter("dev/supermux/ui/")
                f.readLines().forEachIndexed { i, raw ->
                    val line = raw.trim()
                    if (banned.matches(line) || wildcard.matches(line) || qualified.containsMatchIn(line)) {
                        out.getOrPut(rel) { mutableListOf() } += "${i + 1}: $line"
                    }
                }
            }
        }
        return out
    }

    @Test fun settings_files_use_the_kit_not_stock_material() {
        val bad = hits().filterKeys { it !in step2 && it !in pending }
        if (bad.isNotEmpty()) {
            fail(
                "Stock Material in Settings — use ui/settings/kit:\n" +
                    bad.entries.joinToString("\n") { (f, ls) -> "$f\n  " + ls.joinToString("\n  ") },
            )
        }
    }

    @Test fun the_pending_list_only_shrinks() {
        val now = hits()
        val clean = pending.filter { it !in now.keys }
        if (clean.isNotEmpty()) fail("These are clean or gone — remove them from `pending`: $clean")
    }

    @Test fun the_step2_list_names_files_that_exist() {
        val missing = step2.filter { rel -> roots.none { File(it.substringBeforeLast("/ui/") + "/ui/" + rel).isFile } }
        if (missing.isNotEmpty()) fail("Gone — remove them from `step2`: $missing")
    }
}
