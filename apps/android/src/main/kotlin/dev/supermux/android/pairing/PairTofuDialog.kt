package dev.supermux.android.pairing

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.supermux.net.PairUrl

/**
 * Trust-on-first-connect confirmation. The token already validated against the broker;
 * this is the explicit "do you trust this host?" gate before the credential is persisted.
 * Native M3 [AlertDialog] showing the broker host + the resolved device name.
 */
@Composable
fun PairTofuDialog(
    pair: PairUrl,
    deviceName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Lock, contentDescription = null) },
        title = { Text("Connect to this broker?") },
        text = {
            Column {
                Text(
                    "You're about to trust and store credentials for:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    pair.baseUrl,
                    style = MaterialTheme.typography.titleSmall,
                    color = cs.onSurface,
                )
                Text(
                    "Device: $deviceName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
