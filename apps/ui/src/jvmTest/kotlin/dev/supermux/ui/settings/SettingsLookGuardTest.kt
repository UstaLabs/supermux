package dev.supermux.ui.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Settings owns its look (#97 spec §3, §10.10): from Material 3 its files take only the theme, its types, Text and
 * Icon — every control is the kit's (`ui/settings/kit`), every confirmation a kit sheet. An allowlist, so a stock
 * component nobody thought to name cannot slip in. Run from `apps/ui` (Gradle's test working directory); the
 * desktop app's settings files are read through `../desktop`.
 */
class SettingsLookGuardTest {
    private val roots = listOf(
        "src/commonMain/kotlin/dev/supermux/ui/settings",
        "src/commonMain/kotlin/dev/supermux/ui/accounts",
        "../desktop/src/main/kotlin/dev/supermux/desktop/settings",
        "../desktop/src/main/kotlin/dev/supermux/desktop/overlay/DesktopOverlaySettings.kt",
    )

    /** Deleted in Step 2 (the machine page): untouched until then. Never add to this list. */
    private val step2 = setOf(
        "settings/SystemSettingsScreen.kt",
        "settings/HostingSettingsScreen.kt",
        "settings/DevicesSettingsScreen.kt",
        "settings/ProxiesSettingsScreen.kt",
        "settings/WorktreesSettingsScreen.kt",
        "desktop/settings/DesktopHostingSettings.kt",
    )

    /** Converted by this plan, one page task at a time. Remove a file the moment it is clean; never add to it. */
    private val pending = setOf(
        "settings/AgentSettingsScreen.kt",
        "accounts/AccountsSettingsScreen.kt",
        "accounts/AccountsWidgets.kt",
        "accounts/AddAccountSheet.kt",
    )

    /** `settings/X.kt` for `ui`'s files, `desktop/settings/X.kt` for the desktop app's. */
    private fun relOf(f: File) = f.invariantSeparatorsPath.substringAfter("dev/supermux/").removePrefix("ui/")

    private fun fileOf(rel: String) = File(
        if (rel.startsWith("desktop/")) "../desktop/src/main/kotlin/dev/supermux/$rel" else "src/commonMain/kotlin/dev/supermux/ui/$rel",
    )

    private fun hits(): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        for (r in roots) {
            val root = File(r)
            assertTrue(root.exists(), "run from apps/ui: ${root.absolutePath} is missing")
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                f.readLines().forEachIndexed { i, raw ->
                    val why = SettingsLook.offence(raw)
                    if (why != null) out.getOrPut(relOf(f)) { mutableListOf() } += "${i + 1}: $why — ${raw.trim()}"
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
        val missing = step2.filter { !fileOf(it).isFile }
        if (missing.isNotEmpty()) fail("Gone — remove them from `step2`: $missing")
    }

    @Test fun the_rule_sees_through_aliases_comments_and_qualified_calls() {
        assertNull(SettingsLook.offence("import androidx.compose.material3.MaterialTheme"))
        assertNull(SettingsLook.offence("import androidx.compose.material3.Text as M3Text"))
        assertNull(SettingsLook.offence("import androidx.compose.material3.LocalTextStyle // the theme's"))
        assertNull(SettingsLook.offence("    // androidx.compose.material3.Switch(checked = true, onCheckedChange = null)"))
        assertNull(SettingsLook.offence(" * Not Material's [androidx.compose.material3.TopAppBar]."))
        assertNull(SettingsLook.offence("        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,"))
        assertNull(SettingsLook.offence("import dev.supermux.ui.widgets.Dialog"))
        assertNotNull(SettingsLook.offence("import androidx.compose.material3.Switch as Toggle"))
        assertNotNull(SettingsLook.offence("import androidx.compose.material3.HorizontalDivider // a hairline"))
        assertNotNull(SettingsLook.offence("import androidx.compose.material3.*"))
        assertNotNull(SettingsLook.offence("import androidx.compose.material3.pulltorefresh.PullToRefreshBox"))
        assertNotNull(SettingsLook.offence("        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = {}) {"))
        assertNotNull(SettingsLook.offence("import dev.supermux.ui.widgets.AlertDialog"))
        assertNotNull(SettingsLook.offence("    dev.supermux.ui.widgets.AlertDialog(onDismissRequest = {}, confirmButton = {})"))
    }
}

/** The rule itself: one line of Kotlin in, the reason it breaks Settings' look out (or null). */
internal object SettingsLook {
    /** All Settings may take from Material 3: the theme, its types, and the two primitives the kit draws with. */
    val allowed = setOf(
        "MaterialTheme", "Text", "Icon", "LocalContentColor", "LocalTextStyle", "ProvideTextStyle",
        "minimumInteractiveComponentSize", "ExperimentalMaterial3Api", "ColorScheme", "Typography",
    )
    private val m3Import = Regex("""^import\s+androidx\.compose\.material3\.([\w.]+|\*)(?:\s+as\s+\w+)?$""")
    private val m3Reference = Regex("""\bandroidx\.compose\.material3\.(\w+)""")
    private val alertImport = Regex("""^import\s+dev\.supermux\.ui\.widgets\.AlertDialog(?:\s+as\s+\w+)?$""")
    private val alertReference = Regex("""\bdev\.supermux\.ui\.widgets\.AlertDialog\b""")

    fun offence(raw: String): String? {
        val trimmed = raw.trim()
        // KDoc and block comments may name what the kit replaces.
        if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return null
        val line = trimmed.substringBefore("//").trim()
        if (line.isEmpty()) return null
        if (line.startsWith("import ")) {
            m3Import.matchEntire(line)?.let { m ->
                val name = m.groupValues[1]
                return if (name in allowed) null else "Material 3 $name"
            }
            return if (alertImport.matches(line)) "the stock confirmation dialog (use a KitSheet)" else null
        }
        m3Reference.findAll(line).firstOrNull { it.groupValues[1] !in allowed }?.let { return "Material 3 ${it.groupValues[1]}" }
        if (alertReference.containsMatchIn(line)) return "the stock confirmation dialog (use a KitSheet)"
        return null
    }
}
