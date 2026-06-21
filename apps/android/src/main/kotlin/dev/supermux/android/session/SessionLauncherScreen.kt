package dev.supermux.android.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.Space
import dev.supermux.proto.SessionInfo
import dev.supermux.session.formatWorkdir
import kotlinx.coroutines.launch

@Composable
fun SessionLauncherScreen(
    sessions: List<SessionInfo>,
    home: String,
    onBack: () -> Unit,
    loadProjects: suspend () -> List<String>,
    validatePath: suspend (String) -> dev.supermux.net.PathValidation?,
    onSubmit: suspend (workdir: String, agent: String, model: String?, message: String) -> String,
    onOpenSession: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var workdir by remember { mutableStateOf("~") }
    var workdirTouched by remember { mutableStateOf(false) }
    var agent by remember { mutableStateOf("claude") }
    var model by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var projects by remember { mutableStateOf(emptyList<String>()) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val agents = listOf("claude", "codex", "cursor")

    LaunchedEffect(Unit) { projects = loadProjects() }

    // Default workdir from most recent session, like web chooseDefaultProject.
    LaunchedEffect(sessions) {
        if (!workdirTouched && sessions.isNotEmpty()) {
            workdir = sessions.first().workdir.ifBlank { "~" }
        }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = cs.onSurface,
        unfocusedTextColor = cs.onSurface,
        focusedBorderColor = cs.primary,
        unfocusedBorderColor = cs.outline,
        focusedLabelColor = cs.primary,
        unfocusedLabelColor = cs.onSurfaceVariant,
    )

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.surfaceContainerHigh),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.sm, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_left),
                    contentDescription = "Back",
                    tint = cs.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text("New session", color = cs.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        }
        HorizontalDivider(color = cs.outlineVariant)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg, vertical = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text("Let's build", color = cs.onSurface, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Space.sm))
                Text(
                    formatWorkdir(workdir, home),
                    color = cs.primary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("Project", color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                ProjectPathPicker(
                    value = workdir,
                    onValueChange = { workdir = it; workdirTouched = true; error = null },
                    projects = projects,
                    home = home,
                    fieldColors = fieldColors,
                )
            }

            OutlinedTextField(
                value = message,
                onValueChange = { message = it; error = null },
                placeholder = { Text("What should the agent do?", color = cs.onSurfaceVariant) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                colors = fieldColors,
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .border(1.dp, cs.outline, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                agents.forEach { a ->
                    val selected = agent == a
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (selected) cs.primary else Color.Transparent)
                            .clickable { agent = a }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            a,
                            color = if (selected) cs.onPrimary else cs.onSurfaceVariant,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                placeholder = { Text("Model (optional)", color = cs.onSurfaceVariant) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = fieldColors,
            )

            error?.let { Text(it, color = cs.error, fontSize = 12.sp) }

            Button(
                onClick = {
                    val text = message.trim()
                    if (text.isEmpty()) {
                        error = "Enter a message"
                        return@Button
                    }
                    submitting = true
                    error = null
                    scope.launch {
                        try {
                            val sessionId = onSubmit(workdir.trim(), agent, model.ifBlank { null }, text)
                            onOpenSession(sessionId)
                        } catch (e: Exception) {
                            error = e.message ?: "Failed to create session"
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !submitting && workdir.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary,
                ),
            ) {
                if (submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = cs.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (submitting) "Creating…" else "Start session")
            }
        }
    }
}
