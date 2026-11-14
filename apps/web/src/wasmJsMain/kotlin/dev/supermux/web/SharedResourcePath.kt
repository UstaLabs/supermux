package dev.supermux.web

/**
 * JetBrains Mono ships once (#142). terminal-compose and editor-compose each carry the same three
 * faces as their own Compose resources — each library has to stand alone, and terminal-compose is
 * published on its own — so the bundle used to hold, and a browser that opened both a terminal and
 * an editor used to download, ~382 KB gzip twice. The web app maps the editor's paths onto the
 * terminal's files, and `stageForBroker` drops the editor's copy after proving the two are still
 * byte-identical (its `sharedMonoFaces`, which must name the same files as [SHARED_MONO_FACES]).
 */
internal val SHARED_MONO_FACES = listOf("jetbrains_mono_regular.ttf", "jetbrains_mono_bold.ttf", "jetbrains_mono_italic.ttf")

private const val EDITOR_FONTS = "composeResources/dev.supermux.editor.compose.resources/font/"
private const val TERMINAL_FONTS = "composeResources/dev.supermux.terminal.compose.resources/font/"

/** [path] as the web app fetches it: the editor's JetBrains Mono comes from the terminal's copy. */
internal fun sharedResourcePath(path: String): String {
    if (!path.startsWith(EDITOR_FONTS)) return path
    val face = path.removePrefix(EDITOR_FONTS)
    return if (face in SHARED_MONO_FACES) TERMINAL_FONTS + face else path
}
