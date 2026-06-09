package dev.supermux.ui

/** Resolved ARGB colour tokens (mirror of web-app/src/style.css .dark). */
data class SupermuxColors(
    val background: Int, val foreground: Int, val card: Int, val primary: Int,
    val primaryForeground: Int, val muted: Int, val mutedForeground: Int,
    val border: Int, val destructive: Int,
    val rail: Int, val sessionList: Int, val header: Int, val chat: Int,
    val workspace: Int, val code: Int, val terminal: Int, val terminalForeground: Int,
    val warning: Int,
)

fun supermuxDark(): SupermuxColors = SupermuxColors(
    background = oklchToArgb(0.14, 0.006, 130.0),
    foreground = oklchToArgb(0.94, 0.008, 120.0),
    card = oklchToArgb(0.18, 0.008, 130.0),
    primary = oklchToArgb(0.72, 0.105, 180.0),
    primaryForeground = oklchToArgb(0.13, 0.018, 170.0),
    muted = oklchToArgb(0.24, 0.008, 130.0),
    mutedForeground = oklchToArgb(0.68, 0.018, 125.0),
    border = oklchToArgb(0.28, 0.01, 130.0),
    destructive = oklchToArgb(0.72, 0.18, 24.0),
    rail = oklchToArgb(0.18, 0.008, 130.0),
    sessionList = oklchToArgb(0.205, 0.008, 130.0),
    header = oklchToArgb(0.16, 0.008, 130.0),
    chat = oklchToArgb(0.155, 0.008, 130.0),
    workspace = oklchToArgb(0.135, 0.008, 130.0),
    code = oklchToArgb(0.115, 0.008, 130.0),
    terminal = 0xFF050605.toInt(),
    terminalForeground = 0xFFD8DED3.toInt(),
    warning = oklchToArgb(0.78, 0.12, 75.0),
)
