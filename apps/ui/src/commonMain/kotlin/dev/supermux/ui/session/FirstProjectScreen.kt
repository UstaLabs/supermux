// The launcher's first-run project step: "Where should your agents work?" — open a folder or
// create a new project. See LauncherFirstRun.kt for when it shows.
package dev.supermux.ui.session

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.supermux.net.PathValidation
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.submitOnEnter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class FirstProjectStep { Choose, TypePath, Name }

/** What the broker accepts as a local project name (`ForgeService.createLocal`). */
private val NAME_START = Regex("^[^A-Za-z0-9]+")
private val NAME_INVALID = Regex("[^A-Za-z0-9._-]")

/** A typed project name as the folder the broker will make: spaces become dashes, the rest is dropped. */
internal fun projectFolderName(typed: String): String =
    typed.trim().replace(Regex("\\s+"), "-").replace(NAME_INVALID, "").replace(NAME_START, "")

/**
 * A fresh host's new-session screen: two big choices instead of a search over nothing.
 *
 * @param validatePath the launcher's `validatePath` (null = transport failure).
 * @param createLocalRepo the launcher's `createLocalRepo`: the new folder's path, null on a network
 *   failure, or a thrown [BrokerRefusal] carrying the broker's reason.
 * @param pickFolder the native folder dialog, only when the host is this computer (null otherwise):
 *   the chosen path, or null when the user cancelled.
 * @param onProject a folder is ready: the launcher takes it as its project.
 */
