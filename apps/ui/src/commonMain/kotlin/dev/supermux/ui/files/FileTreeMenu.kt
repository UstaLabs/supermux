// The Files tree's row actions (spec 2026-09-27 §5): a context menu (long-press / right-click)
// with New file / New folder / Rename / Delete / Copy path, the name and delete-confirm dialogs, and
// the host `/fs/ops` calls behind them. Paths here are ABSOLUTE host paths, like the tree's.
package dev.supermux.ui.files

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.fs.FileSystemService
import dev.supermux.fs.FsOpRequest
import dev.supermux.fs.snapshotOrPrevious
import dev.supermux.net.FsException
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.DropdownMenuItem
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Which dialog a row action opened. */
@Immutable
sealed interface FileTreeDialog {
    /** New file / folder inside [parent]. */
    data class NewEntry(val parent: String, val folder: Boolean) : FileTreeDialog
    data class Rename(val path: String) : FileTreeDialog
    data class Delete(val path: String, val folder: Boolean) : FileTreeDialog
}

/**
 * Why [name] can't be used as a new name in a folder holding [siblings], or null when it can.
 * [current] is the entry's own name on a rename (keeping it is not a clash). An empty name is
 * invalid but gets an empty message: the dialog just keeps its button disabled.
 */
fun validateNewName(name: String, siblings: Collection<String>, current: String? = null): String? = when {
    name.isBlank() -> ""
    '/' in name -> "A name can't contain “/”"
    '\u0000' in name -> "A name can't contain a null character"
    name == "." || name == ".." -> "“$name” isn't a valid name"
    name != current && name in siblings -> "A file with that name already exists"
    else -> null
}

/** A user-facing message for a failed `/fs/ops` call (the body is `{error: code, message}`). */
fun fsOpErrorMessage(e: Throwable): String {
    val body = e.message.orEmpty()
    val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
    val code = obj?.get("error")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    val message = obj?.get("message")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    return when {
        code == "EEXIST" || (e is FsException && e.status == 409) -> "A file with that name already exists"
        code == "EACCES" || code == "EPERM" -> "Permission denied"
        code == "ENOENT" -> "It no longer exists"
        !message.isNullOrBlank() -> message
        obj == null && body.isNotBlank() -> body
        else -> code ?: "Something went wrong"
    }
}

/** Where "New file…" / "New folder…" create: inside a folder row, beside a file row. */
fun newEntryParent(row: TreeRow): String =
    if (row.status == RowStatus.FILE) parentOf(row.path) ?: "/" else row.path

/**
 * [FileTreeView] plus its row actions: the context menu, F2 → rename, Delete → move to trash, and
 * the dialogs. [onOpenFile] gets an absolute path (the host decides what "outside the workdir" means).
 */
@Composable
fun FileTreeWithActions(
    fileSystem: FileSystemService,
    view: TreeViewState,
    onOpenFile: (absolutePath: String) -> Unit,
    modifier: Modifier = Modifier,
    activePath: String? = null,
    revealActive: Boolean = true,
    compact: Boolean = false,
) {
    var dialog by remember(view) { mutableStateOf<FileTreeDialog?>(null) }
    FileTreeView(
        fileSystem = fileSystem,
        view = view,
        onOpenFile = onOpenFile,
        modifier = modifier,
        activePath = activePath,
        revealActive = revealActive,
        compact = compact,
        onRename = { dialog = FileTreeDialog.Rename(it.path) },
        onDelete = { dialog = FileTreeDialog.Delete(it.path, folder = it.status != RowStatus.FILE) },
        rowMenu = { row, dismiss ->
            FileTreeMenuItems(row, workdir = view.workdir, dismiss = dismiss, onDialog = { dialog = it })
        },
    )
    dialog?.let { d ->
        FileTreeDialogs(d, fileSystem, view, onOpenFile, onDismiss = { dialog = null })
    }
}

/** The menu rows for [row]. Picking one dismisses the menu first. */
@Composable
fun ColumnScope.FileTreeMenuItems(
    row: TreeRow,
    workdir: String,
    dismiss: () -> Unit,
    onDialog: (FileTreeDialog) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val parent = newEntryParent(row)
    val folder = row.status != RowStatus.FILE
    DropdownMenuItem(
        text = { Text("New file…") },
        modifier = Modifier.testTag("tree_menu_new_file"),
        onClick = { dismiss(); onDialog(FileTreeDialog.NewEntry(parent, folder = false)) },
    )
    DropdownMenuItem(
        text = { Text("New folder…") },
        modifier = Modifier.testTag("tree_menu_new_folder"),
        onClick = { dismiss(); onDialog(FileTreeDialog.NewEntry(parent, folder = true)) },
    )
    MenuDivider()
    DropdownMenuItem(
        text = { Text("Rename…") },
        modifier = Modifier.testTag("tree_menu_rename"),
        onClick = { dismiss(); onDialog(FileTreeDialog.Rename(row.path)) },
    )
    DropdownMenuItem(
        text = { Text("Delete…") },
        modifier = Modifier.testTag("tree_menu_delete"),
        onClick = { dismiss(); onDialog(FileTreeDialog.Delete(row.path, folder)) },
    )
    MenuDivider()
    DropdownMenuItem(
        text = { Text("Copy path") },
        modifier = Modifier.testTag("tree_menu_copy_path"),
        onClick = { dismiss(); clipboard.setText(AnnotatedString(row.path)) },
    )
    val relative = relativeToWorkdir(workdir, row.path)?.takeIf { it != "." }
    if (relative != null) {
        DropdownMenuItem(
            text = { Text("Copy relative path") },
            modifier = Modifier.testTag("tree_menu_copy_relative_path"),
            onClick = { dismiss(); clipboard.setText(AnnotatedString(relative)) },
        )
    }
}

