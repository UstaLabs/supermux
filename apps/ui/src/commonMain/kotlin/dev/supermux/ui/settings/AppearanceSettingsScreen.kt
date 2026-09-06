// The one Appearance screen for both apps (cluster E7).
//
// Base = Android's `MoreScreens.kt` `AppearanceSettingsPage` (theme segmented row, Material You
// switch, text-size slider). What changed in the move:
//
//  - The three values left `MainActivity`'s `cmux-editor-settings` SharedPreferences for
//    `SettingsKeys.APPEARANCE` / `DYNAMIC_COLOR` / `TEXT_SCALE` through [UiPrefs], so the screen
//    reads and writes them itself on both platforms and neither app has to thread six parameters
//    down from its entry point. Android's stored values are migrated once
//    (`android/settings/AppearancePrefsMigration.kt`); desktop's old `ui-state.json` `appearance`
//    field is seeded the same way in `Main.kt`.
//  - Desktop gains the screen. Its sidebar theme toggle writes the SAME key, so flipping the
//    toggle moves the radio here and choosing here repaints the shell — one source of truth.
//  - The Material You row is gated on `Caps.dynamicColor` (Android 12+ only). It has been a no-op
//    for colour since the brand palette became the only palette; the switch is kept where the OS
//    could plausibly provide one, so nobody's stored opt-in silently disappears.
package dev.supermux.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.prefs.LocalUiPrefs
import dev.supermux.ui.prefs.TEXT_SCALE_DEFAULT
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.TEXT_SCALE_MAX
import dev.supermux.ui.theme.TEXT_SCALE_MIN
import dev.supermux.ui.widgets.SettingsDetailMaxWidth
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Theme mode, Material You and app text size.
 *
 * @param defaultAppearance what "never chosen" means on THIS host — the same fallback its root
 *   theme applies (Android follows the system, desktop opens dark). Only used until the user picks
 *   one, after which the stored value wins on both.
 * @param onBack leave the screen (the hub's `SettingsSlotScope.onClose`).
 * @param topBarShown the hub already painted a `TopAppBar` for this detail.
 * @param standalone the screen is its own destination (Android's `Route.Appearance`, reached from
 *   a deep link / the sidebar), so it paints title + Back at every width.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(
    modifier: Modifier = Modifier,
    defaultAppearance: AppearanceMode = AppearanceMode.SYSTEM,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    standalone: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    if ((standalone || compact) && !topBarShown) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Appearance", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("appearance_settings_back"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = cs.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = cs.surfaceContainerHigh,
                    ),
                )
            },
            containerColor = cs.background,
        ) { padding ->
            AppearanceSettingsBody(modifier.padding(padding), defaultAppearance)
        }
    } else {
        AppearanceSettingsBody(modifier, defaultAppearance)
    }
}

@Composable
private fun AppearanceSettingsBody(
    modifier: Modifier = Modifier,
    defaultAppearance: AppearanceMode = AppearanceMode.SYSTEM,
) {
    val cs = MaterialTheme.colorScheme
    val prefs = LocalUiPrefs.current
    val caps = LocalPlatform.current.caps
    val scope = rememberCoroutineScope()
    val appearance by prefs.appearance(defaultAppearance).collectAsState(defaultAppearance)
    val dynamicColor by prefs.dynamicColor.collectAsState(false)
    val storedTextScale by prefs.textScale.collectAsState(TEXT_SCALE_DEFAULT)
    // The slider is DRAGGED, so it cannot be driven by the persisted value: writing on every frame
    // of the gesture is a disk write per frame, and the thumb would lag behind the finger by a
    // round trip through the store. The drag lives here and is persisted once, on release; this
    // local value is dropped again as soon as the store has caught up to it.
    var draggedTextScale by remember { mutableStateOf<Float?>(null) }
    val textScale = draggedTextScale ?: storedTextScale
    LaunchedEffect(storedTextScale) {
        if (draggedTextScale == storedTextScale) draggedTextScale = null
    }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .verticalScroll(rememberScrollState())
            .testTag("appearance_settings_screen"),
    ) {
        Column(
            Modifier
                .widthIn(max = SettingsDetailMaxWidth)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val modes = AppearanceMode.entries
                    modes.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = appearance == mode,
                            onClick = { scope.launch { prefs.putAppearance(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                            modifier = Modifier.testTag("appearance_mode_${mode.name.lowercase()}"),
                        ) {
                            Text(
                                when (mode) {
                                    AppearanceMode.SYSTEM -> "System"
                                    AppearanceMode.LIGHT -> "Light"
                                    AppearanceMode.DARK -> "Dark"
                                },
                            )
                        }
                    }
                }
            }
            if (caps.dynamicColor) {
                Row(
                    Modifier.fillMaxWidth().testTag("appearance_dynamic_row"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Material You",
                            style = MaterialTheme.typography.titleMedium,
                            color = cs.onBackground,
                        )
                        Text(
                            "Use colours from your wallpaper (Android 12+). Currently unavailable — " +
                                "supermux always uses the brand palette.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = dynamicColor,
                        onCheckedChange = { on -> scope.launch { prefs.putDynamicColor(on) } },
                        modifier = Modifier.testTag("appearance_dynamic_switch"),
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Text size",
                            style = MaterialTheme.typography.titleMedium,
                            color = cs.onBackground,
                        )
                        Text(
                            "Scales all text in the app — the whole screen previews it live.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "${(textScale * 100).roundToInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (textScale == 1f) cs.onSurfaceVariant else cs.primary,
                        modifier = Modifier.testTag("appearance_text_scale_value"),
                    )
                }
                Slider(
                    value = textScale,
                    onValueChange = { raw ->
                        // Snap to 5% increments so the stored value stays clean.
                        draggedTextScale = (raw * 20).roundToInt() / 20f
                    },
                    onValueChangeFinished = {
                        val picked = draggedTextScale
                        if (picked != null && picked != storedTextScale) {
                            scope.launch { prefs.putTextScale(picked) }
                        } else {
                            // The drag ended back ON the stored value, so no write lands and the
                            // effect above never fires — drop the local override here or the screen
                            // would stop following an outside change for the rest of its life.
                            draggedTextScale = null
                        }
                    },
                    valueRange = TEXT_SCALE_MIN..TEXT_SCALE_MAX,
                    steps = 7,
                    modifier = Modifier.testTag("appearance_text_scale_slider"),
                )
            }
        }
    }
}