@Composable
fun FirstProjectScreen(
    validatePath: suspend (String) -> PathValidation?,
    createLocalRepo: suspend (String) -> String?,
    onProject: (String) -> Unit,
    stacked: Boolean,
    modifier: Modifier = Modifier,
    pickFolder: (suspend () -> String?)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(FirstProjectStep.Choose) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun go(next: FirstProjectStep) {
        if (busy) return
        step = next
        error = null
    }

    suspend fun openPath(path: String) {
        val res = validatePath(path)
        val resolved = res?.path
        if (res != null && res.ok && !resolved.isNullOrBlank()) onProject(resolved)
        else error = res?.error ?: "Couldn't check that folder — check the connection and try again."
    }

    fun run(label: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = hostOpFailureMessage(label, e)
            } finally {
                busy = false
            }
        }
    }

    fun openFolder() {
        if (pickFolder == null) return go(FirstProjectStep.TypePath)
        run("open that folder") { pickFolder()?.let { openPath(it) } }
    }

    Column(
        modifier.fillMaxWidth().testTag(LauncherFirstRunTags.FIRST_PROJECT),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                "Where should your agents work?",
                color = cs.onSurface,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                "Agents read and change files only inside the folder you choose.",
                color = cs.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Space.lg))
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "first-project-step",
            ) { current ->
                when (current) {
                    FirstProjectStep.Choose -> ChooseStep(
                        stacked = stacked,
                        busy = busy,
                        localPicker = pickFolder != null,
                        onOpen = ::openFolder,
                        onCreate = { go(FirstProjectStep.Name) },
                        onTypePath = { go(FirstProjectStep.TypePath) },
                    )
                    FirstProjectStep.TypePath -> InputStep(
                        icon = Icons.Outlined.FolderOpen,
                        title = "Open a folder",
                        label = "Folder path",
                        placeholder = "~/code/my-app",
                        hint = { "The folder must already exist on the host." },
                        confirm = "Open folder",
                        fieldTag = LauncherFirstRunTags.PATH_FIELD,
                        busy = busy,
                        onBack = { go(FirstProjectStep.Choose) },
                        onConfirm = { value -> run("open $value") { openPath(value) } },
                        onEdit = { error = null },
                    )
                    FirstProjectStep.Name -> InputStep(
                        icon = Icons.Outlined.CreateNewFolder,
                        title = "Create a new project",
                        label = "Project name",
                        placeholder = "my-app",
                        hint = { typed ->
                            val folder = projectFolderName(typed)
                            if (folder.isEmpty()) "An empty folder in Supermux's projects folder."
                            else "Creates “$folder” in Supermux's projects folder."
                        },
                        confirm = "Create project",
                        fieldTag = LauncherFirstRunTags.NAME_FIELD,
                        busy = busy,
                        transform = ::projectFolderName,
                        onBack = { go(FirstProjectStep.Choose) },
                        onConfirm = { name ->
                            run("create $name") {
                                val path = createLocalRepo(name)
                                if (!path.isNullOrBlank()) onProject(path) else error = hostOpFailureMessage("create $name", null)
                            }
                        },
                        onEdit = { error = null },
                    )
                }
            }
            error?.let { ErrorLine(it) }
            if (step == FirstProjectStep.Choose) {
                Spacer(Modifier.height(Space.sm))
                Text(
                    "You can add more projects any time.",
                    color = cs.onSurfaceVariant.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun ChooseStep(
    stacked: Boolean,
    busy: Boolean,
    localPicker: Boolean,
    onOpen: () -> Unit,
    onCreate: () -> Unit,
    onTypePath: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        val open: @Composable (Modifier) -> Unit = { m ->
            ChoiceCard(
                icon = Icons.Outlined.FolderOpen,
                title = "Open a folder",
                caption = if (localPicker) "Choose a folder on this computer" else "Use a folder you already have",
                busy = busy && localPicker,
                onClick = onOpen,
                modifier = m.testTag(LauncherFirstRunTags.OPEN_FOLDER),
            )
        }
        val create: @Composable (Modifier) -> Unit = { m ->
            ChoiceCard(
                icon = Icons.Outlined.CreateNewFolder,
                title = "Create a new project",
                caption = "Start from an empty folder",
                busy = false,
                onClick = onCreate,
                modifier = m.testTag(LauncherFirstRunTags.CREATE_PROJECT),
            )
        }
        if (stacked) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                open(Modifier.fillMaxWidth())
                create(Modifier.fillMaxWidth())
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                open(Modifier.weight(1f))
                create(Modifier.weight(1f))
            }
        }
        if (localPicker) {
            TextButton(onClick = onTypePath, enabled = !busy, modifier = Modifier.testTag(LauncherFirstRunTags.TYPE_PATH)) {
                Text("or type a folder path", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    caption: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.lg + 4.dp)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        modifier
            .clip(shape)
            .background(if (hovered) cs.surfaceContainerHigh else cs.surfaceContainer)
            .border(Stroke.hairline, if (hovered) cs.primary.copy(alpha = 0.6f) else cs.outlineVariant.copy(alpha = 0.6f), shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, enabled = !busy, onClick = onClick)
            .padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.weight(1f))
            if (busy) {
                CircularProgressIndicator(Modifier.size(20.dp).testTag(LauncherFirstRunTags.BUSY), strokeWidth = Stroke.thin)
            } else {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = if (hovered) cs.primary else cs.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(Space.md))
        Text(title, color = cs.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(caption, color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun IconBadge(icon: ImageVector) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(Radii.md)).background(cs.primary.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = cs.primary, modifier = Modifier.size(26.dp))
    }
}

@Composable
private fun InputStep(
    icon: ImageVector,
    title: String,
    label: String,
    placeholder: String,
    hint: (String) -> String,
    confirm: String,
    fieldTag: String,
    busy: Boolean,
    onBack: () -> Unit,
    onConfirm: (String) -> Unit,
    onEdit: () -> Unit,
    transform: (String) -> String = { it.trim() },
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.lg + 4.dp)
    var input by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val value = transform(input)
    fun submit() { if (value.isNotEmpty() && !busy) onConfirm(value) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceContainer)
            .border(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            IconBadge(icon)
            Text(title, color = cs.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; onEdit() },
                singleLine = true,
                enabled = !busy,
                label = { Text(label) },
                placeholder = { Text(placeholder) },
                shape = RoundedCornerShape(Radii.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag(fieldTag)
                    .submitOnEnter(!busy) { submit() },
            )
            Text(hint(input), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.testTag(LauncherFirstRunTags.BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(Space.xs))
                Text("Back")
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { submit() },
                enabled = !busy && value.isNotEmpty(),
                modifier = Modifier.height(44.dp).testTag(LauncherFirstRunTags.CONFIRM),
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        Modifier.size(18.dp).testTag(LauncherFirstRunTags.BUSY),
                        strokeWidth = Stroke.thin,
                        color = cs.onPrimary,
                    )
                } else {
                    Text(confirm)
                }
            }
        }
    }
}

@Composable
internal fun ErrorLine(message: String, tag: String = LauncherFirstRunTags.ERROR) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.sm)
            .clip(RoundedCornerShape(Radii.md))
            .background(cs.errorContainer.copy(alpha = 0.35f))
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = cs.error, modifier = Modifier.size(18.dp))
        Text(message, color = cs.onSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(tag))
    }
}