@Composable
private fun MenuDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = 4.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** The dialog for [dialog]; on success it updates [view] (expand / select) and closes. */
@Composable
fun FileTreeDialogs(
    dialog: FileTreeDialog,
    fileSystem: FileSystemService,
    view: TreeViewState,
    onOpenFile: (absolutePath: String) -> Unit,
    onDismiss: () -> Unit,
) {
    when (dialog) {
        is FileTreeDialog.NewEntry -> {
            val parent = dialog.parent
            NameDialog(
                title = if (dialog.folder) "New folder" else "New file",
                confirmLabel = "Create",
                initial = "",
                current = null,
                siblings = siblingNames(fileSystem, parent),
                onSubmit = { name ->
                    fileSystem.op(FsOpRequest(op = if (dialog.folder) "mkdir" else "touch", path = childOf(parent, name)))
                },
                onDone = { name ->
                    val path = childOf(parent, name)
                    // Open the folder it went into (and the folders above it) so the new row shows.
                    if (parent != view.rootPath && isWithin(view.rootPath, parent)) {
                        view.reveal(parent)
                        view.expand(parent)
                    }
                    view.selected = path
                    if (!dialog.folder) onOpenFile(path)
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        is FileTreeDialog.Rename -> {
            val old = dialog.path
            val parent = parentOf(old) ?: "/"
            val oldName = displayName(old)
            NameDialog(
                title = "Rename",
                confirmLabel = "Rename",
                initial = oldName,
                current = oldName,
                siblings = siblingNames(fileSystem, parent),
                onSubmit = { name ->
                    if (name == oldName) Result.success(Unit)
                    else fileSystem.op(FsOpRequest(op = "rename", path = old, to = childOf(parent, name)))
                },
                onDone = { name ->
                    val new = childOf(parent, name)
                    if (new != old) {
                        val wasOpen = old in view.expanded
                        view.prune(old)
                        if (wasOpen) view.expand(new)
                        val sel = view.selected
                        if (sel != null && isWithin(old, sel)) view.selected = new + sel.removePrefix(old)
                    }
                    onDismiss()
                },
                onDismiss = onDismiss,
            )
        }
        is FileTreeDialog.Delete -> DeleteDialog(dialog, fileSystem, view, onDismiss)
    }
}

@Composable
private fun siblingNames(fileSystem: FileSystemService, parent: String): List<String> {
    val state by remember(fileSystem, parent) { fileSystem.dir(parent) }.collectAsState()
    return state.snapshotOrPrevious?.entries?.map { it.name }.orEmpty()
}

/**
 * The name prompt behind New file / New folder / Rename: validates as you type against the
 * folder's current listing, and shows the broker's refusal inline instead of closing.
 */
@Composable
private fun NameDialog(
    title: String,
    confirmLabel: String,
    initial: String,
    current: String?,
    siblings: List<String>,
    onSubmit: suspend (String) -> Result<Unit>,
    onDone: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // A rename pre-selects the stem, so typing replaces "name" but keeps ".kt".
    val stemEnd = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stemEnd))) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val name = field.text
    val invalid = validateNewName(name, siblings, current)
    val shown = serverError ?: invalid?.takeIf { it.isNotEmpty() }
    val submit = {
        if (invalid == null && !busy) {
            busy = true
            scope.launch {
                val r = onSubmit(name)
                busy = false
                r.onSuccess { onDone(name) }.onFailure { serverError = fsOpErrorMessage(it) }
            }
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    if (it.text != field.text) serverError = null
                    field = it
                },
                label = { Text("Name") },
                singleLine = true,
                isError = shown != null,
                supportingText = shown?.let { msg ->
                    { Text(msg, color = cs.error, fontSize = 12.sp, modifier = Modifier.testTag("tree_dialog_error")) }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("tree_dialog_name"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = invalid == null && !busy,
                modifier = Modifier.testTag("tree_dialog_confirm"),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteDialog(
    dialog: FileTreeDialog.Delete,
    fileSystem: FileSystemService,
    view: TreeViewState,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val path = dialog.path
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move “${displayName(path)}” to the trash?") },
        text = {
            val what = if (dialog.folder) "This folder and everything in it" else "This file"
            val err = error
            if (err == null) {
                Text("$what will be moved to the host's trash.")
            } else {
                Text(err, color = cs.error, modifier = Modifier.testTag("tree_dialog_error"))
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val r = fileSystem.op(FsOpRequest(op = "delete", path = path))
                        busy = false
                        r.onSuccess {
                            view.prune(path)
                            val sel = view.selected
                            if (sel != null && isWithin(path, sel)) view.selected = parentOf(path)?.takeIf { it != view.rootPath }
                            onDismiss()
                        }.onFailure { error = fsOpErrorMessage(it) }
                    }
                },
                modifier = Modifier.testTag("tree_dialog_confirm"),
            ) { Text("Move to Trash", color = cs.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
