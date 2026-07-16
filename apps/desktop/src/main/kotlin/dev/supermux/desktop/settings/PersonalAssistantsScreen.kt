package dev.supermux.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.PADto
import kotlinx.coroutines.launch

@Composable
fun PersonalAssistantsScreen(
    load: suspend () -> List<PADto>,
    create: suspend (name: String, agent: String, focus: String?) -> Boolean,
    kill: suspend (id: String) -> Unit,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var assistants by remember { mutableStateOf<List<PADto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showCreate by remember { mutableStateOf(false) }
    var killTarget by remember { mutableStateOf<PADto?>(null) }

    suspend fun refresh() {
        loading = true
        assistants = load()
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize().background(cs.background)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Personal assistants", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            Button(onClick = { showCreate = true }) { Text("Create") }
        }
        HorizontalDivider(color = cs.outlineVariant)

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            assistants.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No personal assistants", fontWeight = FontWeight.SemiBold)
                    Text("They are optional. Create one when you want a persistent orchestrator.", color = cs.onSurfaceVariant)
                }
            }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(assistants, key = { it.id }) { pa ->
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(pa.name, fontWeight = FontWeight.Medium)
                                if (pa.isDefault) Text("default", color = cs.primary, fontSize = 11.sp)
                            }
                        },
                        supportingContent = {
                            Text(listOfNotNull(pa.agent, pa.model).joinToString(" · ").ifBlank { pa.workdir })
                        },
                        leadingContent = {
                            Box(
                                Modifier.size(9.dp).clip(CircleShape)
                                    .background(if (pa.connected) cs.primary else cs.outline),
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { killTarget = pa }) { Text("Kill") }
                        },
                    )
                    HorizontalDivider(color = cs.outlineVariant)
                }
            }
        }
    }

    if (showCreate) {
        CreatePersonalAssistantDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, agent, focus ->
                scope.launch {
                    if (create(name, agent, focus)) {
                        showCreate = false
                        refresh()
                    }
                }
            },
        )
    }
    killTarget?.let { pa ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text("Kill ${pa.name}?") },
            text = { Text("Its session will be archived. You can create another personal assistant later.") },
            confirmButton = {
                TextButton(onClick = {
                    killTarget = null
                    scope.launch { kill(pa.id); refresh() }
                }) { Text("Kill") }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CreatePersonalAssistantDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("claude") }
    var focus by remember { mutableStateOf("") }
    val agents = listOf("claude", "codex", "cursor", "opencode")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create personal assistant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Text("Agent", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                agents.chunked(2).forEach { choices ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.forEach { value ->
                            FilterChip(
                                selected = agent == value,
                                onClick = { agent = value },
                                label = { Text(value) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                OutlinedTextField(focus, { focus = it }, label = { Text("Focus (optional)") }, minLines = 2)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim(), agent, focus.trim().takeIf { it.isNotEmpty() }) },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
