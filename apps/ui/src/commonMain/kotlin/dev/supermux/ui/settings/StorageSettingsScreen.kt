// Settings ▸ Storage: how much the attachment cache holds, and the one way to empty it. Nothing is
// ever deleted in the background — this button and forgetting a host are the only deletions.
package dev.supermux.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.supermux.media.MediaCache
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.widgets.SettingsDetailMaxWidth
import dev.supermux.ui.worktree.formatBytes
import kotlinx.coroutines.launch

/**
 * The cache's size and a Clear button. No confirmation: nothing is lost — every file downloads again
 * from its host the next time it is viewed.
 *
 * @param cache the seam under test; production reads `LocalPlatform.current.mediaCache`, and the hub
 *   only offers this row when that is non-null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
    cache: MediaCache? = LocalPlatform.current.mediaCache,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    if (compact && !topBarShown) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Storage", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("storage_settings_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
                )
            },
            containerColor = cs.background,
        ) { padding -> StorageSettingsBody(modifier.padding(padding), cache) }
    } else {
        StorageSettingsBody(modifier, cache)
    }
}

@Composable
private fun StorageSettingsBody(modifier: Modifier, cache: MediaCache?) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var size by remember(cache) { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    LaunchedEffect(cache) { size = cache?.sizeBytes() ?: 0L }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .verticalScroll(rememberScrollState())
            .testTag("storage_settings_screen"),
    ) {
        Column(
            Modifier
                .widthIn(max = SettingsDetailMaxWidth)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Media cache", style = MaterialTheme.typography.titleMedium, color = cs.onBackground)
            Text(
                if (cache?.persistent == false) {
                    "Images, videos and files you have opened. Kept only until this page closes."
                } else {
                    "Images, videos and files you have opened, kept so they open instantly and offline."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onSurfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val current = size
                Text(
                    when {
                        current == null -> "Calculating…"
                        current == 0L -> "Empty"
                        else -> formatBytes(current)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = cs.onBackground,
                    modifier = Modifier.testTag("storage_size"),
                )
                FilledTonalButton(
                    onClick = {
                        clearing = true
                        scope.launch {
                            cache?.clear()
                            size = cache?.sizeBytes() ?: 0L
                            clearing = false
                        }
                    },
                    enabled = cache != null && !clearing && (size ?: 0L) > 0L,
                    modifier = Modifier.testTag("storage_clear"),
                ) { Text("Clear") }
            }
        }
    }
}
