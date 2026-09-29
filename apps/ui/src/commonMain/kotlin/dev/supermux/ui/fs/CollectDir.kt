package dev.supermux.ui.fs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import dev.supermux.fs.DirState
import dev.supermux.fs.FileSystemService

/** Subscribe to [path] for as long as this composition lives, and read its state. */
@Composable
fun FileSystemService.collectDir(path: String): State<DirState> {
    DisposableEffect(this, path) {
        val sub = subscribe(path)
        onDispose { sub.close() }
    }
    val flow = remember(this, path) { dir(path) }
    return flow.collectAsState()
}
