package dev.supermux.android.editor

import android.app.Activity
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.supermux.android.R
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.net.FsEntry
import dev.supermux.net.FsSearchResult
import kotlinx.coroutines.delay

/**
 * Code editor panel: lazy file tree, multi-tab editing, filename search.
 * Tablet (Expanded): split sidebar. Phone: slide-over tree drawer.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun EditorPanel(
    fsList: suspend (String) -> List<FsEntry>,
    fsRead: suspend (String) -> Result<String>,
    fsWrite: suspend (String, String) -> Boolean,
    fsSearch: suspend (String) -> List<FsSearchResult>,
    onConsumesBackChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val windowSizeClass = calculateWindowSizeClass(context as Activity)
    val expanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    val editor = remember(fsRead, fsWrite) {
        EditorState(fsRead, fsWrite, scope)
    }

    if (editor.treeVisible == null) {
        SideEffect { editor.treeVisible = expanded }
    }
    val treeVisible = editor.treeVisible ?: expanded
    val searchResults = remember { mutableStateListOf<FsSearchResult>() }

    LaunchedEffect(editor.searchQuery) {
        delay(200)
        val q = editor.searchQuery.trim()
        if (q.isEmpty()) {
            searchResults.clear()
            return@LaunchedEffect
        }
        searchResults.clear()
        searchResults.addAll(fsSearch(q))
    }

    val prefs = context.getSharedPreferences("cmux-editor-settings", Context.MODE_PRIVATE)
    val lineWrap = prefs.getBoolean("lineWrap", true)
    val fontSize = prefs.getInt("fontSize", 13)

    val engine = rememberEditorEngine(
        lineWrap = lineWrap,
        fontSize = fontSize,
        onChange = { content -> editor.activeTab?.path?.let { editor.updateContent(it, content) } },
        onSave = { editor.saveActive() },
    )

    fun revealFile(path: String) {
        focusManager.clearFocus()
        engine.readScrollTop { scroll -> editor.captureActiveScroll(scroll) }
        editor.openFile(path)
        editor.searchQuery = ""
        searchResults.clear()
        if (!expanded) editor.treeVisible = false
    }
    val searchOpen = searchResults.isNotEmpty()
    val treeDrawerOpen = !expanded && treeVisible

    SideEffect {
        onConsumesBackChange(searchOpen || treeDrawerOpen)
    }

    DisposableEffect(Unit) {
        onDispose { onConsumesBackChange(false) }
    }

    BackHandler(enabled = searchOpen) {
        focusManager.clearFocus()
        editor.searchQuery = ""
        searchResults.clear()
    }
    BackHandler(enabled = treeDrawerOpen) {
        editor.treeVisible = false
    }

    val activeTab = editor.activeTab
    val loadingNew = editor.loadingPath?.let { path ->
        editor.tabs.none { it.path == path }
    } == true

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(cs.surfaceContainerLow)
                    .padding(horizontal = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    haptic(HapticKind.Tick)
                    focusManager.clearFocus()
                    searchResults.clear()
                    editor.treeVisible = !treeVisible
                }) {
                    Icon(
                        painter = painterResource(
                            if (treeVisible) R.drawable.ic_chevron_down else R.drawable.ic_folder_open,
                        ),
                        contentDescription = if (treeVisible) "Hide file tree" else "Show file tree",
                        tint = cs.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
                EditorSearchField(
                    query = editor.searchQuery,
                    onQueryChange = { editor.searchQuery = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = Space.xs),
                )
                if (editor.saving) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = cs.primary,
                        )
                    }
                } else {
                    IconButton(
                        onClick = {
                            haptic(HapticKind.Confirm)
                            editor.saveActive()
                        },
                        enabled = editor.activeTab?.let { editor.isDirty(it.path) } == true,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = "Save",
                            tint = if (editor.activeTab?.let { editor.isDirty(it.path) } == true) {
                                cs.primary
                            } else {
                                cs.onSurfaceVariant.copy(alpha = 0.4f)
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    if (expanded && treeVisible) {
                        Box(
                            Modifier
                                .width(192.dp)
                                .fillMaxHeight()
                                .background(cs.surfaceContainerHigh),
                        ) {
                            FileTree(fsList = fsList, editor = editor, onOpenFile = { revealFile(it) })
                        }
                        Box(
                            Modifier
                                .width(1.dp)
                                .fillMaxHeight()
                                .background(cs.outlineVariant),
                        )
                    }

                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        EditorTabs(
                            tabs = editor.tabs,
                            activeTabPath = editor.activeTabPath,
                            loadingPath = if (loadingNew) editor.loadingPath else null,
                            isDirty = editor::isDirty,
                            onSelect = { path ->
                                engine.readScrollTop { scroll -> editor.captureActiveScroll(scroll) }
                                editor.selectTab(path)
                            },
                            onClose = editor::closeTab,
                        )
                        HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            // Pre-warm WebView as soon as the editor panel opens.
                            WebCodeEditor(
                                engine = engine,
                                content = activeTab?.content ?: "",
                                filename = activeTab?.path ?: "",
                                fontSize = fontSize,
                                scrollTop = activeTab?.scrollTop ?: 0,
                                onChange = { content ->
                                    activeTab?.path?.let { editor.updateContent(it, content) }
                                },
                                onSave = { editor.saveActive() },
                                modifier = Modifier.fillMaxSize(),
                            )

                            if (editor.tabs.isEmpty() && editor.loadingPath == null && editor.loadError == null) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(c.code).copy(alpha = 0.92f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "Open a file from the tree or search",
                                        color = cs.onSurfaceVariant,
                                        fontSize = 13.sp,
                                    )
                                }
                            }

                            editor.loadError?.takeIf { editor.tabs.isEmpty() }?.let { err ->
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(c.code).copy(alpha = 0.92f))
                                        .padding(Space.xl),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(err, color = cs.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }

                            // Full-area spinner only while waiting for the very first file.
                            if (editor.tabs.isEmpty() && editor.loadingPath != null) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Color(c.code).copy(alpha = 0.72f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 2.dp,
                                            color = cs.primary,
                                        )
                                        Text(
                                            editor.loadingPath!!.substringAfterLast('/'),
                                            color = cs.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(top = Space.sm),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = !expanded && treeVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(1f),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable {
                                    haptic(HapticKind.Tick)
                                    editor.treeVisible = false
                                },
                        )
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(280.dp)
                                .background(cs.surfaceContainerHigh),
                        ) {
                            FileTree(fsList = fsList, editor = editor, onOpenFile = { revealFile(it) })
                        }
                    }
                }
            }
        }

        if (searchResults.isNotEmpty()) {
            EditorSearchOverlay(
                results = searchResults,
                onSelect = { revealFile(it) },
                onDismiss = {
                    focusManager.clearFocus()
                    editor.searchQuery = ""
                    searchResults.clear()
                },
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(10f),
            )
        }
    }
}
