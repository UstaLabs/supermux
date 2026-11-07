package dev.supermux.webview.sample

import dev.supermux.webview.testing.MacEngineTesting
import java.awt.Window
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * How long AppKit's thread keeps the app waiting, for the chat's trackpad runs (`-chat-shift`): a thread of its own asks
 * AppKit's thread for a trivial answer every [PERIOD_MILLIS] (the frame sync's state, which waits for that thread) and
 * keeps every wait over [SLOW_MILLIS], by [now] (the probe's clock, ms). A wait still under way after [SAMPLE_AFTER_MILLIS]
 * is sampled while it lasts (macOS's `sample`, [SAMPLE_SECONDS] s, into [folder]), at most [MAX_SAMPLES] times, so what
 * AppKit's thread is doing then is on file.
 */
internal class AppKitWatch(private val window: Window, private val folder: File, private val now: () -> Double) {
    /** (when the wait began, how long it was), ms. */
    private val waits = ArrayList<Pair<Double, Double>>()

    @Volatile
    private var running = true

    @Volatile
    private var askedAt = 0L

    private var samples = 0

    private val pinger = thread(name = "appkit-watch", isDaemon = true) {
        while (running) {
            val began = now()
            askedAt = System.nanoTime()
            MacEngineTesting.frameSyncState(window)
            askedAt = 0L
            val took = now() - began
            if (took > SLOW_MILLIS) synchronized(waits) { waits += began to took }
            Thread.sleep(PERIOD_MILLIS)
        }
    }

    private val sampler = thread(name = "appkit-sampler", isDaemon = true) {
        while (running) {
            val asked = askedAt
            if (asked != 0L && System.nanoTime() - asked > SAMPLE_AFTER_MILLIS * 1_000_000L && samples < MAX_SAMPLES) {
                samples++
                val file = File(folder, "appkit-wait-$samples.txt")
                println("MEASURE chat-shift: AppKit's thread busy for ${SAMPLE_AFTER_MILLIS}+ ms at ${now().toLong()} ms (probe): sampling into ${file.name}")
                runCatching {
                    ProcessBuilder("/usr/bin/sample", ProcessHandle.current().pid().toString(), SAMPLE_SECONDS.toString(), "1", "-mayDie", "-file", file.absolutePath)
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                        .waitFor(SAMPLE_SECONDS + 20L, TimeUnit.SECONDS)
                }
                // One sample a wait: the next wait is another.
                while (running && askedAt == asked) Thread.sleep(10)
            }
            Thread.sleep(10)
        }
    }

    /** The waits since the last call, and their summary: how many over 50, 100 and 250 ms, and the longest. */
    fun drain(): String {
        val taken = synchronized(waits) { ArrayList(waits).also { waits.clear() } }
        val longest = taken.maxOfOrNull { it.second } ?: 0.0
        val list = taken.filter { it.second > 50.0 }.joinToString { (at, took) -> "${(at / 1000).fmt3()} s ${took.toLong()} ms" }
        return "AppKit's thread kept the app waiting over $SLOW_MILLIS ms ${taken.size} times, over 50 ms ${taken.count { it.second > 50 }}, " +
            "over 100 ms ${taken.count { it.second > 100 }}, over 250 ms ${taken.count { it.second > 250 }}, the longest ${longest.toLong()} ms" +
            if (list.isEmpty()) "" else ": $list"
    }

    fun stop() {
        running = false
    }

    private fun Double.fmt3(): String = "%.3f".format(this)

    private companion object {
        const val PERIOD_MILLIS = 50L
        const val SLOW_MILLIS = 20.0
        const val SAMPLE_AFTER_MILLIS = 150L
        const val SAMPLE_SECONDS = 2
        const val MAX_SAMPLES = 4
    }
}
