package dev.supermux.webview.layouttest

import java.io.File

/** probe: the first launch (both layouts at rest, one screenshot each); auto: the whole scripted run; manual: the window stays for the user. */
enum class Mode { Probe, Auto, Manual }

/** How screenshots are taken (Screenshots.kt). */
enum class ShotMode { App, External, Off }

/** What mac/run.sh passes: -Dlayouttest.mode, -Dlayouttest.out, -Dlayouttest.shots. */
data class TestConfig(val mode: Mode, val outDir: File, val shots: ShotMode) {
    companion object {
        fun fromSystemProperties(): TestConfig = TestConfig(
            mode = when (System.getProperty("layouttest.mode", "manual")) {
                "probe" -> Mode.Probe
                "auto" -> Mode.Auto
                else -> Mode.Manual
            },
            outDir = File(System.getProperty("layouttest.out") ?: error("-Dlayouttest.out=<directory> is required")).also { it.mkdirs() },
            shots = when (System.getProperty("layouttest.shots", "app")) {
                "external" -> ShotMode.External
                "off" -> ShotMode.Off
                else -> ShotMode.App
            },
        )
    }
}
