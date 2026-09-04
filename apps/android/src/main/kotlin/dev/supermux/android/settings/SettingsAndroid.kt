package dev.supermux.android.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

// The Android-only remainder of the old `settings/SettingsShared.kt`: everything that survives on
// both platforms moved to `dev.supermux.ui.widgets.SettingsShared` in cluster A5, and these two are
// the bits that cannot — one is a plain data class the Android VM signature uses, the other paints
// an `R.drawable` through `painterResource(Int)`, which has no multiplatform equivalent.

/** The 7 add-custom-LSP fields, mirroring BrokerApi.addCustomEditorLsp(...) so the
 *  page→VM lambda carries a single arg. Used by EditorLspSection + AppViewModel. */
data class AddCustomLspArgs(
    val id: String,
    val label: String,
    val command: String,
    val extensions: List<String>,
    val args: List<String> = emptyList(),
    val languageId: String? = null,
    val installCmd: String? = null,
)

/** A 34dp rounded icon box used by the forge connection rows. */
@Composable
fun ForgeIconBox(iconRes: Int, tint: androidx.compose.ui.graphics.Color) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outline, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}
